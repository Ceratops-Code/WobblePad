package io.github.ceratops_code.wobblepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class JoystickMapperTest {
    @Test
    fun calibratedCardinalPosesReachFullScaleAndExpectedKeys() {
        val mapper = JoystickMapper.calibrate(calibration())

        assertEquals(1.0, mapper.project(vector(100.0, 0.0)).first, EPSILON)
        assertEquals(-1.0, mapper.project(vector(-100.0, 0.0)).first, EPSILON)
        assertEquals(1.0, mapper.project(vector(0.0, 100.0)).second, EPSILON)
        assertEquals(-1.0, mapper.project(vector(0.0, -100.0)).second, EPSILON)

        assertEquals(2, mapper.update(vector(100.0, 0.0), 1L).keys)
        mapper.reset()
        assertEquals(4, mapper.update(vector(0.0, 100.0), 1L).keys)
    }

    @Test
    fun radialDeadZoneSuppressesNoiseAndRescalesMovement() {
        val mapper = JoystickMapper.calibrate(calibration())

        assertEquals(0f, mapper.update(vector(5.0, 0.0), 1L).x, 0f)
        mapper.reset()
        assertEquals(((0.5 - 0.08) / 0.92).toFloat(), mapper.update(vector(50.0, 0.0), 1L).x, 0.0001f)
    }

    @Test
    fun smoothingUsesControlledTimeWithoutWaiting() {
        val mapper = JoystickMapper.calibrate(calibration())

        val first = mapper.update(vector(100.0, 0.0), 1_000_000L)
        val unchanged = mapper.update(vector(-100.0, 0.0), 1_000_000L)
        val settled = mapper.update(vector(-100.0, 0.0), 10_001_000_000L)

        assertEquals(1f, first.x, 0.0001f)
        assertEquals(1f, unchanged.x, 0.0001f)
        assertTrue(settled.x < -0.99f)
    }

    @Test
    fun keyHysteresisPreventsThresholdChatter() {
        val mapper = JoystickMapper.calibrate(calibration())

        val pressed = mapper.update(vector(50.0, 0.0), 1L)
        val retained = mapper.update(vector(35.0, 0.0), 10_000_000_001L)
        val released = mapper.update(vector(20.0, 0.0), 20_000_000_001L)

        assertEquals(2, pressed.keys)
        assertEquals(2, retained.keys)
        assertEquals(0, released.keys)
    }

    @Test
    fun diagonalOutputIsClampedRadially() {
        val mapper = JoystickMapper.calibrate(calibration())

        val stick = mapper.update(vector(100.0, 100.0), 1L)

        assertEquals(1.0, kotlin.math.hypot(stick.x.toDouble(), stick.y.toDouble()), 0.0001)
        assertEquals(2 or 4, stick.keys)
    }

    @Test
    fun calibrationRejectsMissingInvalidAndIndistinctPoses() {
        val missing = calibration().toMutableMap().apply { remove(Pose.DOWN) }
        val invalid = calibration().toMutableMap().apply {
            this[Pose.RIGHT] = samples(vector(40_000.0, 0.0))
        }
        val collinear = calibration().toMutableMap().apply {
            this[Pose.UP] = samples(vector(100.0, 0.0))
            this[Pose.DOWN] = samples(vector(-100.0, 0.0))
        }

        assertThrows(IllegalArgumentException::class.java) { JoystickMapper.calibrate(missing) }
        assertThrows(IllegalArgumentException::class.java) { JoystickMapper.calibrate(invalid) }
        assertThrows(IllegalArgumentException::class.java) { JoystickMapper.calibrate(collinear) }
    }

    private fun calibration(): Map<Pose, List<DoubleArray>> = mapOf(
        Pose.CENTER to samples(vector(0.0, 0.0)),
        Pose.LEFT to samples(vector(-100.0, 0.0)),
        Pose.RIGHT to samples(vector(100.0, 0.0)),
        Pose.UP to samples(vector(0.0, 100.0)),
        Pose.DOWN to samples(vector(0.0, -100.0)),
    )

    private fun samples(value: DoubleArray): List<DoubleArray> =
        List(10) { value.copyOf() }

    private fun vector(x: Double, y: Double): DoubleArray =
        DoubleArray(9).apply { this[0] = x; this[1] = y }

    private companion object {
        const val EPSILON = 0.000001
    }
}
