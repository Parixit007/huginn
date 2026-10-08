package app.huginn.core.mesh.routing

import app.huginn.core.mesh.MeshConfig
import app.huginn.core.mesh.packet.LinkMessage
import app.huginn.core.mesh.packet.OuterPacket
import app.huginn.core.model.DeviceId
import app.huginn.core.model.PacketId
import app.huginn.core.model.RandomBytes
import app.huginn.core.transport.LinkId
import app.huginn.core.transport.Scheduler
import app.huginn.core.transport.Transport

/** Where the router hands packets addressed to this phone. */
internal interface LocalDelivery {
    fun onData(packet: OuterPacket)

    fun onHandshake(packet: OuterPacket)
}

/** Counters for tests and the cost report. */
class RouterStats {
    var malformed = 0L
    var unknownLink = 0L
    var rateLimited = 0L
    var duplicates = 0L
    var relayed = 0L
    var carried = 0L
    var handedOver = 0L
}

/**
 * Packet-level mesh (spec §6, D45, D66–D71):
 * - plain flooding: every DATA packet is forwarded once to every link except the one it came from,
 *   until hops_left runs out;
 * - a phone with nobody else around carries the packet instead, and hands it to the next phone it meets
 *   that doesn't have it yet (OFFER/WANT), then drops it.
 */
internal class Router(
    private val me: DeviceId,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val config: MeshConfig,
    private val carryStore: CarryStore,
    private val random: RandomBytes,
    private val local: LocalDelivery,
) {
    val stats = RouterStats()
    private val seen = SeenCache(config.seenMaxEntries, config.seenRetentionMillis)
    private val limiters = mutableMapOf<LinkId, RateLimiter>()
    private val neighbours = mutableMapOf<LinkId, DeviceId>()
    private val offered = mutableMapOf<LinkId, MutableSet<PacketId>>()

    val linkCount: Int get() = transport.links.size

    fun onLinkUp(link: LinkId) {
        limiters[link] = RateLimiter(config.packetsPerSecondPerLink, scheduler.now())
        offerCarried(link)
    }

    fun onLinkDown(link: LinkId) {
        limiters.remove(link)
        neighbours.remove(link)
        offered.remove(link)
    }

    fun onReceive(
        link: LinkId,
        bytes: ByteArray,
    ) {
        // Only links the transport announced are listened to; otherwise per-link state could pile up forever.
        val limiter = limiters[link]
        if (limiter == null) {
            stats.unknownLink++
            return
        }
        if (!limiter.tryAcquire(scheduler.now())) {
            stats.rateLimited++
            return
        }
        val packet = OuterPacket.decode(bytes)
        if (packet == null || packet.sender == me) {
            stats.malformed++
            return
        }
        when (packet.kind) {
            OuterPacket.Kind.LINK -> onLink(link, packet)
            OuterPacket.Kind.HANDSHAKE -> if (packet.recipient == me) local.onHandshake(packet)
            OuterPacket.Kind.DATA -> onData(link, packet)
        }
    }

    /** Sends a packet this phone created. Returns how many neighbours it went to (0 = nobody around). */
    fun sendOwn(packet: OuterPacket): Int {
        seen.add(packet.packetId, scheduler.now())
        val bytes = packet.encode()
        return transport.links.count { transport.send(it, bytes) }
    }

    /** Pairing messages go to direct neighbours only; only the addressed phone accepts them. */
    fun sendHandshake(
        recipient: DeviceId,
        body: ByteArray,
    ): Int = sendOwn(OuterPacket(OuterPacket.Kind.HANDSHAKE, 1, PacketId.random(random), me, recipient, body))

    /** Drops carried packets older than the carry limit (D67). */
    fun expireCarried() = carryStore.dropStoredBefore(scheduler.now() - config.carryRetentionMillis)

    private fun onData(
        link: LinkId,
        packet: OuterPacket,
    ) {
        if (!seen.add(packet.packetId, scheduler.now())) {
            stats.duplicates++
            return
        }
        if (packet.recipient == me) {
            local.onData(packet)
            return
        }
        if (packet.hopsLeft <= 1) return // this was its last allowed link
        val others = transport.links - link
        if (others.isEmpty()) {
            carryStore.put(packet, scheduler.now())
            stats.carried++
        } else {
            val forwarded = packet.withHopsLeft(packet.hopsLeft - 1).encode()
            others.forEach { transport.send(it, forwarded) }
            stats.relayed++
        }
    }

    private fun onLink(
        link: LinkId,
        packet: OuterPacket,
    ) {
        neighbours[link] = packet.sender
        val message = LinkMessage.decode(packet.body) ?: return
        when (message.type) {
            LinkMessage.Type.OFFER -> {
                val wanted = message.packetIds.filterNot { seen.contains(it, scheduler.now()) }
                if (wanted.isNotEmpty()) sendLink(link, LinkMessage(LinkMessage.Type.WANT, wanted))
            }

            LinkMessage.Type.WANT -> {
                handOver(link, message.packetIds)
            }
        }
    }

    /** Gives the neighbour what it asked for (only what we offered it), then drops our copy (D66). */
    private fun handOver(
        link: LinkId,
        ids: List<PacketId>,
    ) {
        val offeredHere = offered[link] ?: return
        ids
            .filter(offeredHere::remove)
            .mapNotNull(carryStore::take)
            .forEach { packet ->
                transport.send(link, packet.withHopsLeft(packet.hopsLeft - 1).encode())
                stats.handedOver++
            }
    }

    /** On meeting a phone: list what we carry (an empty OFFER is a hello). */
    private fun offerCarried(link: LinkId) {
        expireCarried()
        val ids = carryStore.ids()
        offered.getOrPut(link) { mutableSetOf() }.addAll(ids)
        if (ids.isEmpty()) {
            sendLink(link, LinkMessage(LinkMessage.Type.OFFER, emptyList()))
        } else {
            ids.chunked(LinkMessage.MAX_IDS).forEach { sendLink(link, LinkMessage(LinkMessage.Type.OFFER, it)) }
        }
    }

    private fun sendLink(
        link: LinkId,
        message: LinkMessage,
    ) {
        // The neighbour's ID may not be known yet; all zeros means "whoever is on this link".
        val recipient = neighbours[link] ?: ANY_NEIGHBOUR
        val packet = OuterPacket(OuterPacket.Kind.LINK, 1, PacketId.random(random), me, recipient, message.encode())
        transport.send(link, packet.encode())
    }

    private fun OuterPacket.withHopsLeft(hops: Int) = OuterPacket(kind, hops, packetId, sender, recipient, body)

    private companion object {
        val ANY_NEIGHBOUR = DeviceId(ByteArray(DeviceId.SIZE))
    }
}
