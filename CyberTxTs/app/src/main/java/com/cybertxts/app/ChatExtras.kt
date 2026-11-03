package com.cybertxts.app

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Disappearing messages: Off / 1 hour / 1 day / 1 week, for everyone in this node or contact. */
@Composable
fun TtlDialog(current: Long, onPick: (Long) -> Unit, onClose: () -> Unit) {
    var choice by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Disappearing messages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Messages, photos and videos older than this are deleted on every phone in this chat. Anyone here can change it, and everyone sees who did.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                ChoiceRow(listOf("0" to "Off", "3600000" to "1 hour", "86400000" to "1 day", "604800000" to "1 week"), choice) { choice = it }
                Text("Someone could still screenshot or photograph a message before it goes.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(choice.toLong()) }, enabled = choice != current.toString()) {
                Text("SET", fontFamily = Mono, color = Holo.Primary)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}

/** Every photo and video in this chat, newest first. */
@Composable
fun MediaGallery(vm: NodeSession, messages: List<Msg>, onPhoto: (Msg) -> Unit, onVideo: (Msg) -> Unit, onClose: () -> Unit) {
    val items = remember(messages) { messages.filter { !it.deleted && (it.image != null || it.video != null) }.reversed() }
    val tracks = remember(messages) { messages.filter { !it.deleted && it.audio != null }.reversed() }
    ConsolePage("MEDIA", "${items.count { it.image != null }} PHOTOS · ${items.count { it.video != null }} VIDEOS · ${tracks.size} AUDIO", onClose) {
        if (items.isEmpty() && tracks.isEmpty()) InfoCard("", "No photos, videos or audio files in this chat yet.")
        if (tracks.isNotEmpty()) {
            MonoLabel("AUDIO FILES", Holo.InkDim, size = 11)
            tracks.forEach { m ->
                Box(Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.35f), cut = 8.dp).padding(10.dp)) {
                    AudioBubble(vm, m, null, m.senderId == vm.myId, onLongClick = {})
                }
            }
            if (items.isNotEmpty()) MonoLabel("PHOTOS & VIDEOS", Holo.InkDim, size = 11)
        }
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m -> GalleryTile(vm, m, Modifier.weight(1f)) { if (m.image != null) onPhoto(m) else onVideo(m) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GalleryTile(vm: NodeSession, m: Msg, modifier: Modifier, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) {
            (if (m.image != null) vm.readImage(m) else vm.readThumb(m))?.let { ImageUtil.decodeSampled(it, 300)?.asImageBitmap() }
        }
    }
    Box(modifier.aspectRatio(1f).openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.35f), cut = 8.dp)
        .clickable(role = Role.Button, onClickLabel = if (m.image != null) "Open photo" else "Play video", onClick = onClick),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(bitmap = b, contentDescription = if (m.image != null) "Photo" else "Video",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else MonoLabel(if (m.image != null) "IMG" else "VID", Holo.InkDim)
        if (m.video != null) {
            Box(Modifier.align(Alignment.BottomStart).background(Holo.Void.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text("VID " + fmtDur(m.durMs), fontFamily = Mono, fontSize = 10.sp, color = Holo.Primary)
            }
        }
    }
}

