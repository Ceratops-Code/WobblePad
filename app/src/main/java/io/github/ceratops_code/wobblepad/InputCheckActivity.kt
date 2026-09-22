package io.github.ceratops_code.wobblepad

import android.app.Activity
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*

/** A visible, opt-in synthetic controller check. Receives real Android input events;
 * it does not read back requested values or substitute for a live BLE test.
 */
class InputCheckActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var link: ControllerLink
    private lateinit var status: TextView
    private lateinit var received: TextView
    private lateinit var plot: StickView
    private var token = 0
    private var testMode = 0
    private var events = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 70, 28, 28) }
        setContentView(layout)
        layout.addView(TextView(this).apply { text = "Android controller check"; textSize = 25f })
        layout.addView(TextView(this).apply { text = "This sends a short synthetic sequence without moving the board. It checks controller access on this device."; textSize = 16f })
        status = TextView(this); layout.addView(status)
        plot = StickView(this); layout.addView(plot, LinearLayout.LayoutParams(-1, 400))
        received = TextView(this); layout.addView(received)
        link = ControllerLink(this) { ready, message ->
            status.text = message
            if (ready) sequence(token)
        }
        for ((mode, title) in listOf(0 to "Test analog controller", 1 to "Test arrow keys")) layout.addView(Button(this).apply {
            text = title; isAllCaps = false
            setOnClickListener {
                stop(); testMode = mode; events = 0
                runCatching { link.start(mode) }.onFailure { status.text = it.message }
            }
        })
        layout.addView(Button(this).apply { text = "Stop check"; setOnClickListener { stop(); status.text = "Check stopped; controller removed" } })
        layout.addView(Button(this).apply { text = "Back to WobblePad"; setOnClickListener { finish() } })
    }
    private fun sequence(id: Int) {
        val sequence = listOf(Stick(), Stick(-1f, 0f, 1), Stick(), Stick(1f, 0f, 2), Stick(),
            Stick(0f, 1f, 4), Stick(), Stick(0f, -1f, 8), Stick(), Stick(0.5f, 0.25f, 2), Stick())
        sequence.forEachIndexed { i, stick -> handler.postDelayed({
            if (id == token && link.ready) {
                link.send(stick)
                Log.i("WobblePadCheck", "SENT mode=$testMode x=${stick.x} y=${stick.y} keys=${stick.keys}")
            }
        }, i * 550L + 300) }
        handler.postDelayed({ if (id == token) { stop(); status.text = "Check finished: $events Android events received. Controller removed." } }, sequence.size * 550L + 600)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device?.name == ControllerUserService.names(0).single() && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val x = event.getAxisValue(MotionEvent.AXIS_X); val y = event.getAxisValue(MotionEvent.AXIS_Y)
            plot.stick = Stick(x, -y); plot.invalidate(); events++
            received.text = "Android received: X $x, Y $y\nEvents: $events"
            Log.i("WobblePadCheck", "RECEIVED analog x=$x y=$y")
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.device?.name == ControllerUserService.names(1).single()) {
            events++; received.text = "Android received: ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == 0) "down" else "up"}\nEvents: $events"
            Log.i("WobblePadCheck", "RECEIVED key=${event.keyCode} action=${event.action}")
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun stop() { token++; handler.removeCallbacksAndMessages(null); link.stop() }
    override fun onPause() { super.onPause(); stop() }
    override fun onDestroy() { link.shutdown(); super.onDestroy() }
}
