package app.huginn.transport.fake

import app.huginn.core.transport.LinkId
import app.huginn.core.transport.Scheduler
import app.huginn.core.transport.Transport
import app.huginn.core.transport.TransportListener
import kotlin.random.Random

/** How links behave. Jitter lets later packets overtake earlier ones (reordering). */
data class LinkConditions(
    val latencyMillis: Long = 20,
    val jitterMillis: Long = 10,
    val lossRate: Double = 0.0,
)

/**
 * In-memory network of virtual phones for tests (spec D21). Repeatable: everything random comes from [seed].
 * Counts every transmission so the cost report (build plan 2.9) can compare routing strategies.
 */
class SimNetwork(
    /** Usually a [VirtualScheduler]; on-device UI tests pass the app's real mesh-thread scheduler. */
    val scheduler: Scheduler,
    seed: Long,
    var conditions: LinkConditions = LinkConditions(),
) {
    private val random = Random(seed)
    private val nodes = mutableListOf<SimTransport>()
    private var nextLinkId = 1L

    /** Every packet put on the air, including lost ones. */
    var transmissions = 0L
        private set
    var bytesSent = 0L
        private set

    fun addNode(): SimTransport = SimTransport(this, nodes.size).also(nodes::add)

    fun isConnected(
        a: SimTransport,
        b: SimTransport,
    ): Boolean = a.linkTo(b) != null

    /** Opens a link between two phones; both sides get onLinkUp. Does nothing if already connected. */
    fun connect(
        a: SimTransport,
        b: SimTransport,
    ) {
        require(a !== b) { "a phone can't link to itself" }
        if (isConnected(a, b)) return
        val aSide = LinkId(nextLinkId++)
        val bSide = LinkId(nextLinkId++)
        a.attach(aSide, b, bSide)
        b.attach(bSide, a, aSide)
        scheduler.schedule(0) {
            a.notifyUp(aSide)
            b.notifyUp(bSide)
        }
    }

    fun disconnect(
        a: SimTransport,
        b: SimTransport,
    ) {
        val aSide = a.linkTo(b) ?: return
        val bSide = b.linkTo(a) ?: return
        a.detach(aSide)
        b.detach(bSide)
        scheduler.schedule(0) {
            a.notifyDown(aSide)
            b.notifyDown(bSide)
        }
    }

    /** Disconnects a phone from everyone (it walked away). */
    fun isolate(node: SimTransport) {
        nodes.filter { isConnected(node, it) }.forEach { disconnect(node, it) }
    }

    internal fun transmit(
        to: SimTransport,
        toLink: LinkId,
        packet: ByteArray,
    ) {
        transmissions++
        bytesSent += packet.size
        if (random.nextDouble() < conditions.lossRate) return
        val delay = conditions.latencyMillis + random.nextLong(conditions.jitterMillis + 1)
        val copy = packet.copyOf()
        scheduler.schedule(delay) { to.deliver(toLink, copy) }
    }

    // ---------------------------------------------------------------- layouts

    /** a — b — c — …: each phone only reaches its neighbours. */
    fun line(count: Int): List<SimTransport> = List(count) { addNode() }.also { it.zipWithNext(::connect) }

    /** Phones at random spots in a [size]×[size] area, linked to their nearest neighbours in [range]. */
    fun crowd(
        count: Int,
        size: Double,
        range: Double,
        maxLinks: Int = MAX_LINKS,
    ): List<SimTransport> {
        val phones = List(count) { addNode() }
        val spots = List(count) { random.nextDouble() * size to random.nextDouble() * size }
        for (i in phones.indices) {
            phones.indices
                .filter { it != i }
                .map { it to distance(spots[i], spots[it]) }
                .filter { it.second <= range }
                .sortedBy { it.second }
                .forEach { (j, _) ->
                    if (phones[i].links.size < maxLinks &&
                        phones[j].links.size < maxLinks
                    ) {
                        connect(phones[i], phones[j])
                    }
                }
        }
        return phones
    }

    private fun distance(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double = kotlin.math.hypot(a.first - b.first, a.second - b.second)

    private companion object {
        /** Typical number of stable BLE connections per phone (AUDIT.md F1). */
        const val MAX_LINKS = 5
    }
}

/** One virtual phone's radio. */
class SimTransport internal constructor(
    private val network: SimNetwork,
    val index: Int,
) : Transport {
    private class Peer(
        val node: SimTransport,
        val remoteLink: LinkId,
    )

    private val peers = linkedMapOf<LinkId, Peer>()
    private val announced = mutableSetOf<LinkId>()
    private var listener: TransportListener? = null

    override val links: Set<LinkId> get() = peers.keys.toSet()

    override fun start(listener: TransportListener) {
        this.listener = listener
        peers.keys.toList().forEach(::notifyUp)
    }

    override fun stop() {
        listener = null
    }

    override fun send(
        link: LinkId,
        packet: ByteArray,
    ): Boolean {
        val peer = peers[link] ?: return false
        network.transmit(peer.node, peer.remoteLink, packet)
        return true
    }

    internal fun linkTo(other: SimTransport): LinkId? = peers.entries.firstOrNull { it.value.node === other }?.key

    internal fun attach(
        link: LinkId,
        other: SimTransport,
        remote: LinkId,
    ) {
        peers[link] = Peer(other, remote)
    }

    internal fun detach(link: LinkId) {
        peers.remove(link)
    }

    /** Each link is announced exactly once, whether it came up before or after [start]. */
    internal fun notifyUp(link: LinkId) {
        val current = listener ?: return
        if (link in peers && announced.add(link)) current.onLinkUp(link)
    }

    internal fun notifyDown(link: LinkId) {
        if (announced.remove(link)) listener?.onLinkDown(link)
    }

    internal fun deliver(
        link: LinkId,
        packet: ByteArray,
    ) {
        if (link in peers) listener?.onReceive(link, packet)
    }

    override fun toString(): String = "phone#$index"
}
