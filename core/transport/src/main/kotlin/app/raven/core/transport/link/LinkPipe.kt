package app.raven.core.transport.link

/** What the radio said when asked to send one fragment. */
enum class WriteOutcome {
    /** Accepted; a completion callback will follow ([LinkPipe.onWriteDone]). */
    STARTED,

    /** The radio is busy right now; try the same fragment again shortly. */
    BUSY,

    /** The link is broken; close it. */
    FAILED,
}

/**
 * One Bluetooth link's data path (PROTOCOL.md §8.4–§8.5), the same for both roles and for the Mac test peer:
 * packets are queued (at most [LinkConfig.queueMaxPackets]), split into fragments and written **one at a
 * time**; the next fragment goes out only after the radio confirms the previous one (Spike A). Incoming
 * fragments are reassembled. Not thread-safe: the owner calls it from one thread.
 */
class LinkPipe(
    private val config: LinkConfig,
    /** Fragment size for this link, from its MTU (PROTOCOL.md §8.3). */
    var fragmentSize: Int,
    private val write: (ByteArray) -> WriteOutcome,
) {
    private val packets = ArrayDeque<ByteArray>()
    private val fragments = ArrayDeque<ByteArray>()
    private val reassembler = Reassembler(config.maxPacketSize)

    /** True while a fragment is with the radio and its completion hasn't arrived. */
    var inFlight = false
        private set

    /** Since when the current fragment has been in flight (for the stuck-write watchdog), or null. */
    var inFlightSince: Long? = null
        private set

    /** Packets refused because the queue was full. */
    var droppedPackets = 0L
        private set

    val queuedPackets: Int get() = packets.size + if (fragments.isEmpty()) 0 else 1

    /**
     * Queues one packet. False if the queue is full (the packet is dropped; the engine's retries and
     * OFFER/WANT recover it). The returned [WriteOutcome] of the first write decides whether the link is
     * still usable: callers check [pump]'s result.
     */
    fun enqueue(packet: ByteArray): Boolean {
        if (queuedPackets >= config.queueMaxPackets) {
            droppedPackets++
            return false
        }
        packets.addLast(packet)
        return true
    }

    /**
     * Starts the next write if none is in flight. Returns [WriteOutcome.BUSY] if the caller should call again
     * a little later, [WriteOutcome.FAILED] if the link should be closed, otherwise [WriteOutcome.STARTED]
     * (also when there was nothing to do).
     */
    fun pump(now: Long): WriteOutcome {
        if (inFlight) return WriteOutcome.STARTED
        if (fragments.isEmpty()) {
            val next = packets.removeFirstOrNull() ?: return WriteOutcome.STARTED
            fragments.addAll(Framing.split(next, fragmentSize))
        }
        val outcome = write(fragments.first())
        if (outcome == WriteOutcome.STARTED) {
            fragments.removeFirst()
            inFlight = true
            inFlightSince = now
        }
        return outcome
    }

    /** The radio finished the fragment in flight. Call [pump] next. */
    fun onWriteDone() {
        inFlight = false
        inFlightSince = null
    }

    /** One fragment from the neighbour. */
    fun onFragment(fragment: ByteArray): FragmentResult = reassembler.accept(fragment)

    /** True if a write has been in flight for longer than [limitMillis]: the link is stuck. */
    fun isStuck(
        now: Long,
        limitMillis: Long,
    ): Boolean = inFlightSince?.let { now - it > limitMillis } ?: false
}
