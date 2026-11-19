package com.dmcc26.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dmcc26.app.call.CallActivity
import com.dmcc26.app.data.BlobData
import com.dmcc26.app.data.Media
import com.dmcc26.app.data.Message
import com.dmcc26.app.data.Repo
import com.dmcc26.app.data.UserProfile
import com.dmcc26.app.data.Watcher
import com.dmcc26.app.ui.Bar
import com.dmcc26.app.ui.Picker
import com.dmcc26.app.ui.fmtTime
import com.dmcc26.app.ui.rememberBlobBitmap
import com.dmcc26.app.ui.toast
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** kind = "g" (group chat, id = group id) or "d" (direct chat, id = the other person's uid). */
@Composable
fun ChatScreen(nav: NavController, kind: String, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isGroup = kind == "g"
    val group by remember(id) { if (isGroup) Repo.groupFlow(id) else flowOf(null) }.collectAsState(initial = null)
    var peer by remember { mutableStateOf<UserProfile?>(null) }
    LaunchedEffect(id) { if (!isGroup) peer = runCatching { Repo.getUser(id) }.getOrNull() }
    val dmId = if (isGroup) "" else Repo.dmId(Repo.uid, id)
    var reloadKey by remember { mutableStateOf(0) }
    val messages by remember(id, reloadKey) { if (isGroup) Repo.groupMessagesFlow(id) else Repo.dmMessagesFlow(dmId) }.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }

    DisposableEffect(id) {
        Watcher.openChat = if (isGroup) "g:$id" else "d:$dmId"
        onDispose { Watcher.openChat = "" }
    }

    fun send(file: BlobData?) {
        val t = text.trim()
        if (t.isBlank() && file == null) return
        sending = true
        scope.launch {
            runCatching {
                if (isGroup) Repo.sendGroupMessage(id, t, file)
                else Repo.sendDm(peer ?: Repo.getUser(id) ?: error("User not found"), t, file)
            }.onSuccess {
                if (file == null) text = ""
                if (!isGroup && messages.isEmpty()) reloadKey++
            }
                .onFailure { ctx.toast(it.message ?: "Couldn't send") }
            sending = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching { Media.read(ctx, uri) }.onSuccess { send(it) }.onFailure { ctx.toast(it.message ?: "Couldn't attach") }
        }
    }

    val title = if (isGroup) group?.name ?: "Group chat" else peer?.name ?: "Chat"
    Scaffold(topBar = {
        Bar(title, subtitle = if (isGroup) "${group?.memberIds?.size ?: 0} members" else peer?.phone ?: "", onBack = { nav.popBackStack() }) {
            if (!isGroup && peer != null) {
                IconButton({ CallActivity.outgoing(ctx, id, peer!!.name, false) }) { Icon(Icons.Default.Call, "Voice call") }
                IconButton({ CallActivity.outgoing(ctx, id, peer!!.name, true) }) { Icon(Icons.Default.Videocam, "Video call") }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF3EFE7)), reverseLayout = true) {
                items(messages, key = { it.id }) { m -> Bubble(m, isGroup) }
                if (messages.isEmpty()) item {
                    Text("No messages yet. Say hello! 👋", Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton({ attachMenu = true }) { Icon(Icons.Default.AttachFile, "Attach") }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem({ Text("Photo") }, { attachMenu = false; pick.launch("image/*") }, leadingIcon = { Icon(Icons.Default.Image, null) })
                        DropdownMenuItem({ Text("Document (PDF etc.)") }, { attachMenu = false; pick.launch("*/*") }, leadingIcon = { Icon(Icons.Default.Description, null) })
                    }
                }
                OutlinedTextField(text, { text = it }, placeholder = { Text("Type a message") }, modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                    shape = RoundedCornerShape(24.dp))
                Spacer(Modifier.size(6.dp))
                if (sending) CircularProgressIndicator(Modifier.size(40.dp))
                else FilledIconButton({ send(null) }, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, showName: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine = m.from == Repo.uid
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (mine) Color(0xFFDCF8C6) else Color.White, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            if (showName && !mine) Text(m.fromName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
            if (m.blobId.isNotBlank()) {
                if (m.mime.startsWith("image/")) {
                    val bmp = rememberBlobBitmap(m.blobId)
                    if (bmp != null) Image(bmp, null, Modifier.widthIn(max = 260.dp).heightIn(max = 300.dp).clickable {
                        scope.launch { Repo.getBlob(m.blobId)?.let { Media.open(ctx, it) } }
                    }, contentScale = ContentScale.Fit)
                    else CircularProgressIndicator(Modifier.size(32.dp))
                } else {
                    Row(Modifier.clickable { scope.launch { runCatching { Repo.getBlob(m.blobId)?.let { Media.open(ctx, it) } } } },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null)
                        Text(m.fileName, Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            if (m.text.isNotBlank()) Text(m.text)
            Text(fmtTime(m.at), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
fun NewNoticeScreen(nav: NavController, presetGid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by remember { Repo.myGroupsFlow() }.collectAsState(initial = emptyList())
    val adminGroups = groups.filter { Repo.uid in it.admins }
    var gid by remember { mutableStateOf(presetGid) }
    LaunchedEffect(adminGroups.size) { if (gid.isBlank() && adminGroups.size == 1) gid = adminGroups.first().id }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    Scaffold(topBar = { Bar("Send notice", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("This notice goes to every member of the group, with a notification.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(12.dp))
            Picker("Group", gid, adminGroups.map { it.id to it.name }, Modifier.fillMaxWidth()) { gid = it }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(16.dp))
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else Button({
                if (gid.isBlank() || title.isBlank() || body.isBlank()) { ctx.toast("Choose a group and fill title and message"); return@Button }
                busy = true
                scope.launch {
                    runCatching { Repo.postNotice(gid, title, body) }
                        .onSuccess { done = true }
                        .onFailure { ctx.toast(it.message ?: "Couldn't send") }
                    busy = false
                }
            }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Campaign, null); Spacer(Modifier.size(8.dp)); Text("Send to all members")
            }
        }
    }
    if (done) AlertDialog({ done = false; nav.popBackStack() }, { TextButton({ done = false; nav.popBackStack() }) { Text("OK") } },
        title = { Text("Notice sent") }, text = { Text("All members will see it in the Notices tab.") })
}
