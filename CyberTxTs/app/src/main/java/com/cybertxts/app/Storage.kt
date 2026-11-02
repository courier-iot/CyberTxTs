package com.cybertxts.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your privacy choices. Stored on this phone only.
 * Voice memos have no setting on purpose: they are never saved and vanish when you leave a room.
 */
data class AppSettings(
    val keepHistory: Boolean = true,
    val retentionDays: Int = 0,      // 0 = keep forever
    val keepImages: Boolean = true,
    val keepVideos: Boolean = true,
    val keepAlive: Boolean = true,   // foreground service: stay connected with the screen off
    val internet: Boolean = true,    // also link over the internet (relays + direct WebRTC), not just WiFi
    val relay: Boolean = true,       // forward frames for operatives who can't link to each other directly
    val quality: String = "standard", // default sending quality for photos and videos (a Quality key)
    val askQuality: Boolean = true,   // show the quality chooser every time
    val theme: String = "dark",       // "dark" (Night ops), "light" (Day shift) or "system"
    val readMarks: Boolean = true,    // send READ marks (off: you don't see others' either)
    val typingSignal: Boolean = true  // send TRANSMITTING… (off: you don't see others' either)
)

object SettingsStore {
    fun load(p: SharedPreferences) = AppSettings(
        keepHistory = p.getBoolean("s_hist", true),
        retentionDays = p.getInt("s_days", 0),
        keepImages = p.getBoolean("s_img", true),
        keepVideos = p.getBoolean("s_vid", true),
        keepAlive = p.getBoolean("s_keep", true),
        internet = p.getBoolean("s_net", true),
        relay = p.getBoolean("s_relay", true),
        quality = p.getString("s_q", "standard") ?: "standard",
        askQuality = p.getBoolean("s_ask", true),
        theme = p.getString("s_theme", "dark") ?: "dark",
        readMarks = p.getBoolean("s_read", true),
        typingSignal = p.getBoolean("s_typing", true)
    )

    fun save(p: SharedPreferences, s: AppSettings) {
        p.edit().putBoolean("s_hist", s.keepHistory).putInt("s_days", s.retentionDays)
            .putBoolean("s_img", s.keepImages).putBoolean("s_vid", s.keepVideos).putBoolean("s_keep", s.keepAlive).putBoolean("s_net", s.internet).putBoolean("s_relay", s.relay)
            .putString("s_q", s.quality).putBoolean("s_ask", s.askQuality)
            .putString("s_theme", s.theme).putBoolean("s_read", s.readMarks).putBoolean("s_typing", s.typingSignal).apply()
    }
}

/** Encrypted-at-rest chat history, one file per room. */
class HistoryStore(context: Context) {
    private val ctx = context.applicationContext
    private val master by lazy { MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }

    private fun file(tag: String) = File(ctx.filesDir, "history_$tag.bin")
    private fun enc(f: File) = EncryptedFile.Builder(
        ctx, f, master, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
    ).build()

    private fun cutoff(days: Int) = if (days > 0) System.currentTimeMillis() - days * 86_400_000L else 0L

    @Synchronized
    fun save(tag: String, msgs: List<Msg>, retentionDays: Int) {
        if (tag.isEmpty()) return
        val limit = cutoff(retentionDays)
        val arr = JSONArray()
        msgs.filter { it.ts >= limit }.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("sid", m.senderId).put("n", m.senderName)
                .put("text", m.text).put("ts", m.ts).put("sys", m.system)
                .put("img", m.image ?: "").put("w", m.imgW).put("h", m.imgH)
                .put("vid", m.video ?: "").put("dur", m.durMs).put("re", m.replyTo ?: "")
                .put("dm", m.dm ?: "").put("ed", m.edited).put("del", m.deleted)
                .put("pin", m.pinned).put("og", m.og).put("ogh", m.ogHave).put("eda", m.editedAt)
                .put("poll", m.poll ?: "").put("pv", JSONObject().apply { m.votes.forEach { (k, v) -> put(k, v) } })
                .put("rx", JSONObject().apply { m.reactions.forEach { (e, who) -> put(e, JSONArray(who.toList())) } }))
        }
        val f = file(tag)
        f.delete() // EncryptedFile refuses to overwrite
        enc(f).openFileOutput().use { it.write(arr.toString().toByteArray()) }
    }

    @Synchronized
    fun load(tag: String, retentionDays: Int): List<Msg> {
        val f = file(tag)
        if (!f.exists()) return emptyList()
        return try {
            val txt = enc(f).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(txt)
            val limit = cutoff(retentionDays)
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Msg(it.getString("id"), it.getString("sid"), it.getString("n"),
                    it.getString("text"), it.getLong("ts"), it.optBoolean("sys", false),
                    it.optString("img", "").ifEmpty { null }, it.optInt("w", 0), it.optInt("h", 0),
                    durMs = it.optLong("dur", 0L), video = it.optString("vid", "").ifEmpty { null },
                    replyTo = it.optString("re", "").ifEmpty { null },
                    dm = it.optString("dm", "").ifEmpty { null },
                    edited = it.optBoolean("ed", false), deleted = it.optBoolean("del", false),
                    pinned = it.optBoolean("pin", false), og = it.optLong("og", 0L), ogHave = it.optBoolean("ogh", false),
                    editedAt = it.optLong("eda", 0L), poll = it.optString("poll", "").ifEmpty { null },
                    votes = it.optJSONObject("pv")?.let { o -> o.keys().asSequence().associateWith { k -> o.getString(k) } } ?: emptyMap(),
                    reactions = it.optJSONObject("rx")?.let { o ->
                        o.keys().asSequence().associateWith { e ->
                            val a = o.getJSONArray(e); (0 until a.length()).map { k -> a.getString(k) }.toSet()
                        }
                    } ?: emptyMap())
            }.filter { it.ts >= limit }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized fun delete(tag: String) { file(tag).delete(); frames(tag).delete() }

    // ---- catch-up log: a week of signed frames per node, so phones that were away can be filled in ----
    private fun frames(tag: String) = File(ctx.filesDir, "frames_$tag.bin")

    @Synchronized
    fun saveFrames(tag: String, json: String) {
        if (tag.isEmpty()) return
        val f = frames(tag)
        f.delete()
        enc(f).openFileOutput().use { it.write(json.toByteArray()) }
    }

    @Synchronized
    fun loadFrames(tag: String): String? {
        val f = frames(tag)
        if (!f.exists()) return null
        return runCatching { enc(f).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    /** The saved log as stored (JSON text), for backups and moving phones. */
    @Synchronized
    fun readRaw(tag: String): String? {
        val f = file(tag)
        if (!f.exists()) return null
        return runCatching { enc(f).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    @Synchronized
    fun writeRaw(tag: String, json: String) {
        if (tag.isEmpty() || runCatching { JSONArray(json) }.isFailure) return
        val f = file(tag)
        f.delete()
        enc(f).openFileOutput().use { it.write(json.toByteArray()) }
    }

    @Synchronized
    fun deleteAll() {
        ctx.filesDir.listFiles { _, name -> name.startsWith("history_") || name.startsWith("frames_") }?.forEach { it.delete() }
    }
}
