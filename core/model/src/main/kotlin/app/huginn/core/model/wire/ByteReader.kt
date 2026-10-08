package app.huginn.core.model.wire

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Thrown by [ByteReader] when input is malformed. Decoders turn it into "invalid" via [decodeOrNull]. */
class MalformedInputException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads big-endian binary encodings with a bounds check before every read (docs/PROTOCOL.md §6).
 * All problems with the input surface as [MalformedInputException], never as other exceptions.
 */
class ByteReader(
    private val data: ByteArray,
) {
    private var position = 0

    val remaining: Int get() = data.size - position

    fun u8(): Int = take(1)[0].toInt() and ByteWriter.U8_MAX

    fun u16(): Int {
        val b = take(2)
        return ((b[0].toInt() and ByteWriter.U8_MAX) shl ByteWriter.BYTE_BITS) or (b[1].toInt() and ByteWriter.U8_MAX)
    }

    fun u32(): Long = take(U32_SIZE).fold(0L) { acc, b -> (acc shl ByteWriter.BYTE_BITS) or (b.toLong() and BYTE_MASK) }

    fun u64(): Long = take(U64_SIZE).fold(0L) { acc, b -> (acc shl ByteWriter.BYTE_BITS) or (b.toLong() and BYTE_MASK) }

    fun bytes(count: Int): ByteArray = take(count)

    fun rest(): ByteArray = take(remaining)

    /** Strict UTF-8: malformed sequences are rejected, not replaced. */
    fun utf8(byteCount: Int): String {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(take(byteCount))).toString()
        } catch (e: CharacterCodingException) {
            throw MalformedInputException("invalid UTF-8", e)
        }
    }

    /** Requires that every byte was consumed, so trailing garbage is never silently accepted. */
    fun requireEnd() {
        check(remaining == 0) { "$remaining unexpected trailing bytes" }
    }

    /** Throws [MalformedInputException] unless [condition] holds. */
    fun check(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw MalformedInputException(message())
    }

    private fun take(count: Int): ByteArray {
        check(count in 0..remaining) { "need $count bytes, only $remaining left" }
        return data.copyOfRange(position, position + count).also { position += count }
    }

    private companion object {
        const val U32_SIZE = 4
        const val U64_SIZE = 8
        const val BYTE_MASK = 0xFFL
    }
}

/** Runs a decoder; malformed input gives null. Any other exception is a bug and is not hidden. */
inline fun <T> decodeOrNull(decode: () -> T): T? =
    try {
        decode()
    } catch (expected: MalformedInputException) {
        null // malformed input is an expected outcome, reported as "invalid"
    }
