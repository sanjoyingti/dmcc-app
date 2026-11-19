package com.dmcc26.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmcc26.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.shareText(text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(i, "Send invite via"))
}

fun Context.dial(phone: String) {
    runCatching { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))) }
}

fun fmtTime(t: Long): String {
    if (t <= 0) return ""
    val now = System.currentTimeMillis()
    val day = 24 * 3600 * 1000L
    val pattern = when {
        now - t < day && SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t)) == SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now)) -> "h:mm a"
        now - t < 6 * day -> "EEE h:mm a"
        else -> "d MMM yyyy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, subtitle: String = "", onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
    )
}

@Composable
fun rememberBlobBitmap(blobId: String): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, blobId) {
        value = if (blobId.isBlank()) null else runCatching {
            val b = Repo.getBlob(blobId) ?: return@runCatching null
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b.bytes, 0, b.bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }
    return v
}

@Composable
fun Avatar(name: String, photo: String = "", size: Dp = 44.dp) {
    val bmp = rememberBlobBitmap(photo)
    Box(
        Modifier.size(size).clip(CircleShape).background(if (bmp == null) colorFor(name) else Color.LightGray),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}

private fun initials(n: String) = n.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
    .joinToString("") { it.first().uppercase() }.ifBlank { "?" }

private val palette = listOf(Color(0xFFF37021), Color(0xFF138808), Color(0xFF0B3D91), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFC62828), Color(0xFF6D4C41))
private fun colorFor(s: String) = palette[(s.hashCode() and 0x7fffffff) % palette.size]

/** A read-only text field that opens a list of choices. */
@Composable
fun Picker(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == value }?.second ?: value
    Box(modifier) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (k, v) ->
                DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick(k) })
            }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
