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
import android.widget.Toast
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
    val mode: Int = 0, val analogControls: ControlSettings = ControlSettings(),
    val arrowControls: ControlSettings = ControlSettings()
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
    private var manager: BalanceBoardBLEManager? = null
    private val retiringClients = mutableSetOf<BalanceBoardBLEManager>()
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
    private var samplesAddress: String? = null
    private var analogMapper: JoystickMapper? = null
    private var arrowMapper: JoystickMapper? = null
    private val keyRepeater = KeyPulseRepeater()
    private var wantOutput = false
    private var autoDiscover = false
    private var autoOutputPending = false
    private var nextOutputAttempt = 0L
    private var nextBatteryRead = 0L
    private var connectionToastShown = false
    private var controllerToastShown = false
    private val packetTimes = ArrayDeque<Long>()
    private val csv = ArrayDeque<String>()
    @Volatile var state = BridgeState(); private set
    var listener: ((BridgeState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        calibration = CalibrationStore(this)
        val savedMode = calibration.mode
        val analogControls = calibration.controlsFor(0)
        val arrowControls = calibration.controlsFor(1)
        state = state.copy(mode = savedMode, analogControls = analogControls, arrowControls = arrowControls)
        keyRepeater.setInterval(arrowControls.repeatIntervalMs)
        controller = ControllerLink(this) { ready, message ->
            val retry = !ready && !controller.binding && wantOutput && state.connected
            if (!ready && !controller.binding) wantOutput = false
            state = state.copy(output = ready && wantOutput, outputMessage = message)
            if (retry) {
                autoOutputPending = true
                nextOutputAttempt = 0
                handler.removeCallbacks(outputRestart)
                handler.postDelayed(outputRestart, OUTPUT_RESTART_MS)
            }
            showControllerToastIfReady()
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
    private val connectionToast = Runnable {
        if (!state.connected || connectionToastShown) return@Runnable
        val controllerActive = state.output && packetIsLive()
        connectionToastShown = true
        if (controllerActive) controllerToastShown = true
        showToast(if (controllerActive) "BoBo connected — controller active" else "BoBo connected")
    }
    private fun packetIsLive() = lastPacket > 0 && SystemClock.elapsedRealtime() - lastPacket <= 1000
    private fun scheduleConnectionToast() {
        handler.removeCallbacks(connectionToast)
        handler.postDelayed(connectionToast, CONNECTION_TOAST_COALESCE_MS)
    }
    private fun showControllerToastIfReady() {
        if (!state.connected || !state.output || controllerToastShown || !packetIsLive()) return
        handler.removeCallbacks(connectionToast)
        val combined = !connectionToastShown
        connectionToastShown = true
        controllerToastShown = true
        showToast(if (combined) "BoBo connected — controller active" else "Controller active")
    }
    private fun showToast(text: String) {
        handler.post {
            val toast = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT)
            toast.addCallback(object : Toast.Callback() {
                override fun onToastShown() { Log.i("WobblePad", "Connection feedback shown: $text") }
            })
            toast.show()
        }
    }
    private fun resetConnectionToasts() {
        handler.removeCallbacks(connectionToast)
        connectionToastShown = false
        controllerToastShown = false
    }
    private val outputRestart = Runnable { ensureOutput() }
    fun message(text: String) {
        messageUntil = SystemClock.elapsedRealtime() + 6000
        state = state.copy(status = text); Log.i("WobblePad", text); publish()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!state.scanning || !permissions()) return
            val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
            val service = result.scanRecord?.serviceUuids?.any { it.uuid == BalanceBoardBLEManager.SERVICE } == true
            if (!name.contains("BoBo", true) && !service) return
            if (state.boards.none { it.address == result.device.address }) {
                state = state.copy(boards = state.boards + Board(result.device.address, name.ifBlank { "BoBo" }))
                publish()
            }
            if (autoDiscover) {
                stopScan()
                message("BoBo found. Connecting automatically…")
                connect(result.device.address, automatic = true)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            stopScan()
            if (autoDiscover) {
                message("Bluetooth scan paused ($errorCode). Retrying automatically…")
                handler.postDelayed(scanAgain, SCAN_RETRY_MS)
            } else message("Bluetooth scan failed ($errorCode). Check Bluetooth and try again.")
        }
    }
    fun scan() {
        if (!permissions()) { message("Allow Nearby Devices to find BoBo."); return }
        if (adapter?.isEnabled != true) { message("Turn on Bluetooth, then scan again."); return }
        autoDiscover = true
        autoOutputPending = true
        keepRunning()
        startScanCycle()
    }
    private fun startScanCycle() {
        if (!autoDiscover || !permissions() || state.connected) return
        if (adapter?.isEnabled != true) {
            message("Bluetooth is off. Waiting for it to turn on…")
            handler.postDelayed(scanAgain, SCAN_RETRY_MS)
            return
        }
        handler.removeCallbacks(scanAgain)
        stopScan()
        state = state.copy(scanning = true, boards = emptyList(), status = "Looking for BoBo…")
        try {
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            handler.postDelayed(scanEnd, 10000)
        } catch (e: Exception) {
            stopScan(); message("Could not scan: ${e.message}. Retrying automatically…")
            handler.postDelayed(scanAgain, SCAN_RETRY_MS)
        }
        publish()
    }
    private val scanAgain = Runnable { startScanCycle() }
    private val scanEnd = Runnable {
        stopScan()
        if (autoDiscover) {
            message("BoBo is not visible yet. Watching for it…")
            handler.postDelayed(scanAgain, SCAN_RETRY_MS)
        } else message("No BoBo found. Keep it awake and disconnect other BoBo apps.")
    }
    private fun stopScan() {
        handler.removeCallbacks(scanEnd)
        runCatching { scanner?.stopScan(scanCallback) }; scanner = null
        state = state.copy(scanning = false)
    }
    fun connect(address: String, automatic: Boolean = false) {
        if (!permissions()) { message("Allow Nearby Devices first."); return }
        if (adapter?.isEnabled != true) { message("Turn on Bluetooth first."); return }
        val keepCalibration = address.equals(samplesAddress, ignoreCase = true)
        if (!automatic) autoDiscover = false
        handler.removeCallbacks(scanAgain)
        ++generation; stopScan(); stopOutput("Controller output is waiting for BoBo", resume = true); cancelCapture(); retireClient()
        lastPacket = 0; readyAt = 0; nextBatteryRead = 0
        analogMapper?.reset(); arrowMapper?.reset()
        autoOutputPending = true
        keepRunning()
        if (!keepCalibration) {
            samples.clear(); analogMapper = null; arrowMapper = null; samplesAddress = address
            runCatching { calibration.load(address) }.onSuccess { saved ->
                if (saved != null) {
                    samples.putAll(saved)
                    analogMapper = JoystickMapper.calibrate(samples, calibration.controlsFor(0))
                    arrowMapper = JoystickMapper.calibrate(samples, calibration.controlsFor(1))
                }
            }
        }
        reconnects = 0
        state = BridgeState(address = address, calibrated = analogMapper != null && arrowMapper != null,
            counts = samples.mapValues { it.value.size }, outputMessage = "Controller output starts automatically",
            mode = calibration.mode, analogControls = calibration.controlsFor(0), arrowControls = calibration.controlsFor(1))
        connectAttempt()
    }
    private fun connectAttempt() {
        val address = state.address ?: return
        val token = ++generation
        lastPacket = 0; readyAt = 0; stablePackets = 0; nextBatteryRead = 0
        packetTimes.clear(); resetConnectionToasts()
        state = state.copy(battery = null)
        message(if (reconnects == 0) "Connecting to BoBo…" else "Reconnecting to BoBo ($reconnects/3)…")
        try {
            val client = BalanceBoardBLEManager(this, { bytes -> if (token == generation) onPacket(bytes) },
                { level -> if (token == generation) {
                    nextBatteryRead = SystemClock.elapsedRealtime() + BATTERY_REFRESH_MS
                    state = state.copy(battery = level); publish()
                } },
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
                    scheduleConnectionToast()
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
        controller.send(Stick()); analogMapper?.reset(); arrowMapper?.reset(); keyRepeater.reset(); cancelCapture()
        nextBatteryRead = 0
        state = state.copy(connected = false, battery = null, stick = Stick(), rate = 0, status = "$reason. Input paused.")
        publish()
        val token = generation
        retireClient {
            if (token == generation && state.address != null) {
                if (++reconnects <= 3 && permissions() && adapter?.isEnabled == true) {
                    handler.postDelayed({ if (token == generation) connectAttempt() }, 500)
                } else if (autoDiscover) {
                    reconnects = 0
                    autoOutputPending = true
                    state = state.copy(address = null, connected = false, battery = null, stick = Stick(), rate = 0)
                    message("$reason. Watching for BoBo to return…")
                    handler.postDelayed(scanAgain, SCAN_RETRY_MS)
                } else {
                    stopOutput(); message("$reason. Reconnect stopped. Check BoBo is on, tap Scan for BoBo, then connect.")
                }
            }
        }
    }
    fun disconnect() {
        autoDiscover = false; autoOutputPending = false; handler.removeCallbacks(scanAgain)
        ++generation; stopScan(); stopOutput(); cancelCapture(); retireClient()
        lastPacket = 0; readyAt = 0; nextBatteryRead = 0; resetConnectionToasts()
        analogMapper?.reset(); arrowMapper?.reset()
        state = state.copy(address = null, connected = false, battery = null, stick = Stick(), rate = 0, status = "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); publish()
    }
    private fun onPacket(bytes: ByteArray) {
        val raw = PacketParser.parse(bytes)
        if (raw == null) { state = state.copy(rejected = state.rejected + 1); return }
        val now = SystemClock.elapsedRealtime()
        lastPacket = now; packetTimes.addLast(now)
        if (++stablePackets >= 100) reconnects = 0
        if (autoOutputPending && now >= nextOutputAttempt) tryStartOutput(now)
        pendingPose?.let { if (pendingSamples.size < 500) pendingSamples.add(raw) }
        val analog = if (pendingPose == null) analogMapper?.update(raw) ?: Stick() else Stick()
        val arrows = if (pendingPose == null) arrowMapper?.update(raw) ?: Stick() else Stick()
        val stick = when (state.mode) {
            1 -> arrows
            2 -> Stick(analog.x, analog.y, arrows.keys)
            else -> analog
        }
        if (wantOutput) {
            val report = when (state.mode) {
                1 -> Stick(keys = keyRepeater.update(arrows.keys, now))
                2 -> Stick(analog.x, analog.y, keyRepeater.update(arrows.keys, now))
                else -> analog
            }
            controller.send(report)
        }
        val pose = pendingPose?.name.orEmpty()
        csv.addLast("${Instant.now()},$pose,${raw.joinToString(",") { it.toInt().toString() }},${stick.x},${stick.y},${stick.keys}")
        while (csv.size > 10000) csv.removeFirst()
        state = state.copy(packets = state.packets + 1, raw = raw.joinToString(", ") { it.toInt().toString() },
            hex = bytes.joinToString("-") { "%02X".format(it.toInt() and 255) }, stick = stick,
            status = if (pendingPose != null) "Hold ${pendingPose!!.name.lowercase()}: ${pendingSamples.size} packets"
                else if (now < messageUntil) state.status else "Receiving BoBo tilt")
        showControllerToastIfReady()
    }
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            while (packetTimes.isNotEmpty() && now - packetTimes.first() > 1000) packetTimes.removeFirst()
            state = state.copy(rate = packetTimes.size)
            if (state.connected && now >= nextBatteryRead) {
                nextBatteryRead = now + BATTERY_REFRESH_MS
                manager?.readBattery()
            }
            if (pendingPose != null && now >= captureEnds) completeCapture()
            if (state.connected && ((lastPacket > 0 && now - lastPacket > 1000) || (lastPacket == 0L && readyAt > 0 && now - readyAt > 3000))) recover("Tilt stream stopped")
            publish(); handler.postDelayed(this, 200)
        }
    }
    fun capture(pose: Pose) {
        if (!state.connected || SystemClock.elapsedRealtime() - lastPacket > 1000) { message("Connect and wait for live packets first."); return }
        stopOutput("Controller output paused during calibration"); pendingSamples.clear(); pendingPose = pose
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
        ensureOutput()
    }
    fun finishCalibration() {
        val address = state.address ?: return
        runCatching {
            val analog = JoystickMapper.calibrate(samples, calibration.controlsFor(0))
            val arrows = JoystickMapper.calibrate(samples, calibration.controlsFor(1))
            calibration.save(address, samples); analogMapper = analog; arrowMapper = arrows
        }.onSuccess { state = state.copy(calibrated = true); message("Calibration saved."); ensureOutput() }
            .onFailure { message(it.message ?: "Calibration could not be saved") }
    }
    fun closeBoBoHome() {
        message("Closing BoBo Home…")
        runCatching {
            controller.closeBoBoHome { result ->
                message(if (result.isBlank()) "BoBo Home closed." else result)
            }
        }.onFailure { message(it.message ?: "Could not close BoBo Home") }
    }
    fun setMode(mode: Int) {
        val nextMode = mode.coerceIn(0, 2)
        if (nextMode == state.mode) { ensureOutput(); return }
        calibration.mode = nextMode
        keyRepeater.setInterval(state.arrowControls.repeatIntervalMs)
        state = state.copy(mode = nextMode)
        restartOutput()
    }
    fun setControlSettings(profile: Int, settings: ControlSettings) {
        val selected = profile.coerceIn(0, 1)
        val normalized = settings.normalized()
        calibration.saveControls(selected, normalized)
        if (selected == 0) {
            analogMapper?.setControlSettings(normalized)
            state = state.copy(analogControls = normalized)
        } else {
            arrowMapper?.setControlSettings(normalized)
            keyRepeater.setInterval(normalized.repeatIntervalMs)
            state = state.copy(arrowControls = normalized)
        }
        publish()
        ensureOutput()
    }
    fun ensureOutput() {
        autoOutputPending = true
        nextOutputAttempt = 0
        tryStartOutput(SystemClock.elapsedRealtime())
    }
    private fun tryStartOutput(now: Long) {
        if (!autoOutputPending || state.output || controller.binding || !state.connected || analogMapper == null || arrowMapper == null || pendingPose != null ||
            lastPacket == 0L || now - lastPacket > 1000) return
        if (!ControllerLink.available()) {
            nextOutputAttempt = now + OUTPUT_ACCESS_RETRY_MS
            if (state.outputMessage != "Waiting for Shizuku controller access") {
                state = state.copy(outputMessage = "Waiting for Shizuku controller access")
                publish()
            }
            return
        }
        startOutput()
    }
    private fun startOutput() {
        autoOutputPending = false
        nextOutputAttempt = 0
        wantOutput = true; analogMapper?.reset(); arrowMapper?.reset(); keyRepeater.reset()
        runCatching { controller.start(state.mode) }.onFailure {
            wantOutput = false
            autoOutputPending = true
            nextOutputAttempt = SystemClock.elapsedRealtime() + OUTPUT_ACCESS_RETRY_MS
            message(it.message ?: "Controller access failed")
        }
    }
    private fun restartOutput() {
        stopOutput("Restarting controller output…", resume = true)
        handler.postDelayed(outputRestart, OUTPUT_RESTART_MS)
    }
    fun pauseOutputForInputCheck() { stopOutput("Controller output paused for input check") }
    private fun stopOutput(message: String = "Controller output is stopped", resume: Boolean = false) {
        handler.removeCallbacks(outputRestart)
        autoOutputPending = resume
        nextOutputAttempt = 0
        wantOutput = false
        keyRepeater.reset()
        if (::controller.isInitialized) controller.stop()
        state = state.copy(output = false, outputMessage = message); publish()
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
            .put("autoDiscover", autoDiscover).put("autoOutputPending", autoOutputPending)
            .put("reconnects", reconnects).put("lastPacketAgeMs", if (lastPacket == 0L) -1 else SystemClock.elapsedRealtime() - lastPacket))
    }
    override fun onDestroy() {
        ++generation; stopScan(); controller.shutdown(); manager?.close(); manager = null
        retiringClients.forEach { it.close() }; retiringClients.clear()
        handler.removeCallbacksAndMessages(null); listener = null
        super.onDestroy()
    }

    private companion object {
        const val CONNECTION_TOAST_COALESCE_MS = 500L
        const val BATTERY_REFRESH_MS = 60_000L
        const val OUTPUT_ACCESS_RETRY_MS = 2000L
        const val OUTPUT_RESTART_MS = 300L
        const val SCAN_RETRY_MS = 2500L
    }
}
