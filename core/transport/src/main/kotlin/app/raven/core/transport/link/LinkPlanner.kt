package app.raven.core.transport.link

/**
 * Android ignores scan starts beyond a limit per time window, without any error (spec §6). This keeps us
 * under it: [canStart] says whether a start is allowed now, [nextStartAt] when it will be.
 */
class ScanBudget(
    private val maxStarts: Int,
    private val windowMillis: Long,
) {
    private val starts = ArrayDeque<Long>()

    fun canStart(now: Long): Boolean {
        prune(now)
        return starts.size < maxStarts
    }

    fun record(now: Long) {
        prune(now)
        starts.addLast(now)
    }

    fun nextStartAt(now: Long): Long {
        prune(now)
        return if (starts.size < maxStarts) now else starts.first() + windowMillis
    }

    private fun prune(now: Long) {
        while (starts.isNotEmpty() && starts.first() + windowMillis <= now) starts.removeFirst()
    }
}

/**
 * The connection manager's decisions (spec §6, PROTOCOL.md §8.2 and §8.6), kept free of Android so they can
 * be tested on the JVM. [A] identifies a remote phone as the radio sees it (a Bluetooth address).
 * The Bluetooth transport tells it what happens and asks it what to do; it never touches the radio itself.
 */
class LinkPlanner<A : Any>(
    private val config: LinkConfig,
) {
    private class Sighting(
        val token: LinkToken?,
        val seenAt: Long,
    )

    private val sightings = mutableMapOf<A, Sighting>()
    private val dialing = mutableMapOf<A, Long>()
    private val linked = linkedMapOf<A, Long>()
    private val backoffUntil = mutableMapOf<A, Long>()

    /** Set while "Pause Raven" is on (D96): nothing is dialed or advertised. */
    var paused = false

    val slotsUsed: Int get() = linked.size + dialing.size

    val linkCount: Int get() = linked.size

    /** Advertise only while a slot is free (PROTOCOL.md §8.6). */
    val wantsAdvertising: Boolean get() = !paused && slotsUsed < config.maxLinks

    /**
     * A phone was seen in a scan. Returns true if it should be dialed now: a free slot, not already linked
     * or being dialed, not in back-off, and our token is the lower one (§8.2). Duplicates are ignored.
     */
    fun onSeen(
        address: A,
        token: LinkToken?,
        mine: LinkToken,
        now: Long,
    ): Boolean {
        sightings[address] = Sighting(token, now)
        val busy = address in linked || address in dialing
        val backingOff = (backoffUntil[address] ?: Long.MIN_VALUE) > now
        return !paused && !busy && !backingOff && slotsUsed < config.maxLinks && LinkToken.shouldDial(mine, token)
    }

    fun onDialStarted(
        address: A,
        now: Long,
    ) {
        dialing[address] = now
    }

    /** A dial failed or timed out: wait before trying that phone again. */
    fun onDialFailed(
        address: A,
        now: Long,
    ) {
        dialing.remove(address)
        backoffUntil[address] = now + config.redialBackoffMillis
    }

    /** Dials that have been pending longer than the dial timeout; the transport abandons them. */
    fun overdueDials(now: Long): List<A> = dialing.filterValues { now - it >= config.dialTimeoutMillis }.keys.toList()

    /**
     * A link came up, dialed by us or accepted from them. False means no slot is free and the transport should
     * close it right away (an accepted connection can't be refused before it happens).
     */
    fun onLinkUp(
        address: A,
        now: Long,
    ): Boolean {
        val wasDialing = dialing.remove(address) != null
        if (!wasDialing && slotsUsed >= config.maxLinks) return false
        linked[address] = now
        return true
    }

    fun onLinkDown(address: A) {
        linked.remove(address)
        dialing.remove(address)
    }

    /**
     * Link rotation (D98): if every slot is full, someone we aren't linked to was seen recently, and a link
     * has been up for a whole rotation interval, return the oldest link to close. Otherwise null.
     */
    fun rotationVictim(now: Long): A? {
        if (paused || slotsUsed < config.maxLinks) return null
        val someoneWaiting =
            sightings.any { (address, sighting) ->
                address !in linked && address !in dialing && now - sighting.seenAt < config.rotationIntervalMillis
            }
        if (!someoneWaiting) return null
        val (oldest, since) = linked.entries.firstOrNull()?.toPair() ?: return null
        return oldest.takeIf { now - since >= config.rotationIntervalMillis }
    }

    /** Forgets sightings and back-offs older than a rotation interval, so the maps stay small. */
    fun forgetOld(now: Long) {
        sightings.entries.removeAll { now - it.value.seenAt >= config.rotationIntervalMillis }
        backoffUntil.entries.removeAll { it.value <= now }
    }

    /** Pause (D96) or stop: everything is dropped. */
    fun clear() {
        sightings.clear()
        dialing.clear()
        linked.clear()
        backoffUntil.clear()
    }
}
