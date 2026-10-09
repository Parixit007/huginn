package app.huginn.app.runtime

import app.huginn.core.crypto.pairing.InviteSession
import app.huginn.core.crypto.pairing.PAIRING_TIMEOUT_MILLIS
import app.huginn.core.crypto.pairing.PairedContact
import app.huginn.core.crypto.pairing.QrInvite
import app.huginn.core.crypto.pairing.ScanSession
import app.huginn.core.model.DeviceId
import app.huginn.core.model.Nickname
import app.huginn.core.transport.Cancellable
import app.huginn.core.transport.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the pairing screens show (spec §5, D37). */
sealed interface PairingState {
    data object Idle : PairingState

    /** Showing my QR code; [expiresAt] is on the [Scheduler] clock. */
    data class ShowingQr(
        val qrText: String,
        val expiresAt: Long,
    ) : PairingState

    /** Someone scanned it: compare [code] with their screen, then Accept or Reject. */
    data class ConfirmRequest(
        val peerNickname: String,
        val code: String,
    ) : PairingState

    /** I scanned a code and asked to pair; waiting for the other phone. */
    data class Requesting(
        val ownerNickname: String,
    ) : PairingState

    /** I scanned: show [code]; the other phone decides. */
    data class ShowingCode(
        val ownerNickname: String,
        val code: String,
    ) : PairingState

    data class Paired(
        val peerNickname: String,
    ) : PairingState

    data object Declined : PairingState

    /** Two phones tried to pair with the same QR code (safety rule 1). */
    data object TwoPhones : PairingState

    data object Expired : PairingState

    data object InvalidCode : PairingState
}

/**
 * Drives one pairing at a time on the mesh thread, using the engine's direct-neighbour HANDSHAKE messages.
 * [send] delivers a handshake body to a neighbour; [save] stores the new contact.
 */
class PairingController(
    private val scheduler: Scheduler,
    private val send: (to: DeviceId, body: ByteArray) -> Unit,
    private val save: (PairedContact) -> Unit,
) {
    private val mutableState = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = mutableState

    private var invite: InviteSession? = null
    private var scan: ScanSession? = null
    private var scanOwner: DeviceId? = null
    private var timers = mutableListOf<Cancellable>()

    /** "Show my QR": a fresh key pair and session every time (D27). */
    fun showQr(
        me: DeviceId,
        nickname: Nickname,
    ) {
        reset()
        val session = InviteSession(me, nickname, scheduler.now())
        invite = session
        mutableState.value = PairingState.ShowingQr(session.invite.toQrText(), scheduler.now() + PAIRING_TIMEOUT_MILLIS)
        expireLater()
    }

    /** A QR code was scanned (or, in tests, handed in directly). */
    fun scanned(
        qrText: String,
        me: DeviceId,
        nickname: Nickname,
    ) {
        reset()
        val parsed = QrInvite.fromQrText(qrText)
        val session = parsed?.let { ScanSession.start(it, me, nickname, scheduler.now()) }
        if (parsed == null || session == null) {
            mutableState.value = PairingState.InvalidCode
            return
        }
        scan = session
        scanOwner = parsed.ownerId
        mutableState.value = PairingState.Requesting(parsed.nickname.value)
        sendRequestUntilAnswered(parsed.ownerId, session.request)
        expireLater()
    }

    fun onHandshake(
        from: DeviceId,
        body: ByteArray,
    ) {
        invite?.let { onInviteMessage(it, from, body) }
        scan?.let { onScanMessage(it, from, body) }
    }

    fun accept() {
        val session = invite ?: return
        val accepted = session.accept(scheduler.now()) ?: return
        val request = mutableState.value as? PairingState.ConfirmRequest
        send(accepted.contact.peerId, accepted.reply)
        save(accepted.contact)
        finish(PairingState.Paired(request?.peerNickname ?: accepted.contact.peerNickname.value))
    }

    fun decline() {
        val session = invite ?: return
        val peer = pendingPeer
        session.decline()?.let { reply -> peer?.let { send(it, reply) } }
        finish(PairingState.Declined)
    }

    fun cancel() = finish(PairingState.Idle)

    private var pendingPeer: DeviceId? = null

    private fun onInviteMessage(
        session: InviteSession,
        from: DeviceId,
        body: ByteArray,
    ) {
        when (val result = session.onMessage(from, body, scheduler.now())) {
            is InviteSession.RequestResult.Challenge -> {
                pendingPeer = result.peerId
                send(result.peerId, result.reply)
                mutableState.value = PairingState.ConfirmRequest(result.peerNickname.value, result.code)
            }

            InviteSession.RequestResult.Aborted -> {
                finish(PairingState.TwoPhones)
            }

            InviteSession.RequestResult.Expired -> {
                finish(PairingState.Expired)
            }

            InviteSession.RequestResult.Ignored -> {
                // not a valid request for this session
            }
        }
    }

    private fun onScanMessage(
        session: ScanSession,
        from: DeviceId,
        body: ByteArray,
    ) {
        val owner = mutableState.value
        when (val result = session.onMessage(from, body, scheduler.now())) {
            is ScanSession.Result.Code -> {
                mutableState.value =
                    PairingState.ShowingCode((owner as? PairingState.Requesting)?.ownerNickname ?: "", result.code)
            }

            is ScanSession.Result.Paired -> {
                save(result.contact)
                finish(PairingState.Paired(result.contact.peerNickname.value))
            }

            ScanSession.Result.Declined -> {
                finish(PairingState.Declined)
            }

            ScanSession.Result.Expired -> {
                finish(PairingState.Expired)
            }

            ScanSession.Result.Ignored -> {
                // not a valid reply for this session
            }
        }
    }

    /** The other phone may not be connected yet: repeat the (identical) request every 2 s until it answers. */
    private fun sendRequestUntilAnswered(
        owner: DeviceId,
        request: ByteArray,
    ) {
        if (mutableState.value !is PairingState.Requesting) return
        send(owner, request)
        timers += scheduler.schedule(REQUEST_REPEAT_MILLIS) { sendRequestUntilAnswered(owner, request) }
    }

    private fun expireLater() {
        timers += scheduler.schedule(PAIRING_TIMEOUT_MILLIS) { finish(PairingState.Expired) }
    }

    private fun finish(state: PairingState) {
        reset()
        mutableState.value = state
    }

    private fun reset() {
        timers.forEach(Cancellable::cancel)
        timers = mutableListOf()
        invite = null
        scan = null
        scanOwner = null
        pendingPeer = null
    }

    private companion object {
        const val REQUEST_REPEAT_MILLIS = 2_000L
    }
}