/** Pick a node or contact to forward [m] to. */
@Composable
fun ForwardDialog(from: NodeSession, m: Msg, targets: List<Pair<String, NodeSession>>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val list = targets.filter { it.second !== from }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Forward to…") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(snippetOf(m), style = MaterialTheme.typography.bodySmall, color = Holo.InkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (list.isEmpty()) Text("No other nodes or contacts yet.", style = MaterialTheme.typography.bodyMedium)
                list.forEach { (name, s) ->
                    GhostButton(name, Modifier.fillMaxWidth(), color = Holo.Primary) {
                        s.forwardFrom(from, m)
                        Toast.makeText(ctx, "Forwarded to $name", Toast.LENGTH_SHORT).show()
                        onClose()
                    }
                }
                Text("It's sent as a new message from you; the original sender isn't shown.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}

fun snippetOf(m: Msg): String = when {
    m.image != null -> "IMG · Photo"
    m.video != null -> "VID · Video " + fmtDur(m.durMs)
    m.voice != null -> "VOX · Voice note " + fmtDur(m.durMs)
    m.audio != null -> "AUD · " + m.aTitle.ifBlank { "Audio file" } + (if (m.aArtist.isNotBlank()) " · " + m.aArtist else "") + " " + fmtDur(m.durMs)
    else -> m.text
}

/** Search every node and contact chat stored on this phone. */
@Composable
fun GlobalSearchDialog(vm: ChatViewModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Triple<String, NodeSession, Msg>>>(emptyList()) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(200)
        hits = withContext(Dispatchers.Default) {
            vm.allChats().flatMap { (name, s) ->
                s.messages.value.filter { !it.deleted && !it.system && it.matches(q) }.map { Triple(name, s, it) }
            }.sortedByDescending { it.third.ts }.take(300)
        }
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    ConsolePage("SEARCH ALL CHATS", "NODES AND CONTACTS ON THIS PHONE", onClose) {
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("At least 2 characters") },
            shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
        if (query.trim().length >= 2) MonoLabel(if (hits.isEmpty()) "NO MATCHES" else "${hits.size} MATCH${if (hits.size == 1) "" else "ES"}")
        hits.forEach { (name, s, m) ->
            Column(
                Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.35f))
                    .clickable(role = Role.Button) { vm.openMessage(s, m); onClose() }.padding(10.dp)
            ) {
                MonoLabel(name.uppercase() + " · " + fmt.format(Date(m.ts)).uppercase(), Holo.Accent, size = 10)
                MonoLabel(if (m.senderId == vm.myId) "YOU" else opTag(vm.people.label(m.senderId, m.senderName), m.senderId), Holo.InkDim, size = 10)
                Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Polls

@Composable
fun PollDialog(onCreate: (String, List<String>) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val opts = remember { mutableStateListOf("", "") }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("New poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it.take(300) }, label = { Text("Question") }, shape = RectangleShape,
                    colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
                opts.forEachIndexed { i, o ->
                    OutlinedTextField(o, { opts[i] = it.take(100) }, singleLine = true, label = { Text("Option ${i + 1}") },
                        shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
                }
                if (opts.size < 6) GhostButton("Add option", Modifier.fillMaxWidth(), color = Holo.Primary) { opts.add("") }
                Text("Everyone gets one vote and can change it. Votes are signed, so nobody can vote twice or as someone else.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(q, opts.toList()) }, enabled = q.isNotBlank() && opts.count { it.isNotBlank() } >= 2) {
                Text("POST", fontFamily = Mono, color = Holo.Primary)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}

@Composable
fun PollBubble(vm: NodeSession, m: Msg) {
    val options = remember(m.poll) { runCatching { org.json.JSONArray(m.poll).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList()) }
    val choices = m.votes.mapValues { it.value.substringBefore(':').toIntOrNull() ?: -1 }.filterValues { it >= 0 }
    val counts = options.indices.map { i -> choices.count { it.value == i } }
    val total = counts.sum()
    val mine = choices[vm.myId]
    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLabel("POLL", Holo.Accent, size = 10)
        Text(m.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        options.forEachIndexed { i, o ->
            val n = counts[i]; val on = mine == i
            val frac = if (total == 0) 0f else n.toFloat() / total
            val barColor = Holo.Primary.copy(alpha = if (on) 0.28f else 0.14f)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .openButton(if (on) Holo.Primary else Holo.Muted.copy(alpha = 0.6f), cut = 6.dp)
                    .clickable(role = Role.RadioButton, onClickLabel = if (on) "Take back your vote" else "Vote for $o") {
                        vm.vote(m.id, if (on) -1 else i)
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$o, $n vote${if (n == 1) "" else "s"}" + if (on) ", your vote" else ""
                    }
                    .drawBehind { drawRect(barColor, size = androidx.compose.ui.geometry.Size(size.width * frac, size.height)) }
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (on) "◉ " else "○ ") + o, modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Text("$n", fontFamily = Mono, fontSize = 13.sp, color = if (on) Holo.Primary else Holo.InkDim)
                }
            }
        }
        MonoLabel("$total VOTE${if (total == 1) "" else "S"} · TAP TO VOTE, TAP AGAIN TO TAKE IT BACK", Holo.InkDim, size = 10)
    }
}

// ---------------------------------------------------------------- Scheduled messages

@Composable
fun ScheduleDialog(initial: String, onSchedule: (String, Long) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var choice by remember { mutableStateOf("3600000") }
    var customH by remember { mutableStateOf("8") }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    fun at(): Long {
        val now = System.currentTimeMillis()
        return when (choice) {
            "tomorrow" -> java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }.timeInMillis
            "custom" -> now + ((customH.toDoubleOrNull() ?: 1.0).coerceIn(0.1, 24.0 * 30) * 3_600_000).toLong()
            else -> now + choice.toLong()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Schedule message") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it.take(4000) }, label = { Text("Message") }, minLines = 2,
                    shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
                ChoiceRow(listOf("900000" to "15 min", "3600000" to "1 hour", "tomorrow" to "9 am", "custom" to "Hours…"), choice) { choice = it }
                if (choice == "custom") OutlinedTextField(customH, { customH = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, singleLine = true,
                    label = { Text("Hours from now") }, shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
                MonoLabel("SENDS " + fmt.format(Date(at())).uppercase(), Holo.Primary)
                Text("Your phone needs to be on with CyberTxTs running (Hold link in background) when it's due.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = { TextButton(onClick = { onSchedule(text, at()) }, enabled = text.isNotBlank()) { Text("SCHEDULE", fontFamily = Mono, color = Holo.Primary) } },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}

@Composable
fun ScheduledDialog(items: List<NodeSession.Scheduled>, onCancel: (String) -> Unit, onClose: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Scheduled") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text("Nothing scheduled here.")
                items.forEach { s ->
                    Row(Modifier.fillMaxWidth().openPanel(Holo.Void, Holo.Muted.copy(alpha = 0.35f)).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            MonoLabel(fmt.format(Date(s.at)).uppercase(), Holo.Primary, size = 10)
                            Text(s.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onCancel(s.id) }) { Text("CANCEL", fontFamily = Mono, color = Holo.Danger) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", fontFamily = Mono, color = Holo.Primary) } }
    )
}

/** Search match: the text, or an audio file's title, artist or file name. */
fun Msg.matches(q: String): Boolean =
    text.contains(q, ignoreCase = true) ||
        (audio != null && (aTitle.contains(q, ignoreCase = true) || aArtist.contains(q, ignoreCase = true) || aName.contains(q, ignoreCase = true)))
