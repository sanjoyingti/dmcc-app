package com.dmcc26.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dmcc26.app.BuildConfig
import com.dmcc26.app.data.APP_FULL_NAME
import com.dmcc26.app.data.APP_SHORT_NAME
import com.dmcc26.app.data.DEVELOPER_NOTE
import com.dmcc26.app.data.Group
import com.dmcc26.app.data.Notice
import com.dmcc26.app.data.Repo
import com.dmcc26.app.data.UpdateInfo
import com.dmcc26.app.data.Updates
import com.dmcc26.app.data.Watcher
import com.dmcc26.app.ui.Avatar
import com.dmcc26.app.ui.Bar
import com.dmcc26.app.ui.EmptyNote
import com.dmcc26.app.ui.fmtTime
import com.dmcc26.app.ui.toast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: NavController, startTab: Int) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val groups by remember { Repo.myGroupsFlow() }.collectAsState(initial = null)
    val titles = listOf(APP_SHORT_NAME, "Chats", "Notices", "More")
    Scaffold(
        topBar = { Bar(titles[tab], subtitle = if (tab == 0) "Duarbagori Mandal Congress Committee" else "") },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Groups, null) }, label = { Text("Groups") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("Chats") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Campaign, null) }, label = { Text("Notices") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Menu, null) }, label = { Text("More") })
            }
        },
        floatingActionButton = {
            when (tab) {
                0 -> ExtendedFloatingActionButton(onClick = { nav.navigate("newgroup") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New group") })
                2 -> if (groups.orEmpty().any { Repo.uid in it.admins }) ExtendedFloatingActionButton(onClick = { nav.navigate("newnotice") }, icon = { Icon(Icons.Default.Campaign, null) }, text = { Text("Send notice") })
                else -> {}
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> GroupsTab(nav, groups)
                1 -> ChatsTab(nav, groups.orEmpty())
                2 -> NoticesTab(groups.orEmpty())
                else -> MoreTab(nav)
            }
        }
    }
}

