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
 * Turns the radio on and off underneath the engine (D96 "Pause Raven", and the background service's
 * lifetime): while off there are no links, so messages simply wait as pending. The engine never notices
 * beyond links going down and coming back.
 */
class RadioSwitch(
    val inner: Transport,
    private var on: Boolean,
) : Transport {
    private var listener: TransportListener? = null
    private var started = false

    override val links: Set<LinkId> get() = if (on && started) inner.links else emptySet()

    override fun start(listener: TransportListener) {
        this.listener = listener
        started = true
        if (on) inner.start(listener)
    }

    override fun stop() {
        if (on && started) inner.stop()
        started = false
    }

    override fun send(
        link: LinkId,
        packet: ByteArray,
    ): Boolean = on && started && inner.send(link, packet)

    override fun disconnect(link: LinkId) {
        if (on && started) inner.disconnect(link)
    }

    fun setOn(value: Boolean) {
        if (value == on) return
        on = value
        val current = listener
        if (!started || current == null) return
        if (value) inner.start(current) else inner.stop()
    }
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
