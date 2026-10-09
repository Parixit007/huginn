package app.huginn.app.ui.chat

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.huginn.app.R
import app.huginn.app.media.ImageProcessing
import app.huginn.app.runtime.MeshRuntime
import app.huginn.app.ui.camera.PhotoCapture
import app.huginn.app.ui.common.Avatar
import app.huginn.app.ui.common.IncognitoTextField
import app.huginn.core.mesh.packet.Content
import app.huginn.core.model.DeviceId
import app.huginn.core.model.ImageId
import app.huginn.core.model.MessageId
import app.huginn.data.db.ContactEntity
import app.huginn.data.db.MessageEntity
import app.huginn.data.db.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Quick reactions (D87); "+" opens the full emoji picker. */
val QUICK_REACTIONS = listOf("😂", "😭", "👍", "🔥", "❤️")

private const val MAX_TEXT = 2000

/** Outgoing ticks (spec D18, D82). */
fun tick(status: Int): String =
    when (status) {
        MessageStatus.PENDING -> "⏳"
        MessageStatus.SENT -> "✓"
        MessageStatus.DELIVERED -> "✓✓"
        MessageStatus.READ -> "👁"
        else -> "!"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    runtime: MeshRuntime,
    peer: DeviceId,
    back: () -> Unit,
    openContact: () -> Unit,
) {
    val db = runtime.storage.database
    // remember: a new Flow on every recomposition would restart the query, and each fresh result recomposes again.
    val contact by remember(peer) { db.contacts().observe(peer.toByteArray()) }.collectAsStateWithLifecycle(null)
    val all by remember(peer) { db.messages().observeChat(peer.toByteArray()) }.collectAsStateWithLifecycle(emptyList())
    var reactingTo by remember { mutableStateOf<MessageId?>(null) }
    var camera by remember { mutableStateOf(false) }
    val sendPhoto = rememberPhotoSender(runtime, peer)
    MarkReadWhileVisible(runtime, peer, all.size)
    if (camera) {
        PhotoCapture { bytes, rotation ->
            camera = false
            sendPhoto(bytes, rotation)
        }
        return
    }
    val blocked = contact?.blocked == true
    Scaffold(topBar = { ChatTopBar(contact, back, openContact) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            MessageList(
                runtime,
                all,
                Modifier.weight(1f).fillMaxWidth(),
                onLongPress = { if (!blocked) reactingTo = it },
                onRetry = { runtime.chat.retry(peer, it) },
            )
            if (blocked) {
                Text(
                    "You blocked ${contact?.nickname}. Unblock them in their contact info to chat again.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                InputBar(runtime, peer, sendPhoto, onCamera = { camera = true })
            }
        }
    }
    reactingTo?.let { target ->
        ReactionSheet(onPick = { emoji ->
            runtime.chat.sendReaction(peer, target, emoji)
            reactingTo = null
        }, onDismiss = { reactingTo = null })
    }
}

/** While the chat is on screen its messages are marked read and raise no notification. */
@Composable
private fun MarkReadWhileVisible(
    runtime: MeshRuntime,
    peer: DeviceId,
    messageCount: Int,
) {
    DisposableEffect(peer) {
        runtime.visibleChat = peer
        onDispose { runtime.visibleChat = null }
    }
    LaunchedEffect(messageCount) { runtime.chat.markRead(peer) }
}

/** Re-encodes a photo off the main thread (metadata stripped, ≤ 50 KB), then sends it. */
@Composable
private fun rememberPhotoSender(
    runtime: MeshRuntime,
    peer: DeviceId,
): (ByteArray?, Int) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(peer) {
        { raw, rotation ->
            scope.launch {
                val photo = raw?.let { withContext(Dispatchers.Default) { ImageProcessing.chatPhoto(it, rotation) } }
                if (photo != null) runtime.chat.sendPhoto(peer, photo)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    contact: ContactEntity?,
    back: () -> Unit,
    openContact: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), "Back") } },
        title = {
            Row(
                Modifier.clickable(onClick = openContact).testTag("contactTitle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(contact?.nickname ?: "?", contact?.avatar, size = 36.dp)
                Text(contact?.nickname ?: "")
            }
        },
    )
}

