package app.huginn.core.mesh.messaging

import app.huginn.core.crypto.ContactCipher
import app.huginn.core.model.DeviceId
import app.huginn.core.model.MessageId

/** Contacts the mesh can talk to. Null for strangers and blocked contacts (D30): their packets are only relayed. */
fun interface ContactDirectory {
    fun cipherFor(peer: DeviceId): ContactCipher?
}

/** Replay protection (spec D36): remembers every message ID received per chat. Phase 3 stores this in the database. */
interface ReplayGuard {
    /** True the first time [id] is seen from [peer]; false for every repeat. */
    fun firstTime(
        peer: DeviceId,
        id: MessageId,
    ): Boolean

    fun contains(
        peer: DeviceId,
        id: MessageId,
    ): Boolean
}

/** Per-chat sending counter, starting at 1 (spec D36). Phase 3 stores this in the database. */
fun interface CounterStore {
    fun next(peer: DeviceId): Long
}

class InMemoryReplayGuard : ReplayGuard {
    private val seen = mutableMapOf<DeviceId, MutableSet<MessageId>>()

    override fun firstTime(
        peer: DeviceId,
        id: MessageId,
    ): Boolean = seen.getOrPut(peer) { mutableSetOf() }.add(id)

    override fun contains(
        peer: DeviceId,
        id: MessageId,
    ): Boolean = seen[peer]?.contains(id) == true
}

class InMemoryCounterStore : CounterStore {
    private val counters = mutableMapOf<DeviceId, Long>()

    override fun next(peer: DeviceId): Long = (counters[peer] ?: 0L).plus(1).also { counters[peer] = it }
}
