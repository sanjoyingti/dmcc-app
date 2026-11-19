package com.dmcc26.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dmcc26.app.data.Group
import com.dmcc26.app.data.Member
import com.dmcc26.app.data.Pcc
import com.dmcc26.app.data.Repo
import com.dmcc26.app.data.designationRank
import com.dmcc26.app.ui.Avatar
import com.dmcc26.app.ui.Bar
import com.dmcc26.app.ui.EmptyNote
import com.dmcc26.app.ui.Loading
import com.dmcc26.app.ui.Saffron
import com.dmcc26.app.ui.shareText
import com.dmcc26.app.ui.toast
import kotlinx.coroutines.launch

@Composable
fun BigAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier.height(56.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
fun RoleBadge(m: Member) {
    val t = when (m.role) { "owner" -> "Administrator"; "admin" -> "Sub-admin"; else -> "" }
    if (t.isNotBlank()) Text(t, color = Saffron, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun MemberRow(m: Member, pccName: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Avatar(m.name) },
        headlineContent = { Text(m.name, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(listOf(m.designation + if (pccName.isNotBlank()) " – $pccName" else "", m.phone).filter { it.isNotBlank() }.joinToString("\n"))
        },
        trailingContent = { RoleBadge(m) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(nav: NavController, gid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val group by remember(gid) { Repo.groupFlow(gid) }.collectAsState(initial = null)
    val members by remember(gid) { Repo.membersFlow(gid) }.collectAsState(initial = emptyList())
    val pccs by remember(gid) { Repo.pccsFlow(gid) }.collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var addPcc by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val g = group
    val isAdmin = g != null && Repo.uid in g.admins
    val isOwner = g != null && Repo.uid == g.ownerId

    Scaffold(topBar = {
        Bar(g?.name ?: "Group", subtitle = "${members.size} members · ${pccs.size} PCC", onBack = { nav.popBackStack() }) {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                if (isAdmin) DropdownMenuItem({ Text("Edit group name") }, { menu = false; editGroup = true })
                if (isAdmin && g != null) DropdownMenuItem({ Text("Make a new invite code") }, {
                    menu = false
                    scope.launch { runCatching { Repo.resetInvite(gid, g.inviteCode, g.name) }.onSuccess { ctx.toast("New invite code made. Old one no longer works.") } }
                })
                if (!isOwner) DropdownMenuItem({ Text("Leave group") }, { menu = false; confirmLeave = true })
                if (isOwner) DropdownMenuItem({ Text("Delete group") }, { menu = false; confirmDelete = true })
            }
        }
    }) { pad ->
        if (g == null) { Column(Modifier.padding(pad)) { Loading() }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Big, simple buttons
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigAction(Icons.AutoMirrored.Filled.Chat, "Group chat", Modifier.weight(1f)) { nav.navigate("chat/g/$gid") }
                    if (isAdmin) BigAction(Icons.Default.PersonAdd, "Add member", Modifier.weight(1f)) { nav.navigate("add/$gid") }
                }
                if (isAdmin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigAction(Icons.Default.Share, "Invite link", Modifier.weight(1f)) { ctx.shareText(Repo.inviteText(g.name, g.inviteCode)) }
                    BigAction(Icons.Default.Add, "Add PCC", Modifier.weight(1f)) { addPcc = true }
                }
                if (isAdmin) Text("Invite code: ${g.inviteCode}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("PCC (${pccs.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Members (${members.size})") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Notices") })
            }
            when (tab) {
                0 -> PccList(nav, gid, pccs, members, isAdmin) { addPcc = true }
                1 -> AllMembers(nav, gid, members, pccs)
                else -> Column(Modifier.fillMaxSize()) {
                    if (isAdmin) Button({ nav.navigate("newnotice?gid=$gid") }, Modifier.fillMaxWidth().padding(12.dp)) {
                        Icon(Icons.Default.Campaign, null); Spacer(Modifier.width(8.dp)); Text("Send notice to all members")
                    }
                    GroupNotices(g)
                }
            }
        }
    }

    if (addPcc) TextDialog("New PCC (village)", "Village / PCC name", "", "Add", { addPcc = false }) { name ->
        scope.launch { runCatching { Repo.addPcc(gid, name) }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
    if (editGroup && g != null) TextDialog("Group name", "Name", g.name, "Save", { editGroup = false }) { name ->
        scope.launch { runCatching { Repo.updateGroup(gid, name, g.about) }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
    if (confirmLeave) Confirm("Leave this group?", "You will no longer see this group.", "Leave", { confirmLeave = false }) {
        scope.launch { runCatching { Repo.leaveGroup(gid) }.onSuccess { nav.popBackStack() }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
    if (confirmDelete && g != null) Confirm("Delete this group?", "All members lose access. This can't be undone.", "Delete", { confirmDelete = false }) {
        scope.launch { runCatching { Repo.deleteGroup(gid, g.inviteCode) }.onSuccess { nav.popBackStack() }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
}

@Composable
private fun GroupNotices(g: Group) {
    val notices by remember(g.id) { Repo.noticesFlow(g.id, g.name) }.collectAsState(initial = emptyList())
    if (notices.isEmpty()) EmptyNote("No notices yet.") else NoticeList(notices, listOf(g))
}

@Composable
private fun PccList(nav: NavController, gid: String, pccs: List<Pcc>, members: List<Member>, isAdmin: Boolean, onAdd: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (pccs.isEmpty()) item {
            EmptyNote("No Primary Congress Committees yet.")
            if (isAdmin) Button(onAdd, Modifier.fillMaxWidth()) { Text("Add first PCC (village)") }
        }
        items(pccs, key = { it.id }) { p ->
            val inPcc = members.filter { it.pccId == p.id }
            val president = inPcc.firstOrNull { it.designation == "President" }
            Card(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { nav.navigate("pcc/$gid/${p.id}") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("President: ${president?.name ?: "not set"}", style = MaterialTheme.typography.bodyMedium)
                    }
                    AssistChip({ nav.navigate("pcc/$gid/${p.id}") }, { Text("${inPcc.size} members") })
                }
            }
        }
        val noPcc = members.filter { it.pccId.isBlank() || pccs.none { p -> p.id == it.pccId } }
        if (noPcc.isNotEmpty()) item {
            Text("Mandal level (not in a PCC): ${noPcc.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun AllMembers(nav: NavController, gid: String, members: List<Member>, pccs: List<Pcc>) {
    var q by remember { mutableStateOf("") }
    val shown = members.filter { q.isBlank() || it.name.contains(q, true) || it.phone.contains(q) || it.designation.contains(q, true) }
        .sortedWith(compareBy<Member>({ if (it.role == "owner") 0 else if (it.role == "admin") 1 else 2 }, { designationRank(it.designation) }, { it.name.lowercase() }))
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search name, phone or post") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.uid }) { m ->
                MemberRow(m, pccs.firstOrNull { it.id == m.pccId }?.name ?: "") { nav.navigate("member/$gid/${m.uid}") }
            }
        }
    }
}

@Composable
fun TextDialog(title: String, label: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ if (t.isNotBlank()) { onOk(t.trim()); onDismiss() } }) { Text(ok) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, label = { Text(label) }, singleLine = true) },
    )
}

@Composable
fun Confirm(title: String, text: String, ok: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onOk(); onDismiss() }) { Text(ok) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

@Composable
fun NewGroupScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Scaffold(topBar = { Bar("Create group", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("You will be the Administrator of this group. Only people you add (or who use your invite link) can see it.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Group name") }, placeholder = { Text("e.g. 26-Duarbagori Mandal Congress Committee") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(about, { about = it }, label = { Text("About (optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else Button({
                if (name.isBlank()) { ctx.toast("Enter a group name"); return@Button }
                busy = true
                scope.launch {
                    runCatching { Repo.createGroup(name, about) }
                        .onSuccess { id -> nav.popBackStack(); nav.navigate("group/$id") }
                        .onFailure { ctx.toast(it.message ?: "Couldn't create group"); busy = false }
                }
            }, Modifier.fillMaxWidth().height(52.dp)) { Text("Create group") }
        }
    }
}

@Composable
fun JoinScreen(nav: NavController, initialCode: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialCode) }
    var busy by remember { mutableStateOf(false) }
    Scaffold(topBar = { Bar("Join a group", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).imePadding().padding(20.dp)) {
            Text("Type the 8-letter invite code, or paste the whole invite message you received.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(text, { text = it }, label = { Text("Invite code") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else Button({
                val code = Regex("join/([A-Z0-9]{8})").find(text.uppercase())?.groupValues?.get(1)
                    ?: Regex("CODE:\\s*([A-Z0-9]{8})").find(text.uppercase())?.groupValues?.get(1)
                    ?: text.trim().uppercase().takeIf { it.length == 8 }
                if (code == null) { ctx.toast("That doesn't look like an invite code"); return@Button }
                busy = true
                scope.launch {
                    runCatching { Repo.joinWithCode(code) }
                        .onSuccess { id -> nav.popBackStack(); nav.navigate("group/$id") }
                        .onFailure { ctx.toast(it.message ?: "Couldn't join"); busy = false }
                }
            }, Modifier.fillMaxWidth().height(52.dp)) { Text("Join") }
        }
    }
}