@Composable
private fun GroupsTab(nav: NavController, groups: List<Group>?) {
    if (groups == null) { Box0(); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedButton({ nav.navigate("join") }, Modifier.fillMaxWidth().padding(12.dp)) {
                Icon(Icons.Default.GroupAdd, null); Spacer(Modifier.width(8.dp)); Text("Join a group with invite code")
            }
        }
        if (groups.isEmpty()) item { EmptyNote("You are not in any group yet. Create one, or ask an administrator to add you.") }
        items(groups.sortedByDescending { it.lastAt }, key = { it.id }) { g ->
            ListItem(
                leadingContent = { Avatar(g.name) },
                headlineContent = { Text(g.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text(
                        (if (Repo.uid == g.ownerId) "Administrator · " else if (Repo.uid in g.admins) "Sub-administrator · " else "") + "${g.memberIds.size} members",
                        maxLines = 1,
                    )
                },
                modifier = Modifier.clickable { nav.navigate("group/${g.id}") },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun Box0() { com.dmcc26.app.ui.Loading() }

private data class ChatRow(val key: String, val title: String, val last: String, val at: Long, val route: String)

@Composable
private fun ChatsTab(nav: NavController, groups: List<Group>) {
    val dms by remember { Repo.myDmsFlow() }.collectAsState(initial = emptyList())
    val rows = groups.map { ChatRow("g" + it.id, it.name, it.lastMessage, it.lastAt, "chat/g/${it.id}") } +
        dms.map { d ->
            val peer = d.members.firstOrNull { it != Repo.uid } ?: ""
            ChatRow("d" + d.id, d.names[peer] ?: "Chat", d.lastMessage, d.lastAt, "chat/d/$peer")
        }
    if (rows.isEmpty()) { EmptyNote("No chats yet. Open a group or a member to start chatting."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows.sortedByDescending { it.at }, key = { it.key }) { r ->
            ListItem(
                leadingContent = { Avatar(r.title) },
                headlineContent = { Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                supportingContent = { Text(r.last.ifBlank { "Tap to chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text(fmtTime(r.at), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.clickable { nav.navigate(r.route) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun NoticesTab(groups: List<Group>) {
    val ids = groups.joinToString(",") { it.id + it.name }
    val notices by remember(ids) {
        if (groups.isEmpty()) flowOf(emptyList<Notice>())
        else combine(groups.map { Repo.noticesFlow(it.id, it.name) }) { arr -> arr.flatMap { it }.sortedByDescending { it.at } }
    }.collectAsState(initial = emptyList())
    if (notices.isEmpty()) { EmptyNote("No notices yet. Important notices from administrators appear here."); return }
    NoticeList(notices, groups)
}

@Composable
fun NoticeList(notices: List<Notice>, groups: List<Group>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(notices, key = { it.groupId + it.id }) { n ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("📢 " + n.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(n.body, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${n.groupName} · ${n.byName} · ${fmtTime(n.at)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        val isAdmin = groups.firstOrNull { it.id == n.groupId }?.admins?.contains(Repo.uid) == true
                        if (isAdmin) TextButton({
                            scope.launch { runCatching { Repo.deleteNotice(n.groupId, n.id) }.onFailure { ctx.toast(it.message ?: "Failed") } }
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreTab(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by produceState<com.dmcc26.app.data.UserProfile?>(null) { value = runCatching { Repo.me() }.getOrNull() }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var noUpdate by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        scope.launch {
            runCatching { Updates.check(BuildConfig.REPO) }
                .onSuccess { u -> if (u != null && u.versionCode > BuildConfig.VERSION_CODE) update = u else noUpdate = true }
                .onFailure { ctx.toast("Couldn't check for updates: ${it.message}") }
            checking = false
        }
    }
    LaunchedEffect(Unit) {
        runCatching { Updates.check(BuildConfig.REPO) }.getOrNull()?.let { if (it.versionCode > BuildConfig.VERSION_CODE) update = it }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            leadingContent = { Avatar(me?.name ?: "", me?.photo ?: "", 52.dp) },
            headlineContent = { Text(me?.name ?: "", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(listOf(me?.phone ?: "", me?.email ?: "").filter { it.isNotBlank() }.joinToString(" · ")) },
            modifier = Modifier.clickable { nav.navigate("profile") },
        )
        HorizontalDivider()
        ListItem(leadingContent = { Icon(Icons.Default.Person, null) }, headlineContent = { Text("My profile") },
            modifier = Modifier.clickable { nav.navigate("profile") })
        ListItem(
            leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
            headlineContent = { Text("Check for updates") },
            supportingContent = { Text(if (update != null) "New version ${update!!.versionName} available" else "Installed version ${BuildConfig.VERSION_NAME}") },
            trailingContent = { if (checking) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp) },
            modifier = Modifier.clickable { if (update != null) Updates.openDownload(ctx, update!!) else check() },
        )
        ListItem(
            leadingContent = { Icon(Icons.Default.Info, null) },
            headlineContent = { Text("About") },
            supportingContent = { Text("$APP_FULL_NAME\nVersion ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n$DEVELOPER_NOTE") },
        )
        ListItem(leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, headlineContent = { Text("Log out") },
            modifier = Modifier.clickable { confirmLogout = true })
        Spacer(Modifier.height(24.dp))
    }
    if (noUpdate) AlertDialog({ noUpdate = false }, { TextButton({ noUpdate = false }) { Text("OK") } },
        title = { Text("Up to date") }, text = { Text("You have the latest version (${BuildConfig.VERSION_NAME}).") })
    if (confirmLogout) AlertDialog({ confirmLogout = false },
        { TextButton({ confirmLogout = false; Watcher.stop(); Repo.signOut() }) { Text("Log out") } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Cancel") } },
        title = { Text("Log out?") })
}
