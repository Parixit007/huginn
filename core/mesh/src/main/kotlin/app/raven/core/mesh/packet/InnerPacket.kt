package app.raven.core.mesh.packet

import app.raven.core.model.MessageId
import app.raven.core.model.wire.ByteReader
import app.raven.core.model.wire.ByteWriter
import app.raven.core.model.wire.decodeOrNull

/**
 * The encrypted part of a DATA packet (docs/PROTOCOL.md §3):
 * `type ‖ message_id ‖ counter ‖ timestamp ‖ content_length ‖ content ‖ zero padding`,
 * padded to the next size in [PADDING_BUCKETS] so a read receipt looks like a short text (spec D46, D58).
 */
class InnerPacket(
    val messageId: MessageId,
    /** Per sender, per chat, starting at 1 (spec D36). */
    val counter: Long,
    /** Sender's clock, milliseconds since 1970. For display only, never trusted. */
    val timestampMillis: Long,
    val content: Content,
) {
    init {
        require(counter >= 1) { "counter starts at 1" }
    }

    fun encode(): ByteArray {
        val contentBytes = content.encode()
        require(contentBytes.size <= MAX_CONTENT_SIZE) { "content too large: ${contentBytes.size} bytes" }
        val unpadded = HEADER_SIZE + contentBytes.size
        val bucket = PADDING_BUCKETS.first { it >= unpadded }
        return ByteWriter()
            .u8(content.type)
            .bytes(messageId)
            .u64(counter)
            .u64(timestampMillis)
            .u16(contentBytes.size)
            .bytes(contentBytes)
            .zeros(bucket - unpadded)
            .toByteArray()
    }

    companion object {
        /** Padding sizes in bytes (spec D58). */
        val PADDING_BUCKETS = listOf(128, 256, 512, 1024, 2048, 4096, 8192)
        const val MAX_SIZE = 8192
        const val HEADER_SIZE = 1 + MessageId.SIZE + 8 + 8 + 2
        const val MAX_CONTENT_SIZE = MAX_SIZE - HEADER_SIZE

        /**
         * Strict: the size must be exactly one bucket, the padding all zeros and the content fully used,
         * so every valid packet has exactly one encoding. Null if anything is off. Never throws.
         */
        fun decode(bytes: ByteArray): InnerPacket? {
            if (bytes.size !in PADDING_BUCKETS) return null
            return decodeOrNull {
                val reader = ByteReader(bytes)
                val type = reader.u8()
                val messageId = MessageId(reader.bytes(MessageId.SIZE))
                val counter = reader.u64()
                reader.check(counter >= 1) { "counter must be at least 1" }
                val timestamp = reader.u64()
                val contentLength = reader.u16()
                val content = Content.decode(type, reader.bytes(contentLength))
                val padding = reader.rest()
                reader.check(padding.all { it == 0.toByte() }) { "non-zero padding" }
                val smallestFit = PADDING_BUCKETS.first { it >= HEADER_SIZE + contentLength }
                reader.check(bytes.size == smallestFit) { "padded to the wrong size" }
                InnerPacket(messageId, counter, timestamp, content)
            }
        }
    }
}
