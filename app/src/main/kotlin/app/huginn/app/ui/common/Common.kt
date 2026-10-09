package app.huginn.app.ui.common

import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.huginn.app.media.ImageProcessing
import app.huginn.core.model.DeviceId
import app.huginn.core.model.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A contact's avatar, or their initial on a coloured circle. Decoded off the main thread, size-checked (S4). */
@Composable
fun Avatar(
    name: String,
    bytes: ByteArray?,
    size: Dp = 44.dp,
) {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap = bytes?.let { withContext(Dispatchers.Default) { ImageProcessing.decodeForDisplay(it, maxSide = 256) } }
    }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name
                    .codePoints()
                    .findFirst()
                    .orElse('?'.code)
                    .let { String(Character.toChars(it)) }
                    .uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** A main action in Huginn's lime with dark text, in light and dark mode alike (D93). */
@Composable
fun MainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        content = content,
    )
}

/**
 * Message input with the "incognito keyboard" flag (hardening H4): keyboards such as Gboard are told not to
 * learn from or remember what is typed. Compose text fields can't set this flag, so this is a classic EditText.
 */
@Composable
fun IncognitoTextField(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                    EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = INPUT_MAX_LINES
                this.hint = hint
                background = null
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) = onTextChange(s?.toString() ?: "")
                    },
                )
            }
        },
        update = { field ->
            if (field.text.toString() != text) field.setText(text)
            field.isEnabled = enabled
            field.setTextColor(textColor)
            field.setHintTextColor(hintColor)
        },
    )
}

/** Tapjacking protection (hardening H5): ignore taps while another app draws over this screen. */
@Composable
fun IgnoreTouchesWhenObscured() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = before }
    }
}

/** Navigation-safe text form of a device ID. */
fun DeviceId.route(): String = toByteArray().toHex()

fun deviceIdFromRoute(hex: String): DeviceId =
    DeviceId(
        ByteArray(hex.length / HEX_DIGITS_PER_BYTE) {
            hex.substring(it * HEX_DIGITS_PER_BYTE, (it + 1) * HEX_DIGITS_PER_BYTE).toInt(HEX).toByte()
        },
    )

private const val HEX = 16
private const val HEX_DIGITS_PER_BYTE = 2

/** The 6-digit pairing code, split for easy reading aloud: "482 913". */
fun formatCode(code: String): String = code.chunked(CODE_GROUP).joinToString(" ")

private const val CODE_GROUP = 3

private const val INPUT_MAX_LINES = 5
