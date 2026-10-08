package app.huginn.core.mesh.routing

import app.huginn.core.model.PacketId
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Packet IDs this phone has already handled, so each packet is forwarded at most once (build plan 2.3).
 * Memory only. Bounded in size and age; the oldest entries go first.
 *
 * Compact on purpose (D76): plain number arrays instead of boxed objects, about 26 bytes per ID
 * (~5 MB at 200k IDs instead of ~15 MB). The hash uses a secret per-run [salt], so strangers can't choose
 * packet IDs that pile up in one spot of the table and slow every lookup down.
 */
internal class SeenCache(
    private val maxEntries: Int,
    private val retentionMillis: Long,
    private val salt: Long = SecureRandom().nextLong(),
) {
    // Arrival order, oldest at [head]: tells us what to forget first.
    private val ringIds = LongArray(maxEntries)
    private val ringTimes = LongArray(maxEntries)
    private var head = 0
    private var count = 0

    // Open-addressing set with linear probing; 0 marks an empty slot, so the ID 0 is tracked separately.
    private val table = LongArray(tableSize(maxEntries))
    private val mask = table.size - 1
    private var hasZero = false

    val size: Int get() = count

    /** Records [id]; true if it was new. */
    fun add(
        id: PacketId,
        now: Long,
    ): Boolean {
        expire(now)
        val key = key(id)
        if (containsKey(key)) return false
        if (count == maxEntries) forgetOldest()
        insertKey(key)
        val tail = (head + count) % maxEntries
        ringIds[tail] = key
        ringTimes[tail] = now
        count++
        return true
    }

    fun contains(
        id: PacketId,
        now: Long,
    ): Boolean {
        expire(now)
        return containsKey(key(id))
    }

    private fun expire(now: Long) {
        while (count > 0 && now - ringTimes[head] > retentionMillis) forgetOldest()
    }

    private fun forgetOldest() {
        removeKey(ringIds[head])
        head = (head + 1) % maxEntries
        count--
    }

    private fun containsKey(key: Long): Boolean {
        if (key == 0L) return hasZero
        var slot = home(key)
        while (table[slot] != 0L) {
            if (table[slot] == key) return true
            slot = (slot + 1) and mask
        }
        return false
    }

    private fun insertKey(key: Long) {
        if (key == 0L) {
            hasZero = true
            return
        }
        var slot = home(key)
        while (table[slot] != 0L) slot = (slot + 1) and mask
        table[slot] = key
    }

    /** Linear-probing delete with backward shift: no tombstones, so lookups never slow down over time. */
    private fun removeKey(key: Long) {
        if (key == 0L) {
            hasZero = false
            return
        }
        var hole = home(key)
        while (table[hole] != key) hole = (hole + 1) and mask
        var next = (hole + 1) and mask
        while (table[next] != 0L) {
            val home = home(table[next])
            val stays = if (hole <= next) home in (hole + 1)..next else home > hole || home <= next
            if (!stays) {
                table[hole] = table[next]
                hole = next
            }
            next = (next + 1) and mask
        }
        table[hole] = 0L
    }

    /** MurmurHash3's 64-bit finaliser over the salted key. */
    private fun home(key: Long): Int {
        var h = key xor salt
        h = (h xor (h ushr SHIFT_A)) * MIX_1
        h = (h xor (h ushr SHIFT_A)) * MIX_2
        h = h xor (h ushr SHIFT_A)
        return h.toInt() and mask
    }

    private fun key(id: PacketId): Long = ByteBuffer.wrap(id.toByteArray()).long

    private companion object {
        const val SHIFT_A = 33
        const val MIX_1 = -0xae502812aa7333L // 0xff51afd7ed558ccd
        const val MIX_2 = -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        const val MAX_LOAD = 0.8
        const val MIN_TABLE = 16

        /** Smallest power of two that keeps the table at most 80% full. */
        fun tableSize(entries: Int): Int {
            var size = MIN_TABLE
            while (size * MAX_LOAD < entries) size *= 2
            return size
        }
    }
}
