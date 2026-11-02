package com.cybertxts.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line for the Settings home row. */
fun usageSummary(r: UsageTracker.Report): String {
    val drain = r.drainPerHour?.let { "≈%.1f%%/h unplugged".format(it) } ?: "Collecting data"
    return drain + " · " + fmtSize(r.wifiBytes + r.mobileBytes) + " in 24 h"
}

@Composable
fun UsagePage(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick by vm.usageTick.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var resetTick by remember { mutableStateOf(0) }
    val r by produceState(UsageTracker.report(vm.usagePrefs()), tick, resetTick) {
        value = withContext(Dispatchers.Default) { UsageTracker.report(vm.usagePrefs()) }
    }
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val contacts by vm.contacts.list.collectAsStateWithLifecycle()
    val (level, charging) = remember(tick) { UsageTracker.battery(ctx) }

    ConsolePage("BATTERY & DATA", "SETTINGS", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageStat("DRAIN · UNPLUGGED", r.drainPerHour?.let { "%.1f%% / H".format(it) } ?: "COLLECTING",
                if ((r.drainPerHour ?: 0.0) > 3.0) Holo.Accent else Holo.Primary, Modifier.weight(1f))
            UsageStat("BATTERY NOW", if (level >= 0) "$level%" + if (charging) " · CHG" else "" else "—", Holo.Ink, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageStat("CYBERTXTS CPU · 24H", "%.1f MIN".format(r.cpuMinutes), Holo.Ink, Modifier.weight(1f))
            UsageStat("AVG LINKS OPEN", "%.1f".format(r.avgLinks), Holo.Ink, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageStat("DATA · WIFI · 24H", fmtSize(r.wifiBytes), Holo.Ink, Modifier.weight(1f))
            UsageStat("DATA · MOBILE · 24H", fmtSize(r.mobileBytes), if (r.mobileBytes > 200_000_000) Holo.Accent else Holo.Ink, Modifier.weight(1f))
        }

        SettingsSection("BATTERY · LAST 24 HOURS") {
            BatteryChart(r)
            Text(
                if (r.hours < 1) "Readings are taken every 10 minutes while CyberTxTs runs. Check back in a few hours."
                else "Covers %.1f h of running time (%.1f h unplugged). Grey stretches were on the charger.".format(r.hours, r.unpluggedHours),
                style = MaterialTheme.typography.bodySmall, color = Holo.InkDim
            )
        }

        SettingsSection("RIGHT NOW") {
            Text(
                "${nodes.size} node${if (nodes.size == 1) "" else "s"} · ${contacts.size} contact${if (contacts.size == 1) "" else "s"} · " +
                    "${vm.linkCount()} link${if (vm.linkCount() == 1) "" else "s"} · relays ${RelayHub.connectedRelays()}/${NostrPool.DEFAULT_RELAYS.size}",
                fontFamily = Mono, fontSize = 13.sp, color = Holo.Ink
            )
            Text(listOf(
                if (s.keepAlive) "Background link on" else "Background link off",
                if (s.internet) "internet link on" else "WiFi only",
                if (s.relay) "relaying for others" else "not relaying"
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
        }

        val tips = buildList {
            val d = r.drainPerHour ?: 0.0
            if (s.keepAlive && d > 2.0) add("Hold link in background keeps a connection open with the screen off. Turn it off (Settings › Network) if you don't need instant alerts.")
            if (s.relay && r.avgLinks >= 3) add("You're relaying for other operatives. Turning relaying off saves some battery and data.")
            if (s.internet && r.mobileBytes == 0L && r.wifiBytes > 0) add("Everything has been on WiFi. If your operatives are all on the same WiFi, Internet link off saves battery.")
            if (r.mobileBytes > 200_000_000) add("Lots of mobile data. Sending photos and videos at Saver quality cuts it a lot.")
            if (contacts.size + nodes.size > 12) add("Each node and contact keeps its own links. Unlinking nodes you no longer use helps.")
        }
        if (tips.isNotEmpty()) SettingsSection("TIPS") {
            tips.forEach { InfoCard("", it) }
        }

        GhostButton("Android battery details for CyberTxTs", Modifier.fillMaxWidth(), color = Holo.Primary) {
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }
        }
        GhostButton("Reset tracker", Modifier.fillMaxWidth()) { vm.resetUsage(); resetTick++ }
        Text("Android doesn't let apps see their exact share of the battery, so drain here is the whole phone's while CyberTxTs runs. " +
            "CPU time and data are CyberTxTs' own. Nothing here leaves your phone.",
            style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
    }
}

@Composable
private fun UsageStat(title: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.openPanel(Holo.Panel, Holo.Muted.copy(alpha = 0.4f), cut = 10.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        MonoLabel(title, Holo.InkDim, size = 10)
        Text(value, fontFamily = Mono, fontSize = 17.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun BatteryChart(r: UsageTracker.Report) {
    val line = Holo.Primary; val grid = Holo.Muted.copy(alpha = 0.25f); val plug = Holo.Muted
    val pts = r.points
    val desc = if (pts.isEmpty()) "No battery readings yet" else "Battery from ${pts.first().second}% to ${pts.last().second}% over the last day"
    Canvas(Modifier.fillMaxWidth().height(150.dp).openPanel(Holo.Void, Holo.Muted.copy(alpha = 0.35f)).padding(10.dp)
        .semantics { contentDescription = desc }) {
        val end = System.currentTimeMillis(); val start = end - 24 * 3_600_000L
        fun x(t: Long) = ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f) * size.width
        fun y(l: Int) = size.height - l / 100f * size.height
        listOf(0, 50, 100).forEach { l -> drawLine(grid, Offset(0f, y(l)), Offset(size.width, y(l)), 1f) }
        for (h in 0..24 step 6) drawLine(grid, Offset(x(start + h * 3_600_000L), 0f), Offset(x(start + h * 3_600_000L), size.height), 1f)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (b.first - a.first > 2 * 3_600_000L) continue          // gap: app wasn't running
            val c = if (r.charging.getOrElse(i) { false }) plug else line
            drawLine(c, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)), 4f)
        }
        if (pts.size == 1) drawCircle(line, 5f, Offset(x(pts[0].first), y(pts[0].second)))
    }
    Row(Modifier.fillMaxWidth()) {
        MonoLabel("24H AGO", Holo.InkDim, size = 10, modifier = Modifier.weight(1f))
        MonoLabel("NOW", Holo.InkDim, size = 10)
    }
}
