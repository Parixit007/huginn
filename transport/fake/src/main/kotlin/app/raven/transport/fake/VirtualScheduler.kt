package app.raven.transport.fake

import app.raven.core.transport.Cancellable
import app.raven.core.transport.Scheduler
import java.util.PriorityQueue

/** A clock that only moves when told to, so days of mesh activity run in milliseconds and repeat exactly. */
class VirtualScheduler : Scheduler {
    private var time = 0L
    private var sequence = 0L
    private val queue = PriorityQueue(compareBy<Entry>({ it.time }, { it.sequence }))

    override fun now(): Long = time

    override fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        val entry = Entry(time + delayMillis.coerceAtLeast(0), sequence++, task)
        queue.add(entry)
        return Cancellable { entry.cancelled = true }
    }

    /** Runs everything due up to [millis] from now, in time order, then sets the clock there. */
    fun advanceBy(millis: Long) = runUntil(time + millis)

    fun runUntil(target: Long) {
        while (queue.peek()?.let { it.time <= target } == true) {
            val next = queue.poll()
            time = next.time
            if (!next.cancelled) next.task()
        }
        time = maxOf(time, target)
    }

    private class Entry(
        val time: Long,
        val sequence: Long,
        val task: () -> Unit,
    ) {
        var cancelled = false
    }
}
