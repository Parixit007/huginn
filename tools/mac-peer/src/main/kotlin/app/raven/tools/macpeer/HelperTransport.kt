package app.raven.tools.macpeer

import app.raven.core.transport.Cancellable
import app.raven.core.transport.LinkId
import app.raven.core.transport.Scheduler
import app.raven.core.transport.Transport
import app.raven.core.transport.TransportListener
import app.raven.core.transport.link.FragmentResult
import app.raven.core.transport.link.LinkConfig
import app.raven.core.transport.link.LinkPipe
import app.raven.core.transport.link.WriteOutcome
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The engine's single thread on the Mac, with the same timers a phone has. */
class EngineThread : Scheduler {
    private val executor =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "mesh").apply { isDaemon = true } }

    override fun now(): Long = System.nanoTime() / NANOS_PER_MILLI

    override fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable {
        val future = executor.schedule({ safely(task) }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    fun post(task: () -> Unit) {
        executor.execute { safely(task) }
    }

    /** Runs [task] on the engine thread and waits for its result. */
    fun <T> call(task: () -> T): T = executor.submit(Callable(task)).get()

    private fun safely(task: () -> Unit) {
        runCatching(task).onFailure { System.err.println("engine error: $it") }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The Swift helper as a line-based pipe (see radio/main.swift); an interface so tests can fake it. */
interface RadioHelper {
    fun start(onLine: (String) -> Unit)

    fun send(line: String)

    fun stop()
}

/** The real helper: a child process, built from radio/main.swift by run.sh. */
class ProcessRadioHelper(
    private val executable: Path,
) : RadioHelper {
    private var process: Process? = null

    override fun start(onLine: (String) -> Unit) {
        val started = ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process = started
        thread(name = "radio-reader", isDaemon = true) {
            started.inputStream.bufferedReader().forEachLine(onLine)
            onLine("STATE stopped")
        }
    }

    override fun send(line: String) {
        val stdin = process?.outputStream ?: return
        stdin.write((line + "\n").toByteArray())
        stdin.flush()
    }

    override fun stop() {
        process?.destroy()
        process = null
    }
}

/**
 * The Mac's [Transport] (PROTOCOL.md §8): the helper only moves fragments; framing, the one-write-at-a-time
 * rule and the limits are the same [LinkPipe] the phones use. Every helper event is handed to the engine thread.
 */
class HelperTransport(
    private val helper: RadioHelper,
    private val engine: (() -> Unit) -> Unit,
    private val now: () -> Long,
    private val onState: (String) -> Unit,
    private val onLinkCount: (Int) -> Unit = {},
    private val config: LinkConfig = LinkConfig(),
) : Transport {
    private val active = linkedMapOf<LinkId, LinkPipe>()
    private var listener: TransportListener? = null

    override val links: Set<LinkId> get() = active.keys.toSet()

    override fun start(listener: TransportListener) {
        this.listener = listener
        helper.start { line -> engine { onLine(line) } }
    }

    override fun stop() {
        helper.stop()
        active.keys.toList().forEach(::down)
        listener = null
    }

    override fun send(
        link: LinkId,
        packet: ByteArray,
    ): Boolean {
        val pipe = active[link] ?: return false
        if (!pipe.enqueue(packet)) return false
        pipe.pump(now())
        return true
    }

    override fun disconnect(link: LinkId) {
        if (link in active) helper.send("CLOSE ${link.value}")
    }

    private fun onLine(line: String) {
        val parts = line.split(' ')
        val id = parts.getOrNull(1)?.toLongOrNull()?.let(::LinkId)
        when (parts.firstOrNull()) {
            "STATE" -> onState(parts.getOrElse(1) { "unknown" })
            "UP" -> if (id != null) up(id, parts.getOrNull(2)?.toIntOrNull() ?: LinkConfig.FALLBACK_FRAGMENT)
            "RX" -> if (id != null) received(id, parts.getOrNull(2).orEmpty())
            "DONE" -> if (id != null) active[id]?.let { it.onWriteDone().also { _ -> it.pump(now()) } }
            "DOWN" -> if (id != null) down(id)
        }
    }

    private fun up(
        id: LinkId,
        maxWrite: Int,
    ) {
        if (id in active) return
        // macOS reports the largest write directly; the fragment size rule wants an MTU (write + 3).
        active[id] =
            LinkPipe(config, LinkConfig.fragmentSizeFor(maxWrite + ATT_HEADER)) { fragment ->
                helper.send("TX ${id.value} ${fragment.toHex()}")
                WriteOutcome.STARTED
            }
        listener?.onLinkUp(id)
        onLinkCount(active.size)
    }

    private fun received(
        id: LinkId,
        hex: String,
    ) {
        val pipe = active[id] ?: return
        val fragment = hex.hexToBytesOrNull() ?: return
        when (val result = pipe.onFragment(fragment)) {
            is FragmentResult.Packet -> listener?.onReceive(id, result.bytes)
            FragmentResult.Hostile -> disconnect(id)
            FragmentResult.Pending, FragmentResult.Dropped -> Unit
        }
    }

    private fun down(id: LinkId) {
        if (active.remove(id) == null) return
        listener?.onLinkDown(id)
        onLinkCount(active.size)
    }

    private companion object {
        const val ATT_HEADER = 3
    }
}

private const val HEX_RADIX = 16

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toIntOrNull(HEX_RADIX)?.toByte() ?: return null }
}
