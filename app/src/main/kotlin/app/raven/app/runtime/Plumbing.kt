package app.raven.app.runtime

import android.os.Handler
import android.os.SystemClock
import app.raven.core.transport.Cancellable
import app.raven.core.transport.LinkId
import app.raven.core.transport.Scheduler
import app.raven.core.transport.Transport
import app.raven.core.transport.TransportListener

/** Runs mesh timers on the mesh thread's [Handler], on the phone's monotonic clock. */
class HandlerScheduler(
    private val handler: Handler,
) : Scheduler {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis.coerceAtLeast(0))
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/**
 * Stand-in until the Bluetooth transport exists (build plan Phase 5): no neighbours ever, so messages simply
 * stay pending. It never touches the radio.
 */
class NoRadioTransport : Transport {
    override fun start(listener: TransportListener) = Unit

    override fun stop() = Unit

    override val links: Set<LinkId> = emptySet()

    override fun send(
        link: LinkId,
        packet: ByteArray,
    ): Boolean = false
}

/** Wraps a transport to report how many neighbours are connected (the nearby count, spec §3). */
class CountingTransport(
    private val inner: Transport,
    private val onCountChanged: (Int) -> Unit,
) : Transport by inner {
    override fun start(listener: TransportListener) {
        inner.start(
            object : TransportListener {
                override fun onLinkUp(link: LinkId) {
                    listener.onLinkUp(link)
                    onCountChanged(inner.links.size)
                }

                override fun onLinkDown(link: LinkId) {
                    listener.onLinkDown(link)
                    onCountChanged(inner.links.size)
                }

                override fun onReceive(
                    link: LinkId,
                    packet: ByteArray,
                ) = listener.onReceive(link, packet)
            },
        )
        onCountChanged(inner.links.size)
    }
}
