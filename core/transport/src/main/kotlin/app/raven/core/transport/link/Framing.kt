package app.raven.core.transport.link

/**
 * Per-hop fragmentation (PROTOCOL.md §8.4). Each Bluetooth write or notification carries one fragment:
 * `flags (1) ‖ [total_length (2), first fragment only] ‖ data`, flags bit 7 = FIRST, bits 0–6 = 0.
 */
object Framing {
    const val FLAG_FIRST = 0x80
    const val FIRST_HEADER = 3
    const val NEXT_HEADER = 1
    private const val MAX_TOTAL = 0xFFFF

    /** Splits one packet into fragments of at most [fragmentSize] bytes each. */
    fun split(
        packet: ByteArray,
        fragmentSize: Int,
    ): List<ByteArray> {
        require(packet.size in 1..MAX_TOTAL) { "packet size ${packet.size}" }
        require(fragmentSize > FIRST_HEADER) { "fragment size $fragmentSize" }
        val fragments = mutableListOf<ByteArray>()
        val firstData = minOf(packet.size, fragmentSize - FIRST_HEADER)
        fragments +=
            byteArrayOf(FLAG_FIRST.toByte(), (packet.size ushr Byte.SIZE_BITS).toByte(), packet.size.toByte()) +
            packet.copyOfRange(0, firstData)
        var offset = firstData
        while (offset < packet.size) {
            val end = minOf(packet.size, offset + fragmentSize - NEXT_HEADER)
            fragments += byteArrayOf(0) + packet.copyOfRange(offset, end)
            offset = end
        }
        return fragments
    }
}

/** What one incoming fragment did. */
sealed interface FragmentResult {
    /** A whole packet is complete. */
    class Packet(
        val bytes: ByteArray,
    ) : FragmentResult

    /** Part of a packet; more fragments are expected. */
    data object Pending : FragmentResult

    /** A broken fragment: it and any unfinished packet were thrown away; the link stays. */
    data object Dropped : FragmentResult

    /** The neighbour announced a packet larger than any real one: close the link (PROTOCOL.md §8.4). */
    data object Hostile : FragmentResult
}

/**
 * Rebuilds packets from one neighbour's fragments. Bytes come from strangers, so nothing here may throw
 * or allocate more than [maxPacketSize] (fuzz-tested). One instance per link and direction.
 */
class Reassembler(
    private val maxPacketSize: Int,
) {
    private var buffer: ByteArray? = null
    private var filled = 0

    /** Fragments thrown away so far (for tests and statistics). */
    var dropped = 0L
        private set

    fun accept(fragment: ByteArray): FragmentResult {
        if (fragment.isEmpty() || fragment[0].toInt() and RESERVED_BITS != 0) return drop()
        return if (fragment[0].toInt() and Framing.FLAG_FIRST != 0) first(fragment) else next(fragment)
    }

    private fun first(fragment: ByteArray): FragmentResult {
        if (buffer != null) dropped++ // a new packet started before the last one finished
        buffer = null
        val total =
            if (fragment.size < Framing.FIRST_HEADER) {
                0 // too short to hold the length: treated like a zero length
            } else {
                (fragment[1].toInt() and BYTE_MASK shl Byte.SIZE_BITS) or (fragment[2].toInt() and BYTE_MASK)
            }
        val dataSize = fragment.size - Framing.FIRST_HEADER
        return when {
            total > maxPacketSize -> {
                FragmentResult.Hostile
            }

            total == 0 || dataSize > total -> {
                drop()
            }

            else -> {
                val fresh = ByteArray(total)
                fragment.copyInto(fresh, 0, Framing.FIRST_HEADER)
                buffer = fresh
                filled = dataSize
                completeOrPending()
            }
        }
    }

    private fun next(fragment: ByteArray): FragmentResult {
        val current = buffer ?: return drop()
        val dataSize = fragment.size - Framing.NEXT_HEADER
        if (filled + dataSize > current.size) return drop()
        fragment.copyInto(current, filled, Framing.NEXT_HEADER)
        filled += dataSize
        return completeOrPending()
    }

    private fun completeOrPending(): FragmentResult {
        val current = buffer ?: return FragmentResult.Pending
        if (filled < current.size) return FragmentResult.Pending
        buffer = null
        filled = 0
        return FragmentResult.Packet(current)
    }

    private fun drop(): FragmentResult {
        dropped++
        buffer = null
        filled = 0
        return FragmentResult.Dropped
    }

    private companion object {
        const val RESERVED_BITS = 0x7F
        const val BYTE_MASK = 0xFF
    }
}
