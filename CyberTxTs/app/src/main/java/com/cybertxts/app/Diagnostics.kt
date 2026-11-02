package com.cybertxts.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Link diagnostics: why phones aren't linking, in plain words, plus linking by address
 * for networks where automatic discovery doesn't work.
 */

/** One finding: a short title and what to do about it. warn = something is wrong. */
data class Finding(val title: String, val body: String, val warn: Boolean = true)

fun diagnose(i: NodeSession.LinkInfo, linked: Int): List<Finding> {
    val out = ArrayList<Finding>()
    if (!i.onWifi) out.add(Finding(
        "NOT ON WIFI",
        "WiFi linking needs both phones on the same WiFi. " +
            if (i.internetOn) "You can still link over the internet." else "Turn on Internet link in Settings to link from anywhere."
    ))
    val err = i.discoveryError ?: i.advertiseError
    if (i.onWifi && err != null) out.add(Finding(
        "WIFI DISCOVERY FAILED",
        "Android couldn't start finding phones on this WiFi (code $err). Turn WiFi off and on again, or use LINK BY ADDRESS below."
    ))
    if (i.lanStuck > 0) out.add(Finding(
        "ROUTER IS BLOCKING PHONES",
        "This phone can see ${i.lanStuck} operative${if (i.lanStuck == 1) "" else "s"} on the WiFi but can't connect. " +
            "Guest, hotel, school and public WiFi often stop phones talking to each other (\"client isolation\"). " +
            "Use a home network or a phone hotspot, turn on Internet link, or ask whoever runs the WiFi to switch isolation off."
    ))
    if (i.onWifi && err == null && i.lanFound == 0 && linked == 0) out.add(Finding(
        "NOBODY FOUND ON THIS WIFI",
        "Check the other phone has CyberTxTs open on this node and is on the same WiFi (not a guest network). " +
            "Some routers hide phones from each other: try LINK BY ADDRESS below with the address on their screen."
    ))
    if (i.internetOn && i.relays == 0) out.add(Finding(
        "RELAYS UNREACHABLE",
        "This network may block the relays used to find operatives over the internet (common on work and school WiFi). WiFi linking still works."
    ))
    if (i.internetOn && i.relays > 0 && i.netLinked == 0 && linked == 0) out.add(Finding(
        "NO INTERNET LINK YET",
        "Relays are reachable but nobody has linked. Check the node code and access key match exactly and the other phone is online. " +
            "About 1 in 5 network pairs can't link directly (strict mobile carriers or office firewalls): try a different network on one side."
    ))
    if (out.isEmpty()) out.add(Finding(
        if (linked > 0) "LINKS LOOK HEALTHY" else "WAITING FOR OPERATIVES",
        if (linked > 0) "Nothing is blocking this phone right now."
        else "Nothing looks wrong on this phone. Once another operative opens this node, it should link within about 30 seconds.",
        warn = false
    ))
    return out
}

@Composable
fun LinkDiagnosticsDialog(vm: NodeSession, onClose: () -> Unit) {
    var info by remember { mutableStateOf<NodeSession.LinkInfo?>(null) }
    var linked by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            info = withContext(Dispatchers.Default) { runCatching { vm.linkInfo() }.getOrNull() }
            linked = vm.connected.value.size
            delay(2_000)
        }
    }
    var addr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // message, success
    val scope = rememberCoroutineScope()
    val fieldColors = holoFieldColors()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).openPanel(Holo.Panel, Holo.Primary.copy(alpha = 0.8f), cut = 18.dp)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("LINK DIAGNOSTICS", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Holo.Primary)
            val i = info
            if (i == null) {
                CircularProgressIndicator(color = Holo.Primary, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Column(
                    Modifier.fillMaxWidth().openPanel(Holo.Void, Holo.Muted.copy(alpha = 0.4f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DiagLine("WIFI", if (i.onWifi) "CONNECTED" else "NOT ON WIFI", if (i.onWifi) Holo.Primary else Holo.Accent)
                    DiagLine("YOUR ADDRESS",
                        if (i.addresses.isEmpty() || i.port == 0) "NONE" else i.addresses.joinToString("  ") { "$it:${i.port}" },
                        Holo.Ink)
                    val err = i.discoveryError ?: i.advertiseError
                    DiagLine("WIFI DISCOVERY",
                        if (err != null) "FAILED · CODE $err" else "FOUND ${i.lanFound} · LINKED ${i.lanLinked}",
                        if (err != null || i.lanStuck > 0) Holo.Accent else Holo.Primary)
                    DiagLine("INTERNET LINK",
                        if (!i.internetOn) "OFF" else "RELAYS ${i.relays}/${NostrPool.DEFAULT_RELAYS.size} · LINKED ${i.netLinked}",
                        if (i.internetOn && i.relays == 0) Holo.Accent else Holo.Primary)
                }
                diagnose(i, linked).forEach { f ->
                    val c = if (f.warn) Holo.Accent else Holo.Primary
                    Column(Modifier.fillMaxWidth().openPanel(Holo.Panel, c.copy(alpha = 0.7f)).padding(10.dp)) {
                        MonoLabel(f.title, c, size = 12)
                        Spacer(Modifier.height(4.dp))
                        Text(f.body, style = MaterialTheme.typography.bodyMedium, color = Holo.Ink)
                    }
                }

                MonoLabel("LINK BY ADDRESS", Holo.Accent)
                Text(
                    "Same WiFi but not finding each other? Type the address shown on the other phone's Link diagnostics screen.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim
                )
                OutlinedTextField(
                    addr, { addr = it; result = null }, singleLine = true,
                    label = { Text("e.g. 192.168.1.23:${if (i.port > 0) i.port else 41234}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RectangleShape, colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
                HoloButton(if (busy) "Reaching…" else "Link", enabled = !busy && addr.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    busy = true; result = null
                    scope.launch {
                        val ok = runCatching { vm.linkByAddress(addr) }.getOrDefault(false)
                        busy = false
                        result = if (ok) "REACHED · LINKING NOW. IT STAYS LINKED AND RECONNECTS ON ITS OWN." to true
                        else "NO ANSWER AT THAT ADDRESS. Check the numbers, that both phones are on the same WiFi, " +
                            "and that CyberTxTs is open on this node on the other phone. If the router blocks phones from " +
                            "each other, linking by address can't get through either." to false
                    }
                }
                result?.let { (msg, ok) ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) Holo.Primary else Holo.Accent)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton("Close", color = Holo.Primary, onClick = onClose)
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        MonoLabel(label, Holo.InkDim, size = 11, modifier = Modifier.width(118.dp))
        MonoLabel(value, color, size = 12, modifier = Modifier.weight(1f))
    }
}
