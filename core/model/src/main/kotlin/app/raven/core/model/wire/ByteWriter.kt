package app.raven.core.model.wire

import app.raven.core.model.FixedBytes
import java.io.ByteArrayOutputStream

/** Builds big-endian binary encodings (docs/PROTOCOL.md). */
class ByteWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u8(value: Int): ByteWriter =
        apply {
            require(value in 0..U8_MAX) { "u8 out of range: $value" }
            out.write(value)
        }

    fun u16(value: Int): ByteWriter =
        apply {
            require(value in 0..U16_MAX) { "u16 out of range: $value" }
            out.write(value ushr BYTE_BITS)
            out.write(value and U8_MAX)
        }

    fun u32(value: Long): ByteWriter =
        apply {
            require(value in 0..U32_MAX) { "u32 out of range: $value" }
            for (shift in U32_SHIFTS) out.write(((value ushr shift) and U8_MAX.toLong()).toInt())
        }

    fun u64(value: Long): ByteWriter =
        apply {
            for (shift in U64_SHIFTS) out.write(((value ushr shift) and U8_MAX.toLong()).toInt())
        }

    fun bytes(value: ByteArray): ByteWriter = apply { out.write(value) }

    fun bytes(value: FixedBytes): ByteWriter = bytes(value.toByteArray())

    fun zeros(count: Int): ByteWriter = apply { out.write(ByteArray(count)) }

    fun toByteArray(): ByteArray = out.toByteArray()

    internal companion object {
        const val BYTE_BITS = 8
        const val U8_MAX = 0xFF
        const val U16_MAX = 0xFFFF
        const val U32_MAX = 0xFFFF_FFFFL
        val U32_SHIFTS = intArrayOf(24, 16, 8, 0)
        val U64_SHIFTS = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)
    }
}
