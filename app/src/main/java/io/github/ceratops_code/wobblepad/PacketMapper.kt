package io.github.ceratops_code.wobblepad

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

enum class Pose { CENTER, LEFT, RIGHT, UP, DOWN }

const val DEFAULT_KEY_REPEAT_MS = 333
const val MIN_KEY_REPEAT_MS = 100
const val MAX_KEY_REPEAT_MS = 1000

/** The observed frame has nine signed fields; their physical meanings are not assumed. */
object PacketParser {
    fun parse(packet: ByteArray): DoubleArray? {
        if (packet.size != 20 || packet[0] != 0x41.toByte() || packet[19] != 0x42.toByte()) return null
        val buffer = ByteBuffer.wrap(packet, 1, 18).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(9) { buffer.short.toDouble() }
    }
}

data class Stick(val x: Float = 0f, val y: Float = 0f, val keys: Int = 0)

data class ControlSettings(
    val left: Double = 1.0,
    val right: Double = 1.0,
    val up: Double = 1.0,
    val down: Double = 1.0,
    val deadZone: Double = 0.08,
    val repeatIntervalMs: Int = DEFAULT_KEY_REPEAT_MS,
) {
    fun normalized() = ControlSettings(
        left = left.coerceIn(0.5, 2.0),
        right = right.coerceIn(0.5, 2.0),
        up = up.coerceIn(0.5, 2.0),
        down = down.coerceIn(0.5, 2.0),
        deadZone = deadZone.coerceIn(0.0, 0.30),
        repeatIntervalMs = repeatIntervalMs.coerceIn(MIN_KEY_REPEAT_MS, MAX_KEY_REPEAT_MS),
    )
}

/** Converts a held logical direction into short reports separated by neutral reports. */
class KeyPulseRepeater(intervalMs: Int = DEFAULT_KEY_REPEAT_MS) {
    private var intervalMs = intervalMs.coerceIn(MIN_KEY_REPEAT_MS, MAX_KEY_REPEAT_MS)
    private var requested = 0
    private var pulseUntil = 0L
    private var nextPulse = 0L

    fun setInterval(value: Int) {
        intervalMs = value.coerceIn(MIN_KEY_REPEAT_MS, MAX_KEY_REPEAT_MS)
        reset()
    }

    fun reset() {
        requested = 0
        pulseUntil = 0L
        nextPulse = 0L
    }

    fun update(keys: Int, nowMs: Long = System.nanoTime() / 1_000_000): Int {
        val next = keys and 0x0F
        if (next == 0) {
            reset()
            return 0
        }
        if (next != requested) {
            requested = next
            pulseUntil = nowMs + min(60, intervalMs / 2).toLong()
            nextPulse = nowMs + intervalMs
            return requested
        }
        if (nowMs >= nextPulse) {
            pulseUntil = nowMs + min(60, intervalMs / 2).toLong()
            nextPulse = nowMs + intervalMs
        }
        return if (nowMs < pulseUntil) requested else 0
    }
}

/** Least-squares calibration plus a time-based filter, radial dead zone and key hysteresis. */
class JoystickMapper private constructor(
    private val center: DoubleArray,
    private val a: DoubleArray,
    private val b: DoubleArray,
    private val gains: DoubleArray,
    settings: ControlSettings,
) {
    private var settings = settings.normalized()
    private var filteredX = 0.0
    private var filteredY = 0.0
    private var lastNanos = 0L
    private var keys = 0

    fun reset() { filteredX = 0.0; filteredY = 0.0; lastNanos = 0; keys = 0 }
    fun setControlSettings(value: ControlSettings) { settings = value.normalized() }

    fun project(v: DoubleArray): Pair<Double, Double> {
        val d = DoubleArray(9) { v[it] - center[it] }
        val aa = dot(a, a); val ab = dot(a, b); val bb = dot(b, b)
        val ad = dot(a, d); val bd = dot(b, d); val determinant = aa * bb - ab * ab
        return (ad * bb - bd * ab) / determinant to (bd * aa - ad * ab) / determinant
    }

    fun update(values: DoubleArray, nowNanos: Long = System.nanoTime()): Stick {
        val (px, py) = project(values)
        val controls = settings
        val x = px / gains[if (px >= 0) 0 else 1] * if (px >= 0) controls.right else controls.left
        val y = py / gains[if (py >= 0) 2 else 3] * if (py >= 0) controls.up else controls.down
        val alpha = if (lastNanos == 0L) 1.0 else 1 - exp(-max(0L, nowNanos - lastNanos) / 60_000_000.0)
        filteredX += alpha * (x - filteredX); filteredY += alpha * (y - filteredY)
        lastNanos = nowNanos
        val radius = hypot(filteredX, filteredY)
        val scale = if (radius <= controls.deadZone) 0.0 else
            min(1.0, (radius - controls.deadZone) / (1.0 - controls.deadZone)) / radius
        val ox = (filteredX * scale).toFloat(); val oy = (filteredY * scale).toFloat()
        var next = 0
        for ((bit, value) in listOf(1 to -ox, 2 to ox, 4 to oy, 8 to -oy)) {
            if (value >= if (keys and bit != 0) 0.25f else 0.35f) next = next or bit
        }
        keys = next
        return Stick(ox, oy, keys)
    }

    companion object {
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        fun average(samples: List<DoubleArray>) = DoubleArray(9) { i -> samples.sumOf { it[i] } / samples.size }

        fun calibrate(
            samples: Map<Pose, List<DoubleArray>>,
            settings: ControlSettings = ControlSettings(),
        ): JoystickMapper {
            require(Pose.entries.all { samples[it]?.size in 10..500 }) { "Capture all five poses with at least 10 packets each." }
            require(samples.values.flatten().all { it.size == 9 && it.all { n -> n.isFinite() && n in -32768.0..32767.0 } }) {
                "Calibration contains invalid sensor values."
            }
            val means = samples.mapValues { average(it.value) }
            val c = means.getValue(Pose.CENTER)
            val a = DoubleArray(9) { (means.getValue(Pose.RIGHT)[it] - means.getValue(Pose.LEFT)[it]) / 2 }
            val b = DoubleArray(9) { (means.getValue(Pose.UP)[it] - means.getValue(Pose.DOWN)[it]) / 2 }
            val aa = dot(a, a); val bb = dot(b, b); val ab = dot(a, b)
            val noise = samples.maxOf { (pose, vectors) ->
                sqrt(vectors.sumOf { v -> v.indices.sumOf { i -> (v[i] - means.getValue(pose)[i]).pow(2) } } / vectors.size)
            }
            require(min(aa, bb) > max(1.0, 4 * noise).pow(2)) { "The poses moved too much during capture, or the tilts were too small. Recapture steady poses." }
            require(aa * bb - ab * ab > 0.01 * aa * bb) { "Left/right and forward/back were too similar. Recapture distinct directions." }
            val mapper = JoystickMapper(c, a, b, doubleArrayOf(1.0, 1.0, 1.0, 1.0), settings)
            val gains = doubleArrayOf(mapper.project(means.getValue(Pose.RIGHT)).first,
                -mapper.project(means.getValue(Pose.LEFT)).first,
                mapper.project(means.getValue(Pose.UP)).second, -mapper.project(means.getValue(Pose.DOWN)).second)
            require(gains.all { it > 0.2 }) { "Center must lie between the opposite tilts. Recapture center and directions." }
            return JoystickMapper(c, a, b, gains, settings)
        }
    }
}
