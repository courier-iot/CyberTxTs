package com.cybertxts.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.util.Calendar
import java.util.Date
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.em
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = (application as CyberApp).chatVm()   // one brain for the whole app, kept alive by the service
        vm.offerInvite(InviteLink.parse(intent?.dataString))
        vm.offerContact(ContactLink.parse(intent?.dataString))
        // Pick the colours before the first frame so there's no flash of the wrong theme
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val startDark = when (vm.settings.value.theme) { "light" -> false; "system" -> systemDark; else -> true }
        Holo.apply(startDark)
        applyBars(startDark)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val sysDark = isSystemInDarkTheme()
            val dark = when (settings.theme) { "light" -> false; "system" -> sysDark; else -> true }
            LaunchedEffect(dark) {
                Holo.apply(dark)
                applyBars(dark)
            }
            // App lock: privacy screen hides the app in the switcher and blocks screenshots
            val lockOn by AppLock.enabled.collectAsStateWithLifecycle()
            val privacy by AppLock.privacyScreen.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            LaunchedEffect(lockOn, privacy) {
                if (lockOn && privacy) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            HoloTheme {
                Box(Modifier.fillMaxSize().background(Holo.Backdrop)) {
                    Surface(
                        color = Color.Transparent,
                        contentColor = Holo.Ink,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding()
                    ) {
                        if (locked) {
                            LockScreen(vm)
                            CallOverlay(vm)      // an ongoing or incoming call stays reachable while the app is locked
                        } else AppRoot(vm)
                    }
                }
            }
        }
    }

    /** Light or dark status/navigation bar icons to match the theme. */
    private fun applyBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as CyberApp).chatVm().offerInvite(InviteLink.parse(intent.dataString))
        (application as CyberApp).chatVm().offerContact(ContactLink.parse(intent.dataString))
    }

    override fun onStart() {
        super.onStart()
        AppLock.onReturn()
        AppState.foreground = true
        Notifier.clearMessages(this)
        (application as CyberApp).chatVm().markActiveRead()
    }

    override fun onStop() {
        if (!isChangingConfigurations) AppLock.onLeave()
        AppState.foreground = false
        super.onStop()
    }
}

// ---------- Which screen? ----------

@Composable
fun AppRoot(vm: ChatViewModel) {
    val name by vm.profileName.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    val contactsOpen by vm.contactsOpen.collectAsStateWithLifecycle()
    val directChat by vm.directChat.collectAsStateWithLifecycle()

    val nm = name
    val act = active
    val dc = directChat
    when {
        nm == null -> NameScreen(vm) { vm.setProfileName(it) }
        dc != null -> DirectChatRoot(vm, dc)
        contactsOpen -> ContactsRoot(vm)
        act == null || showNew -> HomeScreen(vm, nm, canCancel = nodes.isNotEmpty() && act != null) { showNew = false }
        else -> NodeShell(vm, nodes, act) { showNew = true }
    }

    val pending by vm.pendingInvite.collectAsStateWithLifecycle()
    val inv = pending
    if (inv != null && nm != null) {
        AlertDialog(
            onDismissRequest = { vm.pendingInvite.value = null },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Link to ${inv.code}?", fontFamily = Mono) },
            text = {
                Text(
                    if (inv.host != null) "Host fingerprint ${inv.host.take(4).uppercase()}·${inv.host.drop(4).take(4).uppercase()} is " +
                        "included, so only that phone will ever be accepted as host."
                    else "This invite has no host fingerprint. You'll trust the first phone that claims to be host."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.pendingInvite.value = null
                    vm.join(inv.code, inv.key, created = false, host = inv.host)
                }) { Text("LINK", fontFamily = Mono, color = Holo.Primary) }
            },
            dismissButton = { TextButton(onClick = { vm.pendingInvite.value = null }) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
        )
    }

    if (nm != null) PendingContactDialog(vm)
    if (nm != null) CallOverlay(vm)

    val n = notice
    if (n != null) {
        AlertDialog(
            onDismissRequest = { vm.clearNotice() },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Heads up") },
            text = { Text(n) },
            confirmButton = { TextButton(onClick = { vm.clearNotice() }) { Text("OK", color = Holo.Primary) } }
        )
    }
}

// ---------- Shared pieces ----------

/** Log-style tag for a person: NAME//ABCD (first 4 characters of their permanent ID). */
fun opTag(name: String, id: String): String = name.uppercase() + "//" + id.take(4).uppercase()

/** Small monospace label line, e.g. section headings and message log lines. */
@Composable
fun MonoLabel(text: String, color: Color = Holo.InkDim, modifier: Modifier = Modifier, size: Int = 11, align: TextAlign? = null) {
    Text(text, modifier = modifier, fontFamily = Mono, fontSize = size.sp, letterSpacing = 0.08.em, color = color,
        textAlign = align, maxLines = 4)
}

/** Solid cyan action button with two cut corners. */
@Composable
fun HoloButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, height: Int = 48, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height.dp),
        shape = cutShape(9.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Holo.Primary, contentColor = Holo.Void,
            disabledContainerColor = Holo.PrimaryDeep, disabledContentColor = Holo.InkDim
        )
    ) { Text(label.uppercase(), fontFamily = Mono, fontSize = 14.sp, letterSpacing = 0.1.em) }
}

/** Outlined readout-style button (IMG, VOX, REROLL...) with an open clipped corner. */
@Composable
fun GhostButton(
    label: String, modifier: Modifier = Modifier, color: Color = Holo.Muted, height: Int = 44,
    description: String? = null, onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = maxOf(height, 44).dp)
            .openButton(color.copy(alpha = 0.7f))
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label.uppercase(), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.08.em, color = color) }
}

@Composable
fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) HoloButton(label, enabled = true, modifier = modifier, height = 44, onClick = onClick)
    else GhostButton(label, modifier = modifier, color = Holo.Primary, onClick = onClick)
}

/** A row of choices where one is picked (theme, auto-purge, quality). */
@Composable
fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .then(
                        if (on) Modifier.clip(CutCornerShape(topStart = 6.dp)).background(Holo.Primary)
                        else Modifier.openButton(Holo.Muted.copy(alpha = 0.6f), cut = 6.dp)
                    )
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(key) })
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label.uppercase(), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.06.em,
                    color = if (on) Holo.Void else Holo.InkDim, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** Square holo switch with an open clipped corner. */
@Composable
fun HoloSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = if (checked) Holo.Primary else Holo.Muted
    Box(
        Modifier.size(width = 56.dp, height = 44.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label; stateDescription = if (checked) "On" else "Off" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 28.dp)
                .openPanel(if (checked) Holo.Primary.copy(alpha = 0.15f) else Color.Transparent, c, left = true, cut = 5.dp)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) { Box(Modifier.size(20.dp).background(c)) }
    }
}

@Composable
fun InfoCard(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.4f)).padding(12.dp)
    ) {
        if (title.isNotEmpty()) {
            MonoLabel(title.uppercase(), Holo.Primary)
            Spacer(Modifier.height(4.dp))
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Holo.Ink.copy(alpha = 0.85f))
    }
}

