package app.raven.core.transport

/** One direct connection to a neighbouring phone. Opaque: the mesh learns who is behind it from LINK packets. */
@JvmInline
value class LinkId(
    val value: Long,
)

/**
 * Moves raw packets between direct neighbours (spec §6). Implementations: Bluetooth LE (Phase 5) and the
 * in-memory simulator (`:transport:fake`). The mesh engine only ever talks to this interface.
 *
 * Threading: all [TransportListener] callbacks must arrive on the engine's single thread (the same one
 * [Scheduler] runs tasks on). A link must be announced with onLinkUp before packets arrive on it; packets on
 * unannounced links are ignored.
 */
interface Transport {
    fun start(listener: TransportListener)

    fun stop()

    /** Links currently up. */
    val links: Set<LinkId>

    /** Sends one packet to one neighbour. False if the link is gone. */
    fun send(
        link: LinkId,
        packet: ByteArray,
    ): Boolean

    /**
     * Closes one link: a neighbour that never said hello, or a second link to a neighbour we already have
     * (PROTOCOL.md §8.3, §8.2). The listener gets onLinkDown as usual. Does nothing if the link is gone.
     */
    fun disconnect(link: LinkId)
}

interface TransportListener {
    fun onLinkUp(link: LinkId)

    fun onLinkDown(link: LinkId)

    fun onReceive(
        link: LinkId,
        packet: ByteArray,
    )
}

/**
 * Time and timers for the mesh engine. Production uses the phone's monotonic clock; tests use a virtual
 * clock, so days of mesh activity run in milliseconds and every run is repeatable.
 */
interface Scheduler {
    /** Milliseconds from a clock that never jumps backwards. */
    fun now(): Long

    fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable
}

fun interface Cancellable {
    fun cancel()
}
