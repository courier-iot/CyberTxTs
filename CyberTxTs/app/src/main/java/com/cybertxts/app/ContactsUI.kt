package com.cybertxts.app

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** What a chat screen needs to know when it's showing a contact's direct link instead of a node. */
data class DirectInfo(val peer: String, val verified: Boolean, val onCard: () -> Unit)

private fun shortTime(ms: Long): String {
    val now = System.currentTimeMillis()
    return if (now - ms < 86_400_000L) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    else if (now - ms < 2 * 86_400_000L) "YDAY"
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
}

private fun preview(m: Msg?, mine: Boolean): String = when {
    m == null -> "No messages yet"
    m.deleted -> "Retracted"
    m.image != null -> (if (mine) "You: " else "") + "IMG"
    m.video != null -> (if (mine) "You: " else "") + "VID " + fmtDur(m.durMs)
    m.voice != null -> (if (mine) "You: " else "") + "VOX " + fmtDur(m.durMs)
    else -> (if (mine) "You: " else "") + m.text
}

@Composable
private fun Avatar(name: String, color: Color, size: Int = 42) {
    Box(Modifier.size(size.dp).openButton(color, cut = 7.dp), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = (size * 0.38f).sp, color = color)
    }
}

// =====================================================================================================
// Contacts list (a full screen of its own, opened from the nodes menu)
// =====================================================================================================

@Composable
fun ContactsRoot(vm: ChatViewModel) {
    BackHandler { vm.contactsOpen.value = false }
    val ctx = LocalContext.current
    val list by vm.contacts.list.collectAsStateWithLifecycle()
    val reqs by vm.contacts.requests.collectAsStateWithLifecycle()
    val sessions by vm.contacts.sessions.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    val aliases by vm.people.aliases.collectAsStateWithLifecycle()
    val blocked by vm.contacts.blocked.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    fun name(id: String) = nicks[id] ?: aliases[id] ?: "Operative"

    val online = remember(tick, sessions) { sessions.filter { (_, s) -> s.connected.value.isNotEmpty() }.keys }
    val q = query.trim().lowercase()
    val shown = list.filter { c ->
        q.isEmpty() || name(c.id).lowercase().contains(q) || (aliases[c.id] ?: "").lowercase().contains(q) ||
            c.id.startsWith(q.replace(" ", ""))
    }.sortedWith(compareByDescending<Contact> { sessions[it.id]?.unread?.value ?: 0 }
        .thenByDescending { sessions[it.id]?.messages?.value?.lastOrNull()?.ts ?: it.since })
    val met = remember(nodes, list, blocked, tick) {
        nodes.flatMap { n -> n.known.value.keys.map { it to n } }
            .filter { (id, _) -> id != vm.myId && !vm.contacts.isContact(id) && id !in blocked }
            .distinctBy { it.first }.take(30)
    }

    Box(Modifier.fillMaxSize()) {
        Starfield(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel.copy(alpha = 0.8f)).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTextButton("‹", "Back", Holo.Primary) { vm.contactsOpen.value = false }
                Column(Modifier.weight(1f)) {
                    Text("CONTACTS", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.08.em, color = Holo.Primary)
                    MonoLabel("${list.size} DIRECT LINK${if (list.size == 1) "" else "S"} · ${online.size} ONLINE")
                }
                HoloButton("+ Add", enabled = true, height = 40) { adding = true }
            }
            HorizontalDivider(color = Holo.Primary.copy(alpha = 0.35f))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search: name, nickname or fingerprint") },
                    shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())

                reqs.forEach { r ->
                    Column(
                        Modifier.fillMaxWidth().openPanel(Holo.Theirs, Holo.Accent.copy(alpha = 0.7f)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MonoLabel("CONTACT REQUEST", Holo.Accent)
                        Text(buildString {
                            append(name(r.from)); append("  "); append(opTag(aliases[r.from] ?: r.name, r.from))
                            append(when (r.via) { "link" -> " · via your contact link"; "qr" -> " · scanned your code"; else -> " · from a shared node" })
                        }, style = MaterialTheme.typography.bodyMedium)
                        if (vm.people.lookalikeOf(r.from) != null) MonoLabel("⚠ SAME CALLSIGN AS SOMEONE YOU KNOW · CHECK THE FINGERPRINT", Holo.Danger, size = 10)
                        MonoLabel(fingerprint(r.from), Holo.InkDim, size = 10)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HoloButton("Accept", enabled = true, modifier = Modifier.weight(1f), height = 40) { vm.contacts.accept(r) }
                            GhostButton("Decline", Modifier.weight(1f), height = 40) { vm.contacts.decline(r) }
                        }
                    }
                }

                MonoLabel("—— DIRECT LINKS ——", align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                if (list.isEmpty()) {
                    InfoCard("", "No contacts yet. Tap + ADD to scan someone's code in person or share a contact link, or add someone you've met in a node below.")
                }
                shown.forEach { c ->
                    val s = sessions[c.id]
                    if (s != null) ContactRow(vm, c, s, name(c.id), c.id in online,
                        onOpen = { vm.directChat.value = c.id }, onCard = { card = c.id })
                }

                if (met.isNotEmpty()) {
                    MonoLabel("—— MET IN YOUR NODES ——", align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    met.forEach { (id, node) ->
                        Row(
                            Modifier.fillMaxWidth().openPanel(Color.Transparent, Holo.Muted.copy(alpha = 0.35f)).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name(id), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                                MonoLabel(opTag(aliases[id] ?: name(id), id) + " · " + node.displayName().uppercase(), Holo.InkDim, size = 10)
                            }
                            GhostButton("Add", color = Holo.Primary, height = 36) {
                                val ok = vm.contacts.requestViaNode(node, id)
                                Toast.makeText(ctx,
                                    if (ok) "Request sent. ${name(id)} needs to accept." else "${name(id)} needs to be online in ${node.displayName()} to add them.",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(40.dp))
            }
        }
    }
    if (adding) AddContactScreen(vm) { adding = false }
    val cd = card
    if (cd != null) ContactCardScreen(vm, cd, onMessage = { card = null; vm.directChat.value = cd }) { card = null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(vm: ChatViewModel, c: Contact, s: NodeSession, name: String, online: Boolean, onOpen: () -> Unit, onCard: () -> Unit) {
    val msgs by s.messages.collectAsStateWithLifecycle()
    val unread by s.unread.collectAsStateWithLifecycle()
    val last = msgs.lastOrNull { !it.system }
    val queued = remember(msgs, unread) { s.queuedCount() }
    val tint = if (unread > 0) Holo.Accent else if (online) Holo.Primary else Holo.Muted
    Row(
        Modifier.fillMaxWidth().openPanel(if (unread > 0) Holo.Theirs else Holo.Panel, tint.copy(alpha = 0.55f))
            .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onCard, onClickLabel = "Open chat", onLongClickLabel = "Contact card")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.verified) { Spacer(Modifier.width(8.dp)); MonoLabel("✓ VERIFIED", Holo.Primary, size = 10) }
            }
            val status = when {
                c.state == Contact.STATE_SENT -> "REQUEST SENT · WAITING FOR THEM"
                online -> "ONLINE · VIA " + (s.route(c.id) ?: "LINK")
                else -> "OFFLINE"
            }
            MonoLabel(opTag(vm.people.alias(c.id, name), c.id) + " · " + status, Holo.InkDim, size = 10)
            Text(
                if (queued > 0) "Pending: $queued message${if (queued == 1) "" else "s"} waiting to send" else preview(last, last?.senderId == vm.myId),
                style = MaterialTheme.typography.bodySmall, color = if (unread > 0) Holo.Ink else Holo.InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (last != null) MonoLabel(shortTime(last.ts), Holo.InkDim, size = 10)
            if (unread > 0) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.background(Holo.Accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text("$unread", fontFamily = Mono, fontSize = 12.sp, color = Holo.Void)
                }
            }
        }
    }
}

