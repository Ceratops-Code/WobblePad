package io.github.ceratops_code.wobblepad

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.roundToInt

/** Phone setup and live tilt screen. Android handles permission prompts and file export. */
class MainActivity : Activity() {
    private var service: BridgeService? = null
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var access: TextView
    private lateinit var live: TextView
    private lateinit var raw: TextView
    private lateinit var output: TextView
    private lateinit var boards: LinearLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var plot: StickView
    private val poses = mutableMapOf<Pose, Button>()
    private var previousBoards = emptyList<Board>()
    private var displayedOutputMode = -1
    private var displayedControlProfile = 0
    private var displayedControls = ControlSettings()
    private var shizukuRequestPending = false
    private var shizukuRequestAttempted = false
    private var saveText = ""
    private val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { ensureControllerAccess() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) runOnUiThread {
            shizukuRequestPending = false
            showAccess()
            if (result == PackageManager.PERMISSION_GRANTED) service?.ensureOutput()
        }
    }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread {
        shizukuRequestPending = false
        shizukuRequestAttempted = false
        showAccess()
    } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BridgeService.LocalBinder).service
            service?.listener = ::render
            service?.state?.let(::render)
            ensureControllerAccess()
            service?.ensureOutput()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(14, 23, 39)); isFillViewport = true }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(32)) }
        scroll.addView(column); setContentView(scroll)
        title("WobblePad", 28)
        title("Turn your balance board into an Android gamepad.", 15)
        title("Unofficial controller bridge compatible with BoBo Wobbly. Not affiliated with BO&BO Ltd.", 12)
        status = title("Starting…", 17)
        row(button("Scan & auto-connect") { scan() }, button("Disconnect") { service?.disconnect() })
        boards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; column.addView(boards)
        plot = StickView(this); column.addView(plot, LinearLayout.LayoutParams(-1, dp(210)))
        live = title("X 0.00   Y 0.00", 19)
        access = title("", 14)
        column.addView(button("Shizuku setup") { openShizuku() })
        column.addView(button("Close BoBo Home") { service?.closeBoBoHome() })
        output = title("Controller output starts automatically", 15)
        val controlStore = CalibrationStore(this)
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Analog stick", "Arrow keys", "Both").forEachIndexed { i, text ->
            modes.addView(RadioButton(this).apply { id = 100 + i; this.text = text; setTextColor(Color.WHITE) })
        }
        val initialMode = controlStore.mode
        modes.check(100 + initialMode)
        modes.setOnCheckedChangeListener { _, id ->
            val mode = (id - 100).coerceIn(0, 2)
            controlStore.mode = mode
            val profile = if (mode == 1) 1 else 0
            showControlSettings(mode, profile, controlStore.controlsFor(profile))
            service?.setMode(mode)
        }
        column.addView(modes)
        controlsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(controlsPanel)
        val initialProfile = if (initialMode == 1) 1 else 0
        showControlSettings(initialMode, initialProfile, controlStore.controlsFor(initialProfile))
        column.addView(button("Check Android controller input") {
            service?.pauseOutputForInputCheck(); startActivity(Intent(this, InputCheckActivity::class.java))
        })
        title("Calibration", 21)
        title("Calibrate each board before starting controller output. Hold each pose, tap its button, and stay steady for 3 seconds. Up means away from you.", 14)
        for (pose in Pose.entries) {
            poses[pose] = button("Capture ${pose.name.lowercase()}") { service?.capture(pose) }.also { column.addView(it) }
        }
        column.addView(button("Finish calibration") { service?.finishCalibration() })
        raw = title("Raw packets appear here after connection.", 12)
        column.addView(button("Export recent CSV") {
            saveText = service?.csvSnapshot().orEmpty()
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "wobblepad-tilt.csv"), 8)
        })
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderDeadListener(binderDead)
        bindService(Intent(this, BridgeService::class.java), connection, BIND_AUTO_CREATE)
        ensureControllerAccess()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
    }
    override fun onResume() { super.onResume(); if (::access.isInitialized) ensureControllerAccess() }
    override fun onStart() {
        super.onStart()
        service?.listener = ::render
        service?.state?.let(::render)
        service?.ensureOutput()
    }
    override fun onStop() { service?.listener = null; super.onStop() }
    private fun title(text: String, size: Int, parent: LinearLayout = column) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(Color.rgb(229, 239, 245)); setPadding(0, dp(8), 0, dp(8))
        parent.addView(this)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }
    private fun row(vararg views: View) { column.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }) }
    private fun showControlSettings(mode: Int, profile: Int, settings: ControlSettings) {
        displayedOutputMode = mode
        displayedControlProfile = profile.coerceIn(0, 1)
        displayedControls = settings
        controlsPanel.removeAllViews()
        if (mode == 2) {
            val profiles = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            listOf("Stick sensitivity", "Arrow sensitivity").forEachIndexed { index, text ->
                profiles.addView(RadioButton(this).apply { id = 200 + index; this.text = text; setTextColor(Color.WHITE) })
            }
            profiles.check(200 + displayedControlProfile)
            profiles.setOnCheckedChangeListener { _, id ->
                val selected = (id - 200).coerceIn(0, 1)
                showControlSettings(2, selected, CalibrationStore(this).controlsFor(selected))
            }
            controlsPanel.addView(profiles)
        }
        title(if (displayedControlProfile == 0) "Analog stick sensitivity" else "Arrow-key sensitivity", 21, controlsPanel)
        title("Higher sensitivity reaches full input with less tilt. The center dead zone suppresses movement near level.", 14, controlsPanel)
        fun update(transform: (ControlSettings) -> ControlSettings) {
            val updated = transform(displayedControls).normalized()
            displayedControls = updated
            CalibrationStore(this).saveControls(displayedControlProfile, updated)
            service?.setControlSettings(displayedControlProfile, updated)
        }
        percentSlider("Left sensitivity", settings.left, 50, 200, controlsPanel) { value -> update { it.copy(left = value) } }
        percentSlider("Right sensitivity", settings.right, 50, 200, controlsPanel) { value -> update { it.copy(right = value) } }
        percentSlider("Up / forward sensitivity", settings.up, 50, 200, controlsPanel) { value -> update { it.copy(up = value) } }
        percentSlider("Down / backward sensitivity", settings.down, 50, 200, controlsPanel) { value -> update { it.copy(down = value) } }
        percentSlider("Center dead zone", settings.deadZone, 0, 30, controlsPanel) { value -> update { it.copy(deadZone = value) } }
        if (displayedControlProfile == 1) millisecondSlider("Key repeat interval", settings.repeatIntervalMs, MIN_KEY_REPEAT_MS,
            MAX_KEY_REPEAT_MS, controlsPanel) { value -> update { it.copy(repeatIntervalMs = value) } }
    }
    private fun percentSlider(label: String, initial: Double, minimum: Int, maximum: Int, parent: LinearLayout = column,
        onChange: (Double) -> Unit) = integerSlider(label, (initial * 100).roundToInt(), minimum, maximum, "%", parent) {
            onChange(it / 100.0)
        }
    private fun millisecondSlider(label: String, initial: Int, minimum: Int, maximum: Int, parent: LinearLayout = column,
        onChange: (Int) -> Unit) = integerSlider(label, initial, minimum, maximum, " ms", parent, onChange)
    private fun integerSlider(label: String, initial: Int, minimum: Int, maximum: Int, suffix: String, parent: LinearLayout,
        onChange: (Int) -> Unit) {
        val value = title("", 14, parent)
        val slider = SeekBar(this).apply {
            max = maximum - minimum
            progress = initial.coerceIn(minimum, maximum) - minimum
        }
        fun render(progress: Int) { value.text = "$label: ${minimum + progress}$suffix" }
        render(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                render(progress)
                if (fromUser) onChange(minimum + progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        parent.addView(slider)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun scan() {
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions, 1)
        else service?.scan()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) service?.scan()
    }
    private fun showAccess() {
        access.text = when {
            ControllerLink.available() -> "Controller access: allowed through Shizuku"
            shizukuRequestPending -> "Requesting controller access through Shizuku…"
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Controller access is not allowed. Review WobblePad in Shizuku."
            else -> "Start Shizuku to enable automatic controller output. Live tilt works without it."
        }
    }
    private fun ensureControllerAccess() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (ControllerLink.available()) {
            shizukuRequestPending = false
            showAccess()
            service?.ensureOutput()
            return
        }
        if (!running || shizukuRequestPending || shizukuRequestAttempted) { showAccess(); return }
        shizukuRequestAttempted = true
        shizukuRequestPending = true
        showAccess()
        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
            .onFailure {
                shizukuRequestPending = false
                service?.message("Controller permission failed: ${it.message}")
                showAccess()
            }
    }
    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) startActivity(launch)
        else startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")))
    }
    private fun render(state: BridgeState) {
        status.text = state.status
        plot.stick = state.stick; plot.invalidate()
        live.text = String.format(Locale.US, "X %+.2f   Y %+.2f\n%d packets/sec   •   BoBo battery %s", state.stick.x, state.stick.y,
            state.rate, state.battery?.let { "$it%" } ?: "—")
        output.text = state.outputMessage
        val profile = if (state.mode == 2) displayedControlProfile else if (state.mode == 1) 1 else 0
        val controls = if (profile == 0) state.analogControls else state.arrowControls
        if (displayedOutputMode != state.mode || displayedControlProfile != profile || displayedControls != controls)
            showControlSettings(state.mode, profile, controls)
        poses.forEach { (pose, button) ->
            button.text = "Capture ${pose.name.lowercase()}" + (state.counts[pose]?.let { " ✓ ($it)" } ?: "")
            button.isEnabled = state.connected && state.capture == null
        }
        raw.text = "Packets: ${state.packets}   Rejected: ${state.rejected}\n${state.hex}\n${state.raw}"
        if (previousBoards != state.boards) {
            previousBoards = state.boards; boards.removeAllViews()
            state.boards.forEach { board -> boards.addView(button("Connect ${board.name}\n${board.address}") { service?.connect(board.address) }) }
        }
    }
    @Deprecated("Platform activity result used to keep this small native prototype dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 8 && resultCode == RESULT_OK) data?.data?.let { uri ->
            runCatching { checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(saveText) } }
                .onSuccess { service?.message("CSV exported (up to the latest 10,000 packets).") }
                .onFailure { service?.message("CSV export failed: ${it.message}") }
        }
        saveText = ""
    }
    override fun onDestroy() {
        service?.listener = null; unbindService(connection)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST = 2
    }
}

class StickView(context: Context) : View(context) {
    var stick = Stick()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) * 0.4f
        val x = width / 2f; val y = height / 2f
        paint.color = Color.rgb(42, 60, 78); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        canvas.drawCircle(x, y, radius, paint)
        canvas.drawLine(x - radius, y, x + radius, y, paint); canvas.drawLine(x, y - radius, x, y + radius, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(86, 229, 179)
        canvas.drawCircle(x + stick.x * radius, y - stick.y * radius, 12 * resources.displayMetrics.density, paint)
    }
}
