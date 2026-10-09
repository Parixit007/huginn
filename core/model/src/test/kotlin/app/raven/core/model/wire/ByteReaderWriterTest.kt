package app.raven.core.model.wire

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ByteReaderWriterTest {
    @Test
    fun `integers round-trip big-endian`() {
        val bytes =
            ByteWriter()
                .u8(0xAB)
                .u16(0x1234)
                .u32(0xDEADBEEFL)
                .u64(-2L)
                .toByteArray()
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0x12, 0x34, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            bytes.copyOf(7),
        )
        val reader = ByteReader(bytes)
        assertEquals(0xAB, reader.u8())
        assertEquals(0x1234, reader.u16())
        assertEquals(0xDEADBEEFL, reader.u32())
        assertEquals(-2L, reader.u64())
        reader.requireEnd()
    }

    @Test
    fun `reading past the end is malformed input, not a crash`() {
        val reader = ByteReader(byteArrayOf(1))
        assertThrows<MalformedInputException> { reader.u16() }
        assertNull(decodeOrNull { ByteReader(ByteArray(3)).bytes(4) })
    }

    @Test
    fun `trailing bytes are rejected`() {
        val reader = ByteReader(byteArrayOf(1, 2))
        reader.u8()
        assertThrows<MalformedInputException> { reader.requireEnd() }
    }

    @Test
    fun `invalid UTF-8 is rejected rather than replaced`() {
        assertEquals("é", ByteReader(byteArrayOf(0xC3.toByte(), 0xA9.toByte())).utf8(2))
        assertThrows<MalformedInputException> { ByteReader(byteArrayOf(0xC3.toByte(), 0x28)).utf8(2) }
        assertThrows<MalformedInputException> { ByteReader(byteArrayOf(0xFF.toByte())).utf8(1) }
    }

    @Test
    fun `writer rejects out-of-range values`() {
        assertThrows<IllegalArgumentException> { ByteWriter().u8(256) }
        assertThrows<IllegalArgumentException> { ByteWriter().u16(-1) }
    }

    @Test
    fun `other exceptions are not hidden by decodeOrNull`() {
        assertThrows<IllegalStateException> { decodeOrNull { error("a bug") } }
    }
}