@Composable
fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        }
        HoloSwitch(checked, title, onChange)
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MonoLabel(title, Holo.Accent, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** A 44dp text glyph button (menu, back, close) with an accessibility label. */
@Composable
fun IconTextButton(glyph: String, description: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Text(glyph, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SettingsHost(vm: ChatViewModel, onClose: () -> Unit) {
    SettingsScreen(vm, onClose)
}

fun buildMembers(
    myId: String, myName: String, known: Map<String, String>, connected: Set<String>, hostId: String?
): List<Member> {
    val list = mutableListOf(Member(myId, myName, hostId == myId, true))
    connected.forEach { id -> list.add(Member(id, known[id] ?: "Unknown", hostId == id, false)) }
    return list.sortedWith(
        compareByDescending<Member> { it.isHost }.thenByDescending { it.isMe }.thenBy { it.name.lowercase() }
    )
}

// ---------- First launch: choose a callsign ----------

@Composable
fun NameScreen(vm: ChatViewModel, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Starfield(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Orbit(104.dp, Holo.Primary, spin = true)
            Spacer(Modifier.height(16.dp))
            Text("CyberTxTs", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp,
                letterSpacing = 0.04.em, color = Holo.Primary)
            MonoLabel("BY STELLARITE · SECURE COMMS", Holo.Accent, size = 12)
            Spacer(Modifier.height(36.dp))
            MonoLabel("CHOOSE YOUR CALLSIGN, OPERATIVE", Holo.InkDim, size = 12)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Callsign") }, singleLine = true,
                shape = CutCornerShape(topStart = 10.dp), colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("Permanent for this phone. If you're new to CyberTxTs, pick one below.", style = MaterialTheme.typography.bodySmall,
                color = Holo.InkDim, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            HoloButton("Confirm callsign", enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { onDone(name.trim()) }
            Spacer(Modifier.height(28.dp))
            ReturningOptions(vm)
        }
    }
}

// ---------- Spin up or link to a node ----------

@Composable
fun HomeScreen(vm: ChatViewModel, alias: String, canCancel: Boolean, onDone: () -> Unit) {
    var creating by remember { mutableStateOf(true) }
    var newCode by remember { mutableStateOf(RoomCodes.generate()) }
    var createPw by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var joinPw by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val joining by vm.joining.collectAsStateWithLifecycle()
    val fieldColors = holoFieldColors()
    val fieldShape = CutCornerShape(topStart = 10.dp)

    if (showSettings) SettingsHost(vm) { showSettings = false }

    Box(Modifier.fillMaxSize()) {
        Starfield(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (canCancel) IconTextButton("‹", "Back", Holo.Primary, onDone)
                Spacer(Modifier.weight(1f))
                MonoLabel(opTag(alias, vm.myId).replace("//", " · OP-ID "))
                Spacer(Modifier.width(8.dp))
                GhostButton("Contacts", description = "Contacts", color = Holo.Primary) { vm.contactsOpen.value = true }
                Spacer(Modifier.width(6.dp))
                GhostButton("SET", description = "Settings") { showSettings = true }
            }
            Spacer(Modifier.height(18.dp))
            Orbit(84.dp, Holo.Primary, spin = true)
            Spacer(Modifier.height(10.dp))
            Text("CyberTxTs", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Holo.Primary)
            Spacer(Modifier.height(22.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Spin up node", creating, Modifier.weight(1f)) { creating = true }
                ModeButton("Link to node", !creating, Modifier.weight(1f)) { creating = false }
            }
            Spacer(Modifier.height(14.dp))

            if (creating) {
                InfoCard("",
                    "You'll host this node and can remove intruders. Share the node code and access key with up to " +
                        "${Limits.MAX_ROOM - 1} others. If anyone is removed, the key rotates for everyone else automatically."
                )
                Spacer(Modifier.height(16.dp))
                MonoLabel("NODE CODE", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 52.dp).openPanel(Holo.Mine, Holo.Primary.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(newCode, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            letterSpacing = 0.05.em, color = Holo.Primary, maxLines = 1)
                    }
                    GhostButton("Reroll", height = 52, description = "Generate a new code") { newCode = RoomCodes.generate() }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(createPw, { createPw = it }, label = { Text("Access key · 6+ characters") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = fieldShape,
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
                HoloButton(
                    if (joining) "Establishing link…" else "Initialise node",
                    enabled = !joining && createPw.length >= 6, modifier = Modifier.fillMaxWidth(), height = 52
                ) { vm.join(newCode, createPw, created = true); onDone() }
            } else {
                InfoCard("", "Scan their QR invite, or enter the node code and access key from whoever spun it up. Works on the same WiFi or across the internet.")
                Spacer(Modifier.height(12.dp))
                var scanning by remember { mutableStateOf(false) }
                HoloButton("Scan QR invite", enabled = true, modifier = Modifier.fillMaxWidth()) { scanning = true }
                if (scanning) ScanInviteScreen(
                    onInvite = { i -> scanning = false; vm.offerInvite(i) },
                    onClose = { scanning = false }
                )
                Spacer(Modifier.height(12.dp))
                MonoLabel("—— OR ENTER MANUALLY ——", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(joinCode, { joinCode = it }, label = { Text("Node code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), shape = fieldShape,
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(joinPw, { joinPw = it }, label = { Text("Access key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), shape = fieldShape,
                    colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
                HoloButton(
                    if (joining) "Establishing link…" else "Link to node",
                    enabled = !joining && joinCode.isNotBlank() && joinPw.isNotEmpty(), modifier = Modifier.fillMaxWidth(), height = 52
                ) { vm.join(joinCode.trim().uppercase(), joinPw, created = false); onDone() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- Nodes drawer + current node ----------

@Composable
fun NodeRow(c: NodeSession, selected: Boolean, onClick: () -> Unit) {
    val room by c.room.collectAsStateWithLifecycle()
    val peers by c.peers.collectAsStateWithLifecycle()
    val unread by c.unread.collectAsStateWithLifecycle()
    val nickname by c.nickname.collectAsStateWithLifecycle()
    val muted by c.muted.collectAsStateWithLifecycle()
    val stroke = when {
        selected -> Holo.Primary.copy(alpha = 0.75f)
        unread > 0 -> Holo.Accent.copy(alpha = 0.6f)
        else -> Holo.Muted.copy(alpha = 0.4f)
    }
    val fill = when {
        selected -> Holo.Mine
        unread > 0 -> Holo.Theirs
        else -> Holo.Panel
    }
    Row(
        Modifier.fillMaxWidth().openPanel(fill, stroke)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (nickname.isBlank()) {
                Text(room.code, fontFamily = Mono, fontSize = 15.sp, maxLines = 1)
            } else {
                Text(nickname, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            }
            MonoLabel(
                (if (nickname.isBlank()) "" else room.code + " · ") + (if (peers == 0) "NO LINK" else "LINK ${peers + 1}/${Limits.MAX_ROOM}"),
                if (selected) Holo.Primary else Holo.InkDim
            )
        }
        when {
            selected -> MonoLabel(if (muted) "VIEWING · MUTED" else "VIEWING", Holo.Primary, size = 10)
            muted && unread == 0 -> MonoLabel("MUTED", Holo.InkDim, size = 10)
            unread > 0 -> Box(
                Modifier.clip(CutCornerShape(topStart = 5.dp)).background(Holo.Accent).padding(horizontal = 7.dp, vertical = 2.dp)
            ) { Text("$unread", fontFamily = Mono, fontSize = 12.sp, color = Holo.Void) }
        }
    }
}

@Composable
fun NodeShell(vm: ChatViewModel, nodes: List<NodeSession>, active: NodeSession, onNew: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showConstellation by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val alias by vm.profileName.collectAsStateWithLifecycle()
    val contactSessions by vm.contacts.sessions.collectAsStateWithLifecycle()
    var contactUnread by remember { mutableStateOf(0) }
    LaunchedEffect(contactSessions) {
        while (true) { contactUnread = contactSessions.values.sumOf { it.unread.value }; delay(2_000) }
    }

    // Ask once for permission to show notifications (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showSettings) SettingsHost(vm) { showSettings = false }
    if (showSearch) GlobalSearchDialog(vm) { showSearch = false }
    if (showConstellation) {
        ConstellationDialog(nodes, onPick = { vm.select(it); showConstellation = false }, onClose = { showConstellation = false })
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Holo.Void.copy(alpha = 0.6f),
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RectangleShape, drawerContainerColor = Holo.Panel, drawerContentColor = Holo.Ink,
                modifier = Modifier.width(320.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    Starfield(Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("CyberTxTs", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                color = Holo.Primary, modifier = Modifier.weight(1f))
                            MonoLabel("BY STELLARITE", Holo.Accent, size = 10)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.4f)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(40.dp).openButton(Holo.Primary, cut = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text((alias ?: "?").take(1).uppercase(), fontFamily = Display, fontWeight = FontWeight.Bold, color = Holo.Primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(alias ?: "", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                MonoLabel("OPERATIVE · OP-ID " + vm.myId.take(4).uppercase())
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        MonoLabel("—— ACTIVE NODES · ${nodes.size} ——", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            nodes.forEach { c ->
                                NodeRow(c, selected = c === active) {
                                    vm.select(c)
                                    scope.launch { drawerState.close() }
                                }
                            }
                        }
                        HorizontalDivider(color = Holo.Primary.copy(alpha = 0.2f))
                        Spacer(Modifier.height(14.dp))
                        HoloButton("+ Spin up / link node", enabled = true, modifier = Modifier.fillMaxWidth()) {
                            scope.launch { drawerState.close() }; onNew()
                        }
                        Spacer(Modifier.height(8.dp))
                        GhostButton(if (contactUnread > 0) "Contacts · $contactUnread" else "Contacts", Modifier.fillMaxWidth(),
                            color = if (contactUnread > 0) Holo.Accent else Holo.Primary) {
                            scope.launch { drawerState.close() }; vm.contactsOpen.value = true
                        }
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Search all chats", Modifier.fillMaxWidth(), color = Holo.Primary) {
                            scope.launch { drawerState.close() }; showSearch = true
                        }
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Constellation", Modifier.fillMaxWidth(), color = Holo.Primary) {
                            scope.launch { drawerState.close() }; showConstellation = true
                        }
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Settings", Modifier.fillMaxWidth()) {
                            scope.launch { drawerState.close() }; showSettings = true
                        }
                    }
                }
            }
        }
    ) {
        // key() gives each node its own fresh screen state (typed text, dialogs)
        key(active.tag) {
            ChatScreen(
                active, onOpenMenu = { scope.launch { drawerState.open() } }, onOpenSettings = { showSettings = true },
                alsoIn = { id -> nodes.filter { it !== active && id in it.memberIds() }.map { it.displayName() } },
                isContact = { vm.contacts.isContact(it) },
                forwardTargets = { vm.allChats() },
                onCall = { p, v -> vm.calls.start(active, p, v) },
                onAddContact = { id ->
                    val ok = vm.contacts.requestViaNode(active, id)
                    Toast.makeText(ctx, if (ok) "Contact request sent. They need to accept." else "They need to be linked right now to add them.",
                        Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

// ---------- Members / kick / vote ----------

@Composable
fun MembersDialog(
    vm: NodeSession, members: List<Member>, votes: Map<String, VoteInfo>,
    iAmHost: Boolean, onClose: () -> Unit
) {
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    var nickTarget by remember { mutableStateOf<Member?>(null) }
    var kickTarget by remember { mutableStateOf<Member?>(null) }
    var voteTarget by remember { mutableStateOf<Member?>(null) }
    var hostTarget by remember { mutableStateOf<Member?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("OPERATIVES ${members.size}/${Limits.MAX_ROOM}", fontFamily = Mono) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (iAmHost) "You're the host: you can remove anyone instantly."
                    else "The host can remove operatives. If the host is away, a majority vote can remove an intruder.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim
                )
                Spacer(Modifier.height(8.dp))
                members.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val nick = if (m.isMe) null else nicks[m.id]
                            Text(
                                buildString {
                                    append(nick ?: m.name)
                                    if (m.isMe) append(" (you)")
                                    if (m.isHost) append(if (vm.hostVerified) "  \u2605 HOST · VERIFIED" else "  \u2605 HOST")
                                },
                                fontWeight = FontWeight.Bold
                            )
                            MonoLabel(opTag(m.name, m.id) + (if (nick != null) " · CALLSIGN" else "") +
                                (if (m.isMe) "" else vm.route(m.id)?.let { " · VIA $it" } ?: ""), Holo.Primary)
                            if (!m.isMe && vm.people.lookalikeOf(m.id) != null) {
                                MonoLabel("\u26A0 NEW ID · SAME CALLSIGN AS SOMEONE YOU KNOW", Holo.Danger, size = 10)
                            }
                            val v = votes[m.id]
                            if (v != null) {
                                Text("REMOVAL VOTE · ${v.yes.size} YES · ${v.no.size} NO", fontFamily = Mono,
                                    style = MaterialTheme.typography.labelSmall, color = Holo.Danger)
                            }
                        }
                        if (!m.isMe) {
                            TextButton(onClick = { nickTarget = m }) { Text("NICK", fontFamily = Mono, color = Holo.Primary) }
                            if (iAmHost) {
                                TextButton(onClick = { hostTarget = m }) { Text("HOST", fontFamily = Mono, color = Gold) }
                                TextButton(onClick = { kickTarget = m }) { Text("REMOVE", fontFamily = Mono, color = Holo.Danger) }
                            } else if (!m.isHost) {
                                TextButton(onClick = { voteTarget = m }) { Text("VOTE OUT", fontFamily = Mono, color = Holo.Danger) }
                            }
                        }
                    }
                    HorizontalDivider(color = Holo.PrimaryDeep)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Holo.Primary) } }
    )

    val nt = nickTarget
    if (nt != null) {
        NicknameDialog(alias = nt.name, current = nicks[nt.id] ?: "",
            onSave = { vm.people.setNickname(nt.id, it); nickTarget = null }, onClose = { nickTarget = null })
    }
    val kt = kickTarget
    if (kt != null) {
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Remove ${kt.name}?") },
            text = { Text("They'll be removed from the node and the access key will rotate so they can't return.") },
            confirmButton = { TextButton(onClick = { vm.hostKick(kt.id); kickTarget = null }) { Text("Remove", color = Holo.Danger) } },
            dismissButton = { TextButton(onClick = { kickTarget = null }) { Text("Cancel", color = Holo.Primary) } }
        )
    }
    val ht = hostTarget
    if (ht != null) MakeHostDialog(vm, ht.id, ht.name) { hostTarget = null }
    val vt = voteTarget
    if (vt != null) {
        AlertDialog(
            onDismissRequest = { voteTarget = null },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Vote to remove ${vt.name}?") },
            text = { Text("Everyone will be asked. They're removed when a majority agrees (at least 2 votes).") },
            confirmButton = { TextButton(onClick = { vm.castVote(vt.id, true); voteTarget = null }) { Text("Start vote", color = Holo.Danger) } },
            dismissButton = { TextButton(onClick = { voteTarget = null }) { Text("Cancel", color = Holo.Primary) } }
        )
    }
}

/** Confirm handing the host role to someone else. */
@Composable
fun MakeHostDialog(vm: NodeSession, id: String, name: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Make $name the host?") },
        text = {
            Text("They'll be able to remove operatives instantly, and you won't. Everyone checks this came from you. " +
                "Fingerprint: ${fingerprint(id)}")
        },
        confirmButton = { TextButton(onClick = { vm.handOverHost(id); onClose() }) { Text("HAND OVER", fontFamily = Mono, color = Gold) } },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", fontFamily = Mono, color = Holo.Primary) } }
    )
}

// ---------- Chat (one node) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    vm: NodeSession, onOpenMenu: () -> Unit, onOpenSettings: () -> Unit,
    alsoIn: (String) -> List<String> = { emptyList() },
    direct: DirectInfo? = null,
    isContact: (String) -> Boolean = { false },
    onAddContact: (String) -> Unit = {},
    forwardTargets: () -> List<Pair<String, NodeSession>> = { emptyList() },
    onCall: (String, Boolean) -> Unit = { _, _ -> }
) {
    var tab by remember { mutableStateOf("chat") }
    val room by vm.room.collectAsStateWithLifecycle()
    val allMessages by vm.messages.collectAsStateWithLifecycle()
    var dmPeer by remember { mutableStateOf<String?>(null) }      // open private thread, or null for the node chat
    val messages = remember(allMessages, dmPeer) { allMessages.filter { it.dm == dmPeer } }
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    val typingMap by vm.typing.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val mutedNode by vm.muted.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Msg?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showTtl by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var forwardMsg by remember { mutableStateOf<Msg?>(null) }
    var pinIdx by remember { mutableStateOf(0) }
    var flashId by remember { mutableStateOf<String?>(null) }
    val ttl by vm.ttl.collectAsStateWithLifecycle()
    val jump by vm.jumpTo.collectAsStateWithLifecycle()
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowTick = System.currentTimeMillis() } }
    DisposableEffect(dmPeer) {
        vm.openDm(dmPeer)
        onDispose { vm.openDm(null) }
    }
    val peers by vm.peers.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val hostId by vm.hostId.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nickname by vm.nickname.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()

    var input by remember(dmPeer) { mutableStateOf(vm.draft(dmPeer)) }
    var showPoll by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    val scheduledList by vm.scheduled.collectAsStateWithLifecycle()
    val gate = rememberPermissionGate()
    fun placeCall(peer: String, video: Boolean) = gate(callPermissions(video)) { onCall(peer, video) }
    var confirmLeave by remember { mutableStateOf(false) }
    var eraseOnLeave by remember { mutableStateOf(false) }
    var searchedTooLong by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var showRename by remember { mutableStateOf(false) }
    var openPhoto by remember { mutableStateOf<Msg?>(null) }
    var openVideo by remember { mutableStateOf<Msg?>(null) }
    var pendingMedia by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }   // (picked item, is it a video)
    var pendingDm by remember { mutableStateOf<String?>(null) }                    // channel it was picked in
    var replyingTo by remember { mutableStateOf<Msg?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }        // message whose long-press menu is open
    var nickFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // (person id, alias)
    val nicks by vm.people.nicknames.collectAsStateWithLifecycle()
    val aliasMap by vm.people.aliases.collectAsStateWithLifecycle()
    val succMap by vm.people.successors.collectAsStateWithLifecycle()
    val vouchSet by vm.people.vouched.collectAsStateWithLifecycle()
    // Senders using the callsign of someone you met earlier (and who aren't them on a new phone)
    val lookalikes = remember(aliasMap, succMap, vouchSet, allMessages.size) {
        allMessages.asSequence().map { it.senderId }.distinct().filter { it != vm.myId && it != "system" }
            .mapNotNull { id -> vm.people.lookalikeOf(id)?.let { id to it } }.toMap()
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    val fieldColors = holoFieldColors()

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val isVideo = (ctx.contentResolver.getType(uri) ?: "").startsWith("video")
            val q = Quality.of(vm.settings.value.quality)
            when {
                vm.settings.value.askQuality -> { pendingMedia = uri to isVideo; pendingDm = dmPeer }
                isVideo -> vm.sendVideo(uri, q, dmPeer)
                else -> vm.sendPhoto(uri, q, dmPeer)
            }
        }
    }

    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start()) { recording = true; recMs = 0L }
        else Toast.makeText(ctx, "Couldn't start the microphone", Toast.LENGTH_SHORT).show()
    }
    fun finishRecording(send: Boolean) {
        if (!recording) return
        recording = false
        if (send) {
            val res = recorder.stop()
            if (res != null) vm.sendVoice(res.first, res.second, dmPeer)
            else Toast.makeText(ctx, "Too short. Tap VOX, speak, then tap TX.", Toast.LENGTH_SHORT).show()
        } else recorder.cancel()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else Toast.makeText(ctx, "CyberTxTs needs the microphone to record VOX notes.", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(recording) {
        while (recording) {
            recMs = recorder.elapsedMs()
            if (recMs >= MAX_VOICE_MS) { finishRecording(true); break }
            delay(200)
        }
    }
    DisposableEffect(Unit) {
        onDispose { recorder.cancel(); VoicePlayer.stop() }
    }

    val members = buildMembers(vm.myId, room.name, known, connected, hostId)
    val title = if (nickname.isBlank()) room.code else nickname
    val iAmHost = hostId == vm.myId

    LaunchedEffect(messages.size, dmPeer, tab) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        if (AppState.foreground && tab == "chat") {
            vm.unread.value = 0
            vm.markRead(dmPeer)
        }
    }
    LaunchedEffect(peers) {
        if (peers > 0) searchedTooLong = false else { delay(45_000); searchedTooLong = true }
    }
    // Live link status for the banner: how many public relays this phone has reached (-1 = internet link off)
    var relays by remember { mutableStateOf(0) }
    var lanStuck by remember { mutableStateOf(0) }
    var offWifi by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            relays = vm.relayCount()
            val li = withContext(Dispatchers.Default) { runCatching { vm.linkInfo() }.getOrNull() }
            lanStuck = li?.lanStuck ?: 0
            offWifi = li?.onWifi == false
            delay(3_000)
        }
    }

    fun invite() { showInvite = true }

    if (showInvite) InviteDialog(vm) { showInvite = false }
    if (showDiag) LinkDiagnosticsDialog(vm) { showDiag = false }
    if (showTtl) TtlDialog(ttl, { vm.setTtl(it); showTtl = false }) { showTtl = false }
    if (showPoll) PollDialog({ q, o -> vm.createPoll(q, o); showPoll = false }) { showPoll = false }
    if (showSchedule) ScheduleDialog(input, { text, at ->
        vm.schedule(text, at, dmPeer, replyingTo?.id); input = ""; vm.saveDraft(dmPeer, ""); replyingTo = null; showSchedule = false
    }) { showSchedule = false }
    if (showScheduled) ScheduledDialog(scheduledList.filter { it.dm == dmPeer }, { vm.cancelScheduled(it) }) { showScheduled = false }
    if (showGallery) MediaGallery(vm, messages, onPhoto = { openPhoto = it }, onVideo = { openVideo = it }) { showGallery = false }
    val fw = forwardMsg
    if (fw != null) ForwardDialog(vm, fw, forwardTargets()) { forwardMsg = null }
    // Search results and pins jump here: switch to the right thread, scroll, flash the bubble
    LaunchedEffect(jump) {
        val id = jump ?: return@LaunchedEffect
        val target = allMessages.firstOrNull { it.id == id }
        vm.jumpTo.value = null
        if (target == null) return@LaunchedEffect
        tab = "chat"; searching = false
        dmPeer = target.dm
        delay(120)
        val idx = allMessages.filter { it.dm == target.dm }.indexOfFirst { it.id == id }
        if (idx >= 0) listState.scrollToItem(idx)
        flashId = id
        delay(2_000)
        flashId = null
    }
    val cd = confirmDelete
    if (cd != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Retract this transmission?") },
            text = { Text("It's removed for everyone in the node. Phones that are offline remove it when they next link.") },
            confirmButton = { TextButton(onClick = { vm.delete(cd.id); confirmDelete = null }) { Text("RETRACT", fontFamily = Mono, color = Holo.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL", fontFamily = Mono, color = Holo.Primary) } }
        )
    }

    if (showMembers) MembersDialog(vm, members, votes, iAmHost) { showMembers = false }
    if (showRename) {
        RenameDialog(current = nickname, code = room.code,
            onSave = { vm.rename(it); showRename = false }, onClose = { showRename = false })
    }
    val photo = openPhoto
    if (photo != null) PhotoViewer(vm, photo) { openPhoto = null }
    val pm = pendingMedia
    if (pm != null) {
        QualityDialog(
            uri = pm.first, isVideo = pm.second, others = if (pendingDm != null) 1 else peers,
            default = Quality.of(settings.quality),
            onSend = { q ->
                pendingMedia = null
                if (pm.second) vm.sendVideo(pm.first, q, pendingDm) else vm.sendPhoto(pm.first, q, pendingDm)
            },
            onClose = { pendingMedia = null }
        )
    }
    val nf = nickFor
    if (nf != null) {
        NicknameDialog(alias = nf.second, current = nicks[nf.first] ?: "",
            onSave = { vm.people.setNickname(nf.first, it); nickFor = null }, onClose = { nickFor = null })
    }
    val video = openVideo
    if (video != null) VideoViewer(vm, video) { openVideo = null }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Unlink from node?") },
            text = {
                Column {
                    Text("You'll unlink from $title. To come back you'll need the node code and access key.")
                    if (settings.keepHistory) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = eraseOnLeave, onCheckedChange = { eraseOnLeave = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Holo.Primary, uncheckedColor = Holo.Muted, checkmarkColor = Holo.Void
                                )
                            )
                            Text("Also purge this node's log from this device", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave(eraseOnLeave) }) {
                    Text("UNLINK", fontFamily = Mono, color = Holo.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("STAY", fontFamily = Mono, color = Holo.Primary) } }
        )
    }

    // Incoming removal vote I haven't answered yet
    val pending = votes.values.firstOrNull {
        it.target != vm.myId && vm.myId !in it.yes && vm.myId !in it.no && it.target !in skipped
    }
    if (pending != null && direct == null) {
        val who = known[pending.target] ?: "an operative"
        AlertDialog(
            onDismissRequest = { skipped = skipped + pending.target },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Remove $who?") },
            text = { Text("A vote has started to remove $who from node $title. Do you vote to remove them?") },
            confirmButton = { TextButton(onClick = { vm.castVote(pending.target, true) }) { Text("Yes, remove", color = Holo.Danger) } },
            dismissButton = { TextButton(onClick = { vm.castVote(pending.target, false) }) { Text("No", color = Holo.Primary) } }
        )
    }

    Box(Modifier.fillMaxSize()) {
    Starfield(Modifier.fillMaxSize())
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Holo.Panel.copy(alpha = 0.8f)).padding(start = 2.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (direct != null) {
                IconTextButton("‹", "Back to contacts", Holo.Primary, onOpenMenu)
                val dn = nicks[direct.peer] ?: known[direct.peer] ?: vm.people.alias(direct.peer, "Contact")
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = direct.onCard)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dn, maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Holo.Ink)
                        if (direct.verified) { Spacer(Modifier.width(8.dp)); MonoLabel("\u2713 VERIFIED", Holo.Primary, size = 10) }
                    }
                    MonoLabel(opTag(vm.people.alias(direct.peer, dn), direct.peer) + " · DIRECT LINK · " +
                        (if (peers > 0) "VIA " + (vm.route(direct.peer) ?: "LINK") else "OFFLINE"))
                }
                GhostButton("Call", color = Holo.Primary, description = "Voice call $dn") { placeCall(direct.peer, false) }
                Spacer(Modifier.width(6.dp))
                GhostButton("Vid", color = Holo.Primary, description = "Video call $dn") { placeCall(direct.peer, true) }
            } else {
            IconTextButton("≡", "Open nodes menu", Holo.Primary, onOpenMenu)
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1,
                    fontFamily = if (nickname.isBlank()) Display else Body,
                    fontWeight = FontWeight.Bold, fontSize = if (nickname.isBlank()) 15.sp else 18.sp,
                    letterSpacing = if (nickname.isBlank()) 0.05.em else 0.em, color = Holo.Primary)
                MonoLabel((if (nickname.isBlank()) "" else room.code + " · ") + "LINK ${peers + 1}/${Limits.MAX_ROOM} · STELLARITE SECURE")
            }
            Orbit(26.dp, Holo.Primary, spin = peers > 0)
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { invite() }, shape = cutShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Holo.Accent, contentColor = Holo.Void)
            ) { Text("INVITE", fontFamily = Mono, fontSize = 13.sp, letterSpacing = 0.08.em) }
            }
            Box {
                IconTextButton("⋮", "More options", Holo.Primary) { menuOpen = true }
                DropdownMenu(
                    expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Holo.Panel)
                ) {
                    if (direct != null) DropdownMenuItem(text = { Text("CONTACT CARD", fontFamily = Mono) },
                        onClick = { menuOpen = false; direct.onCard() })
                    if (direct == null) DropdownMenuItem(text = { Text("OPERATIVES (${peers + 1})", fontFamily = Mono) },
                        onClick = { menuOpen = false; showMembers = true })
                    DropdownMenuItem(text = { Text("NEW POLL", fontFamily = Mono) },
                        onClick = { menuOpen = false; showPoll = true })
                    DropdownMenuItem(text = { Text("SCHEDULE MESSAGE", fontFamily = Mono) },
                        onClick = { menuOpen = false; showSchedule = true })
                    DropdownMenuItem(text = { Text("SEARCH", fontFamily = Mono) },
                        onClick = { menuOpen = false; searching = true; tab = "chat" })
                    DropdownMenuItem(text = { Text("MEDIA", fontFamily = Mono) },
                        onClick = { menuOpen = false; showGallery = true })
                    DropdownMenuItem(text = { Text("DISAPPEARING: " + ttlLabel(ttl), fontFamily = Mono) },
                        onClick = { menuOpen = false; showTtl = true })
                    DropdownMenuItem(text = { Text("LINK DIAGNOSTICS", fontFamily = Mono) },
                        onClick = { menuOpen = false; showDiag = true })
                    DropdownMenuItem(text = { Text(if (mutedNode) "UNMUTE ALERTS" else "MUTE ALERTS", fontFamily = Mono) },
                        onClick = { menuOpen = false; vm.setMuted(!mutedNode) })
                    if (direct == null) {
                    DropdownMenuItem(text = { Text("RENAME NODE", fontFamily = Mono) },
                        onClick = { menuOpen = false; showRename = true })
                    DropdownMenuItem(text = { Text("SETTINGS", fontFamily = Mono) },
                        onClick = { menuOpen = false; onOpenSettings() })
                    DropdownMenuItem(text = { Text("UNLINK", fontFamily = Mono, color = Holo.Danger) },
                        onClick = { menuOpen = false; confirmLeave = true })
                    }
                }
            }
        }
        HorizontalDivider(color = Holo.Primary.copy(alpha = 0.35f))
        if (direct == null) Box(Modifier.background(Holo.Panel.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChoiceRow(listOf("chat" to "Chat", "map" to "Star map"), tab) { tab = it }
        } else {
            MonoLabel("SEALED 1-TO-1 · NO NODE NEEDED · WIFI OR NET", Holo.InkDim, size = 10,
                modifier = Modifier.fillMaxWidth().background(Holo.Theirs).padding(horizontal = 14.dp, vertical = 6.dp))
        }

        if (tab == "chat") {

        if (peers == 0) {
            val (text, color) = when {
                direct != null ->
                    "They aren't linked right now. Texts you send wait here and go out by themselves when you're both online." to Holo.Primary
                room.created ->
                    "Node online and waiting. Tap INVITE to share the node code and access key with your operatives." to Holo.Primary
                !searchedTooLong ->
                    "Scanning WiFi and the net for this node…" to Holo.Primary
                else ->
                    "No link found yet. Check the node code and access key match exactly, and that the person who " +
                        "spun it up has the app open. If someone was removed recently, the key may have rotated: " +
                        "ask an operative for the new one." to Holo.Danger
            }
            val status = when {
                offWifi && relays < 0 -> "NOT ON WIFI · INTERNET LINK OFF"
                offWifi -> "NOT ON WIFI · RELAYS ${maxOf(relays, 0)}/${NostrPool.DEFAULT_RELAYS.size} ONLINE"
                relays < 0 -> "WIFI SCAN ACTIVE · INTERNET LINK OFF"
                relays == 0 -> "WIFI SCAN ACTIVE · CONNECTING TO RELAYS…"
                else -> "WIFI SCAN ACTIVE · RELAYS $relays/${NostrPool.DEFAULT_RELAYS.size} ONLINE"
            }
            Column(
                Modifier.fillMaxWidth().padding(10.dp).openPanel(Holo.Panel, color.copy(alpha = 0.7f)).padding(10.dp)
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel(status, if (relays == 0 || offWifi) Holo.Accent else Holo.Primary, modifier = Modifier.weight(1f))
                    if (searchedTooLong || offWifi) GhostButton("Diagnose", height = 36, color = Holo.Primary) { showDiag = true }
                }
            }
        }
        if (lanStuck > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                    .openPanel(Holo.Panel, Holo.Accent.copy(alpha = 0.7f)).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("ROUTER BLOCKING $lanStuck OPERATIVE${if (lanStuck == 1) "" else "S"}", Holo.Accent, size = 12)
                    MonoLabel("FOUND ON WIFI BUT CAN'T CONNECT", Holo.InkDim, size = 10)
                }
                GhostButton("Why?", height = 36, color = Holo.Accent) { showDiag = true }
            }
        }

        val dp = dmPeer
        if (dp != null) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Theirs).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("PRIVATE CHANNEL · " + opTag(nicks[dp] ?: known[dp] ?: "Operative", dp), Holo.Accent, size = 12)
                    MonoLabel("SEALED TO THEIR KEY · OTHER OPERATIVES CAN'T READ IT", Holo.InkDim, size = 10)
                }
                IconTextButton("\u2715", "Close private channel", Holo.Muted) { dmPeer = null; replyingTo = null; editingId = null }
            }
        } else if (direct == null) {
            val threads = (allMessages.mapNotNull { it.dm } + dmUnread.keys).distinct()
            if (threads.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoLabel("PRIVATE", Holo.InkDim, size = 10)
                    threads.forEach { id ->
                        val n = dmUnread[id] ?: 0
                        GhostButton(
                            (nicks[id] ?: known[id] ?: id.take(4)) + (if (n > 0) " · $n" else ""), height = 36,
                            color = if (n > 0) Holo.Accent else Holo.Primary
                        ) { dmPeer = id }
                    }
                }
            }
        }

        val mySched = scheduledList.count { it.dm == dmPeer }
        if (mySched > 0) {
            MonoLabel("$mySched SCHEDULED · TAP TO VIEW", Holo.Primary, size = 10,
                modifier = Modifier.fillMaxWidth().clickable { showScheduled = true }.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        if (ttl > 0) {
            MonoLabel("DISAPPEARING MESSAGES · " + ttlLabel(ttl), Holo.Accent, size = 10,
                modifier = Modifier.fillMaxWidth().clickable { showTtl = true }.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        val pins = messages.filter { it.pinned && !it.deleted }
        if (pins.isNotEmpty() && !searching) {
            val pm = pins[pinIdx % pins.size]
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel).clickable {
                    vm.jumpTo.value = pm.id; pinIdx = (pinIdx + 1) % pins.size
                }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel(if (pins.size > 1) "PINNED ${pinIdx % pins.size + 1}/${pins.size}" else "PINNED", Holo.Accent, size = 10)
                Spacer(Modifier.width(10.dp))
                Text(snippet(pm), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().background(Holo.Panel).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search this chat") },
                    shape = RectangleShape, colors = fieldColors, modifier = Modifier.weight(1f))
                IconTextButton("\u2715", "Close search", Holo.Muted) { searching = false; query = "" }
            }
            val q = query.trim()
            val hits = if (q.length < 2) emptyList() else messages.filter { !it.deleted && !it.system && it.text.contains(q, ignoreCase = true) }.reversed()
            if (q.length >= 2) {
                MonoLabel(if (hits.isEmpty()) "NO MATCHES" else "${hits.size} MATCH${if (hits.size == 1) "" else "ES"}", Holo.InkDim,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Holo.Panel)) {
                    items(hits.take(200), key = { it.id }) { h ->
                        Column(Modifier.fillMaxWidth().clickable { vm.jumpTo.value = h.id }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            MonoLabel((if (h.senderId == vm.myId) "YOU" else opTag(nicks[h.senderId] ?: h.senderName, h.senderId)) +
                                " · " + timeOf(ctx, h.ts), if (h.senderId == vm.myId) Holo.Primary else Holo.Accent, size = 10)
                            Text(h.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = Holo.Muted.copy(alpha = 0.2f))
                    }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                Column {
                if (i == 0 || !sameDay(messages[i - 1].ts, m.ts)) DateChip(m.ts)
                if (m.system) {
                    MonoLabel(m.text.uppercase(), Holo.Accent, size = 12, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    val mine = m.senderId == vm.myId
                    val label = if (mine) "YOU//" + vm.myId.take(4).uppercase() else opTag(nicks[m.senderId] ?: m.senderName, m.senderId)
                    val twin = if (mine) null else lookalikes[m.senderId]
                    val kind = when {
                        m.image != null -> " · IMG"
                        m.video != null -> " · VID ${fmtDur(m.durMs)}"
                        m.voice != null -> " · VOX ${fmtDur(m.durMs)}"
                        else -> ""
                    }
                    val showMenu = { menuFor = m.id }
                    val tint = if (mine) Holo.Primary else Holo.Accent
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                    ) {
                        val rc = receipts[m.id]
                        val status = when {
                            !mine || m.deleted -> ""
                            rc == null && direct != null && vm.isDelivered(m.id) -> " · \u2713\u2713"
                            rc == null && direct != null && peers == 0 && m.text.isNotEmpty() -> " · QUEUED"
                            rc == null -> " · \u2713"
                            rc.second > 0 && settings.readMarks -> if (m.dm != null || direct != null) " · READ" else " · READ ${rc.second}"
                            else -> " · \u2713\u2713"
                        }
                        val spoken = (if (mine) "You" else "From " + (nicks[m.senderId] ?: m.senderName)) + ", " + timeOf(ctx, m.ts) +
                            (if (m.edited) ", edited" else "") + (if (m.pinned) ", pinned" else "") +
                            (if (!mine && vm.mentionsMe(m.text)) ", mentions you" else "") +
                            when { status.contains("READ") -> ", read"; status.contains("QUEUED") -> ", waiting to send"
                                status.contains("\u2713\u2713") -> ", delivered"; status.contains("\u2713") -> ", sent"; else -> "" }
                        MonoLabel(label + " · " + timeOf(ctx, m.ts) + kind + (if (m.edited) " · EDITED" + (if (m.editedAt > 0) " " + timeOf(ctx, m.editedAt) else "") else "") +
                            (if (m.pinned) " · PINNED" else "") + (if (!mine && vm.mentionsMe(m.text)) " · @YOU" else "") + status, tint,
                            modifier = Modifier.semantics { contentDescription = spoken },
                            align = if (mine) TextAlign.End else TextAlign.Start)
                        if (!mine && nicks[m.senderId] != null) {
                            MonoLabel("CALLSIGN " + m.senderName.uppercase(), Holo.InkDim, size = 10)
                        }
                        if (twin != null) {
                            MonoLabel("\u26A0 NEW OPERATIVE · NOT THE ${(nicks[twin] ?: m.senderName).uppercase()} YOU KNOW", Holo.Danger, size = 10)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box {
                        Column(
                            Modifier.widthIn(max = 300.dp)
                                .openPanel(if (mine) Holo.Mine else Holo.Theirs,
                                    if (flashId == m.id || (!mine && vm.mentionsMe(m.text))) Holo.Accent else tint.copy(alpha = 0.75f), left = mine)
                                .combinedClickable(onClick = {}, onLongClick = showMenu, onLongClickLabel = "Message options")
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            val re = m.replyTo
                            if (re != null) {
                                val orig = messages.firstOrNull { it.id == re }
                                ReplyQuote(
                                    who = orig?.let { o ->
                                        if (o.senderId == vm.myId) "RE YOU//" + vm.myId.take(4).uppercase()
                                        else "RE " + opTag(nicks[o.senderId] ?: o.senderName, o.senderId)
                                    },
                                    snippet = orig?.let { snippet(it) }
                                ) {
                                    val idx = messages.indexOfFirst { it.id == re }
                                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                                }
                            }
                            if (m.deleted) {
                                Text("[ TRANSMISSION RETRACTED ]", fontFamily = Mono, fontSize = 12.sp, color = Holo.InkDim)
                            } else if (m.image != null) {
                                PhotoBubble(vm, m, transfers[m.id], mine, onLongClick = showMenu) { openPhoto = m }
                            } else if (m.video != null) {
                                VideoBubble(vm, m, transfers[m.id], preparing[m.id], mine, onLongClick = showMenu) { openVideo = m }
                            } else if (m.voice != null) {
                                VoiceBubble(vm, m, transfers[m.id], mine)
                            } else if (m.poll != null) {
                                PollBubble(vm, m)
                            } else {
                                Text(m.text, fontSize = 17.sp, lineHeight = 22.sp)
                            }
                        }
                        DropdownMenu(
                            expanded = menuFor == m.id, onDismissRequest = { menuFor = null },
                            modifier = Modifier.background(Holo.Panel)
                        ) {
                            if (!m.deleted) {
                                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    NodeSession.REACTIONS.forEach { e ->
                                        val on = vm.myId in (m.reactions[e] ?: emptySet())
                                        Box(
                                            Modifier.size(40.dp)
                                                .then(if (on) Modifier.background(Holo.PrimaryDeep) else Modifier)
                                                .clickable { vm.react(m.id, e); menuFor = null },
                                            contentAlignment = Alignment.Center
                                        ) { Text(e, fontSize = 20.sp) }
                                    }
                                }
                                HorizontalDivider(color = Holo.Muted.copy(alpha = 0.3f))
                            }
                            DropdownMenuItem(text = { Text("REPLY", fontFamily = Mono) }, onClick = {
                                menuFor = null; replyingTo = m
                            })
                            if (!m.deleted) {
                                DropdownMenuItem(text = { Text("FORWARD", fontFamily = Mono) }, onClick = { menuFor = null; forwardMsg = m })
                                DropdownMenuItem(text = { Text(if (m.pinned) "UNPIN" else "PIN", fontFamily = Mono) },
                                    onClick = { menuFor = null; vm.setPinned(m.id, !m.pinned) })
                            }
                            if (!mine && dmPeer == null && direct == null) {
                                DropdownMenuItem(text = { Text("MESSAGE PRIVATELY", fontFamily = Mono) }, onClick = {
                                    menuFor = null; dmPeer = m.senderId; replyingTo = null
                                })
                            }
                            if (!mine && direct == null) {
                                DropdownMenuItem(text = { Text("VOICE CALL", fontFamily = Mono) }, onClick = { menuFor = null; placeCall(m.senderId, false) })
                                DropdownMenuItem(text = { Text("VIDEO CALL", fontFamily = Mono) }, onClick = { menuFor = null; placeCall(m.senderId, true) })
                            }
                            if (!mine && direct == null && !isContact(m.senderId)) {
                                DropdownMenuItem(text = { Text("ADD CONTACT", fontFamily = Mono) }, onClick = {
                                    menuFor = null; onAddContact(m.senderId)
                                })
                            }
                            if (mine && !m.deleted && m.dm == null && m.text.isNotEmpty() && m.image == null && m.video == null && m.voice == null) {
                                DropdownMenuItem(text = { Text("EDIT", fontFamily = Mono) }, onClick = {
                                    menuFor = null; editingId = m.id; input = m.text; replyingTo = null
                                })
                            }
                            if (mine && !m.deleted) {
                                DropdownMenuItem(text = { Text("RETRACT FOR EVERYONE", fontFamily = Mono, color = Holo.Danger) }, onClick = {
                                    menuFor = null; confirmDelete = m
                                })
                            }
                            if (m.text.isNotEmpty() && !m.deleted) {
                                DropdownMenuItem(text = { Text("COPY TEXT", fontFamily = Mono) }, onClick = {
                                    menuFor = null
                                    clipboard.setText(AnnotatedString(m.text))
                                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                                })
                            }
                            if (!mine) {
                                DropdownMenuItem(
                                    text = { Text(if (nicks[m.senderId] == null) "SET NICKNAME" else "CHANGE NICKNAME", fontFamily = Mono) },
                                    onClick = { menuFor = null; nickFor = m.senderId to m.senderName }
                                )
                            }
                        }
                        }
                        if (m.reactions.isNotEmpty()) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                m.reactions.forEach { (e, who) ->
                                    val on = vm.myId in who
                                    Row(
                                        Modifier.openButton(if (on) Holo.Primary else Holo.Muted.copy(alpha = 0.6f), cut = 5.dp)
                                            .heightIn(min = 36.dp)
                                            .clickable(onClickLabel = if (on) "Remove your reaction" else "React with $e") { vm.react(m.id, e) }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = "$e ${who.size}" + if (on) ", including you" else ""
                                            }
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(e, fontSize = 14.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text("${who.size}", fontFamily = Mono, fontSize = 12.sp, color = if (on) Holo.Primary else Holo.InkDim)
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        val typers = if (!settings.typingSignal) emptyList() else typingMap.filter { (k, exp) ->
            exp > nowTick && (if (dmPeer == null) !k.startsWith("dm:") else k == "dm:$dmPeer")
        }.keys.map { k -> k.removePrefix("dm:") }
        if (typers.isNotEmpty()) {
            MonoLabel(
                typers.take(3).joinToString(", ") { opTag(nicks[it] ?: known[it] ?: "Operative", it) } + " TRANSMITTING\u2026",
                Holo.Primary, size = 11, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        HorizontalDivider(color = Holo.Primary.copy(alpha = 0.25f))
        val eid = editingId
        if (eid != null) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel).padding(start = 12.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel("EDITING TRANSMISSION", Holo.Primary, modifier = Modifier.weight(1f))
                IconTextButton("\u2715", "Cancel edit", Holo.Muted) { editingId = null; input = "" }
            }
        }
        val rt = replyingTo
        if (rt != null) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel).padding(start = 12.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("RE " + (if (rt.senderId == vm.myId) "YOU//" + vm.myId.take(4).uppercase()
                        else opTag(nicks[rt.senderId] ?: rt.senderName, rt.senderId)), Holo.Accent)
                    Text(snippet(rt), style = MaterialTheme.typography.bodySmall, color = Holo.InkDim, maxLines = 1)
                }
                IconTextButton("✕", "Cancel reply", Holo.Muted) { replyingTo = null }
            }
        }
        val at = if (direct == null) Regex("@([^@\\s]*)$").find(input) else null
        if (at != null && !recording) {
            val q = at.groupValues[1]
            val names = members.filter { !it.isMe }.map { nicks[it.id] ?: it.name }.distinct()
                .filter { it.startsWith(q, ignoreCase = true) }.take(6)
            if (names.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(Holo.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEach { nm ->
                        GhostButton("@$nm", height = 36, color = Holo.Primary) {
                            // Mention by their callsign (what their phone listens for), even if you gave them a nickname
                            val call = members.firstOrNull { (nicks[it.id] ?: it.name) == nm }?.name ?: nm
                            input = input.substring(0, at.range.first) + "@" + call + " "
                        }
                    }
                }
            }
        }
        if (recording) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GhostButton("Abort", color = Holo.Muted) { finishRecording(false) }
                Box(Modifier.size(10.dp).background(Holo.Danger))
                MonoLabel("REC ${fmtDur(recMs)} / ${fmtDur(MAX_VOICE_MS)}", Holo.Ink, size = 13, modifier = Modifier.weight(1f))
                HoloButton("TX", enabled = true, height = 44) { finishRecording(true) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel.copy(alpha = 0.9f)).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GhostButton("IMG", Modifier.width(48.dp), description = if (dmPeer != null) "Send photo or video privately" else "Send photo or video") {
                    AppLock.holdOpen()
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                GhostButton("VOX", Modifier.width(48.dp), description = if (dmPeer != null) "Record private voice note" else "Record voice note") {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRecording()
                    } else { AppLock.holdOpen(); micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                }
                OutlinedTextField(
                    input, { input = it; if (editingId == null) vm.saveDraft(dmPeer, it); if (it.isNotBlank()) vm.typingNow(dmPeer) },
                    placeholder = { Text(if (dmPeer != null) "Private transmission" else "Transmit a message") },
                    shape = RectangleShape, colors = fieldColors, modifier = Modifier.weight(1f), maxLines = 4
                )
                Button(
                    onClick = {
                        val e = editingId
                        if (e != null) vm.edit(e, input) else vm.send(input, replyingTo?.id, dmPeer)
                        input = ""; replyingTo = null; editingId = null; vm.saveDraft(dmPeer, "")
                    },
                    enabled = input.isNotBlank(), shape = cutShape(8.dp),
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(width = 52.dp, height = 48.dp)
                        .semantics { contentDescription = "Transmit" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Holo.Primary, contentColor = Holo.Void,
                        disabledContainerColor = Holo.PrimaryDeep, disabledContentColor = Holo.InkDim
                    )
                ) { Text("TX", fontFamily = Mono, fontSize = 15.sp, letterSpacing = 0.1.em) }
            }
        }
        } else {
            StarMapView(vm, alsoIn, isContact = isContact, onAddContact = onAddContact, onCall = { p, v -> placeCall(p, v) }) { id -> tab = "chat"; dmPeer = id }
        }
    }
    }
}

// ---------- Photos ----------

@Composable
fun TransferBar(p: Float?, mine: Boolean, vm: NodeSession? = null, m: Msg? = null) {
    if (p == null) return
    Spacer(Modifier.height(4.dp))
    if (p <= NodeSession.QUEUED) {
        Text("QUEUED · SENDS WHEN THEY'RE ONLINE", style = MaterialTheme.typography.labelSmall, color = Holo.Primary)
        if (vm != null && m != null && mine) {
            Spacer(Modifier.height(4.dp))
            GhostButton("Cancel", height = 36) { vm.cancelTransfer(m.id) }
        }
        return
    }
    if (p < 0f) {
        Text(if (mine) "TX FAILED / CANCELLED" else "RX INCOMPLETE",
            style = MaterialTheme.typography.labelSmall, color = Holo.Danger)
        if (vm != null && m != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mine) GhostButton("Retry", height = 36, color = Holo.Primary) { vm.retry(m) }
                GhostButton("Dismiss", height = 36) { vm.dismiss(m.id) }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                LinearProgressIndicator(
                    progress = { p }, color = Holo.Primary, trackColor = Holo.Primary.copy(alpha = 0.2f), strokeCap = StrokeCap.Butt,
                    modifier = Modifier.width(140.dp).height(4.dp)
                )
                MonoLabel((if (mine) "TX " else "RX ") + "${(p * 100).toInt()}%")
            }
            if (mine && vm != null && m != null) {
                Spacer(Modifier.width(8.dp))
                GhostButton("Cancel", height = 32, color = Holo.Muted) { vm.cancelTransfer(m.id) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoBubble(vm: NodeSession, m: Msg, progress: Float?, mine: Boolean, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    // Incoming photos only exist once every piece has arrived, so reload when the transfer finishes
    val arriving = !mine && progress != null
    val state by produceState<Pair<Boolean, ImageBitmap?>>(false to null, m.image, arriving) {
        value = if (arriving) false to null else true to withContext(Dispatchers.IO) {
            vm.readImage(m)?.let { ImageUtil.decodeSampled(it, 700)?.asImageBitmap() }
        }
    }
    val (done, bmp) = state
    Column {
        if (bmp != null) {
            Image(
                bitmap = bmp, contentDescription = "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Open photo")
            )
        } else {
            Text(
                when {
                    arriving -> "[ IMG INCOMING ]"
                    done -> "[ IMG NO LONGER STORED ]"
                    else -> "[ LOADING IMG ]"
                },
                style = MaterialTheme.typography.bodySmall, color = Holo.InkDim, fontStyle = FontStyle.Italic
            )
        }
        TransferBar(progress, mine, vm, m)
    }
}

@Composable
fun VoiceBubble(vm: NodeSession, m: Msg, progress: Float?, mine: Boolean) {
    val playing by VoicePlayer.playing.collectAsStateWithLifecycle()
    val pos by VoicePlayer.position.collectAsStateWithLifecycle()
    val ready = mine || progress == null
    val isMe = playing == m.voice
    val total = m.durMs.coerceAtLeast(1L)
    Column(Modifier.width(220.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton(if (isMe) "STOP" else "PLAY", Modifier.width(56.dp), color = if (ready) Holo.Primary else Holo.Muted,
                description = if (isMe) "Stop voice note" else "Play voice note") {
                val bytes = if (ready) vm.readVoice(m) else null
                if (bytes != null) VoicePlayer.toggle(m.voice!!, bytes)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { if (isMe) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f },
                    color = Holo.Primary, trackColor = Holo.Primary.copy(alpha = 0.2f), strokeCap = StrokeCap.Butt,
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(Modifier.height(4.dp))
                MonoLabel("VOX " + (if (isMe) "${fmtDur(pos)} / " else "") + fmtDur(m.durMs))
            }
        }
        TransferBar(progress, mine, vm, m)
        if (ready && vm.readVoice(m) == null) {
            Text("VOX expired: not kept after unlinking or restarting",
                style = MaterialTheme.typography.labelSmall, color = Holo.InkDim, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
fun PhotoViewer(vm: NodeSession, m: Msg, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp by produceState<ImageBitmap?>(null, m.image) {
        value = withContext(Dispatchers.IO) {
            vm.readImage(m)?.let { ImageUtil.decodeSampled(it, 2048)?.asImageBitmap() }
        }
    }
    FullScreenDialog(onDismiss = onClose, darkBars = true) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).safeDrawingPadding()) {
            val b = bmp
            if (b != null) {
                Image(bitmap = b, contentDescription = "Photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp))
            } else {
                CircularProgressIndicator(color = Holo.Primary, modifier = Modifier.align(Alignment.Center))
            }
            Text(m.senderName, color = Holo.InkDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (Build.VERSION.SDK_INT >= 29 && bmp != null) {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = savePhoto(ctx, vm.readImage(m))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, if (saved) "Saved to Pictures/CyberTxTs" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Save", color = Holo.Primary) }
                }
                TextButton(onClick = onClose) { Text("Close", color = Holo.Primary) }
            }
        }
    }
}

private fun savePhoto(ctx: android.content.Context, bytes: ByteArray?): Boolean {
    if (bytes == null || Build.VERSION.SDK_INT < 29) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CyberTxTs_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CyberTxTs")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
    } catch (e: Exception) { false }
}

// ---------- Rename ----------

@Composable
fun RenameDialog(current: String, code: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Rename node") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(30) }, singleLine = true,
                    label = { Text("Your name for this node") }, colors = holoFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Only you see this name. Others still see the code ($code). Leave it blank to use the code.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = Holo.Primary) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Holo.Muted) } }
    )
}


