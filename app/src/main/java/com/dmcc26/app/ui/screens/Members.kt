package com.dmcc26.app.ui.screens

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dmcc26.app.call.CallActivity
import com.dmcc26.app.data.DESIGNATIONS
import com.dmcc26.app.data.DocRef
import com.dmcc26.app.data.Media
import com.dmcc26.app.data.Member
import com.dmcc26.app.data.Pcc
import com.dmcc26.app.data.Repo
import com.dmcc26.app.data.UserProfile
import com.dmcc26.app.data.designationRank
import com.dmcc26.app.data.maskAadhaar
import com.dmcc26.app.ui.Avatar
import com.dmcc26.app.ui.Bar
import com.dmcc26.app.ui.EmptyNote
import com.dmcc26.app.ui.Loading
import com.dmcc26.app.ui.Picker
import com.dmcc26.app.ui.dial
import com.dmcc26.app.ui.shareText
import com.dmcc26.app.ui.toast
import kotlinx.coroutines.launch

/** Choice lists used when adding / editing a member. */
private fun pccOptions(pccs: List<Pcc>) = listOf("" to "Mandal level (no PCC)") + pccs.map { it.id to it.name } + ("+new" to "➕ Add new PCC…")
private val designationOptions = DESIGNATIONS.map { it to it } + ("+other" to "Other (type it)…")

/** PCC / designation pickers with "add new" and "other" handled. */
@Composable
private fun PostPickers(gid: String, pccs: List<Pcc>, pccId: String, designation: String, onPcc: (String) -> Unit, onDesignation: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newPcc by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    Picker("Primary Congress Committee (PCC)", pccId, pccOptions(pccs), Modifier.fillMaxWidth()) {
        if (it == "+new") newPcc = true else onPcc(it)
    }
    Spacer(Modifier.height(8.dp))
    Picker("Designation", designation, designationOptions + (if (designation !in DESIGNATIONS) listOf(designation to designation) else emptyList()), Modifier.fillMaxWidth()) {
        if (it == "+other") other = true else onDesignation(it)
    }
    if (newPcc) TextDialog("New PCC (village)", "Village / PCC name", "", "Add", { newPcc = false }) { name ->
        scope.launch { runCatching { Repo.addPcc(gid, name) }.onSuccess(onPcc).onFailure { ctx.toast(it.message ?: "Failed") } }
    }
    if (other) TextDialog("Designation", "Type the designation", "", "OK", { other = false }) { onDesignation(it) }
}

