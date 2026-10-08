package app.huginn.core.mesh

/** Tunable mesh values. Defaults are the approved Phase 2 starting values (spec D43, D64, D67, D69, D72). */
@Suppress("MagicNumber") // this class *is* the list of named settings
data class MeshConfig(
    /** How long to wait for a "delivered" receipt before resending (D72). */
    val receiptTimeoutMillis: Long = 30 * SECOND,
    /** Resends after the first attempt, before the message goes back to pending (D72). */
    val resendsPerRound: Int = 3,
    /** Slow timer retry while at least one neighbour is connected: first wait, then doubling up to the max (D64). */
    val timerRetryStartMillis: Long = 2 * MINUTE,
    val timerRetryMaxMillis: Long = 30 * MINUTE,
    /** The sender gives up after this and shows "not delivered" (D43). */
    val pendingExpiryMillis: Long = 3 * DAY,
    /** Relays remember packet IDs this long so packets can't loop (D72). */
    val seenRetentionMillis: Long = 3 * DAY,
    val seenMaxEntries: Int = 200_000,
    /** A carrier keeps someone else's packet at most this long (D67). */
    val carryRetentionMillis: Long = 3 * DAY,
    /** Storage for carried packets; oldest dropped first (D69). */
    val carryMaxBytes: Long = 100L * 1024 * 1024,
    /** Packets accepted per neighbour per second (D72, hardening H9). */
    val packetsPerSecondPerLink: Int = 100,
    /** Photos being reassembled at once (D72). */
    val maxConcurrentImages: Int = 4,
    /** Without new chunks for this long, ask the sender for the missing ones (D35). */
    val chunkRequestDelayMillis: Long = 5 * SECOND,
    /** A stalled photo is dropped from reassembly after this; the sender's retry restarts it (D74). */
    val imageAssemblyTimeoutMillis: Long = 3 * DAY,
) {
    companion object {
        const val SECOND = 1000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
