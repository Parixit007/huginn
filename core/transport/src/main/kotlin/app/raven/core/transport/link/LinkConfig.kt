package app.raven.core.transport.link

/**
 * Tunable Bluetooth link values (PROTOCOL.md §8). Defaults are the owner's starting values (D98, D99);
 * they are meant to be tuned from real tests (spec §13, open question 4).
 */
@Suppress("MagicNumber") // this class *is* the list of named settings
data class LinkConfig(
    /** Links at once, dialed and accepted together (D98). */
    val maxLinks: Int = 4,
    /** When all slots are full and someone new is waiting, the oldest link is closed this often (D98). */
    val rotationIntervalMillis: Long = 10 * MINUTE,
    /** Background scanning: on for this long … */
    val backgroundScanOnMillis: Long = 10 * SECOND,
    /** … once per this period (D98). While the app is on screen, scanning is continuous. */
    val backgroundScanPeriodMillis: Long = 60 * SECOND,
    /** Android silently ignores more scan starts than this per window (spec §6). */
    val scanStartsPerWindow: Int = 5,
    val scanWindowMillis: Long = 30 * SECOND,
    /** The link token and the advertising address change together this often (PROTOCOL.md §8.2). */
    val tokenRotationMillis: Long = 15 * MINUTE,
    /** Packets waiting per link; more are dropped (PROTOCOL.md §8.5). */
    val queueMaxPackets: Int = 64,
    /** The largest packet a neighbour may announce (PROTOCOL.md §8.4: the largest outer packet). */
    val maxPacketSize: Int = 8_259,
    /** A dial that hasn't produced a link by then is abandoned. */
    val dialTimeoutMillis: Long = 20 * SECOND,
    /** After a failed dial, that phone isn't dialed again for this long. */
    val redialBackoffMillis: Long = 30 * SECOND,
) {
    companion object {
        const val SECOND = 1000L
        const val MINUTE = 60 * SECOND

        /** ATT allows at most 512 bytes per attribute value (Spike A). */
        const val MAX_FRAGMENT = 512

        /** Fragment size when the MTU can't be raised: the default ATT MTU 23 minus 3 (PROTOCOL.md §8.3). */
        const val FALLBACK_FRAGMENT = 20

        /** The MTU every dialer asks for. */
        const val REQUESTED_MTU = 517

        fun fragmentSizeFor(mtu: Int): Int = (mtu - 3).coerceIn(FALLBACK_FRAGMENT, MAX_FRAGMENT)
    }
}