@Composable
fun PccScreen(nav: NavController, gid: String, pid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val group by remember(gid) { Repo.groupFlow(gid) }.collectAsState(initial = null)
    val members by remember(gid) { Repo.membersFlow(gid) }.collectAsState(initial = emptyList())
    val pccs by remember(gid) { Repo.pccsFlow(gid) }.collectAsState(initial = emptyList())
    val pcc = pccs.firstOrNull { it.id == pid }
    val isAdmin = group?.admins?.contains(Repo.uid) == true
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val inPcc = members.filter { it.pccId == pid }.sortedWith(compareBy<Member>({ designationRank(it.designation) }, { it.name.lowercase() }))
    val bearers = inPcc.filter { it.designation != "Member" }
    val plain = inPcc.filter { it.designation == "Member" }
    Scaffold(topBar = {
        Bar(pcc?.name ?: "PCC", subtitle = "Primary Congress Committee", onBack = { nav.popBackStack() }) {
            if (isAdmin) {
                IconButton({ rename = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton({ delete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Total members: ${inPcc.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Office bearers: ${bearers.size} · Members: ${plain.size}")
                        val counts = bearers.groupBy { it.designation }.toList().sortedBy { designationRank(it.first) }
                        counts.forEach { (d, l) -> Text("• $d: ${l.joinToString { it.name }}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (isAdmin) Button({ nav.navigate("add/$gid?pcc=$pid") }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp)) {
                    Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Add member to this PCC")
                }
            }
            if (bearers.isNotEmpty()) item { Section("Office bearers") }
            items(bearers, key = { "b" + it.uid }) { m -> MemberRow(m, "") { nav.navigate("member/$gid/${m.uid}") } }
            if (plain.isNotEmpty()) item { Section("Members") }
            items(plain, key = { "m" + it.uid }) { m -> MemberRow(m, "") { nav.navigate("member/$gid/${m.uid}") } }
            if (inPcc.isEmpty()) item { EmptyNote("No one in this PCC yet.") }
        }
    }
    if (rename && pcc != null) TextDialog("Rename PCC", "Village / PCC name", pcc.name, "Save", { rename = false }) { n ->
        scope.launch { runCatching { Repo.renamePcc(gid, pid, n) }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
    if (delete) Confirm("Delete this PCC?", "Its members stay in the group but move to Mandal level.", "Delete", { delete = false }) {
        scope.launch { runCatching { Repo.deletePcc(gid, pid) }.onSuccess { nav.popBackStack() }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** Add a member: pick from contacts or type a number/email. Only app users can be added. */
@Composable
fun AddMemberScreen(nav: NavController, gid: String, presetPcc: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val group by remember(gid) { Repo.groupFlow(gid) }.collectAsState(initial = null)
    val pccs by remember(gid) { Repo.pccsFlow(gid) }.collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<UserProfile?>(null) }
    var notInstalled by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pccId by remember { mutableStateOf(presetPcc) }
    var designation by remember { mutableStateOf("Member") }
    var voter by remember { mutableStateOf("") }
    var aadhaar by remember { mutableStateOf("") }

    fun lookup(value: String) {
        if (value.isBlank()) { ctx.toast("Enter a mobile number or email"); return }
        busy = true
        found = null
        scope.launch {
            runCatching { Repo.findUser(value) }
                .onSuccess { u ->
                    when {
                        u == null -> notInstalled = contactName.ifBlank { value }
                        group?.memberIds?.contains(u.uid) == true -> ctx.toast("${u.name} is already in this group")
                        else -> found = u
                    }
                }
                .onFailure { ctx.toast(it.message ?: "Couldn't check") }
            busy = false
        }
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    input = c.getString(0) ?: ""
                    contactName = c.getString(1) ?: ""
                }
            }
        }
        if (input.isNotBlank()) lookup(input)
    }

    Scaffold(topBar = { Bar("Add member", subtitle = group?.name ?: "", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Button({
                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Contacts, null); Spacer(Modifier.width(8.dp)); Text("Choose from contacts")
            }
            Text("or type", modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it; contactName = ""; found = null }, label = { Text("Mobile number or email") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
                IconButton({ lookup(input) }) { Icon(Icons.Default.Search, "Find") }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            val u = found
            if (u != null) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    ListItem(leadingContent = { Avatar(u.name, u.photo) }, headlineContent = { Text(u.name, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(listOf(u.phone, u.email).filter { it.isNotBlank() }.joinToString(" · ") + "\n✅ Has the app") })
                }
                Spacer(Modifier.height(12.dp))
                PostPickers(gid, pccs, pccId, designation, { pccId = it }, { designation = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(voter, { voter = it.uppercase() }, label = { Text("Voter ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(aadhaar, { aadhaar = it.filter { c -> c.isDigit() }.take(12) }, label = { Text("Aadhaar number (optional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("ID numbers are seen only by administrators and the member.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Button({
                    if (aadhaar.isNotEmpty() && aadhaar.length != 12) { ctx.toast("Aadhaar must be 12 digits"); return@Button }
                    busy = true
                    scope.launch {
                        runCatching { Repo.addMember(gid, u, pccId, designation, voter, aadhaar) }
                            .onSuccess {
                                ctx.toast("${u.name} added")
                                found = null; input = ""; voter = ""; aadhaar = ""; designation = "Member"
                            }
                            .onFailure { ctx.toast(it.message ?: "Couldn't add") }
                        busy = false
                    }
                }, Modifier.fillMaxWidth().height(52.dp)) { Text("Add to group") }
            }
        }
    }

    val ni = notInstalled
    if (ni != null) AlertDialog(
        onDismissRequest = { notInstalled = null },
        title = { Text("App not installed") },
        text = { Text("$ni has not installed the 26-DMCC app yet. Only people who have installed the app can be added.\n\nSend them an invitation link so they can install the app and join this group.") },
        confirmButton = {
            TextButton({
                notInstalled = null
                group?.let { ctx.shareText(Repo.inviteText(it.name, it.inviteCode)) }
            }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Send invite") }
        },
        dismissButton = { TextButton({ notInstalled = null }) { Text("Cancel") } },
    )
}

/** Member details: post, contact, call/chat, IDs and documents, admin actions. */
@Composable
fun MemberScreen(nav: NavController, gid: String, uid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val group by remember(gid) { Repo.groupFlow(gid) }.collectAsState(initial = null)
    val member by remember(gid, uid) { Repo.memberFlow(gid, uid) }.collectAsState(initial = null)
    val pccs by remember(gid) { Repo.pccsFlow(gid) }.collectAsState(initial = emptyList())
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    LaunchedEffect(uid) { profile = runCatching { Repo.getUser(uid) }.getOrNull() }
    val g = group
    val m = member
    val iAmAdmin = g != null && Repo.uid in g.admins
    val iAmOwner = g != null && Repo.uid == g.ownerId
    val isMe = uid == Repo.uid
    val canSeePrivate = iAmAdmin || isMe
    var editPost by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(topBar = { Bar(m?.name ?: "Member", subtitle = g?.name ?: "", onBack = { nav.popBackStack() }) }) { pad ->
        if (m == null) { Column(Modifier.padding(pad)) { Loading() }; return@Scaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name, profile?.photo ?: "", 72.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(m.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(m.designation + (pccs.firstOrNull { it.id == m.pccId }?.let { " – ${it.name}" } ?: " – Mandal level"))
                    RoleBadge(m)
                }
            }
            Spacer(Modifier.height(12.dp))
            val pccName = pccs.firstOrNull { it.id == m.pccId }?.name ?: "Mandal level (no PCC)"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Detail("Name", m.name)
                    Detail("Designation", m.designation)
                    Detail("PCC", pccName)
                    Detail("Role in group", when (m.role) { "owner" -> "Administrator"; "admin" -> "Sub-administrator"; else -> "Member" })
                    Detail("Mobile", m.phone.ifBlank { profile?.phone ?: "" }.ifBlank { "—" })
                    Detail("Email", profile?.email?.ifBlank { "—" } ?: "—")
                    Detail("Member since", if (m.addedAt > 0) java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(m.addedAt)) else "—")
                }
            }
            val phone = m.phone.ifBlank { profile?.phone ?: "" }
            if (phone.isNotBlank()) TextButton({ ctx.dial(phone) }) { Icon(Icons.Default.Phone, null); Spacer(Modifier.width(6.dp)); Text("Phone call $phone") }
            if (!isMe) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigAction(Icons.AutoMirrored.Filled.Chat, "Message", Modifier.weight(1f)) { nav.navigate("chat/d/$uid") }
                    BigAction(Icons.Default.Call, "Call", Modifier.weight(1f)) { CallActivity.outgoing(ctx, uid, m.name, false) }
                    BigAction(Icons.Default.Videocam, "Video", Modifier.weight(1f)) { CallActivity.outgoing(ctx, uid, m.name, true) }
                }
            }
            if (iAmAdmin) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Section("Administrator options")
                OutlinedButton({ editPost = true }, Modifier.fillMaxWidth()) { Text("Change PCC / designation") }
                if (iAmOwner && m.role != "owner") OutlinedButton({
                    scope.launch { runCatching { Repo.setAdmin(gid, uid, m.role != "admin") }.onFailure { ctx.toast(it.message ?: "Failed") } }
                }, Modifier.fillMaxWidth()) { Text(if (m.role == "admin") "Remove as sub-administrator" else "Make sub-administrator") }
                if (m.role != "owner" && !isMe && (iAmOwner || m.role == "member")) OutlinedButton({ confirmRemove = true }, Modifier.fillMaxWidth()) {
                    Text("Remove from group", color = MaterialTheme.colorScheme.error)
                }
            }
            if (canSeePrivate) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                PrivateSection(gid, uid)
            }
        }
    }
    if (editPost && m != null) {
        var p by remember { mutableStateOf(m.pccId) }
        var d by remember { mutableStateOf(m.designation) }
        AlertDialog(
            onDismissRequest = { editPost = false },
            title = { Text("PCC and designation") },
            text = { Column { PostPickers(gid, pccs, p, d, { p = it }, { d = it }) } },
            confirmButton = {
                TextButton({
                    editPost = false
                    scope.launch { runCatching { Repo.setPost(gid, uid, p, d) }.onFailure { ctx.toast(it.message ?: "Failed") } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editPost = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemove && m != null) Confirm("Remove ${m.name}?", "They will no longer see this group.", "Remove", { confirmRemove = false }) {
        scope.launch { runCatching { Repo.removeMember(gid, uid) }.onSuccess { nav.popBackStack() }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
}

@Composable
private fun PrivateSection(gid: String, uid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by remember(gid, uid) { Repo.privateFlow(gid, uid) }.collectAsState(initial = null)
    var editIds by remember { mutableStateOf(false) }
    var showAadhaar by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("Other") }
    var uploading by remember { mutableStateOf(false) }
    var kindPicker by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                runCatching { Repo.addDocument(gid, uid, Media.read(ctx, uri), kind) }
                    .onSuccess { ctx.toast("Uploaded") }
                    .onFailure { ctx.toast(it.message ?: "Upload failed") }
                uploading = false
            }
        }
    }
    Section("ID & documents (private)")
    Text("Only administrators and this member can see this.", style = MaterialTheme.typography.labelSmall)
    ListItem(
        leadingContent = { Icon(Icons.Default.Badge, null) },
        headlineContent = { Text("Voter ID: " + (info?.voterId?.ifBlank { "not added" } ?: "not added")) },
        supportingContent = {
            val a = info?.aadhaar ?: ""
            Text("Aadhaar: " + if (a.isBlank()) "not added" else if (showAadhaar) a.chunked(4).joinToString(" ") else maskAadhaar(a))
        },
        trailingContent = {
            Row {
                if (!info?.aadhaar.isNullOrBlank()) IconButton({ showAadhaar = !showAadhaar }) { Icon(Icons.Default.Visibility, "Show") }
                IconButton({ editIds = true }) { Icon(Icons.Default.Edit, "Edit") }
            }
        },
    )
    Text("Documents & photos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val docs = info?.docs.orEmpty()
    if (docs.isEmpty()) Text("No documents yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
    docs.forEach { d -> DocRow(gid, uid, d) }
    Spacer(Modifier.height(8.dp))
    if (uploading) CircularProgressIndicator()
    else Button({ kindPicker = true }, Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("Upload ID / document / photo")
    }
    Text("Photos are resized automatically. PDFs/other files: up to 900 KB.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))

    if (kindPicker) AlertDialog(
        onDismissRequest = { kindPicker = false },
        title = { Text("What are you uploading?") },
        text = {
            Column {
                listOf("Voter ID card" to "image/*", "Aadhaar card" to "image/*", "Passport photo" to "image/*", "Other photo" to "image/*", "PDF / other file" to "*/*").forEach { (k, mime) ->
                    TextButton({ kindPicker = false; kind = k; pick.launch(mime) }, Modifier.fillMaxWidth()) { Text(k) }
                }
            }
        },
        confirmButton = { TextButton({ kindPicker = false }) { Text("Cancel") } },
    )
    if (editIds) {
        var v by remember { mutableStateOf(info?.voterId ?: "") }
        var a by remember { mutableStateOf(info?.aadhaar ?: "") }
        AlertDialog(
            onDismissRequest = { editIds = false },
            title = { Text("ID numbers") },
            text = {
                Column {
                    OutlinedTextField(v, { v = it.uppercase() }, label = { Text("Voter ID") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(a, { a = it.filter { c -> c.isDigit() }.take(12) }, label = { Text("Aadhaar (12 digits)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton({
                    if (a.isNotEmpty() && a.length != 12) { ctx.toast("Aadhaar must be 12 digits"); return@TextButton }
                    editIds = false
                    scope.launch { runCatching { Repo.saveIds(gid, uid, v, a) }.onFailure { ctx.toast(it.message ?: "Failed") } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editIds = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DocRow(gid: String, uid: String, d: DocRef) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { Icon(Icons.Default.Description, null) },
        headlineContent = { Text(d.kind.ifBlank { "Document" }) },
        supportingContent = { Text(d.name, maxLines = 1) },
        trailingContent = { IconButton({ confirm = true }) { Icon(Icons.Default.Delete, "Delete") } },
        modifier = Modifier.fillMaxWidth().clickable {
            scope.launch {
                runCatching { Repo.getBlob(d.id)?.let { Media.open(ctx, it) } ?: ctx.toast("File not found") }
                    .onFailure { ctx.toast(it.message ?: "Couldn't open") }
            }
        },
    )
    if (confirm) Confirm("Delete this document?", d.name, "Delete", { confirm = false }) {
        scope.launch { runCatching { Repo.removeDocument(gid, uid, d) }.onFailure { ctx.toast(it.message ?: "Failed") } }
    }
}
