package app.huginn.app.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.huginn.app.R
import app.huginn.app.runtime.MeshRuntime
import app.huginn.app.ui.chat.tick
import app.huginn.app.ui.common.Avatar
import app.huginn.core.mesh.packet.Content
import app.huginn.core.model.DeviceId
import app.huginn.data.db.ChatSummary

/** Home (D84): chats, newest first; nearby count; + to pair. */
@Composable
fun ChatsScreen(
    runtime: MeshRuntime,
    openChat: (DeviceId) -> Unit,
    showQr: () -> Unit,
    scanQr: () -> Unit,
    openProfile: () -> Unit,
) {
    // remember: a new Flow on every recomposition would restart the query, and each fresh result recomposes again.
    val chats by remember {
        runtime.storage.database
            .contacts()
            .summaries()
    }.collectAsStateWithLifecycle(emptyList())
    val nearby by runtime.nearby.collectAsStateWithLifecycle()
    var addSheet by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { ChatsTopBar(nearby, openProfile) },
        floatingActionButton = {
            FloatingActionButton(onClick = { addSheet = true }, modifier = Modifier.testTag("add")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add contact")
            }
        },
    ) { padding ->
        if (chats.isEmpty()) {
            EmptyChats(Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(chats, key = { it.peerId.contentHashCode() }) { chat ->
                    ChatRow(chat) { openChat(DeviceId(chat.peerId)) }
                }
            }
        }
    }
    if (addSheet) {
        AddContactSheet(
            dismiss = { addSheet = false },
            showQr = {
                addSheet = false
                showQr()
            },
            scanQr = {
                addSheet = false
                scanQr()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsTopBar(
    nearby: Int,
    openProfile: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            AssistChip(
                onClick = {},
                label = { Text(if (nearby == 0) "No one nearby" else "$nearby nearby") },
                colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("nearby"),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Profile") }, onClick = {
                        menu = false
                        openProfile()
                    })
                }
            }
        },
    )
}

@Composable
private fun EmptyChats(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No contacts yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Meet a friend, tap + and scan each other's QR code.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactSheet(
    dismiss: () -> Unit,
    showQr: () -> Unit,
    scanQr: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        ListItem(
            headlineContent = { Text("Show my QR code") },
            supportingContent = { Text("Your friend scans it with Huginn") },
            leadingContent = { Icon(painterResource(R.drawable.ic_qr), null) },
            modifier = Modifier.testTag("showQr").clickable(onClick = showQr),
        )
        ListItem(
            headlineContent = { Text("Scan a QR code") },
            supportingContent = { Text("Scan your friend's Huginn code") },
            leadingContent = { Icon(painterResource(R.drawable.ic_scan), null) },
            modifier = Modifier.testTag("scanQr").clickable(onClick = scanQr),
        )
    }
}

@Composable
private fun ChatRow(
    chat: ChatSummary,
    onClick: () -> Unit,
) {
    val preview =
        when {
            chat.blocked -> "Blocked"
            chat.lastType == null -> "Say hello"
            chat.lastType == Content.IMAGE_MANIFEST -> "📷 Photo"
            else -> chat.lastText.orEmpty()
        }
    val prefix = if (chat.lastOutgoing == true && !chat.blocked) "${tick(chat.lastStatus ?: 0)} " else ""
    ListItem(
        headlineContent = { Text(chat.nickname, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(prefix + preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Avatar(chat.nickname, chat.avatar) },
        trailingContent = {
            if (chat.unread > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Text("${chat.unread}") }
            }
        },
        modifier = Modifier.clickable(onClick = onClick).testTag("chat-${chat.nickname}"),
    )
}
