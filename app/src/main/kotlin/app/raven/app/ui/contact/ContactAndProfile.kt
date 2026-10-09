package app.raven.app.ui.contact

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raven.app.R
import app.raven.app.media.ImageProcessing
import app.raven.app.runtime.MeshRuntime
import app.raven.app.ui.camera.PhotoCapture
import app.raven.app.ui.common.Avatar
import app.raven.app.ui.common.MainButton
import app.raven.core.model.DeviceId
import app.raven.core.model.Nickname
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Contact info (D30): block/unblock, delete (wipes key, chat and photos), pair again (spec §5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(
    runtime: MeshRuntime,
    peer: DeviceId,
    back: () -> Unit,
    deleted: () -> Unit,
    pairAgain: () -> Unit,
) {
    // remember: a new Flow on every recomposition would restart the query, and each fresh result recomposes again.
    val contact by remember(peer) {
        runtime.storage.database
            .contacts()
            .observe(peer.toByteArray())
    }.collectAsStateWithLifecycle(null)
    var confirmDelete by remember { mutableStateOf(false) }
    val current = contact ?: return
    Scaffold(topBar = { BackTopBar("Contact info", back) }) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(current.nickname, current.avatar, size = 96.dp)
            Text(current.nickname, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Paired on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current.addedAt))}",
                style = MaterialTheme.typography.bodyMedium,
            )
            BlockControls(current.blocked) { runtime.contacts.setBlocked(peer, !current.blocked) }
            OutlinedButton(onClick = pairAgain, modifier = Modifier.fillMaxWidth()) { Text("Pair again (new key)") }
            Button(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("delete"),
            ) { Text("Delete contact") }
        }
    }
    if (confirmDelete) {
        DeleteDialog(current.nickname, dismiss = { confirmDelete = false }) {
            confirmDelete = false
            runtime.contacts.deleteContact(peer)
            deleted()
        }
    }
}

@Composable
private fun BlockControls(
    blocked: Boolean,
    toggle: () -> Unit,
) {
    OutlinedButton(onClick = toggle, modifier = Modifier.fillMaxWidth().testTag("block")) {
        Text(if (blocked) "Unblock" else "Block")
    }
    Text(
        if (blocked) {
            "Blocked: their messages are ignored. Your phone still relays them for the mesh, unread."
        } else {
            "Blocking ignores their messages; your phone still relays them for others, unread."
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DeleteDialog(
    name: String,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Delete $name?") },
        text = { Text("This removes their key, your whole chat and its photos from this phone. It can't be undone.") },
        confirmButton = {
            TextButton(
                onClick = confirm,
                modifier = Modifier.testTag("confirmDelete"),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(
    title: String,
    back: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), "Back") } },
    )
}

/** Your profile (D42): name and photo; changes are sent to all contacts. */
@Composable
fun ProfileScreen(
    runtime: MeshRuntime,
    back: () -> Unit,
) {
    // remember: a new Flow on every recomposition would restart the query, and each fresh result recomposes again.
    val identity by remember {
        runtime.storage.database
            .identity()
            .observe()
    }.collectAsStateWithLifecycle(null)
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var avatar by remember { mutableStateOf<ByteArray?>(null) }
    var avatarChanged by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    LaunchedEffect(identity) {
        if (name == null) name = identity?.nickname
        if (!avatarChanged) avatar = identity?.avatar
    }
    val setAvatar: (ByteArray?) -> Unit = {
        avatar = it
        avatarChanged = true
    }
    if (camera) {
        AvatarCamera(onDone = { camera = false }, onAvatar = setAvatar)
        return
    }
    val nickname = Nickname.clean(name.orEmpty())
    Scaffold(topBar = { BackTopBar("Profile", back) }) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AvatarChooser(name.orEmpty(), avatar, setAvatar, openCamera = { camera = true })
            OutlinedTextField(
                value = name.orEmpty(),
                onValueChange = { name = it },
                label = { Text("Nickname") },
                isError = nickname == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Saving sends your new name and photo to all your contacts.",
                style = MaterialTheme.typography.bodyMedium,
            )
            MainButton(onClick = {
                runtime.contacts.updateProfile(checkNotNull(nickname), avatar)
                back()
            }, enabled = nickname != null, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

/** Avatar preview with "choose / take / remove" (gallery via the system photo picker, D88). */
@Composable
fun AvatarChooser(
    name: String,
    avatar: ByteArray?,
    onAvatar: (ByteArray?) -> Unit,
    openCamera: () -> Unit,
) {
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
                    onAvatar(raw?.let { withContext(Dispatchers.Default) { ImageProcessing.avatar(it) } })
                }
            }
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(name.ifBlank { "?" }, avatar, size = 72.dp)
        Column {
            TextButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) {
                Text("Choose a photo")
            }
            TextButton(onClick = openCamera) { Text("Take a photo") }
            if (avatar != null) TextButton(onClick = { onAvatar(null) }) { Text("Remove photo") }
        }
    }
}

/** Full-screen camera for an avatar photo; the picture stays in memory (D88). */
@Composable
fun AvatarCamera(
    onDone: () -> Unit,
    onAvatar: (ByteArray?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    PhotoCapture { bytes, rotation ->
        onDone()
        scope.launch { onAvatar(withContext(Dispatchers.Default) { ImageProcessing.avatar(bytes, rotation) }) }
    }
}
