package io.github.ceratops_code.wobblepad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketParserTest {
    @Test
    fun decodesObservedSignedLittleEndianFrame() {
        val packet = hex("41-3F-01-43-01-29-01-F1-FF-E7-FF-FE-FF-3A-E0-68-E0-39-E0-42")

        assertArrayEquals(
            doubleArrayOf(319.0, 323.0, 297.0, -15.0, -25.0, -2.0, -8134.0, -8088.0, -8135.0),
            PacketParser.parse(packet),
            0.0,
        )
    }

    @Test
    fun rejectsWrongLengthAndFrameMarkers() {
        val valid = hex("41-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-42")

        assertNull(PacketParser.parse(valid.copyOf(19)))
        assertNull(PacketParser.parse(valid.copyOf().apply { this[0] = 0x40 }))
        assertNull(PacketParser.parse(valid.copyOf().apply { this[19] = 0x43 }))
    }

    private fun hex(value: String): ByteArray =
        value.split('-').map { it.toInt(16).toByte() }.toByteArray()
}
