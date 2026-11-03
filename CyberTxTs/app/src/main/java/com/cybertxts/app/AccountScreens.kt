package com.cybertxts.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// =====================================================================================================
// Shared bits
// =====================================================================================================

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** After erasing: close everything. Opening CyberTxTs again starts fresh with a new identity. */
fun closeApp(ctx: Context) {
    ctx.findActivity()?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}

fun biometricAvailable(ctx: Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun promptBiometric(ctx: Context, onOk: () -> Unit) {
    val act = ctx.findActivity() as? FragmentActivity ?: return
    if (!biometricAvailable(ctx)) return
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle("Unlock CyberTxTs").setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build()
    )
}

/** A full-screen console page with a back arrow. */
@Composable
fun ConsolePage(
    title: String, crumb: String? = null, onBack: () -> Unit, secure: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    FullScreenDialog(onDismiss = onBack, secure = secure) {
        Column(Modifier.fillMaxSize().background(Holo.Void).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().background(Holo.Panel).padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTextButton("‹", "Back", Holo.Primary, onBack)
                Column {
                    if (crumb != null) MonoLabel(crumb, Holo.InkDim, size = 10)
                    Text(title, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        letterSpacing = 0.08.em, color = Holo.Primary, modifier = Modifier.semantics { heading() })
                }
            }
            HorizontalDivider(color = Holo.Primary.copy(alpha = 0.35f))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = {
                    content()
                    Spacer(Modifier.navigationBarsPadding().height(80.dp))
                }
            )
        }
    }
}

@Composable
private fun Para(text: String, color: Color = Holo.InkDim) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun Notice(title: String, body: String, color: Color = Holo.Accent) {
    Column(Modifier.fillMaxWidth().openPanel(Holo.Panel, color.copy(alpha = 0.7f)).padding(12.dp)) {
        MonoLabel(title, color, size = 12)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Holo.Ink)
    }
}

@Composable
private fun CheckLine(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Holo.Primary, uncheckedColor = Holo.Muted, checkmarkColor = Holo.Void))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Holo.Ink)
    }
}

/** Settings home row: title, one-line status, chevron. */
@Composable
private fun NavRow(title: String, status: String, statusColor: Color = Holo.InkDim, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.4f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Mono, fontSize = 14.sp, letterSpacing = 0.08.em, color = Holo.Primary)
            Spacer(Modifier.height(2.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 2)
        }
        Text("›", color = Holo.Primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

private fun dateText(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 86_400_000L
    return when { d <= 0 -> "today"; d == 1L -> "yesterday"; else -> "$d days ago" }
}

// =====================================================================================================
// Settings
// =====================================================================================================

@Composable
fun SettingsScreen(vm: ChatViewModel, onClose: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val alias by vm.profileName.collectAsStateWithLifecycle()
    val rec by vm.recoveryPub.collectAsStateWithLifecycle()
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    val lockOn by AppLock.enabled.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf<String?>(null) }
    val themeName = when (s.theme) { "light" -> "Day shift"; "system" -> "Match device"; else -> "Night ops" }

    ConsolePage("SETTINGS", onBack = onClose) {
        // Operative card
        Column(Modifier.fillMaxWidth().openPanel(Holo.Panel, Holo.Primary.copy(alpha = 0.6f), cut = 16.dp).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).openButton(Holo.Primary, cut = 8.dp), contentAlignment = Alignment.Center) {
                    Text((alias ?: "?").take(1).uppercase(), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Holo.Primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(alias ?: "", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    MonoLabel(opTag(alias ?: "", vm.myId), Holo.Primary, size = 12)
                }
            }
            Spacer(Modifier.height(10.dp))
            MonoLabel("FINGERPRINT", Holo.InkDim, size = 10)
            Text(fingerprint(vm.myId), fontFamily = Mono, fontSize = 13.sp, color = Holo.Ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(if (lockOn) "LOCK ON" else "LOCK OFF", lockOn)
                StatusChip(if (rec != null) "RECOVERY SET" else "NO RECOVERY", rec != null)
                StatusChip(if (last > 0) "BACKUP ${ago(last).uppercase()}" else "NO BACKUP", last > 0)
            }
        }
        NavRow("SECURITY",
            listOf(if (lockOn) "App lock on" else "App lock off", if (rec != null) "recovery phrase set" else "no recovery phrase").joinToString(" · "),
            if (lockOn && rec != null) Holo.InkDim else Holo.Accent) { page = "security" }
        NavRow("BACKUP & DEVICES",
            if (last > 0) "Last backup ${ago(last)} · move to a new phone" else "No backup yet · move to a new phone",
            if (last > 0) Holo.InkDim else Holo.Accent) { page = "backup" }
        NavRow("DISPLAY", themeName) { page = "display" }
        NavRow("CHATS & MEDIA",
            (if (!s.keepHistory) "Log not saved" else if (s.retentionDays == 0) "Log kept" else "Log purged after ${s.retentionDays} days") +
                " · " + (Quality.presets.firstOrNull { it.key == s.quality }?.label ?: "Standard") + " quality") { page = "chats" }
        NavRow("NETWORK",
            listOf(if (s.internet) "WiFi + internet" else "WiFi only", if (s.relay) "relaying" else "not relaying",
                if (s.keepAlive) "background link" else "foreground only").joinToString(" · ")) { page = "network" }
        val usageTick by vm.usageTick.collectAsStateWithLifecycle()
        val usage by produceState<UsageTracker.Report?>(null, usageTick) {
            value = withContext(Dispatchers.Default) { UsageTracker.report(vm.usagePrefs()) }
        }
        NavRow("BATTERY & DATA", usage?.let { usageSummary(it) } ?: "Reading…") { page = "usage" }
        val snd by Sounds.conf.collectAsStateWithLifecycle()
        val sctx = LocalContext.current
        NavRow("SOUNDS & ALERTS", Sounds.label(sctx, snd.msgTone, false) + " · " + Sounds.label(sctx, snd.callTone, true) +
            if (AppLock.hideAlerts.collectAsStateWithLifecycle().value) " · text hidden" else "") { page = "alerts" }
        NavRow("DANGER ZONE", "Purge logs · erase this phone", Holo.Danger) { page = "danger" }
        val appCtx = LocalContext.current
        val version = remember { runCatching { appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName }.getOrNull() ?: "" }
        MonoLabel("CYBERTXTS $version · BY STELLARITE".replace("  ", " "), Holo.InkDim, size = 10, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }

    when (page) {
        "security" -> SecurityPage(vm) { page = null }
        "backup" -> BackupPage(vm) { page = null }
        "display" -> ConsolePage("DISPLAY", "SETTINGS", { page = null }) {
            SettingsSection("THEME") {
                ChoiceRow(listOf("dark" to "Night ops", "light" to "Day shift", "system" to "Match device"), s.theme) {
                    vm.updateSettings(s.copy(theme = it))
                }
            }
        }
        "chats" -> ChatsPage(s, { vm.updateSettings(it) }) { page = null }
        "network" -> NetworkPage(s, { vm.updateSettings(it) }) { page = null }
        "alerts" -> AlertsPage { page = null }
        "usage" -> UsagePage(vm) { page = null }
        "danger" -> DangerPage(vm) { page = null }
    }
}

