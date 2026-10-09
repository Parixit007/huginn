package app.raven.core.mesh.routing

/** Token bucket: at most [perSecond] packets per second from one neighbour, with a burst of the same size (H9). */
internal class RateLimiter(
    private val perSecond: Int,
    startMillis: Long,
) {
    private var tokens = perSecond.toDouble()
    private var lastRefill = startMillis

    fun tryAcquire(now: Long): Boolean {
        val elapsed = (now - lastRefill).coerceAtLeast(0)
        tokens = minOf(perSecond.toDouble(), tokens + elapsed * perSecond / MILLIS_PER_SECOND)
        lastRefill = now
        if (tokens < 1) return false
        tokens -= 1
        return true
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
