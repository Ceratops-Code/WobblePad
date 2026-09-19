package io.github.ceratops_code.wobblepad

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.PrintWriter
import java.time.Instant
import java.util.Locale

data class Board(val address: String, val name: String)
data class BridgeState(
    val status: String = "Connect to BoBo to begin", val address: String? = null,
    val scanning: Boolean = false, val connected: Boolean = false,
    val boards: List<Board> = emptyList(), val packets: Int = 0, val rejected: Int = 0,
    val rate: Int = 0, val battery: Int? = null, val raw: String = "", val hex: String = "",
    val stick: Stick = Stick(), val calibrated: Boolean = false,
    val counts: Map<Pose, Int> = emptyMap(), val capture: Pose? = null,
    val output: Boolean = false, val outputMessage: String = "Controller output is stopped",
    val mode: Int = 0
)

/** Owns BLE and controller lifetime while another app is in front. All state changes
 * run on the main looper; the shell helper has an independent input-release watchdog.
 * CSV rows stay in a bounded memory buffer until the user exports them with Android's picker.
 */
@SuppressLint("MissingPermission") // Every public BLE entry point checks Nearby Devices permission.
class BridgeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@BridgeService }
    private val handler = Handler(Looper.getMainLooper())
    private val localBinder = LocalBinder()
    private lateinit var calibration: CalibrationStore
    private lateinit var controller: ControllerLink
    private val adapter get() = getSystemService(BluetoothManager::class.java).adapter
    private var manager: BoBoManager? = null
    private val retiringClients = mutableSetOf<BoBoManager>()
    private var scanner: BluetoothLeScanner? = null
    private var generation = 0
    private var reconnects = 0
    private var stablePackets = 0
    private var lastPacket = 0L
    private var readyAt = 0L
    private var messageUntil = 0L
    private var captureEnds = 0L
    private var pendingPose: Pose? = null
    private val pendingSamples = mutableListOf<DoubleArray>()
    private val samples = mutableMapOf<Pose, List<DoubleArray>>()
    private var mapper: JoystickMapper? = null
    private var wantOutput = false
    private val packetTimes = ArrayDeque<Long>()
    private val csv = ArrayDeque<String>()
    @Volatile var state = BridgeState(); private set
    var listener: ((BridgeState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        calibration = CalibrationStore(this)
        state = state.copy(mode = calibration.mode)
        controller = ControllerLink(this) { ready, message ->
            if (!ready && !controller.binding) wantOutput = false
            state = state.copy(output = ready && wantOutput, outputMessage = message)
            publish()
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("connection", "WobblePad connection", NotificationManager.IMPORTANCE_LOW))
        handler.post(tick)
    }
    override fun onBind(intent: Intent): IBinder = localBinder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") disconnect() else foreground()
        return START_NOT_STICKY
    }
    private fun foreground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7, Notification.Builder(this, "connection").setSmallIcon(R.drawable.ic_wobblepad)
            .setContentTitle("WobblePad is running").setContentText("Tap to see connection and controller controls")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
    }
    private fun keepRunning() {
        startForegroundService(Intent(this, BridgeService::class.java))
        foreground()
    }
    private fun permissions() = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun publish() { listener?.invoke(state) }
    fun message(text: String) {
        messageUntil = SystemClock.elapsedRealtime() + 6000
        state = state.copy(status = text); Log.i("WobblePad", text); publish()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!state.scanning || !permissions()) return
            val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
            val service = result.scanRecord?.serviceUuids?.any { it.uuid == BoBoManager.SERVICE } == true
            if (!name.contains("BoBo", true) && !service) return
            if (state.boards.none { it.address == result.device.address }) {
                state = state.copy(boards = state.boards + Board(result.device.address, name.ifBlank { "BoBo" }))
                publish()
            }
        }
        override fun onScanFailed(errorCode: Int) { stopScan(); message("Bluetooth scan failed ($errorCode). Check Bluetooth and try again.") }
    }
    fun scan() {
        if (!permissions()) { message("Allow Nearby Devices to find BoBo."); return }
        if (adapter?.isEnabled != true) { message("Turn on Bluetooth, then scan again."); return }
        stopScan()
        state = state.copy(scanning = true, boards = emptyList(), status = "Looking for BoBo…")
        try {
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            handler.postDelayed(scanEnd, 10000)
        } catch (e: Exception) { stopScan(); message("Could not scan: ${e.message}") }
        publish()
    }
    private val scanEnd = Runnable { stopScan(); message(if (state.boards.isEmpty()) "No BoBo found. Keep it awake and disconnect other BoBo apps." else "Choose your BoBo below.") }
    private fun stopScan() {
        handler.removeCallbacks(scanEnd)
        runCatching { scanner?.stopScan(scanCallback) }; scanner = null
        state = state.copy(scanning = false)
    }
    fun connect(address: String) {
        if (!permissions()) { message("Allow Nearby Devices first."); return }
        if (adapter?.isEnabled != true) { message("Turn on Bluetooth first."); return }
        disconnect()
        keepRunning()
        samples.clear(); mapper = null
        runCatching { calibration.load(address) }.onSuccess { saved ->
            if (saved != null) { samples.putAll(saved); mapper = JoystickMapper.calibrate(samples) }
        }
        reconnects = 0
        state = BridgeState(address = address, calibrated = mapper != null, counts = samples.mapValues { it.value.size }, mode = calibration.mode)
        connectAttempt()
    }
    private fun connectAttempt() {
        val address = state.address ?: return
        val token = ++generation
        lastPacket = 0; readyAt = 0; stablePackets = 0; packetTimes.clear()
        message(if (reconnects == 0) "Connecting to BoBo…" else "Reconnecting to BoBo ($reconnects/3)…")
        try {
            val client = BoBoManager(this, { bytes -> if (token == generation) onPacket(bytes) },
                { level -> if (token == generation) { state = state.copy(battery = level); publish() } },
                { error -> if (token == generation) recover(error) })
            manager = client
            client.setConnectionObserver(object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) {}
                override fun onDeviceConnected(device: BluetoothDevice) {}
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) { if (token == generation) recover("Connection failed ($reason)") }
                override fun onDeviceReady(device: BluetoothDevice) {
                    if (token != generation) return
                    readyAt = SystemClock.elapsedRealtime()
                    messageUntil = 0
                    state = state.copy(connected = true, status = "Connected — waiting for tilt packets")
                    publish()
                }
                override fun onDeviceDisconnecting(device: BluetoothDevice) {}
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) { if (token == generation) recover("BoBo disconnected ($reason)") }
            })
            client.connect(adapter.getRemoteDevice(address)).useAutoConnect(false).timeout(12000).enqueue()
        } catch (e: Exception) { recover("Bluetooth connection failed: ${e.message}") }
    }
    private fun retireClient(after: () -> Unit = {}) {
        val old = manager; manager = null
        if (old == null) { after(); return }
        retiringClients.add(old)
        var finished = false
        val finish = {
            if (!finished) { finished = true; old.close(); retiringClients.remove(old); after() }
        }
        runCatching { old.disconnect().timeout(2000).done { finish() }.fail { _, _ -> finish() }.enqueue() }
            .onFailure { finish() }
        handler.postDelayed({ finish() }, 2200)
    }
    private fun recover(reason: String) {
        ++generation
        controller.send(Stick()); mapper?.reset(); cancelCapture()
        state = state.copy(connected = false, stick = Stick(), rate = 0, status = "$reason. Input paused.")
        publish()
        val token = generation
        retireClient {
            if (token == generation && state.address != null) {
                if (++reconnects <= 3 && permissions() && adapter?.isEnabled == true) {
                    handler.postDelayed({ if (token == generation) connectAttempt() }, 500)
                } else {
                    stopOutput(); message("$reason. Reconnect stopped. Check BoBo is on, tap Scan for BoBo, then connect.")
                }
            }
        }
    }
    fun disconnect() {
        ++generation; stopScan(); stopOutput(); cancelCapture(); retireClient()
        lastPacket = 0; readyAt = 0; mapper?.reset()
        state = state.copy(address = null, connected = false, stick = Stick(), rate = 0, status = "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); publish()
    }
    private fun onPacket(bytes: ByteArray) {
        val raw = PacketParser.parse(bytes)
        if (raw == null) { state = state.copy(rejected = state.rejected + 1); return }
        val now = SystemClock.elapsedRealtime()
        lastPacket = now; packetTimes.addLast(now)
        if (++stablePackets >= 100) reconnects = 0
        pendingPose?.let { if (pendingSamples.size < 500) pendingSamples.add(raw) }
        val stick = if (pendingPose == null) mapper?.update(raw) ?: Stick() else Stick()
        if (wantOutput) controller.send(stick)
        val pose = pendingPose?.name.orEmpty()
        csv.addLast("${Instant.now()},$pose,${raw.joinToString(",") { it.toInt().toString() }},${stick.x},${stick.y},${stick.keys}")
        while (csv.size > 10000) csv.removeFirst()
        state = state.copy(packets = state.packets + 1, raw = raw.joinToString(", ") { it.toInt().toString() },
            hex = bytes.joinToString("-") { "%02X".format(it.toInt() and 255) }, stick = stick,
            status = if (pendingPose != null) "Hold ${pendingPose!!.name.lowercase()}: ${pendingSamples.size} packets"
                else if (now < messageUntil) state.status else "Receiving BoBo tilt")
    }
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            while (packetTimes.isNotEmpty() && now - packetTimes.first() > 1000) packetTimes.removeFirst()
            state = state.copy(rate = packetTimes.size)
            if (pendingPose != null && now >= captureEnds) completeCapture()
            if (state.connected && ((lastPacket > 0 && now - lastPacket > 1000) || (lastPacket == 0L && readyAt > 0 && now - readyAt > 3000))) recover("Tilt stream stopped")
            publish(); handler.postDelayed(this, 200)
        }
    }
    fun capture(pose: Pose) {
        if (!state.connected || SystemClock.elapsedRealtime() - lastPacket > 1000) { message("Connect and wait for live packets first."); return }
        stopOutput(); pendingSamples.clear(); pendingPose = pose
        captureEnds = SystemClock.elapsedRealtime() + 3000
        state = state.copy(capture = pose); message("Hold ${pose.name.lowercase()} steady for 3 seconds…")
    }
    private fun cancelCapture() { pendingPose = null; pendingSamples.clear(); state = state.copy(capture = null) }
    private fun completeCapture() {
        val pose = pendingPose ?: return
        val count = pendingSamples.size
        if (count >= 10) samples[pose] = pendingSamples.map { it.copyOf() }
        cancelCapture()
        state = state.copy(counts = samples.mapValues { it.value.size })
        message(if (count >= 10) "$pose captured ($count packets). Finish calibration after all poses." else "Only $count packets captured. Hold $pose and try again.")
    }
    fun finishCalibration() {
        val address = state.address ?: return
        runCatching {
            val result = JoystickMapper.calibrate(samples)
            calibration.save(address, samples); mapper = result
        }.onSuccess { state = state.copy(calibrated = true); message("Calibration saved.") }
            .onFailure { message(it.message ?: "Calibration could not be saved") }
    }
    fun setMode(mode: Int) { stopOutput(); calibration.mode = mode; state = state.copy(mode = mode); publish() }
    fun startOutput() {
        if (!state.connected || mapper == null || pendingPose != null || SystemClock.elapsedRealtime() - lastPacket > 1000) {
            message("Connect BoBo and finish calibration before starting output."); return
        }
        wantOutput = true; mapper?.reset()
        runCatching { controller.start(state.mode) }.onFailure { wantOutput = false; message(it.message ?: "Controller access failed") }
    }
    fun stopOutput() {
        wantOutput = false
        if (::controller.isInitialized) controller.stop()
        state = state.copy(output = false, outputMessage = "Controller output is stopped"); publish()
    }
    fun csvSnapshot() = "timestamp,pose,v1,v2,v3,v4,v5,v6,v7,v8,v9,x,y,keys\n" + csv.joinToString("\n") + "\n"
    /** Standard Android service diagnostics; reports received data and current lifecycle state. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val s = state
        writer.println(JSONObject().put("status", s.status).put("address", s.address)
            .put("connected", s.connected).put("calibrated", s.calibrated).put("counts", JSONObject(s.counts.mapKeys { it.key.name }))
            .put("packets", s.packets).put("rejected", s.rejected).put("rate", s.rate).put("battery", s.battery)
            .put("raw", s.raw).put("hex", s.hex).put("x", s.stick.x).put("y", s.stick.y).put("keys", s.stick.keys)
            .put("output", s.output).put("mode", s.mode).put("outputMessage", s.outputMessage)
            .put("reconnects", reconnects).put("lastPacketAgeMs", if (lastPacket == 0L) -1 else SystemClock.elapsedRealtime() - lastPacket))
    }
    override fun onDestroy() {
        ++generation; stopScan(); controller.shutdown(); manager?.close(); manager = null
        retiringClients.forEach { it.close() }; retiringClients.clear()
        handler.removeCallbacksAndMessages(null); listener = null
        super.onDestroy()
    }
}