// ---------- Videos ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoBubble(
    vm: NodeSession, m: Msg, progress: Float?, preparing: Float?, mine: Boolean,
    onLongClick: () -> Unit = {}, onClick: () -> Unit
) {
    val arriving = !mine && progress != null
    val thumb by produceState<ImageBitmap?>(null, m.video, arriving) {
        value = withContext(Dispatchers.IO) {
            vm.readThumb(m)?.let { ImageUtil.decodeSampled(it, 480)?.asImageBitmap() }
        }
    }
    val playable by produceState(false, m.video, progress, preparing) {
        value = preparing == null && (mine || progress == null) && withContext(Dispatchers.IO) { vm.hasVideo(m) }
    }
    Column {
        Box(
            Modifier.widthIn(max = 240.dp)
                .combinedClickable(onClick = { if (playable) onClick() }, onLongClick = onLongClick, onClickLabel = "Play video"),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(bitmap = t, contentDescription = "Video", contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp))
            } else {
                Box(Modifier.size(200.dp, 120.dp).background(Holo.Void))
            }
            Surface(shape = cutShape(6.dp), color = Holo.Void.copy(alpha = 0.8f), border = BorderStroke(1.dp, Holo.Primary)) {
                Text(if (playable) "PLAY" else "WAIT", fontFamily = Mono, color = Holo.Primary, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        MonoLabel("VID " + fmtDur(m.durMs))
        if (preparing != null && mine) {
            GhostButton("Cancel", height = 32, color = Holo.Muted) { vm.cancelTransfer(m.id) }
        }
        if (preparing != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { preparing }, color = Holo.Muted, trackColor = Holo.Void,
                modifier = Modifier.width(160.dp)
            )
            Text("PREP ${(preparing * 100).toInt()}% · COMPRESSING",
                style = MaterialTheme.typography.labelSmall, color = Holo.InkDim)
        }
        TransferBar(progress, mine, vm, m)
        if (m.og > 0 && !m.deleted) {
            val tr by vm.transfers.collectAsStateWithLifecycle()
            val op = tr["${m.video}-og"]
            Spacer(Modifier.height(4.dp))
            when {
                mine -> MonoLabel("ORIGINAL KEPT · ${fmtSize(m.og)} · SENT ON REQUEST", Holo.InkDim, size = 10)
                m.ogHave -> MonoLabel("ORIGINAL QUALITY \u2713", Holo.Primary, size = 10)
                op != null && op >= 0f -> {
                    MonoLabel(if (op == 0f) "ORIGINAL REQUESTED…" else "ORIGINAL ${(op * 100).toInt()}% · ${fmtSize(m.og)}", Holo.Primary, size = 10)
                    LinearProgressIndicator(progress = { op }, color = Holo.Primary, trackColor = Holo.Void, modifier = Modifier.width(160.dp))
                }
                else -> GhostButton((if (op != null) "Retry original" else "Download original") + " · ${fmtSize(m.og)}", height = 36,
                    color = Holo.Primary) { vm.requestOriginal(m) }
            }
        }
        if (!playable && preparing == null && progress == null) {
            Text("Video no longer available", style = MaterialTheme.typography.labelSmall,
                color = Holo.InkDim, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
fun VideoViewer(vm: NodeSession, m: Msg, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // The stored copy is encrypted; a temporary private copy is made for playing and deleted on close
    val tmp = remember { File(ctx.cacheDir, "play_${m.id}.mp4") }
    val ready by produceState<Boolean?>(null, m.video) {
        value = withContext(Dispatchers.IO) { vm.exportVideo(m, tmp) }
    }
    DisposableEffect(Unit) { onDispose { tmp.delete() } }

    FullScreenDialog(onDismiss = onClose, darkBars = true) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            when (ready) {
                null -> CircularProgressIndicator(color = Holo.Primary, modifier = Modifier.align(Alignment.Center))
                false -> Text("Video no longer available", color = Holo.Ink,
                    modifier = Modifier.align(Alignment.Center))
                true -> AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            val controls = MediaController(c)
                            controls.setAnchorView(this)
                            setMediaController(controls)
                            setVideoPath(tmp.absolutePath)
                            setOnPreparedListener { start() }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                )
            }
            Text(m.senderName, color = Holo.InkDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (Build.VERSION.SDK_INT >= 29 && ready == true) {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = saveVideo(ctx, vm, m)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, if (saved) "Saved to Movies/CyberTxTs" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Save", color = Holo.Primary) }
                }
                TextButton(onClick = onClose) { Text("Close", color = Holo.Primary) }
            }
        }
    }
}

private fun saveVideo(ctx: android.content.Context, vm: NodeSession, m: Msg): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CyberTxTs_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CyberTxTs")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = ctx.contentResolver.openOutputStream(uri)?.use { vm.copyVideo(m, it) } ?: false
        if (!ok) ctx.contentResolver.delete(uri, null, null)
        ok
    } catch (e: Exception) { false }
}


