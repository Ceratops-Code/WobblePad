package io.github.ceratops_code.wobblepad

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs under Shizuku's shell identity, not in the ordinary application process.
 * Uses the kernel's public UHID ABI. Closing the FD removes the device.
 * A watchdog releases all input if the app stops supplying fresh commands.
 */
class ControllerUserService : IController.Stub() {
    private var fd: FileDescriptor? = null
    private var mode = 0
    private var lastCommand = 0L
    private var lastReport = byteArrayOf()
    private var error = ""
    private val timer = Executors.newSingleThreadScheduledExecutor()
    init {
        timer.scheduleWithFixedDelay({ synchronized(this) {
            if (fd != null && SystemClock.elapsedRealtime() - lastCommand > 1000) {
                runCatching { writeReport(neutral()) }.onFailure { close(); error = it.message ?: "Input watchdog failed" }
            }
        } }, 200, 200, TimeUnit.MILLISECONDS)
    }
    @Synchronized override fun open(mode: Int): String {
        close()
        require(mode in 0..1)
        this.mode = mode
        return try {
            val descriptor = if (mode == 0) hex(GAMEPAD) else hex(KEYBOARD)
            val handle = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            fd = handle
            val request = ByteBuffer.allocate(280 + descriptor.size).order(ByteOrder.nativeOrder())
            request.putInt(11) // UHID_CREATE2
            request.put(name(mode).toByteArray(Charsets.UTF_8))
            request.position(132)
            request.put("wobblepad:${Process.myPid()}".toByteArray(Charsets.UTF_8))
            request.position(260)
            request.putShort(descriptor.size.toShort()).putShort(0x06) // BUS_VIRTUAL
            request.putInt(0x0b0b).putInt(2 + mode).putInt(1).putInt(0).put(descriptor)
            writeEvent(request.array())
            lastCommand = SystemClock.elapsedRealtime(); error = ""; lastReport = byteArrayOf()
            ""
        } catch (e: Exception) {
            close(); error = "Controller access failed: ${e.message}"; error
        }
    }
    @Synchronized override fun send(x: Float, y: Float, keys: Int) {
        check(fd != null) { "Controller is closed" }
        require(x.isFinite() && y.isFinite() && keys in 0..15)
        lastCommand = SystemClock.elapsedRealtime()
        val report = if (mode == 0) ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            .putShort((y.coerceIn(-1f, 1f) * 32767).toInt().toShort()).put(0).array()
        else ByteArray(8).also { bytes ->
            var i = 2
            for ((mask, usage) in listOf(1 to 0x50, 2 to 0x4f, 4 to 0x52, 8 to 0x51)) {
                if (keys and mask != 0) bytes[i++] = usage.toByte()
            }
        }
        writeReport(report)
    }
    private fun neutral() = if (mode == 0) byteArrayOf(1, 0, 0, 0, 0, 0) else ByteArray(8)
    private fun writeReport(report: ByteArray) {
        if (report.contentEquals(lastReport)) return
        val event = ByteBuffer.allocate(6 + report.size).order(ByteOrder.nativeOrder())
            .putInt(12).putShort(report.size.toShort()).put(report).array() // UHID_INPUT2
        writeEvent(event); lastReport = report
    }
    private fun writeEvent(event: ByteArray) {
        val n = Os.write(checkNotNull(fd), event, 0, event.size)
        check(n == event.size) { "Incomplete UHID event" }
    }
    @Synchronized override fun close() {
        fd?.let { handle ->
            runCatching { writeReport(neutral()) }
            runCatching { Os.close(handle) }
        }
        fd = null; lastReport = byteArrayOf()
    }
    @Synchronized override fun status(): String = "uid=${Process.myUid()};open=${fd != null};$error"
    @Synchronized override fun closeBoBoHome(): String = try {
        val process = ProcessBuilder("/system/bin/am", "force-stop", "--user", "current", BOBO_HOME_PACKAGE)
            .redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Closing BoBo Home timed out."
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.exitValue() == 0) "" else "Could not close BoBo Home: ${output.ifBlank { "exit ${process.exitValue()}" }}"
        }
    } catch (e: Exception) {
        "Could not close BoBo Home: ${e.message}"
    }
    override fun destroy() { close(); timer.shutdownNow(); kotlin.system.exitProcess(0) }
    companion object {
        private const val BOBO_HOME_PACKAGE = "com.bobo.home"
        fun name(mode: Int) = if (mode == 0) "WobblePad Gamepad" else "WobblePad Keys"
        private fun hex(text: String) = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        // Input-only descriptors need no LED, output or feature-report transport.
        private const val GAMEPAD = "05 01 09 05 A1 01 85 01 09 30 09 31 16 01 80 26 FF 7F 75 10 95 02 81 02 05 09 19 01 29 04 15 00 25 01 75 01 95 04 81 02 75 04 95 01 81 03 C0"
        private const val KEYBOARD = "05 01 09 06 A1 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 75 08 95 01 81 03 19 00 29 65 15 00 25 65 75 08 95 06 81 00 C0"
    }
}
