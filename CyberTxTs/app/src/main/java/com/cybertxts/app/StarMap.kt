package com.cybertxts.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

private val Amber = Color(0xFFFFB020)
private val Gold = Color(0xFFFFD166)

fun agoText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}S"
        s < 3600 -> "${s / 60}M"
        else -> "${s / 3600}H ${(s % 3600) / 60}M"
    }
}

private fun dashed(on: Float, off: Float) = PathEffect.dashPathEffect(floatArrayOf(on, off))

private fun DrawScope.label(m: TextMeasurer, text: String, x: Float, y: Float, color: Color, size: Int = 11, center: Boolean = true) {
    val lay = m.measure(AnnotatedString(text), TextStyle(color = color, fontSize = size.sp, fontFamily = Mono, letterSpacing = 0.5.sp))
    drawText(lay, topLeft = Offset(if (center) x - lay.size.width / 2f else x, y))
}

private fun DrawScope.opBox(m: TextMeasurer, c: Offset, letter: String, stroke: Color, fill: Color, faded: Boolean) {
    val half = 18.dp.toPx()
    val a = if (faded) 0.5f else 1f
    drawRect(fill.copy(alpha = a), topLeft = Offset(c.x - half, c.y - half), size = Size(half * 2, half * 2))
    drawRect(stroke.copy(alpha = a), topLeft = Offset(c.x - half, c.y - half), size = Size(half * 2, half * 2),
        style = Stroke(1.5.dp.toPx(), pathEffect = if (faded) dashed(6f, 6f) else null))
    val lay = m.measure(AnnotatedString(letter), TextStyle(color = stroke.copy(alpha = a), fontSize = 15.sp, fontFamily = Display, fontWeight = FontWeight.Bold))
    drawText(lay, topLeft = Offset(c.x - lay.size.width / 2f, c.y - lay.size.height / 2f))
}

private fun DrawScope.meStar(c: Offset, r: Float) {
    drawCircle(Holo.Panel, r, c)
    drawCircle(Holo.Primary, r, c, style = Stroke(2.5.dp.toPx()))
    drawCircle(Holo.Primary, r * 0.3f, c)
}

