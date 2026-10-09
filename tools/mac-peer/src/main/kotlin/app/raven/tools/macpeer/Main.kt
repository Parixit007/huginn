package app.raven.tools.macpeer

import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import kotlin.io.path.Path

/** `mac-peer --radio <helper> [--name <nickname>]`; normally started by tools/mac-peer/run.sh. */
fun main(args: Array<String>) {
    val radio = args.valueAfter("--radio")
    val name = Nickname.clean(args.valueAfter("--name") ?: "Mac")
    if (radio == null || name == null) {
        println("Usage: mac-peer --radio <path to raven-radio> [--name <nickname>]  (or run tools/mac-peer/run.sh)")
        return
    }
    val engine = EngineThread()
    val transport =
        HelperTransport(
            helper = ProcessRadioHelper(Path(radio)),
            engine = engine::post,
            now = engine::now,
            onState = { println(describe(it)) },
            onLinkCount = { println(if (it == 0) "Phone disconnected." else "Connected over Bluetooth ($it nearby).") },
        )
    val peer = engine.call { MacPeer(DeviceId.random(), name, engine, { transport }, ::println).also { it.start() } }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            transport.stop()
            peer.cleanUp()
        },
    )
    println("Raven Mac test peer, as \"${name.value}\". Type qr to pair with your phone, /help for all commands.")
    generateSequence(::readLine).forEach { line -> engine.post { peer.command(line) } }
}

private fun Array<String>.valueAfter(flag: String): String? =
    indexOf(flag)
        .takeIf {
            it >= 0
        }?.let { getOrNull(it + 1) }

private fun describe(state: String): String =
    when (state) {
        "on" -> "Bluetooth is on. Looking for Raven phones nearby…"
        "off" -> "Bluetooth is off on this Mac. Switch it on in Control Centre."
        "unauthorized" -> "macOS hasn't allowed Bluetooth here: System Settings → Privacy & Security → Bluetooth."
        "unsupported" -> "This Mac's Bluetooth can't do what Raven needs."
        "stopped" -> "The Bluetooth helper stopped."
        else -> "Bluetooth: $state"
    }