// ---------- Quality chooser ----------

private fun fileSize(ctx: android.content.Context, uri: Uri): Long? = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }
} catch (e: Exception) { null }

@Composable
fun QualityDialog(
    uri: Uri, isVideo: Boolean, others: Int, default: Quality,
    onSend: (Quality) -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf(default) }
    // Videos: length in ms. Photos: size of the original file in bytes.
    val info by produceState<Long?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            if (isVideo) VideoUtil.durationMs(ctx, uri) else fileSize(ctx, uri)
        }
    }
    val options = Quality.presets + Quality.ORIGINAL
    fun sizeOf(q: Quality): Long? = when {
        isVideo && q == Quality.ORIGINAL -> info?.let { videoEstimateBytes(Quality.STANDARD, it) }
        isVideo -> info?.let { videoEstimateBytes(q, it) }
        q == Quality.ORIGINAL -> info
        else -> q.photoEstimate
    }
    val tooLong = isVideo && (info ?: 0L) > MAX_VIDEO_MS + 999
    val tooBig = !isVideo && choice == Quality.ORIGINAL && (info ?: 0L) > ImageUtil.MAX_ORIGINAL

    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text(if (isVideo) "Send video" else "Send photo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isVideo && info != null) {
                    Text("Length ${fmtDur(info!!)}", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                    Spacer(Modifier.height(4.dp))
                }
                options.forEach { q ->
                    val size = sizeOf(q)
                    Row(
                        Modifier.fillMaxWidth().clickable { choice = q }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = choice == q, onClick = { choice = q },
                            colors = RadioButtonDefaults.colors(selectedColor = Holo.Primary, unselectedColor = Holo.Muted)
                        )
                        Column {
                            Text(q.label + (size?.let { " · ≈ " + fmtSize(it) } ?: ""), fontWeight = FontWeight.Bold)
                            Text(if (isVideo && q == Quality.ORIGINAL) "Everyone gets Standard now; anyone can tap to download the untouched original from you"
                                 else if (isVideo) q.videoDesc else q.photoDesc,
                                style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val per = sizeOf(choice)
                if (per != null && others > 0) {
                    Text("Each of the $others linked operatives gets their own copy: about ${fmtSize(per * others)} sent in total.",
                        style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                }
                if (choice == Quality.ORIGINAL && !isVideo) {
                    Spacer(Modifier.height(4.dp))
                    Text("Location and camera serial numbers are removed. HEIC, PNG and other formats are sent as a full-size JPEG so every phone can open them.",
                        style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                }
                if (isVideo && choice == Quality.ORIGINAL) {
                    Spacer(Modifier.height(4.dp))
                    Text("The original (up to 400 MB) stays on your phone, encrypted. You need to be online when someone downloads it. Not available in private channels.",
                        style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                }
                if (tooLong) {
                    Spacer(Modifier.height(4.dp))
                    Text("Videos can be up to 5 minutes long.", color = Holo.Danger, style = MaterialTheme.typography.bodySmall)
                }
                if (tooBig) {
                    Spacer(Modifier.height(4.dp))
                    Text("This photo is over 25 MB. Choose High instead.", color = Holo.Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(choice) }, enabled = !tooLong && !tooBig) {
                Text("Send", color = if (tooLong || tooBig) Holo.InkDim else Holo.Primary)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Holo.Muted) } }
    )
}


// ---------- Timestamps, replies, nicknames ----------

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Time in the phone's own 12/24-hour style. */
private fun timeOf(ctx: android.content.Context, ts: Long): String =
    android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ts))

@Composable
fun DateChip(ts: Long) {
    val now = System.currentTimeMillis()
    val label = when {
        sameDay(ts, now) -> " · TODAY"
        sameDay(ts, now - 86_400_000L) -> " · YESTERDAY"
        else -> ""
    }
    val cycle = android.text.format.DateFormat.format("yyyy.MM.dd", Date(ts)).toString()
    MonoLabel("—— CYCLE $cycle$label ——", align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

/** Short description of a message, for reply quotes. */
fun snippet(m: Msg): String = when {
    m.image != null -> "IMG · Photo"
    m.video != null -> "VID · Video"
    m.voice != null -> "VOX · Voice note"
    else -> m.text.replace('\n', ' ').take(100)
}

@Composable
fun ReplyQuote(who: String?, snippet: String?, onClick: () -> Unit) {
    Row(
        Modifier.padding(bottom = 6.dp).background(Holo.Void.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            if (who == null) {
                Text("Original message not available", style = MaterialTheme.typography.labelSmall,
                    color = Holo.InkDim, fontStyle = FontStyle.Italic)
            } else {
                MonoLabel(who, Holo.Accent)
                Text(snippet ?: "", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim, maxLines = 2)
            }
        }
    }
}

@Composable
fun NicknameDialog(alias: String, current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Nickname for $alias") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(24) }, singleLine = true,
                    label = { Text("Your name for them") }, colors = holoFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Only you see this, in every node you share with them. Their callsign ($alias) stays the same. Leave blank to remove.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = Holo.Primary) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Holo.Muted) } }
    )
}