@Composable
private fun StatusChip(text: String, good: Boolean) {
    val c = if (good) Holo.Primary else Holo.Accent
    Box(Modifier.openButton(c.copy(alpha = 0.8f), cut = 5.dp).padding(horizontal = 7.dp, vertical = 4.dp)) {
        Text(text, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.06.em, color = c, maxLines = 1)
    }
}

@Composable
private fun ChatsPage(s: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    ConsolePage("CHATS & MEDIA", "SETTINGS", onBack) {
        SettingsSection("MESSAGE LOG") {
            SettingRow("Save message log", "Encrypted, on this device only.", s.keepHistory) { onChange(s.copy(keepHistory = it)) }
            if (s.keepHistory) {
                Text("Auto-purge after", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                ChoiceRow(listOf("0" to "Never", "7" to "7D", "30" to "30D", "90" to "90D"), s.retentionDays.toString()) {
                    onChange(s.copy(retentionDays = it.toInt()))
                }
            }
        }
        SettingsSection("STORED MEDIA") {
            SettingRow("Keep IMG", "Off: photos are wiped when you unlink or restart.", s.keepImages) { onChange(s.copy(keepImages = it)) }
            SettingRow("Keep VID", "Off: videos are wiped when you unlink or restart.", s.keepVideos) { onChange(s.copy(keepVideos = it)) }
            SettingRow("Keep AUD", "Off: audio files others send are wiped when you unlink or restart. Your own are always kept.", s.keepAudio) { onChange(s.copy(keepAudio = it)) }
            Text("VOX notes are never stored. They vanish when you unlink.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        }
        SettingsSection("PRIVACY") {
            SettingRow("Send read marks", "Off: others don't see READ from you, and you don't see theirs.", s.readMarks) { onChange(s.copy(readMarks = it)) }
            SettingRow("Send typing signal", "Off: others don't see TRANSMITTING… from you, and you don't see theirs.", s.typingSignal) {
                onChange(s.copy(typingSignal = it))
            }
        }
        SettingsSection("TRANSMISSION QUALITY") {
            ChoiceRow(Quality.presets.map { it.key to it.label }, s.quality) { onChange(s.copy(quality = it)) }
            SettingRow("Ask before each IMG / VID", "Pick quality (or Original for photos) every time.", s.askQuality) {
                onChange(s.copy(askQuality = it))
            }
        }
    }
}

@Composable
private fun NetworkPage(s: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    ConsolePage("NETWORK", "SETTINGS", onBack) {
        SettingsSection("LINKS") {
            SettingRow("Internet link",
                "Reach operatives anywhere, not just on your WiFi. Content stays end-to-end encrypted; linked operatives can see your IP address.",
                s.internet) { onChange(s.copy(internet = it)) }
            SettingRow("Relay for other operatives",
                "If two operatives can't link directly, your phone forwards their encrypted traffic. Uses some data and battery.",
                s.relay) { onChange(s.copy(relay = it)) }
        }
        SettingsSection("BACKGROUND") {
            SettingRow("Hold link in background", "Stays connected with the screen off. Uses more battery.", s.keepAlive) {
                onChange(s.copy(keepAlive = it))
            }
            if (s.keepAlive) {
                GhostButton("Battery: set CyberTxTs to unrestricted", Modifier.fillMaxWidth()) {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
        }
    }
}

@Composable
private fun AlertsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val hide by AppLock.hideAlerts.collectAsStateWithLifecycle()
    val c by Sounds.conf.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<Boolean?>(null) }     // true = ringtone, false = message tone
    ConsolePage("SOUNDS & ALERTS", "SETTINGS", onBack) {
        SettingsSection("MESSAGES") {
            GhostButton("Message tone · " + Sounds.label(ctx, c.msgTone, false), Modifier.fillMaxWidth(), color = Holo.Primary) { choosing = false }
            Text("Each node or contact can have its own: ⋮ > ALERT TONE in that chat.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            SettingRow("Sounds in an open chat", "A quiet sound when you send, or a message arrives, in the chat on screen. Not in silent mode.", c.chatSounds) {
                Sounds.update(c.copy(chatSounds = it))
            }
            GhostButton("Android notification settings", Modifier.fillMaxWidth()) {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
            }
        }
        SettingsSection("CALLS") {
            GhostButton("Ringtone · " + Sounds.label(ctx, c.callTone, true), Modifier.fillMaxWidth(), color = Holo.Primary) { choosing = true }
            SettingRow("Vibrate when ringing", "Also follows your phone's silent and vibrate modes.", c.callVibrate) {
                Sounds.update(c.copy(callVibrate = it))
            }
            SettingRow("Call tones", "Ringing while you wait for an answer, and short tones when a call connects, ends, or you mute.", c.callTones) {
                Sounds.update(c.copy(callTones = it))
            }
            Text("Do Not Disturb and silent mode keep incoming calls quiet; the call screen still appears.",
                style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        }
        SettingsSection("PRIVACY") {
            SettingRow("Hide message text in alerts",
                "Notifications say \"New transmission\" without the sender, node or text.", hide) { AppLock.setHideAlerts(it) }
            Text("Message text is always hidden on the lock screen.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        }
    }
    when (choosing) {
        true -> ToneDialog(ring = true, current = c.callTone, onPick = { v -> Sounds.update(Sounds.conf.value.copy(callTone = v ?: "comm")) }) { choosing = null }
        false -> ToneDialog(ring = false, current = c.msgTone, onPick = { v -> Sounds.update(Sounds.conf.value.copy(msgTone = v ?: "chirp")) }) { choosing = null }
        null -> {}
    }
}

@Composable
private fun DangerPage(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    ConsolePage("DANGER ZONE", "SETTINGS", onBack) {
        SettingsSection("LOGS") {
            Para("Deletes every saved message log, photo and video on this phone. Nodes stay linked.")
            GhostButton("Purge all saved logs", Modifier.fillMaxWidth(), color = Holo.Danger) { confirmPurge = true }
        }
        SettingsSection("THIS PHONE") {
            Para("Removes everything: nodes, logs, contacts, settings and this phone's identity. Next time you open CyberTxTs it starts fresh with a new OP-ID. Export a backup first if you want to come back as yourself.")
            GhostButton("Erase this phone", Modifier.fillMaxWidth(), color = Holo.Danger) { confirmErase = true }
        }
    }
    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Purge everything?") },
            text = { Text("All saved message logs on this device will be deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmPurge = false; vm.eraseAllHistory() }) { Text("PURGE", fontFamily = Mono, color = Holo.Danger) } },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("CANCEL", fontFamily = Mono, color = Holo.Primary) } }
        )
    }
    if (confirmErase) EraseDialog(vm, { confirmErase = false }) { closeApp(ctx) }
}

/** Type ERASE to confirm. */
@Composable
fun EraseDialog(vm: ChatViewModel, onCancel: () -> Unit, onErased: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Danger, textContentColor = Holo.Ink,
        title = { Text("Erase this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Everything on this phone is deleted, including its identity. Operatives will see a new OP-ID unless you restore a backup or move first. Type ERASE to confirm.")
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("ERASE") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim().equals("ERASE", ignoreCase = true) && !busy, onClick = {
                busy = true; vm.wipeEverything(onErased)
            }) { Text(if (busy) "ERASING…" else "ERASE", fontFamily = Mono, color = Holo.Danger) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("CANCEL", fontFamily = Mono, color = Holo.Primary) } }
    )
}

// =====================================================================================================
// Security: app lock + recovery phrase
// =====================================================================================================

@Composable
private fun SecurityPage(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lockOn by AppLock.enabled.collectAsStateWithLifecycle()
    val bio by AppLock.biometric.collectAsStateWithLifecycle()
    val after by AppLock.afterSec.collectAsStateWithLifecycle()
    val privacy by AppLock.privacyScreen.collectAsStateWithLifecycle()
    val rec by vm.recoveryPub.collectAsStateWithLifecycle()
    var setPin by remember { mutableStateOf(false) }
    var checkThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var setupPhrase by remember { mutableStateOf(false) }
    var testPhrase by remember { mutableStateOf(false) }
    val canBio = remember { biometricAvailable(ctx) }

    ConsolePage("SECURITY", "SETTINGS", onBack) {
        SettingsSection("APP LOCK") {
            if (!lockOn) {
                Para("Ask for a PIN before CyberTxTs opens, so someone holding your unlocked phone can't read your nodes.")
                HoloButton("Set a PIN", enabled = true, modifier = Modifier.fillMaxWidth()) { setPin = true }
            } else {
                if (canBio) SettingRow("Fingerprint or face unlock", "Use the phone's biometrics; the PIN still works.", bio) { AppLock.setBiometric(it) }
                Text("Lock after leaving the app for", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                ChoiceRow(listOf("0" to "Instantly", "60" to "1 min", "300" to "5 min", "900" to "15 min"), after.toString()) {
                    AppLock.setAfter(it.toInt())
                }
                SettingRow("Privacy screen", "Hide CyberTxTs in the app switcher and block screenshots.", privacy) { AppLock.setPrivacyScreen(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Change PIN", Modifier.weight(1f), color = Holo.Primary) { checkThen = { setPin = true } }
                    GhostButton("Turn off", Modifier.weight(1f), color = Holo.Danger) { checkThen = { AppLock.disable() } }
                }
            }
        }
        SettingsSection("RECOVERY PHRASE") {
            if (rec == null) {
                Para("16 words you write on paper. If this phone is lost or stolen, they prove a new phone is really you: " +
                    "operatives keep your nickname, private channels and host role, and the lost phone is locked out of every node.")
                Para("Set it up now. It only works for operatives who see it before something goes wrong.", Holo.Accent)
                HoloButton("Set up recovery phrase", enabled = true, modifier = Modifier.fillMaxWidth()) { setupPhrase = true }
            } else {
                Notice("RECOVERY PHRASE SET", "Key ${rec!!.take(8).uppercase()}… is shared with every operative you link with. " +
                    "The words themselves were never stored on this phone.", Holo.Primary)
                GhostButton("Test my phrase", Modifier.fillMaxWidth(), color = Holo.Primary) { testPhrase = true }
                Text("It can't be replaced: operatives remember the first one they saw, so a thief can't swap it.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        }
        SettingsSection("YOUR FINGERPRINT") {
            Text(fingerprint(vm.myId), fontFamily = Mono, fontSize = 15.sp, color = Holo.Ink)
            Para("Operatives see you as ${opTag(vm.profileName.value ?: "", vm.myId)}. To be certain someone is really you, compare these 32 characters in person; they're on your operative card.")
        }
    }

    if (setPin) PinSetupDialog(onDone = { setPin = false })
    val ct = checkThen
    if (ct != null) PinCheckDialog(onOk = { checkThen = null; ct() }, onCancel = { checkThen = null })
    if (setupPhrase) RecoverySetupDialog(vm) { setupPhrase = false }
    if (testPhrase) RecoveryTestDialog(vm) { testPhrase = false }
}

@Composable
private fun PinSetupDialog(onDone: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = a.length in 4..8 && a.all { it.isDigit() }
    AlertDialog(
        onDismissRequest = onDone,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Set app lock PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("4 to 8 digits. If you forget it, the only way back in is to erase the phone, so make sure you have a backup or recovery phrase.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                PinField(a, "New PIN") { a = it }
                PinField(b, "Repeat PIN") { b = it }
                if (b.isNotEmpty() && b != a) MonoLabel("PINS DON'T MATCH", Holo.Danger)
            }
        },
        confirmButton = {
            TextButton(enabled = valid && a == b && !busy, onClick = {
                busy = true
                scope.launch { withContext(Dispatchers.Default) { AppLock.setPin(a) }; onDone() }
            }) { Text(if (busy) "SAVING…" else "SAVE", fontFamily = Mono, color = Holo.Primary) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(8)) }, singleLine = true, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PinCheckDialog(onOk: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onCancel,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text("Enter current PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, "PIN") { pin = it; wrong = false }
                if (wrong) MonoLabel(if (AppLock.waitMs() > 0) "TOO MANY TRIES · WAIT ${AppLock.waitMs() / 1000 + 1} S" else "WRONG PIN", Holo.Danger)
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4 && !busy, onClick = {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { AppLock.check(pin) }
                    busy = false
                    if (ok) onOk() else { wrong = true; pin = "" }
                }
            }) { Text("OK", fontFamily = Mono, color = Holo.Primary) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}

@Composable
private fun PhraseGrid(words: List<String>) {
    Column(Modifier.fillMaxWidth().openPanel(Holo.Void, Holo.Primary.copy(alpha = 0.6f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        words.chunked(4).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, w ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("${r * 4 + c + 1}".padStart(2, '0'), Holo.InkDim, size = 10)
                        Spacer(Modifier.width(4.dp))
                        Text(w.uppercase(), fontFamily = Mono, fontSize = 13.sp, color = Holo.Primary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoverySetupDialog(vm: ChatViewModel, onClose: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val words = remember { Recovery.newPhrase() }
    val ask = remember { (0 until 16).shuffled().take(3).sorted() }
    val answers = remember { mutableStateListOf("", "", "") }
    var wrong by remember { mutableStateOf(false) }

    ConsolePage("RECOVERY PHRASE", "SETTINGS · SECURITY", onClose, secure = true) {
        when (step) {
            0 -> {
                Para("You'll see 16 words once. Write them on paper, in order, and keep it somewhere safe.", Holo.Ink)
                Notice("KEEP IT SECRET", "Anyone with these words can take over your identity on their own phone and lock this one out. CyberTxTs never stores them, and never asks for them except when you recover.")
                Para("Screenshots are blocked on this screen.")
                HoloButton("Show my words", enabled = true, modifier = Modifier.fillMaxWidth()) { step = 1 }
            }
            1 -> {
                PhraseGrid(words)
                Para("Typing just the first four letters of each word is enough when you recover.")
                HoloButton("I've written them down", enabled = true, modifier = Modifier.fillMaxWidth()) { step = 2 }
            }
            2 -> {
                Para("Check your paper: type these words.", Holo.Ink)
                ask.forEachIndexed { i, pos ->
                    OutlinedTextField(answers[i], { answers[i] = it.trim(); wrong = false }, singleLine = true,
                        label = { Text("Word ${pos + 1}") }, shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
                }
                if (wrong) MonoLabel("THAT DOESN'T MATCH · CHECK YOUR PAPER", Holo.Danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Show again", Modifier.weight(1f), color = Holo.Primary) { step = 1 }
                    HoloButton("Confirm", enabled = answers.all { it.length >= 4 }, modifier = Modifier.weight(1f)) {
                        val ok = ask.indices.all { i -> answers[i].lowercase().take(4) == words[ask[i]].take(4) }
                        if (ok) { vm.setRecovery(words); step = 3 } else wrong = true
                    }
                }
            }
            else -> {
                Notice("RECOVERY PHRASE SET", "Operatives you link with now remember your recovery key. If you lose this phone, " +
                    "install CyberTxTs on a new one and pick RECOVER WITH PHRASE.", Holo.Primary)
                HoloButton("Done", enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }
}

@Composable
private fun RecoveryTestDialog(vm: ChatViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Boolean?>(null) }
    ConsolePage("TEST PHRASE", "SETTINGS · SECURITY", onClose, secure = true) {
        PhraseInput(text) { text = it; result = null }
        HoloButton("Check", enabled = Recovery.parse(text) != null, modifier = Modifier.fillMaxWidth()) {
            result = Recovery.parse(text)?.let { vm.checkRecovery(it) } ?: false
        }
        when (result) {
            true -> Notice("MATCHES", "That's your recovery phrase. Keep the paper safe.", Holo.Primary)
            false -> Notice("DOESN'T MATCH", "Those are valid words, but not the phrase set on this phone.")
            null -> {}
        }
    }
}

@Composable
private fun PhraseInput(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(text, onChange, label = { Text("16 words (first 4 letters is enough)") }, minLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
    val count = text.lowercase().split(Regex("[^a-z]+")).count { it.isNotEmpty() }
    val bad = Recovery.firstUnknownWord(text)
    MonoLabel(
        when {
            bad != null -> "WORD $bad ISN'T ON THE LIST"
            count < 16 -> "$count / 16 WORDS"
            count > 16 -> "TOO MANY WORDS ($count)"
            Recovery.parse(text) == null -> "A WORD IS WRONG OR OUT OF ORDER"
            else -> "PHRASE LOOKS RIGHT"
        },
        if (bad != null || count > 16) Holo.Danger else if (Recovery.parse(text) != null) Holo.Primary else Holo.InkDim
    )
}

// =====================================================================================================
// Backup & devices
// =====================================================================================================

@Composable
private fun BackupPage(vm: ChatViewModel, onBack: () -> Unit) {
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    var export by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    ConsolePage("BACKUP & DEVICES", "SETTINGS", onBack) {
        SettingsSection("ENCRYPTED BACKUP") {
            Para("One file holding your nodes and access keys, nicknames, settings and (optionally) your message log, locked with a passphrase you choose. " +
                "Restoring it on a new phone brings you back as yourself.")
            Text(if (last > 0) "Last backup: ${dateText(last)}" else "No backup yet",
                style = MaterialTheme.typography.bodySmall, color = if (last > 0) Holo.InkDim else Holo.Accent)
            HoloButton("Export backup", enabled = true, modifier = Modifier.fillMaxWidth()) { export = true }
            Text("Photos, videos and VOX notes aren't included. Access keys change when someone is removed: export again after that.",
                style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        }
        SettingsSection("MOVE TO A NEW PHONE") {
            Para("Got a new phone? Install CyberTxTs on it, choose MOVE FROM MY OLD PHONE, then scan its code from here. " +
                "Your nodes, contacts and log move across, and operatives see it's really you.")
            HoloButton("Move to a new phone", enabled = true, modifier = Modifier.fillMaxWidth()) { move = true }
        }
    }
    if (export) BackupExportDialog(vm) { export = false }
    if (move) MoveSendDialog(vm) { move = false }
}

@Composable
private fun BackupExportDialog(vm: ChatViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val b = bytes
        if (uri != null && b != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) } != null }.getOrDefault(false)
            }
            if (ok) { vm.markBackedUp(); done = "Backup saved. Keep the file and its passphrase somewhere safe, and separate." }
            else done = "Couldn't write the file there. Try another location."
        }
        busy = false
    }
    ConsolePage("EXPORT BACKUP", "SETTINGS · BACKUP & DEVICES", onClose) {
        val d = done
        if (d != null) {
            Notice(if (d.startsWith("Backup saved")) "DONE" else "NOT SAVED", d, if (d.startsWith("Backup saved")) Holo.Primary else Holo.Accent)
            HoloButton("Close", enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
        } else {
            Notice("PROTECT BOTH", "Anyone with this file AND its passphrase can come back as you on their phone. Store them apart (e.g. the file in your cloud, the passphrase on paper).")
            OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Backup passphrase · 10+ characters") },
                visualTransformation = PasswordVisualTransformation(), shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass2, { pass2 = it }, singleLine = true, label = { Text("Repeat passphrase") },
                visualTransformation = PasswordVisualTransformation(), shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
            if (pass2.isNotEmpty() && pass2 != pass) MonoLabel("PASSPHRASES DON'T MATCH", Holo.Danger)
            Text("There's no way to recover a forgotten passphrase.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            CheckLine(withLog, "Include message log") { withLog = it }
            HoloButton(if (busy) "Encrypting…" else "Save backup file", enabled = !busy && pass.length >= 10 && pass == pass2,
                modifier = Modifier.fillMaxWidth()) {
                busy = true
                scope.launch {
                    bytes = vm.exportBackup(pass, withLog)
                    AppLock.holdOpen()
                    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
                    saver.launch("cybertxts-backup-$day.ctxb")
                }
            }
        }
    }
}

@Composable
private fun MoveSendDialog(vm: ChatViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withLog by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf<MoveOffer?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }   // "" = success
    var erase by remember { mutableStateOf(false) }

    ConsolePage("MOVE TO A NEW PHONE", "SETTINGS · BACKUP & DEVICES", onClose) {
        val o = offer
        val r = result
        when {
            r == "" -> {
                Notice("MOVE COMPLETE", "Your new phone now has your nodes, contacts and settings, and will show operatives it's really you when it links.", Holo.Primary)
                Para("Erase this phone now so your nodes aren't left on it.", Holo.Ink)
                HoloButton("Erase this phone", enabled = true, modifier = Modifier.fillMaxWidth()) { erase = true }
                GhostButton("Keep for now", Modifier.fillMaxWidth(), color = Holo.Muted, onClick = onClose)
            }
            o == null -> {
                Para("1. On the new phone, install CyberTxTs and choose MOVE FROM MY OLD PHONE.", Holo.Ink)
                Para("2. Put both phones on the same WiFi (or connect this phone to the new phone's hotspot).", Holo.Ink)
                Para("3. Scan the code it shows.", Holo.Ink)
                CheckLine(withLog, "Include message log") { withLog = it }
                Text("Photos, videos and VOX notes stay on this phone.", style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
                if (r != null) Notice("DIDN'T WORK", r)
                HoloButton("Scan new phone's code", enabled = true, modifier = Modifier.fillMaxWidth()) {
                    AppLock.holdOpen(); scanning = true
                }
            }
            else -> {
                Notice("NEW PHONE", "OP-ID ${fingerprint(o.id).take(9)}… at ${o.host}", Holo.Primary)
                Para("This phone signs a statement that the new phone is you. It's sent encrypted, straight to the new phone.")
                HoloButton(if (busy) "Moving…" else "Move now", enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    busy = true
                    scope.launch {
                        val err = vm.sendMove(o, withLog)
                        busy = false
                        if (err == null) result = "" else { result = err; offer = null }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Holo.Primary, trackColor = Holo.PrimaryDeep)
                GhostButton("Scan a different code", Modifier.fillMaxWidth(), color = Holo.Muted) { offer = null }
            }
        }
    }
    if (scanning) ScanQrScreen(
        title = "SCAN NEW PHONE", subtitle = "THE CODE ON YOUR NEW PHONE'S MOVE SCREEN",
        hint = "ALIGN THE NEW PHONE'S QR INSIDE THE FRAME", wrong = "NOT A CYBERTXTS MOVE CODE",
        accept = { t -> MoveLink.parse(t)?.let { offer = it; result = null; scanning = false; true } ?: false },
        onClose = { scanning = false }
    )
    if (erase) EraseDialog(vm, { erase = false }) { closeApp(ctx) }
}

// =====================================================================================================
// First launch: already an operative?
// =====================================================================================================

@Composable
fun ReturningOptions(vm: ChatViewModel) {
    var which by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MonoLabel("—— ALREADY AN OPERATIVE? ——", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        GhostButton("Move from my old phone", Modifier.fillMaxWidth(), color = Holo.Primary) { which = "move" }
        GhostButton("Restore a backup file", Modifier.fillMaxWidth(), color = Holo.Primary) { which = "restore" }
        GhostButton("Recover with phrase", Modifier.fillMaxWidth(), color = Holo.Primary) { which = "phrase" }
    }
    when (which) {
        "move" -> MoveReceiveDialog(vm) { which = null }
        "restore" -> RestoreDialog(vm) { which = null }
        "phrase" -> PhraseRecoverDialog(vm) { which = null }
    }
}

@Composable
private fun MoveReceiveDialog(vm: ChatViewModel, onClose: () -> Unit) {
    val state by vm.moveState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.startMoveReceive() }
    val close = { vm.cancelMove(); onClose() }
    ConsolePage("MOVE FROM OLD PHONE", onBack = close) {
        when (val s = state) {
            is ChatViewModel.MoveState.Waiting -> {
                val qr by produceState<ImageBitmap?>(null, s.link) {
                    value = withContext(Dispatchers.Default) { runCatching { InviteLink.qr(s.link).asImageBitmap() }.getOrNull() }
                }
                Para("On your OLD phone: Settings › Backup & devices › Move to a new phone, then scan this code. Keep both phones on the same WiFi.", Holo.Ink)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(260.dp).background(Holo.Void), contentAlignment = Alignment.Center) {
                        val b = qr
                        if (b != null) Image(bitmap = b, contentDescription = "Move code", modifier = Modifier.size(250.dp))
                        else CircularProgressIndicator(color = Holo.Primary)
                    }
                }
                MonoLabel("NEW OP-ID ${fingerprint(vm.myId).take(9)}…", Holo.Primary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                MonoLabel("WAITING FOR YOUR OLD PHONE…", Holo.InkDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            ChatViewModel.MoveState.Receiving -> {
                Para("Receiving your nodes, contacts and log…", Holo.Ink)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Holo.Primary, trackColor = Holo.PrimaryDeep)
            }
            ChatViewModel.MoveState.Done -> {
                Notice("WELCOME BACK", "Everything's here. Operatives will see this is you as each node links.", Holo.Primary)
                HoloButton("Continue", enabled = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
            is ChatViewModel.MoveState.Failed -> {
                Notice("DIDN'T WORK", s.why)
                HoloButton("Try again", enabled = true, modifier = Modifier.fillMaxWidth()) { vm.startMoveReceive() }
            }
            ChatViewModel.MoveState.Idle -> CircularProgressIndicator(color = Holo.Primary)
        }
    }
}

@Composable
private fun RestoreDialog(vm: ChatViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var lost by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() } }.getOrNull()
            }
            if (b == null || b.size > 300_000_000) error = "Couldn't read that file." else { file = b; fileName = uri.lastPathSegment ?: "backup"; error = null }
        }
    }
    ConsolePage("RESTORE BACKUP", onBack = onClose) {
        Para("Pick your .ctxb backup file and enter its passphrase. You'll come back with your callsign, nodes and contacts, and operatives will see it's really you.", Holo.Ink)
        GhostButton(if (file == null) "Choose backup file" else "File: ${fileName.substringAfterLast('/')}", Modifier.fillMaxWidth(), color = Holo.Primary) {
            AppLock.holdOpen(); picker.launch(arrayOf("*/*"))
        }
        OutlinedTextField(pass, { pass = it; error = null }, singleLine = true, label = { Text("Backup passphrase") },
            visualTransformation = PasswordVisualTransformation(), shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
        CheckLine(lost, "My old phone is lost or stolen: lock it out") { lost = it }
        if (lost) Text("Every node removes your old phone and changes its access key, so whoever has it can't read anything new.",
            style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        error?.let { Notice("COULDN'T RESTORE", it) }
        HoloButton(if (busy) "Decrypting…" else "Restore", enabled = file != null && pass.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
            busy = true
            scope.launch {
                val err = vm.restoreBackup(file!!, pass, lost)
                busy = false
                if (err != null) error = err else onClose()
            }
        }
    }
}

@Composable
private fun PhraseRecoverDialog(vm: ChatViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var lock by remember { mutableStateOf(true) }
    ConsolePage("RECOVER WITH PHRASE", onBack = onClose, secure = true) {
        Para("Lost your phone and have no backup? Your recovery phrase proves this new phone is you.", Holo.Ink)
        OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Your callsign") },
            shape = RectangleShape, colors = holoFieldColors(), modifier = Modifier.fillMaxWidth())
        PhraseInput(text) { text = it }
        CheckLine(lock, "Lock out my old phone") { lock = it }
        Notice("NEXT", "Your nodes aren't stored in the phrase. Ask an operative for a fresh invite to each node; when you link, " +
            "they'll see it's you, with your old callsign, nickname and host role." + if (lock) " Your old phone is removed from those nodes." else "",
            Holo.Primary)
        HoloButton("Recover", enabled = name.isNotBlank() && Recovery.parse(text) != null, modifier = Modifier.fillMaxWidth()) {
            Recovery.parse(text)?.let { vm.recoverWithPhrase(it, name.trim(), lock) }
            onClose()
        }
    }
}

// =====================================================================================================
// Lock screen
// =====================================================================================================

@Composable
fun LockScreen(vm: ChatViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bio by AppLock.biometric.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf(AppLock.waitMs()) }
    LaunchedEffect(Unit) {
        if (bio && biometricAvailable(ctx)) promptBiometric(ctx) { AppLock.unlock() }
        while (true) { wait = AppLock.waitMs(); delay(500) }
    }
    fun submit() {
        if (pin.length < 4 || busy || wait > 0) return
        busy = true
        val p = pin
        scope.launch {
            val ok = withContext(Dispatchers.Default) { AppLock.check(p) }
            busy = false
            if (ok) AppLock.unlock() else { pin = ""; msg = "WRONG PIN · ${AppLock.failures()} TR${if (AppLock.failures() == 1) "Y" else "IES"}" }
        }
    }

    Box(Modifier.fillMaxSize().background(Holo.Void)) {
        Starfield(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Orbit(72.dp, Holo.Primary, spin = busy)
            Spacer(Modifier.height(14.dp))
            Text("CyberTxTs", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Holo.Primary)
            MonoLabel("LOCKED · ENTER PIN", Holo.Accent, size = 12)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = "${pin.length} digits entered" }) {
                repeat(maxOf(4, pin.length)) { i ->
                    Box(Modifier.size(14.dp).then(if (i < pin.length) Modifier.background(Holo.Primary) else Modifier.openButton(Holo.Muted, cut = 3.dp)))
                }
            }
            Spacer(Modifier.height(10.dp))
            MonoLabel(
                when { wait > 0 -> "TOO MANY TRIES · WAIT ${wait / 1000 + 1} S"; msg != null -> msg!!; else -> " " },
                Holo.Danger, align = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(width = 76.dp, height = 60.dp)
                                .openButton(if (k == "OK") Holo.Primary else Holo.Muted.copy(alpha = 0.6f), cut = 8.dp)
                                .clickable(role = Role.Button) {
                                    msg = null
                                    when (k) {
                                        "⌫" -> pin = pin.dropLast(1)
                                        "OK" -> submit()
                                        else -> if (pin.length < 8) pin += k
                                    }
                                }
                                .semantics { contentDescription = if (k == "⌫") "Delete" else k },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(k, fontFamily = Mono, fontSize = if (k.length > 1) 14.sp else 22.sp,
                                color = if (k == "OK") Holo.Primary else Holo.Ink)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (bio && biometricAvailable(ctx)) {
                GhostButton("Use fingerprint / face", color = Holo.Primary) { promptBiometric(ctx) { AppLock.unlock() } }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = { forgot = true }) { Text("FORGOT PIN?", fontFamily = Mono, color = Holo.Muted) }
        }
    }
    if (forgot) {
        var erase by remember { mutableStateOf(false) }
        if (!erase) AlertDialog(
            onDismissRequest = { forgot = false },
            shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
            title = { Text("Forgot your PIN?") },
            text = { Text("The PIN can't be reset. You can erase this phone and then come back as yourself by restoring a backup, moving from another phone, or using your recovery phrase.") },
            confirmButton = { TextButton(onClick = { erase = true }) { Text("ERASE PHONE", fontFamily = Mono, color = Holo.Danger) } },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("BACK", fontFamily = Mono, color = Holo.Primary) } }
        ) else EraseDialog(vm, { forgot = false }) { closeApp(ctx) }
    }
}
