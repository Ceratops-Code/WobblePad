package io.github.ceratops_code.wobblepad

import android.content.*
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.*
import android.view.InputDevice
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns one Shizuku user service. Input is conflated so a slow binder cannot queue old tilts. */
class ControllerLink(private val context: Context, private val changed: (Boolean, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private val latest = AtomicReference<Stick?>()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ControllerUserService::class.java))
        .daemon(false).processNameSuffix("wobblepad_input").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private val commandArgs = Shizuku.UserServiceArgs(ComponentName(context, ControllerUserService::class.java))
        .daemon(false).processNameSuffix("wobblepad_command").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    @Volatile private var remote: IController? = null
    @Volatile private var wanted = false
    private var mode = 0
    private var generation = 0
    private var commandConnection: ServiceConnection? = null
    private var commandResult: ((String) -> Unit)? = null
    var ready = false; private set
    var binding = false; private set
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!wanted) { runCatching { Shizuku.unbindUserService(args, this, true) }; return }
            val token = generation
            val service = IController.Stub.asInterface(binder)
            remote = service
            sender.execute {
                val failure = runCatching { service.open(mode) }.getOrElse { it.message ?: "Controller service failed" }
                handler.post {
                    if (token != generation) return@post
                    if (failure.isNotEmpty()) fail(failure) else awaitInputDevice(token, SystemClock.elapsedRealtime() + 5000)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { if (wanted) fail("Controller helper stopped. Start Shizuku and reconnect controller access.") }
    }
    init {
        sender.scheduleWithFixedDelay({
            val input = latest.getAndSet(null)
            val service = remote
            if (input != null && service != null && wanted) {
                runCatching { service.send(input.x, -input.y, input.keys) }
                    .onFailure { handler.post { if (remote === service) fail("Controller output stopped: ${it.message}") } }
            }
        }, 0, 20, TimeUnit.MILLISECONDS)
    }
    fun start(mode: Int) {
        if (binding || ready) return
        check(available()) { "Start Shizuku and allow WobblePad to use it first." }
        wanted = true; binding = true; this.mode = mode; generation++
        changed(false, "Creating Android controller…")
        try { Shizuku.bindUserService(args, connection) } catch (e: Exception) { fail(e.message ?: "Could not start controller helper") }
        val token = generation
        handler.postDelayed({ if (token == generation && binding) fail("Controller helper did not start. Check Shizuku and try again.") }, 10000)
    }
    private fun awaitInputDevice(token: Int, deadline: Long) {
        if (token != generation || !wanted) return
        val expected = ControllerUserService.names(mode).toSet()
        val found = InputDevice.getDeviceIds().map { InputDevice.getDevice(it)?.name }.filterNotNull().containsAll(expected)
        if (found) {
            ready = true; binding = false; latest.set(Stick())
            changed(true, "Controller ready")
        } else if (SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed({ awaitInputDevice(token, deadline) }, 100)
        } else fail("Android did not register the controller. This device's input access needs checking.")
    }
    fun send(stick: Stick) { if (ready) latest.set(stick) }
    fun closeBoBoHome(completed: (String) -> Unit) {
        check(available()) { "Start Shizuku and allow WobblePad to use it first." }
        remote?.let { service ->
            sender.execute {
                val result = runCatching { service.closeBoBoHome() }
                    .getOrElse { "Could not close BoBo Home: ${it.message}" }
                handler.post { completed(result) }
            }
            return
        }
        check(commandConnection == null) { "BoBo Home is already being closed." }
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = IController.Stub.asInterface(binder)
                sender.execute {
                    val result = runCatching { service.closeBoBoHome() }
                        .getOrElse { "Could not close BoBo Home: ${it.message}" }
                    handler.post { if (commandConnection === connection) finishCommand(result) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (commandConnection === connection) finishCommand("BoBo Home command service stopped unexpectedly.")
            }
        }
        commandConnection = connection
        commandResult = completed
        try {
            Shizuku.bindUserService(commandArgs, connection)
        } catch (e: Exception) {
            commandConnection = null
            commandResult = null
            throw e
        }
        handler.postDelayed({
            if (commandConnection === connection) finishCommand("Closing BoBo Home timed out.")
        }, 10000)
    }
    private fun finishCommand(message: String) {
        val connection = commandConnection ?: return
        commandConnection = null
        val completed = commandResult
        commandResult = null
        runCatching { Shizuku.unbindUserService(commandArgs, connection, true) }
        completed?.invoke(message)
    }
    private fun fail(message: String) { stop(); changed(false, message) }
    fun stop() {
        generation++; wanted = false; ready = false; binding = false; latest.set(null)
        remote = null
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
    fun shutdown() {
        commandConnection?.let { runCatching { Shizuku.unbindUserService(commandArgs, it, true) } }
        commandConnection = null; commandResult = null
        stop(); sender.shutdownNow()
    }
    companion object {
        fun available() = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }
}