// ---------- QR invite ----------

@Composable
fun InviteDialog(vm: NodeSession, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hostId by vm.hostId.collectAsStateWithLifecycle()
    val room by vm.room.collectAsStateWithLifecycle()
    val inv = remember(room.password, hostId) { vm.invite() }
    val link = remember(inv) { InviteLink.build(inv) }
    val qr by produceState<ImageBitmap?>(null, link) {
        value = withContext(Dispatchers.Default) { runCatching { InviteLink.qr(link).asImageBitmap() }.getOrNull() }
    }
    val hostTag = hostId?.let { h ->
        val name = if (h == vm.myId) room.name else (vm.known.value[h] ?: "Host")
        opTag(name, h)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).openPanel(Holo.Panel, Holo.Primary.copy(alpha = 0.8f), cut = 18.dp)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("INVITE TO NODE", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Holo.Primary)
            Box(Modifier.size(240.dp).background(Holo.Void), contentAlignment = Alignment.Center) {
                val b = qr
                if (b != null) Image(bitmap = b, contentDescription = "Invite QR code", modifier = Modifier.size(232.dp))
                else CircularProgressIndicator(color = Holo.Primary)
            }
            MonoLabel("SCAN WITH CYBERTXTS · LINK TO NODE · SCAN QR INVITE", align = TextAlign.Center)
            Column(Modifier.fillMaxWidth().openPanel(Holo.Void, Holo.Muted.copy(alpha = 0.4f)).padding(10.dp)) {
                MonoLabel("NODE CODE", size = 10)
                Text(inv.code, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Holo.Primary)
                Spacer(Modifier.height(6.dp))
                MonoLabel("ACCESS KEY", size = 10)
                Text(inv.key, fontFamily = Mono, fontSize = 15.sp, color = Holo.Ink)
                Spacer(Modifier.height(6.dp))
                MonoLabel("HOST FINGERPRINT", size = 10)
                Text(
                    if (hostTag != null) "$hostTag · " + (if (vm.hostVerified) "VERIFIED" else "UNVERIFIED") else "UNKNOWN (no host seen yet)",
                    fontFamily = Mono, fontSize = 13.sp, color = if (vm.hostVerified) Holo.Primary else Holo.InkDim
                )
            }
            Text("Anyone with this code and key can join. Share it only with people you trust.",
                style = MaterialTheme.typography.bodySmall, color = Holo.InkDim, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Close", Modifier.weight(1f)) { onClose() }
                HoloButton("Share link", enabled = true, modifier = Modifier.weight(1f), height = 44) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, InviteLink.shareText(inv, hostTag))
                    }
                    ctx.startActivity(Intent.createChooser(send, "Invite to node"))
                }
            }
        }
    }
}
