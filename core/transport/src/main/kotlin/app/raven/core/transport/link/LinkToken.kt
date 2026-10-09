package app.raven.core.transport.link

/**
 * The random 8-byte token a phone puts in its scan response (PROTOCOL.md §8.2). It decides who dials when
 * two phones see each other, without ever broadcasting the device ID, and is replaced every 15 minutes.
 */
class LinkToken(
    bytes: ByteArray,
) : Comparable<LinkToken> {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "a link token is $SIZE bytes" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    /** Unsigned, byte by byte. */
    override fun compareTo(other: LinkToken): Int {
        for (i in 0 until SIZE) {
            val diff = (bytes[i].toInt() and BYTE_MASK) - (other.bytes[i].toInt() and BYTE_MASK)
            if (diff != 0) return diff
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is LinkToken && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val SIZE = 8
        private const val BYTE_MASK = 0xFF

        fun random(next: (Int) -> ByteArray): LinkToken = LinkToken(next(SIZE))

        /** Reads a token from scan-response service data; anything that isn't exactly 8 bytes counts as none. */
        fun fromServiceData(data: ByteArray?): LinkToken? = data?.takeIf { it.size == SIZE }?.let(::LinkToken)

        /**
         * True if this phone should dial a phone advertising [theirs]: the lower token dials. A phone without
         * a token (the Mac test peer) may always be dialed; equal tokens dial too, and the engine closes any
         * resulting duplicate (PROTOCOL.md §8.2).
         */
        fun shouldDial(
            mine: LinkToken,
            theirs: LinkToken?,
        ): Boolean = theirs == null || mine <= theirs
    }
}
