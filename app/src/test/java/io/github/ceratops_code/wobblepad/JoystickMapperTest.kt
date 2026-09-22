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
    fun directionalSensitivityScalesEachDirectionIndependently() {
        val mapper = JoystickMapper.calibrate(calibration(), ControlSettings(
            left = 0.5, right = 2.0, up = 1.5, down = 0.75, deadZone = 0.0,
        ))

        assertEquals(1f, mapper.update(vector(50.0, 0.0), 1L).x, 0.0001f)
        mapper.reset()
        assertEquals(-0.25f, mapper.update(vector(-50.0, 0.0), 1L).x, 0.0001f)
        mapper.reset()
        assertEquals(0.75f, mapper.update(vector(0.0, 50.0), 1L).y, 0.0001f)
        mapper.reset()
        assertEquals(-0.375f, mapper.update(vector(0.0, -50.0), 1L).y, 0.0001f)
    }

    @Test
    fun centerDeadZoneIsConfigurableSeparately() {
        val mapper = JoystickMapper.calibrate(calibration(), ControlSettings(deadZone = 0.20))

        assertEquals(0f, mapper.update(vector(15.0, 0.0), 1L).x, 0f)
        mapper.reset()
        assertEquals(0.5f, mapper.update(vector(60.0, 0.0), 1L).x, 0.0001f)
    }

    @Test
    fun sharedConformanceCasesMatchExpectedOutputs() {
        val cases = loadConformanceCases()

        assertTrue(cases.isNotEmpty())
        for (testCase in cases) {
            val mapper = JoystickMapper.calibrate(
                mapOf(
                    Pose.CENTER to packetSamples(testCase.centerPacket),
                    Pose.LEFT to packetSamples(testCase.leftPacket),
                    Pose.RIGHT to packetSamples(testCase.rightPacket),
                    Pose.UP to packetSamples(testCase.upPacket),
                    Pose.DOWN to packetSamples(testCase.downPacket),
                ),
                testCase.settings,
            )
            val output = mapper.update(packetVector(testCase.currentPacket), 1L)

            assertEquals("${testCase.id} x", testCase.expectedX, output.x.toDouble(), 0.0001)
            assertEquals("${testCase.id} y", testCase.expectedY, output.y.toDouble(), 0.0001)
            assertEquals("${testCase.id} keys", testCase.expectedKeys, output.keys)
        }
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
    fun heldDirectionProducesConfigurableRepeatedKeyPulses() {
        val repeater = KeyPulseRepeater(333)

        assertEquals(1, repeater.update(1, 0))
        assertEquals(1, repeater.update(1, 59))
        assertEquals(0, repeater.update(1, 60))
        assertEquals(0, repeater.update(1, 332))
        assertEquals(1, repeater.update(1, 333))
        assertEquals(0, repeater.update(1, 393))

        repeater.setInterval(200)
        assertEquals(1, repeater.update(1, 400))
        assertEquals(0, repeater.update(1, 460))
        assertEquals(1, repeater.update(1, 600))
    }

    @Test
    fun repeatIntervalIsBoundedWithTheOtherControls() {
        assertEquals(MIN_KEY_REPEAT_MS, ControlSettings(repeatIntervalMs = 1).normalized().repeatIntervalMs)
        assertEquals(MAX_KEY_REPEAT_MS, ControlSettings(repeatIntervalMs = 5_000).normalized().repeatIntervalMs)
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

    private fun packetSamples(packet: String): List<DoubleArray> =
        List(10) { packetVector(packet) }

    private fun packetVector(hex: String): DoubleArray {
        require(hex.length % 2 == 0) { "Packet hex must contain complete bytes." }
        val packet = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return requireNotNull(PacketParser.parse(packet)) { "Fixture packet is invalid." }
    }

    private fun loadConformanceCases(): List<ConformanceCase> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/joystick-mapper-v1.csv")) {
            "Shared joystick conformance fixture is missing."
        }
        val rows = stream.bufferedReader().use { it.readLines() }
        require(rows.firstOrNull() == FIXTURE_HEADER) { "Unexpected joystick fixture schema." }
        return rows.drop(1).filter { it.isNotBlank() }.map { row ->
            val columns = row.split(',')
            require(columns.size == 15) { "Invalid joystick fixture row: $row" }
            ConformanceCase(
                id = columns[0],
                centerPacket = columns[1],
                leftPacket = columns[2],
                rightPacket = columns[3],
                upPacket = columns[4],
                downPacket = columns[5],
                currentPacket = columns[6],
                settings = ControlSettings(
                    left = columns[7].toDouble(),
                    right = columns[8].toDouble(),
                    up = columns[9].toDouble(),
                    down = columns[10].toDouble(),
                    deadZone = columns[11].toDouble(),
                ),
                expectedX = columns[12].toDouble(),
                expectedY = columns[13].toDouble(),
                expectedKeys = columns[14].toInt(),
            )
        }
    }

    private data class ConformanceCase(
        val id: String,
        val centerPacket: String,
        val leftPacket: String,
        val rightPacket: String,
        val upPacket: String,
        val downPacket: String,
        val currentPacket: String,
        val settings: ControlSettings,
        val expectedX: Double,
        val expectedY: Double,
        val expectedKeys: Int,
    )

    private companion object {
        const val EPSILON = 0.000001
        const val FIXTURE_HEADER =
            "id,center_packet,left_packet,right_packet,up_packet,down_packet,current_packet," +
                "left_sensitivity,right_sensitivity,up_sensitivity,down_sensitivity,dead_zone," +
                "expected_x,expected_y,expected_keys"
    }
}