// =====================================================================================================
// Add a contact
// =====================================================================================================

@Composable
fun AddContactScreen(vm: ChatViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val alias by vm.profileName.collectAsStateWithLifecycle()
    val offer = remember { vm.contacts.openQr() }
    DisposableEffect(Unit) { onDispose { vm.contacts.closeQr() } }
    val link = remember(offer) { ContactLink.build(offer) }
    val qr by produceState<ImageBitmap?>(null, link) {
        value = withContext(Dispatchers.Default) { runCatching { InviteLink.qr(link).asImageBitmap() }.getOrNull() }
    }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    ConsolePage("ADD A CONTACT", "SCAN EACH OTHER · OR SHARE A LINK", onClose) {
        MonoLabel("YOUR CONTACT CODE", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(250.dp).openPanel(Holo.Panel, Holo.Primary.copy(alpha = 0.7f), cut = 16.dp), contentAlignment = Alignment.Center) {
                val b = qr
                if (b != null) Image(bitmap = b, contentDescription = "Your contact QR code", modifier = Modifier.size(224.dp))
                else CircularProgressIndicator(color = Holo.Primary)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(alias ?: "", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            MonoLabel(opTag(alias ?: "", vm.myId), Holo.Primary, size = 12)
            MonoLabel(fingerprint(vm.myId), Holo.InkDim, size = 11, align = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Primary.copy(alpha = 0.45f)).padding(12.dp)) {
            Text("Scanning each other in person checks both fingerprints, so the contact shows ✓ VERIFIED.",
                style = MaterialTheme.typography.bodyMedium)
        }
        result?.let { InfoCard("", it) }
        HoloButton("Scan their code", enabled = true, modifier = Modifier.fillMaxWidth()) { AppLock.holdOpen(); scanning = true }
        GhostButton("Share contact link", Modifier.fillMaxWidth(), color = Holo.Primary) {
            val l = vm.contacts.createLink()
            AppLock.holdOpen()
            runCatching {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Add me on CyberTxTs (${opTag(alias ?: "", vm.myId)}):\n$l"), "Share contact link"))
            }
        }
        Text("A shared link works once, for 7 days, and needs your OK when someone uses it. It adds them unverified until you meet and scan. " +
            "You can also add anyone from a node: long-press their message or tap them on the Star Map.",
            style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
    }
    if (scanning) ScanQrScreen(
        title = "SCAN CONTACT", subtitle = "THEIR CODE · CONTACTS › + ADD",
        hint = "ALIGN THEIR CONTACT QR INSIDE THE FRAME", wrong = "NOT A CYBERTXTS CONTACT CODE",
        accept = { t ->
            ContactLink.parse(t)?.let { o ->
                scanning = false
                result = vm.contacts.addFromOffer(o) ?: "Request sent to ${vm.people.label(o.id, o.name)}. You'll see them in your contacts as soon as it goes through."
                true
            } ?: false
        },
        onClose = { scanning = false }
    )
}

// =====================================================================================================
// Contact card
// =====================================================================================================

@Composable
fun ContactCardScreen(vm: ChatViewModel, id: String, onMessage: () -> Unit, onClose: () -> Unit) {
    val list by vm.contacts.list.collectAsStateWithLifecycle()
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val c = list.firstOrNull { it.id == id }
    if (c == null) { LaunchedEffect(Unit) { onClose() }; return }
    val s = vm.contacts.session(id)
    val alias = vm.people.alias(id, "Operative")
    val name = nicks[id] ?: alias
    var note by remember(id) { mutableStateOf(c.note) }
    DisposableEffect(id) { onDispose { if (note != c.note) vm.contacts.setNote(id, note) } }
    var showNick by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verifyMsg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }          // "remove" or "block"
    val connected by (s?.connected ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsStateWithLifecycle()
    val msgs by (s?.messages ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<Msg>()) }).collectAsStateWithLifecycle()
    val shared = nodes.filter { id in it.known.value }.map { it.displayName() }
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

    ConsolePage("CONTACT", onBack = onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, Holo.Primary, 64)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                MonoLabel(opTag(alias, id) + (if (nicks[id] != null) " · CALLSIGN ${alias.uppercase()}" else ""), Holo.Primary, size = 12)
                MonoLabel(
                    when {
                        c.state == Contact.STATE_SENT -> "REQUEST SENT · WAITING FOR THEM"
                        connected.isNotEmpty() -> "ONLINE · VIA " + (s?.route(id) ?: "LINK")
                        else -> "OFFLINE"
                    }, Holo.InkDim, size = 12)
            }
        }
        val vColor = if (c.verified) Holo.Primary else Holo.Accent
        Column(Modifier.fillMaxWidth().openPanel(if (c.verified) Holo.Mine else Holo.Panel, vColor.copy(alpha = 0.7f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel(if (c.verified) "✓ VERIFIED IN PERSON · ${fmt.format(Date(c.verifiedAt)).uppercase()}" else "NOT VERIFIED · SCAN THEIR CODE IN PERSON", vColor, size = 12)
            Text(fingerprint(id), fontFamily = Mono, fontSize = 14.sp, color = Holo.Ink)
            if (vm.people.lookalikeOf(id) != null) MonoLabel("⚠ SAME CALLSIGN AS SOMEONE ELSE YOU KNOW", Holo.Danger, size = 10)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardStat("CONTACT SINCE", fmt.format(Date(c.since)).uppercase(), Modifier.weight(1f))
            CardStat("DIRECT LOG", "${msgs.count { !it.system }} MSGS", Modifier.weight(1f))
        }
        if (shared.isNotEmpty()) {
            MonoLabel("SHARED NODES", Holo.Accent)
            Text(shared.joinToString(" · ") { it.uppercase() }, fontFamily = Mono, fontSize = 12.sp, color = Holo.Primary)
        }
        OutlinedTextField(note, { note = it.take(500) }, label = { Text("Private note · only on this phone") }, minLines = 2,
            shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
        verifyMsg?.let { InfoCard("", it) }
        HoloButton("Message", enabled = s != null, modifier = Modifier.fillMaxWidth(), onClick = onMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Nickname", Modifier.weight(1f), color = Holo.Primary) { showNick = true }
            GhostButton(if (c.verified) "Re-verify" else "Verify", Modifier.weight(1f), color = Holo.Primary) { AppLock.holdOpen(); verifying = true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Remove", Modifier.weight(1f), color = Holo.Muted) { confirm = "remove" }
            GhostButton("Block", Modifier.weight(1f), color = Holo.Danger) { confirm = "block" }
        }
    }
    if (showNick) NicknameDialog(alias = alias, current = nicks[id] ?: "",
        onSave = { vm.people.setNickname(id, it); showNick = false }, onClose = { showNick = false })
    if (verifying) ScanQrScreen(
        title = "VERIFY ${name.uppercase()}", subtitle = "SCAN THEIR CONTACT CODE IN PERSON",
        hint = "ALIGN THEIR CONTACT QR INSIDE THE FRAME", wrong = "NOT A CYBERTXTS CONTACT CODE",
        accept = { t ->
            ContactLink.parse(t)?.let { o ->
                verifying = false
                verifyMsg = if (o.id != id && o.id !in vm.people.lineage(id)) "That code belongs to someone else (${fingerprint(o.id).take(9)}…). Not verified."
                else if (o.kind != "q") "That's a shared link, not their in-person code. Ask them to open Contacts › + ADD."
                else { vm.contacts.addFromOffer(o.copy(id = id)); "Fingerprint matches. ${name} is verified." }
                true
            } ?: false
        },
        onClose = { verifying = false }
    )
    val cf = confirm
    if (cf != null) AlertDialog(
        onDismissRequest = { confirm = null },
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text(if (cf == "block") "Block $name?" else "Remove $name?") },
        text = {
            Text(if (cf == "block") "The direct link and its log are deleted, and they can't send you contact requests. You'll still see them in nodes you share."
                 else "The direct link and its log are deleted from this phone. You can add each other again later.")
        },
        confirmButton = {
            TextButton(onClick = { confirm = null; if (cf == "block") vm.contacts.block(id) else vm.contacts.remove(id); onClose() }) {
                Text(if (cf == "block") "BLOCK" else "REMOVE", fontFamily = Mono, color = Holo.Danger)
            }
        },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL", fontFamily = Mono, color = Holo.Primary) } }
    )
}

@Composable
private fun CardStat(title: String, value: String, modifier: Modifier) {
    Column(modifier.openPanel(Color.Transparent, Holo.Muted.copy(alpha = 0.35f), cut = 10.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        MonoLabel(title, Holo.InkDim, size = 10)
        Text(value, fontFamily = Mono, fontSize = 15.sp, color = Holo.Ink, maxLines = 1)
    }
}

// =====================================================================================================
// Direct chat: the node chat screen in direct mode
// =====================================================================================================

@Composable
fun DirectChatRoot(vm: ChatViewModel, id: String) {
    val sessions by vm.contacts.sessions.collectAsStateWithLifecycle()
    val list by vm.contacts.list.collectAsStateWithLifecycle()
    val s = sessions[id]
    val c = list.firstOrNull { it.id == id }
    if (s == null || c == null) { LaunchedEffect(Unit) { vm.directChat.value = null }; return }
    BackHandler { vm.directChat.value = null }
    var card by remember { mutableStateOf(false) }
    DisposableEffect(s.tag) {
        vm.openDirect.value = s.tag
        s.unread.value = 0
        onDispose { if (vm.openDirect.value == s.tag) vm.openDirect.value = null }
    }
    key(s.tag) {
        ChatScreen(s, onOpenMenu = { vm.directChat.value = null }, onOpenSettings = {},
            direct = DirectInfo(id, c.verified) { card = true })
    }
    if (card) ContactCardScreen(vm, id, onMessage = { card = false }) { card = false }
}

/** Confirm a contact link opened from another app. */
@Composable
fun PendingContactDialog(vm: ChatViewModel) {
    val p by vm.pendingContact.collectAsStateWithLifecycle()
    val o = p ?: return
    AlertDialog(
        onDismissRequest = { vm.pendingContact.value = null },
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Add ${o.name.ifBlank { "this operative" }} as a contact?", fontFamily = Mono) },
        text = {
            Text("Fingerprint ${fingerprint(o.id)}. They'll get a request to accept. Added through a link, they show as unverified until you scan each other in person.")
        },
        confirmButton = {
            TextButton(onClick = {
                vm.pendingContact.value = null
                vm.notice.value = vm.contacts.addFromOffer(o) ?: "Request sent. ${o.name.ifBlank { "They" }} will appear in Contacts once it goes through."
            }) { Text("ADD", fontFamily = Mono, color = Holo.Primary) }
        },
        dismissButton = { TextButton(onClick = { vm.pendingContact.value = null }) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}
