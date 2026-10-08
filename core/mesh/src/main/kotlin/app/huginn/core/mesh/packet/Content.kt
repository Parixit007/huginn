package app.huginn.core.mesh.packet

import app.huginn.core.model.ImageId
import app.huginn.core.model.MessageId
import app.huginn.core.model.Nickname
import app.huginn.core.model.wire.ByteReader
import app.huginn.core.model.wire.ByteWriter
import app.huginn.core.model.wire.MalformedInputException

private const val CHAT_IMAGE_MAX_BYTES = 50 * 1024
private const val AVATAR_MAX_BYTES = 20 * 1024

/** What an [InnerPacket] carries (docs/PROTOCOL.md §3). Constructors reject values the protocol forbids. */
sealed class Content(
    val type: Int,
) {
    internal abstract fun write(writer: ByteWriter)

    fun encode(): ByteArray = ByteWriter().also(::write).toByteArray()

    /** A chat message (spec D28: at most 2,000 characters). */
    class Text(
        val text: String,
    ) : Content(TEXT) {
        init {
            val length = text.codePointCount(0, text.length)
            require(length in 1..MAX_TEXT_CHARACTERS) { "text must be 1..$MAX_TEXT_CHARACTERS characters" }
        }

        override fun write(writer: ByteWriter) {
            writer.bytes(text.toByteArray(Charsets.UTF_8))
        }
    }

    class Reaction(
        val target: MessageId,
        val emoji: String,
    ) : Content(REACTION) {
        init {
            require(
                emoji.toByteArray(Charsets.UTF_8).size in 1..MAX_EMOJI_BYTES,
            ) { "emoji must be 1..$MAX_EMOJI_BYTES bytes" }
        }

        override fun write(writer: ByteWriter) {
            writer.bytes(target).bytes(emoji.toByteArray(Charsets.UTF_8))
        }
    }

    class AckDelivered(
        val messageIds: List<MessageId>,
    ) : Content(ACK_DELIVERED) {
        init {
            requireAckCount(messageIds)
        }

        override fun write(writer: ByteWriter) = writeAcks(writer, messageIds)
    }

    class AckRead(
        val messageIds: List<MessageId>,
    ) : Content(ACK_READ) {
        init {
            requireAckCount(messageIds)
        }

        override fun write(writer: ByteWriter) = writeAcks(writer, messageIds)
    }

    /** Announces an image or avatar transfer (spec D35). */
    class ImageManifest(
        val imageId: ImageId,
        val totalBytes: Int,
        val chunkCount: Int,
        sha256: ByteArray,
        val purpose: Purpose,
    ) : Content(IMAGE_MANIFEST) {
        init {
            require(totalBytes in 1..purpose.maxBytes) { "image must be 1..${purpose.maxBytes} bytes" }
            require(chunkCount in 1..totalBytes) { "chunk count must be 1..totalBytes" }
            require(sha256.size == SHA256_SIZE) { "hash must be $SHA256_SIZE bytes" }
        }

        private val hash = sha256.copyOf()

        val sha256: ByteArray get() = hash.copyOf()

        override fun write(writer: ByteWriter) {
            writer
                .bytes(imageId)
                .u32(totalBytes.toLong())
                .u16(chunkCount)
                .bytes(hash)
                .u8(purpose.code)
        }
    }

    enum class Purpose(
        val code: Int,
        val maxBytes: Int,
    ) {
        /** Chat image: at most 50 KB (spec D25). */
        CHAT_IMAGE(1, CHAT_IMAGE_MAX_BYTES),

        /** Avatar: at most 20 KB (spec D31). */
        AVATAR(2, AVATAR_MAX_BYTES),
    }

    class ImageChunk(
        val imageId: ImageId,
        val index: Int,
        data: ByteArray,
    ) : Content(IMAGE_CHUNK) {
        init {
            require(index in 0..U16_MAX) { "chunk index out of range" }
            require(data.isNotEmpty()) { "chunk must not be empty" }
        }

        private val bytes = data.copyOf()

        val data: ByteArray get() = bytes.copyOf()

        override fun write(writer: ByteWriter) {
            writer.bytes(imageId).u16(index).bytes(bytes)
        }
    }

    /** Asks the sender to resend only the listed chunks (spec D35). */
    class ChunkRequest(
        val imageId: ImageId,
        val missing: List<Int>,
    ) : Content(CHUNK_REQUEST) {
        init {
            require(
                missing.isNotEmpty() && missing.size <= MAX_CHUNK_REQUESTS,
            ) { "must list 1..$MAX_CHUNK_REQUESTS chunks" }
            require(missing.all { it in 0..U16_MAX }) { "chunk index out of range" }
        }

        override fun write(writer: ByteWriter) {
            writer.bytes(imageId).u16(missing.size)
            missing.forEach(writer::u16)
        }
    }

    /** Nickname and avatar, pushed to contacts when they change (spec D42). */
    class Profile(
        val nickname: Nickname,
        /** Null when there is no avatar. */
        val avatar: ImageId?,
    ) : Content(PROFILE) {
        override fun write(writer: ByteWriter) {
            val nicknameBytes = nickname.value.toByteArray(Charsets.UTF_8)
            writer.u8(nicknameBytes.size).bytes(nicknameBytes)
            if (avatar == null) writer.zeros(ImageId.SIZE) else writer.bytes(avatar)
        }
    }

    companion object {
        const val TEXT = 1
        const val REACTION = 2
        const val ACK_DELIVERED = 3
        const val ACK_READ = 4
        const val IMAGE_MANIFEST = 5
        const val IMAGE_CHUNK = 6
        const val CHUNK_REQUEST = 7
        const val PROFILE = 8

        const val MAX_TEXT_CHARACTERS = 2000
        const val MAX_EMOJI_BYTES = 32
        const val MAX_ACKS = 255
        const val SHA256_SIZE = 32
        private const val U16_MAX = 0xFFFF
        private val MAX_CHUNK_REQUESTS = (InnerPacket.MAX_CONTENT_SIZE - ImageId.SIZE - 2) / 2

        /** Decodes the content of an inner packet. Throws [MalformedInputException] for anything invalid. */
        internal fun decode(
            type: Int,
            bytes: ByteArray,
        ): Content {
            val reader = ByteReader(bytes)
            val content =
                try {
                    decodeType(type, reader)
                } catch (e: IllegalArgumentException) {
                    // A constructor rejected a value the protocol forbids.
                    throw MalformedInputException("invalid content", e)
                }
            reader.requireEnd()
            return content
        }

        private fun decodeType(
            type: Int,
            reader: ByteReader,
        ): Content =
            when (type) {
                TEXT -> {
                    Text(reader.utf8(reader.remaining))
                }

                REACTION -> {
                    Reaction(MessageId(reader.bytes(MessageId.SIZE)), reader.utf8(reader.remaining))
                }

                ACK_DELIVERED -> {
                    AckDelivered(readAcks(reader))
                }

                ACK_READ -> {
                    AckRead(readAcks(reader))
                }

                IMAGE_MANIFEST -> {
                    readManifest(reader)
                }

                IMAGE_CHUNK -> {
                    ImageChunk(ImageId(reader.bytes(ImageId.SIZE)), reader.u16(), reader.rest())
                }

                CHUNK_REQUEST -> {
                    val imageId = ImageId(reader.bytes(ImageId.SIZE))
                    val count = reader.u16()
                    ChunkRequest(imageId, List(count) { reader.u16() })
                }

                PROFILE -> {
                    readProfile(reader)
                }

                else -> {
                    throw MalformedInputException("unknown content type $type")
                }
            }

        private fun readManifest(reader: ByteReader): ImageManifest {
            val imageId = ImageId(reader.bytes(ImageId.SIZE))
            val total = reader.u32()
            reader.check(total <= Purpose.CHAT_IMAGE.maxBytes) { "image too large" }
            val chunkCount = reader.u16()
            val hash = reader.bytes(SHA256_SIZE)
            val purposeCode = reader.u8()
            val purpose =
                Purpose.entries.firstOrNull { it.code == purposeCode }
                    ?: throw MalformedInputException("unknown image purpose")
            return ImageManifest(imageId, total.toInt(), chunkCount, hash, purpose)
        }

        private fun readProfile(reader: ByteReader): Profile {
            val nicknameLength = reader.u8()
            reader.check(nicknameLength <= Nickname.MAX_BYTES) { "nickname too long" }
            val nickname =
                Nickname.strict(reader.utf8(nicknameLength)) ?: throw MalformedInputException("invalid nickname")
            val avatarBytes = reader.bytes(ImageId.SIZE)
            val avatar = if (avatarBytes.all { it == 0.toByte() }) null else ImageId(avatarBytes)
            return Profile(nickname, avatar)
        }

        private fun readAcks(reader: ByteReader): List<MessageId> {
            val count = reader.u8()
            return List(count) { MessageId(reader.bytes(MessageId.SIZE)) }
        }

        private fun requireAckCount(ids: List<MessageId>) {
            require(ids.size in 1..MAX_ACKS) { "must acknowledge 1..$MAX_ACKS messages" }
        }

        private fun writeAcks(
            writer: ByteWriter,
            ids: List<MessageId>,
        ) {
            writer.u8(ids.size)
            ids.forEach(writer::bytes)
        }
    }
}
