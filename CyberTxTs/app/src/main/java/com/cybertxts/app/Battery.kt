package com.cybertxts.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/*
 * Battery & data tracker.
 *
 * Android doesn't let an app read its own share of the battery, so this records what it can:
 *  - the phone's battery level every 10 minutes while CyberTxTs is running (drain is measured only while unplugged)
 *  - CyberTxTs' own CPU time (the main thing that costs battery besides the radio)
 *  - CyberTxTs' own data sent and received, split by WiFi / mobile at the time of each sample
 *  - how many links, nodes and contacts were open
 * Samples stay on this phone (about two days' worth).
 */
object UsageTracker {
    data class Sample(
        val ts: Long, val level: Int, val charging: Boolean, val cpuMs: Long,
        val rx: Long, val tx: Long, val mobile: Boolean, val links: Int, val sessions: Int
    )

    data class Report(
        val hours: Double,                    // time covered by samples in the window
        val drainPerHour: Double?,            // % per hour while unplugged (null = not enough data)
        val unpluggedHours: Double,
        val cpuMinutes: Double,
        val wifiBytes: Long, val mobileBytes: Long,
        val avgLinks: Double,
        val points: List<Pair<Long, Int>>,    // (time, battery %) for the chart
        val charging: List<Boolean>
    )

    private const val KEY = "usage_samples"
    private const val MAX = 320               // ~2 days at one sample per 10 minutes

    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i: Intent? = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun onMobile(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }.getOrDefault(false)

    fun sample(ctx: Context, prefs: SharedPreferences, links: Int, sessions: Int) {
        val (level, charging) = battery(ctx)
        if (level < 0) return
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        val s = Sample(System.currentTimeMillis(), level, charging, Process.getElapsedCpuTime(), rx, tx, onMobile(ctx), links, sessions)
        val list = (load(prefs) + s).takeLast(MAX)
        save(prefs, list)
    }

    fun load(prefs: SharedPreferences): List<Sample> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Sample(o.getLong("t"), o.getInt("l"), o.getBoolean("c"), o.getLong("cpu"), o.getLong("rx"), o.getLong("tx"),
                o.optBoolean("m"), o.optInt("k"), o.optInt("s"))
        }
    }.getOrDefault(emptyList())

    private fun save(prefs: SharedPreferences, list: List<Sample>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("t", s.ts).put("l", s.level).put("c", s.charging).put("cpu", s.cpuMs)
                .put("rx", s.rx).put("tx", s.tx).put("m", s.mobile).put("k", s.links).put("s", s.sessions))
        }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    fun reset(prefs: SharedPreferences) = prefs.edit().remove(KEY).apply()

    /** Summary of the last [hours] hours. Gaps over 2 hours (app closed, phone off) are left out. */
    fun report(prefs: SharedPreferences, hours: Int = 24): Report {
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val all = load(prefs).filter { it.ts >= since }
        var covered = 0.0; var unplugged = 0.0; var drop = 0.0; var cpu = 0.0
        var wifi = 0L; var mobile = 0L; var linkSum = 0.0
        for (i in 1 until all.size) {
            val a = all[i - 1]; val b = all[i]
            val dtH = (b.ts - a.ts) / 3_600_000.0
            if (dtH <= 0 || dtH > 2.0) continue
            covered += dtH
            linkSum += b.links * dtH
            if (!a.charging && !b.charging) { unplugged += dtH; drop += (a.level - b.level).coerceAtLeast(0) }
            // Counters restart when the app restarts (CPU) or the phone reboots (data): count from zero then
            cpu += (if (b.cpuMs >= a.cpuMs) b.cpuMs - a.cpuMs else b.cpuMs) / 60_000.0
            val d = (if (b.rx >= a.rx) b.rx - a.rx else b.rx) + (if (b.tx >= a.tx) b.tx - a.tx else b.tx)
            if (b.mobile) mobile += d else wifi += d
        }
        return Report(
            hours = covered,
            drainPerHour = if (unplugged >= 1.0) drop / unplugged else null,
            unpluggedHours = unplugged, cpuMinutes = cpu, wifiBytes = wifi, mobileBytes = mobile,
            avgLinks = if (covered > 0) linkSum / covered else 0.0,
            points = all.map { it.ts to it.level }, charging = all.map { it.charging }
        )
    }
}