/** The STAR MAP tab inside a node. */
@Composable
fun StarMapView(vm: NodeSession, alsoIn: (String) -> List<String>, onMessage: (String) -> Unit = {}) {
    var mesh by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(vm.mapSnapshot()) }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { snap = vm.mapSnapshot(); delay(1_000) } }
    val hostId by vm.hostId.collectAsStateWithLifecycle()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "pulseT"
    )
    val measurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()
    val byId = snap.peers.associateBy { it.id }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            ChoiceRow(listOf("mine" to "My links", "mesh" to "Full mesh"), if (mesh) "mesh" else "mine") { mesh = it == "mesh" }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val center = Offset(w / 2f, h / 2f)
            val base = min(w, h)

            // Positions: "mine" puts you in the middle; "mesh" puts everyone on one ring, you at the top
            val pos = HashMap<String, Offset>()
            if (!mesh) {
                pos[vm.myId] = center
                snap.peers.forEachIndexed { i, p ->
                    val ang = -Math.PI / 2 + 2 * Math.PI * i / maxOf(1, snap.peers.size) + 0.35
                    val r = base * (if (p.linked) 0.30f else 0.40f)
                    pos[p.id] = Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat())
                }
            } else {
                val ring = listOf(vm.myId) + snap.peers.map { it.id }
                ring.forEachIndexed { i, id ->
                    val ang = -Math.PI / 2 + 2 * Math.PI * i / maxOf(1, ring.size)
                    val r = base * 0.36f
                    pos[id] = Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat())
                }
            }

            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "Star map of links in this node" }
                    .pointerInput(pos, mesh) {
                        detectTapGestures { tap ->
                            val hit = pos.entries.filter { it.key != vm.myId }
                                .minByOrNull { hypot(it.value.x - tap.x, it.value.y - tap.y) }
                            selected = if (hit != null && hypot(hit.value.x - tap.x, hit.value.y - tap.y) < 34.dp.toPx()) hit.key else null
                        }
                    }
            ) {
                drawCircle(Holo.Primary, base * 0.30f, center, alpha = 0.10f, style = Stroke(1f))
                drawCircle(Holo.Primary, base * 0.42f, center, alpha = 0.06f, style = Stroke(1f))
                val sw = 2.dp.toPx()

                if (!mesh) {
                    val me = center
                    snap.peers.forEach { p ->
                        val at = pos[p.id] ?: return@forEach
                        when {
                            p.route == "WIFI" -> drawLine(Holo.Primary, me, at, sw)
                            p.route == "NET" -> drawLine(Holo.Accent, me, at, sw, pathEffect = dashed(18f, 12f))
                            p.linked -> drawLine(Holo.Primary.copy(alpha = 0.6f), me, at, sw * 0.8f, pathEffect = dashed(4f, 10f))
                            else -> drawLine(Holo.Muted.copy(alpha = 0.35f), me, at, sw * 0.7f, pathEffect = dashed(4f, 14f))
                        }
                        if (p.active && p.linked) {
                            val t = pulse
                            drawCircle(if (p.route == "NET") Holo.Accent else Holo.Primary, 4.dp.toPx(),
                                Offset(at.x + (me.x - at.x) * t, at.y + (me.y - at.y) * t))
                        }
                    }
                } else {
                    snap.edges.forEach { e ->
                        val a = pos[e.a] ?: return@forEach
                        val b = pos[e.b] ?: return@forEach
                        when {
                            e.route == "WIFI" -> drawLine(Holo.Primary, a, b, sw)
                            e.route == "NET" -> drawLine(Holo.Accent, a, b, sw, pathEffect = dashed(18f, 12f))
                            else -> {
                                drawLine(Amber, a, b, sw, pathEffect = dashed(6f, 12f))
                                val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                                val s = 11.dp.toPx()
                                drawRect(Holo.Void, Offset(mid.x - s, mid.y - s), Size(s * 2, s * 2))
                                drawRect(Amber, Offset(mid.x - s, mid.y - s), Size(s * 2, s * 2), style = Stroke(1.5.dp.toPx()))
                                if (e.relayVia != null) {
                                    label(measurer, "R", mid.x, mid.y - 8.dp.toPx(), Amber, 12)
                                } else {
                                    val k = s * 0.5f
                                    drawLine(Amber, Offset(mid.x - k, mid.y - k), Offset(mid.x + k, mid.y + k), 2.dp.toPx())
                                    drawLine(Amber, Offset(mid.x + k, mid.y - k), Offset(mid.x - k, mid.y + k), 2.dp.toPx())
                                }
                            }
                        }
                    }
                }

                // operatives
                snap.peers.forEach { p ->
                    val at = pos[p.id] ?: return@forEach
                    val stroke = when { p.route == "NET" -> Holo.Accent; p.linked -> Holo.Primary; else -> Holo.Muted }
                    val fill = if (p.route == "NET") Holo.Theirs else if (p.linked) Holo.Mine else Holo.Panel
                    opBox(measurer, at, p.name.take(1).uppercase(), stroke, fill, faded = !p.linked)
                    if (p.host) label(measurer, "★", at.x + 20.dp.toPx(), at.y - 34.dp.toPx(), Gold, 14)
                    val line2 = when {
                        p.route != null -> "VIA ${p.route}" + (p.rttMs?.let { " · $it MS" } ?: "")
                        p.viaRelay != null -> "RELAYED VIA " + (byId[p.viaRelay]?.name?.uppercase() ?: "OPERATIVE")
                        p.linked -> "LINKED"
                        else -> "SEEN ${agoText(now - p.lastSeen)} AGO"
                    }
                    label(measurer, opTag(p.name, p.id), at.x, at.y + 22.dp.toPx(), stroke)
                    if (!mesh) label(measurer, line2, at.x, at.y + 36.dp.toPx(), Holo.InkDim, 10)
                }
                // you
                val me = pos[vm.myId] ?: center
                meStar(me, if (mesh) 20.dp.toPx() else 28.dp.toPx())
                if (hostId == vm.myId) label(measurer, "★", me.x + 24.dp.toPx(), me.y - 40.dp.toPx(), Gold, 14)
                label(measurer, "YOU//" + vm.myId.take(4).uppercase(), me.x, me.y + (if (mesh) 24 else 32).dp.toPx(), Holo.Primary)
            }

            if (snap.peers.isEmpty()) {
                Text("No operatives seen yet. Invite someone and they'll appear here.",
                    style = MaterialTheme.typography.bodyMedium, color = Holo.InkDim, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }

            val sel = selected?.let { byId[it] }
            if (sel != null) {
                OperativeCard(vm, sel, hostId == vm.myId, alsoIn(sel.id), byId, Modifier.align(Alignment.BottomCenter),
                    onMessage = { selected = null; onMessage(it) }) { selected = null }
            }
        }

        // Footer: totals, or mesh warnings
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)
                .openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.4f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (mesh) {
                val broken = snap.edges.filter { it.route == null }
                val up = snap.edges.count { it.route != null }
                if (broken.isEmpty()) {
                    MonoLabel("ALL ${snap.edges.size} LINKS UP · EVERYONE REACHES EVERYONE DIRECTLY", Holo.Primary)
                } else {
                    MonoLabel("LINKS UP $up/${snap.edges.size}", Holo.InkDim)
                    broken.take(3).forEach { e ->
                        val a = if (e.a == vm.myId) "YOU" else byId[e.a]?.name?.uppercase() ?: "?"
                        val b = if (e.b == vm.myId) "YOU" else byId[e.b]?.name?.uppercase() ?: "?"
                        val via = e.relayVia?.let { if (it == vm.myId) "YOU" else byId[it]?.name?.uppercase() }
                        MonoLabel(
                            "WARN · $a ⇄ $b · NO DIRECT LINK · " +
                                (if (via != null) "RELAYED VIA $via" else "CAN'T GET THROUGH"),
                            if (via != null) Amber else Holo.Danger
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MonoLabel("━ WIFI", Holo.Primary); MonoLabel("╍ NET", Holo.Accent); MonoLabel("┄ NO LINK", Amber)
                }
            } else {
                val direct = snap.peers.count { it.route != null }
                val relayed = snap.peers.count { it.linked && it.route == null }
                Row(Modifier.fillMaxWidth()) {
                    Stat("LINKED", "$direct" + (if (relayed > 0) " +$relayed" else ""), Modifier.weight(1f))
                    Stat("TX", fmtSize(snap.tx), Modifier.weight(1f))
                    Stat("RX", fmtSize(snap.rx), Modifier.weight(1f))
                    Stat("RELAYS", if (snap.relays < 0) "OFF" else "${snap.relays}/${NostrPool.DEFAULT_RELAYS.size}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonoLabel("━ WIFI", Holo.Primary); MonoLabel("╍ NET", Holo.Accent)
                    MonoLabel("┄ RELAYED / SEEN", Holo.Muted); MonoLabel("★ HOST", Gold)
                }
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        MonoLabel(title, Holo.InkDim, size = 10)
        Text(value, fontFamily = Mono, fontSize = 16.sp, color = Holo.Ink, maxLines = 1)
    }
}

/** Details for one operative, opened by tapping them on the map. */
@Composable
fun OperativeCard(
    vm: NodeSession, p: NodeSession.MapPeer, iAmHost: Boolean, alsoIn: List<String>,
    byId: Map<String, NodeSession.MapPeer>, modifier: Modifier = Modifier, onMessage: (String) -> Unit = {},
    onClose: () -> Unit
) {
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    var showNick by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val alias = vm.people.alias(p.id, p.name)
    val now = System.currentTimeMillis()
    val tint = if (p.route == "NET") Holo.Accent else Holo.Primary

    if (showNick) {
        NicknameDialog(alias = alias, current = nicks[p.id] ?: "",
            onSave = { vm.people.setNickname(p.id, it); showNick = false }, onClose = { showNick = false })
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text(if (iAmHost) "Remove ${p.name}?" else "Vote to remove ${p.name}?") },
            text = {
                Text(if (iAmHost) "They'll be removed and the access key will rotate so they can't return."
                     else "Everyone will be asked. They're removed when a majority agrees (at least 2 votes).")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    if (iAmHost) vm.hostKick(p.id) else vm.castVote(p.id, true)
                    onClose()
                }) { Text(if (iAmHost) "REMOVE" else "START VOTE", fontFamily = Mono, color = Holo.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL", fontFamily = Mono, color = Holo.Primary) } }
        )
    }

    Column(
        modifier.fillMaxWidth().openPanel(Holo.Panel, tint.copy(alpha = 0.8f), cut = 20.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).openButton(tint, cut = 8.dp), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(p.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (p.host) { Spacer(Modifier.width(8.dp)); MonoLabel("★ HOST", Gold) }
                }
                MonoLabel(opTag(alias, p.id) + " · OP-ID " + p.id.take(4).uppercase() + "·" + p.id.drop(4).take(4).uppercase(), tint)
                if (nicks[p.id] != null) Text("Callsign: $alias", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
            IconTextButton("✕", "Close", Holo.Muted, onClose)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("ROUTE", when {
                p.route != null -> "VIA ${p.route}"
                p.viaRelay != null -> "VIA " + (byId[p.viaRelay]?.name?.uppercase() ?: "RELAY")
                p.linked -> "LINKED"
                else -> "OFFLINE"
            }, tint, Modifier.weight(1f))
            StatBox("DELAY", p.rttMs?.let { "$it MS" } ?: "—", Holo.Ink, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox(if (p.since != null) "LINKED FOR" else "LAST SEEN",
                if (p.since != null) agoText(now - p.since) else if (p.lastSeen > 0) agoText(now - p.lastSeen) + " AGO" else "—",
                Holo.Ink, Modifier.weight(1f))
            StatBox("RX FROM THEM", fmtSize(p.rx), Holo.Ink, Modifier.weight(1f))
        }
        if (alsoIn.isNotEmpty()) {
            MonoLabel("ALSO IN YOUR NODES", Holo.Accent)
            Text(alsoIn.joinToString(" · "), fontFamily = Mono, fontSize = 12.sp, color = Holo.Primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Nick", Modifier.weight(1f), color = Holo.Primary) { showNick = true }
            if (p.linked) GhostButton("Message", Modifier.weight(1f), color = Holo.Primary) { onMessage(p.id) }
            if (iAmHost) GhostButton("Remove", Modifier.weight(1f), color = Holo.Danger) { confirm = true }
            else if (!p.host) GhostButton("Vote out", Modifier.weight(1f), color = Holo.Danger) { confirm = true }
        }
    }
}

@Composable
private fun StatBox(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.openPanel(Color.Transparent, Holo.Muted.copy(alpha = 0.35f), cut = 10.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        MonoLabel(title, Holo.InkDim, size = 10)
        Text(value, fontFamily = Mono, fontSize = 15.sp, color = color, maxLines = 1)
    }
}

/** All your nodes as star systems, with operatives you share between them drawn as bridges. Local only. */
@Composable
fun ConstellationDialog(nodes: List<NodeSession>, onPick: (NodeSession) -> Unit, onClose: () -> Unit) {
    data class Sys(val node: NodeSession, val name: String, val members: Set<String>, val linked: Int, val unread: Int)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val systems = remember(tick, nodes) {
        nodes.map { Sys(it, it.displayName(), it.memberIds(), it.peers.value, it.unread.value) }
    }
    val bridges = remember(systems) {
        val count = HashMap<String, MutableList<Int>>()
        systems.forEachIndexed { i, s -> s.members.forEach { count.getOrPut(it) { mutableListOf() }.add(i) } }
        count.filter { it.value.size >= 2 }
    }
    val people = nodes.firstOrNull()?.people
    fun nameOf(id: String): String {
        val n = nodes.firstNotNullOfOrNull { it.known.value[id] } ?: "Unknown"
        return people?.label(id, n) ?: n
    }
    val total = systems.flatMap { it.members }.toSet().size
    val measurer = rememberTextMeasurer()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Holo.Void).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel).padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTextButton("‹", "Back", Holo.Primary, onClose)
                Column {
                    Text("CONSTELLATION", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Holo.Primary)
                    MonoLabel("${systems.size} NODES · $total OPERATIVES · VISIBLE ONLY TO YOU")
                }
            }
            HorizontalDivider(color = Holo.Primary.copy(alpha = 0.35f))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val c = Offset(w / 2f, h / 2f)
                val r = min(w, h) * 0.30f
                val centers = systems.indices.map { i ->
                    if (systems.size == 1) c else {
                        val ang = -Math.PI / 2 + 2 * Math.PI * i / systems.size
                        Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat())
                    }
                }
                Starfield(Modifier.fillMaxSize())
                Canvas(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "Your nodes as star systems" }
                        .pointerInput(systems.size, w, h) {
                            detectTapGestures { tap ->
                                val i = centers.indices.minByOrNull { hypot(centers[it].x - tap.x, centers[it].y - tap.y) }
                                if (i != null && hypot(centers[i].x - tap.x, centers[i].y - tap.y) < 70.dp.toPx()) onPick(systems[i].node)
                            }
                        }
                ) {
                    // bridges first, under the systems
                    bridges.entries.take(12).forEach { (id, idx) ->
                        for (k in 0 until idx.size - 1) {
                            val a = centers[idx[k]]; val b = centers[idx[k + 1]]
                            drawLine(Holo.Primary.copy(alpha = 0.7f), a, b, 1.5.dp.toPx(), pathEffect = dashed(10f, 10f))
                            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                            opBox(measurer, mid, nameOf(id).take(1).uppercase(), Holo.Primary, Holo.Void, false)
                        }
                    }
                    systems.forEachIndexed { i, s ->
                        val sc = centers[i]
                        val tint = when { s.unread > 0 -> Holo.Accent; s.linked > 0 -> Holo.Primary; else -> Holo.Muted }
                        val orbit = (34 + minOf(s.members.size, 12) * 2.5f).dp.toPx()
                        drawCircle(tint, orbit, sc, alpha = 0.3f, style = Stroke(1f))
                        drawCircle(Holo.Panel, 16.dp.toPx(), sc)
                        drawCircle(tint, 16.dp.toPx(), sc, style = Stroke(2.dp.toPx()))
                        s.members.take(12).forEachIndexed { k, _ ->
                            val ang = 2 * Math.PI * k / minOf(s.members.size, 12)
                            drawCircle(Holo.Ink.copy(alpha = if (s.linked > 0) 0.9f else 0.4f), 3.dp.toPx(),
                                Offset(sc.x + orbit * cos(ang).toFloat(), sc.y + orbit * sin(ang).toFloat()))
                        }
                        label(measurer, s.name.uppercase().take(18), sc.x, sc.y + orbit + 6.dp.toPx(), tint)
                        val sub = (if (s.linked > 0) "LINK ${s.linked + 1}" else "NO LINK") + (if (s.unread > 0) " · ${s.unread} UNREAD" else "")
                        label(measurer, sub, sc.x, sc.y + orbit + 20.dp.toPx(), Holo.InkDim, 10)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(12.dp).openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.4f))
                    .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonoLabel("BRIDGES · OPERATIVES IN MORE THAN ONE NODE", Holo.Accent)
                if (bridges.isEmpty()) {
                    Text("No shared operatives yet.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                }
                bridges.entries.sortedBy { nameOf(it.key).lowercase() }.forEach { (id, idx) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(systems[idx[0]].node) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(nameOf(id), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        MonoLabel(idx.joinToString(" · ") { systems[it].name.uppercase().take(12) })
                    }
                }
                Text("Tap a system to open that node.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
