package app.raven.core.model

/** A phone's random ID, created at install (spec D15). Sent in plain text on packets (D14). */
class DeviceId(
    bytes: ByteArray,
) : FixedBytes(bytes, SIZE) {
    companion object {
        const val SIZE = 8

        fun random(random: RandomBytes = RandomBytes.secure): DeviceId = DeviceId(random.next(SIZE))
    }
}

/** New for every transmission; relays use it to drop duplicates (D34). */
class PacketId(
    bytes: ByteArray,
) : FixedBytes(bytes, SIZE) {
    companion object {
        const val SIZE = 8

        fun random(random: RandomBytes = RandomBytes.secure): PacketId = PacketId(random.next(SIZE))
    }
}

/** Identifies one message; stays the same across retries (D34). */
class MessageId(
    bytes: ByteArray,
) : FixedBytes(bytes, SIZE) {
    companion object {
        const val SIZE = 16

        fun random(random: RandomBytes = RandomBytes.secure): MessageId = MessageId(random.next(SIZE))
    }
}

/** Identifies one image or avatar transfer (D35). */
class ImageId(
    bytes: ByteArray,
) : FixedBytes(bytes, SIZE) {
    companion object {
        const val SIZE = 16

        fun random(random: RandomBytes = RandomBytes.secure): ImageId = ImageId(random.next(SIZE))
    }
}

/** Identifies one pairing QR code (D37). */
class SessionId(
    bytes: ByteArray,
) : FixedBytes(bytes, SIZE) {
    companion object {
        const val SIZE = 8

        fun random(random: RandomBytes = RandomBytes.secure): SessionId = SessionId(random.next(SIZE))
    }
}
