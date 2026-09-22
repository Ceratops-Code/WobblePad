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
    private data class Device(val kind: Int, val fd: FileDescriptor, var lastReport: ByteArray = byteArrayOf())
    private val devices = mutableMapOf<Int, Device>()
    private var lastCommand = 0L
    private var error = ""
    private val timer = Executors.newSingleThreadScheduledExecutor()
    init {
        timer.scheduleWithFixedDelay({ synchronized(this) {
            if (devices.isNotEmpty() && SystemClock.elapsedRealtime() - lastCommand > 1000) {
                runCatching { devices.values.forEach { writeReport(it, neutral(it.kind)) } }
                    .onFailure { close(); error = it.message ?: "Input watchdog failed" }
            }
        } }, 200, 200, TimeUnit.MILLISECONDS)
    }
    @Synchronized override fun open(mode: Int): String {
        close()
        require(mode in 0..2)
        return try {
            kinds(mode).forEach { kind -> devices[kind] = createDevice(kind) }
            lastCommand = SystemClock.elapsedRealtime(); error = ""
            ""
        } catch (e: Exception) {
            close(); error = "Controller access failed: ${e.message}"; error
        }
    }
    @Synchronized override fun send(x: Float, y: Float, keys: Int) {
        check(devices.isNotEmpty()) { "Controller is closed" }
        require(x.isFinite() && y.isFinite() && keys in 0..15)
        lastCommand = SystemClock.elapsedRealtime()
        devices.values.forEach { device ->
            val report = if (device.kind == 0) ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                .putShort((y.coerceIn(-1f, 1f) * 32767).toInt().toShort()).put(0).array()
            else ByteArray(8).also { bytes ->
                var i = 2
                for ((mask, usage) in listOf(1 to 0x50, 2 to 0x4f, 4 to 0x52, 8 to 0x51)) {
                    if (keys and mask != 0) bytes[i++] = usage.toByte()
                }
            }
            writeReport(device, report)
        }
    }
    private fun createDevice(kind: Int): Device {
        val descriptor = if (kind == 0) hex(GAMEPAD) else hex(KEYBOARD)
        val handle = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        return try {
            val request = ByteBuffer.allocate(280 + descriptor.size).order(ByteOrder.nativeOrder())
            request.putInt(11) // UHID_CREATE2
            request.put(nameForKind(kind).toByteArray(Charsets.UTF_8))
            request.position(132)
            request.put("wobblepad:${Process.myPid()}:$kind".toByteArray(Charsets.UTF_8))
            request.position(260)
            request.putShort(descriptor.size.toShort()).putShort(0x06) // BUS_VIRTUAL
            request.putInt(0x0b0b).putInt(2 + kind).putInt(1).putInt(0).put(descriptor)
            writeEvent(handle, request.array())
            Device(kind, handle)
        } catch (error: Exception) {
            runCatching { Os.close(handle) }
            throw error
        }
    }
    private fun neutral(kind: Int) = if (kind == 0) byteArrayOf(1, 0, 0, 0, 0, 0) else ByteArray(8)
    private fun writeReport(device: Device, report: ByteArray) {
        if (report.contentEquals(device.lastReport)) return
        val event = ByteBuffer.allocate(6 + report.size).order(ByteOrder.nativeOrder())
            .putInt(12).putShort(report.size.toShort()).put(report).array() // UHID_INPUT2
        writeEvent(device.fd, event); device.lastReport = report
    }
    private fun writeEvent(handle: FileDescriptor, event: ByteArray) {
        val n = Os.write(handle, event, 0, event.size)
        check(n == event.size) { "Incomplete UHID event" }
    }
    @Synchronized override fun close() {
        devices.values.forEach { device ->
            runCatching { writeReport(device, neutral(device.kind)) }
            runCatching { Os.close(device.fd) }
        }
        devices.clear()
    }
    @Synchronized override fun status(): String = "uid=${Process.myUid()};open=${devices.isNotEmpty()};$error"
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
        fun names(mode: Int) = kinds(mode).map(::nameForKind)
        private fun kinds(mode: Int) = if (mode == 2) listOf(0, 1) else listOf(mode.coerceIn(0, 1))
        private fun nameForKind(kind: Int) = if (kind == 0) "WobblePad Gamepad" else "WobblePad Keys"
        private fun hex(text: String) = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        // Input-only descriptors need no LED, output or feature-report transport.
        private const val GAMEPAD = "05 01 09 05 A1 01 85 01 09 30 09 31 16 01 80 26 FF 7F 75 10 95 02 81 02 05 09 19 01 29 04 15 00 25 01 75 01 95 04 81 02 75 04 95 01 81 03 C0"
        private const val KEYBOARD = "05 01 09 06 A1 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 75 08 95 01 81 03 19 00 29 65 15 00 25 65 75 08 95 06 81 00 C0"
    }
}