/** Bubbles, newest at the bottom; reactions shown under the message they belong to. */
@Composable
private fun MessageList(
    runtime: MeshRuntime,
    all: List<MessageEntity>,
    modifier: Modifier,
    onLongPress: (MessageId) -> Unit,
    onRetry: (MessageId) -> Unit,
) {
    val bubbles = all.filter { it.type != Content.REACTION }
    val reactions =
        all
            .filter { it.type == Content.REACTION }
            .mapNotNull { reaction -> reaction.reactionTarget?.let { MessageId(it) to reaction } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.groupBy { it.outgoing }.values.map { it.last().text.orEmpty() } }
    LazyColumn(modifier, reverseLayout = true, contentPadding = PaddingValues(12.dp)) {
        items(bubbles.asReversed(), key = { it.messageId.contentHashCode() }) { message ->
            val id = MessageId(message.messageId)
            Bubble(
                runtime,
                message,
                reactions[id].orEmpty(),
                onLongPress = { onLongPress(id) },
                onRetry = { onRetry(id) },
            )
        }
    }
}

@Composable
private fun InputBar(
    runtime: MeshRuntime,
    peer: DeviceId,
    sendPhoto: (ByteArray?, Int) -> Unit,
    onCamera: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    val raw =
                        withContext(
                            Dispatchers.IO,
                        ) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    sendPhoto(raw, 0)
                }
            }
        }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) {
            Icon(painterResource(R.drawable.ic_image), "Send a photo from the gallery")
        }
        IconButton(onClick = onCamera) { Icon(painterResource(R.drawable.ic_camera), "Take a photo") }
        IncognitoTextField(text = draft, onTextChange = {
            draft = it
        }, hint = "Message", modifier = Modifier.weight(1f).testTag("messageInput"))
        val trimmed = draft.trim()
        FilledIconButton(
            onClick = {
                runtime.chat.sendText(peer, trimmed)
                draft = ""
            },
            enabled = trimmed.codePointCount(0, trimmed.length) in 1..MAX_TEXT,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            modifier = Modifier.testTag("send"),
        ) { Icon(painterResource(R.drawable.ic_send), "Send") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    runtime: MeshRuntime,
    message: MessageEntity,
    reactions: List<String>,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val mine = message.outgoing
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (mine) colors.secondaryContainer else colors.surfaceVariant, // pale lime for mine (D93)
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .testTag("bubble"),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (message.type == Content.IMAGE_MANIFEST && message.imageId != null) {
                    message.imageId?.let { Photo(runtime, ImageId(it)) }
                } else {
                    Text(message.text.orEmpty())
                }
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.receivedAt))
                Text(
                    if (mine) "$time  ${tick(message.status)}" else time,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
        if (reactions.isNotEmpty()) Text(reactions.joinToString(" "), fontSize = 18.sp)
        if (mine && message.status == MessageStatus.NOT_CONFIRMED) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text("Not confirmed yet — retry?") }
        }
    }
}

@Composable
private fun Photo(
    runtime: MeshRuntime,
    id: ImageId,
) {
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) {
        bitmap =
            withContext(Dispatchers.IO) {
                runtime.storage
                    .images()
                    .load(id)
                    ?.let { ImageProcessing.decodeForDisplay(it) }
            }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.height(160.dp).widthIn(min = 160.dp), contentAlignment = Alignment.Center) { Text("📷") }
    } else {
        Image(
            image.asImageBitmap(),
            contentDescription = "Photo",
            modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var full by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (full) {
            AndroidView(
                factory = { context ->
                    EmojiPickerView(context).apply { setOnEmojiPickedListener { onPick(it.emoji) } }
                },
                modifier = Modifier.fillMaxWidth().height(380.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QUICK_REACTIONS.forEach { emoji ->
                    TextButton(
                        onClick = { onPick(emoji) },
                        modifier = Modifier.testTag("react-$emoji"),
                    ) { Text(emoji, fontSize = 28.sp) }
                }
                TextButton(onClick = { full = true }) { Text("＋", fontSize = 28.sp) }
            }
        }
    }
}
