package com.dmcc26.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dmcc26.app.BuildConfig
import com.dmcc26.app.data.UpdateInfo
import com.dmcc26.app.data.Updates
import kotlinx.coroutines.launch
import java.io.File

/** Asks to update, downloads inside the app with a progress bar, then opens Android's install screen. */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf("") }
    var needPermission by remember { mutableStateOf(false) }

    fun tryInstall() {
        val f = apk ?: return
        if (!Updates.canInstall(ctx)) { needPermission = true; Updates.openInstallPermission(ctx); return }
        needPermission = false
        runCatching { Updates.install(ctx, f) }.onFailure { error = it.message ?: "Couldn't open installer" }
    }

    // When the user comes back from the "Install unknown apps" setting, continue automatically.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(needPermission) {
        if (!needPermission) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (Updates.canInstall(ctx)) tryInstall()
        }
    }

    fun start() {
        error = ""
        downloading = true
        progress = 0f
        scope.launch {
            runCatching { Updates.download(ctx, info) { p -> progress = p } }
                .onSuccess { apk = it; downloading = false; tryInstall() }
                .onFailure { error = it.message ?: "Download failed"; downloading = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.SystemUpdate, null) },
        title = { Text("New version available") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Version ${info.versionName} is ready. You have ${BuildConfig.VERSION_NAME}.", fontWeight = FontWeight.SemiBold)
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("What's new:", style = MaterialTheme.typography.labelLarge)
                    Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                when {
                    downloading -> {
                        Text("Downloading… ${(progress * 100).toInt()}%")
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    needPermission -> Text("Please turn on \"Allow from this source\" for 26-DMCC, then come back. The update will install. Your data and login stay safe.")
                    apk != null -> Text("Download complete. Tap Install, then Update on the next screen.")
                    error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error)
                    else -> Text("The update downloads inside the app. Your data and login stay safe.")
                }
            }
        },
        confirmButton = {
            when {
                downloading -> {}
                apk != null -> Button({ tryInstall() }) { Text("Install") }
                else -> Button({ start() }) { Text(if (error.isNotBlank()) "Try again" else "Update now") }
            }
        },
        dismissButton = { if (!downloading) TextButton(onDismiss) { Text("Later") } },
    )
}
