package com.cybertxts.app

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// ---------- Audio files (music, podcasts, recordings) ----------
//
// Sent exactly as picked: no re-encoding, so a FLAC stays a FLAC. The file travels like a video (signed
// header, verified pieces, stored encrypted on the phone). Its title, artist and file name ride in the
// header's signed preview slot, so they're checked like everything else and sealed in private channels.

const val MAX_AUDIO_BYTES = 100 * 1024 * 1024      // a long lossless track, an album side, a podcast
const val MAX_AUDIO_MS = 4 * 3_600_000L            // 4 hours
fun audioKey(id: String) = "$id-a"

/** What we know about an audio file. [mime] is used when saving it back out. */
data class AudioInfo(val title: String, val artist: String, val name: String, val mime: String, val durMs: Long, val size: Long)

object AudioMeta {
    fun encode(title: String, artist: String, name: String, mime: String): ByteArray =
        JSONObject().put("t", title.take(200)).put("a", artist.take(200)).put("n", name.take(200)).put("m", mime.take(80))
            .toString().toByteArray()

    /** (title, artist, file name, mime). Anything missing or odd comes back empty. */
    fun decode(b: ByteArray?): List<String> {
        val j = b?.takeIf { it.size <= 4_000 }?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }
            ?: return listOf("", "", "", "")
        fun clean(k: String, max: Int) = j.optString(k, "").replace(Regex("[\\p{Cntrl}]"), " ").trim().take(max)
        val mime = clean("m", 80).takeIf { it.startsWith("audio/") || it == "application/ogg" } ?: ""
        return listOf(clean("t", 200), clean("a", 200), clean("n", 200), mime)
    }
}

object AudioUtil {
    private val exts = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "3gp", "mka", "webm", "mid", "midi")

    /** Reads the file name, size, length and tags. Null if it isn't something Android can play. */
    fun probe(ctx: Context, uri: Uri): AudioInfo? {
        var name = ""; var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0) ?: ""
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val type = ctx.contentResolver.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (!type.startsWith("audio/") && type != "application/ogg" && ext !in exts) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            val artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim().orEmpty()
            val mime = type.takeIf { it.startsWith("audio/") || it == "application/ogg" }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "audio/mpeg"
            if (dur <= 0L) null
            else AudioInfo(title.ifEmpty { name.substringBeforeLast('.').ifEmpty { "Audio" } }, artist, name, mime, dur, size)
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    /** Save a copy to the phone's Music/CyberTxTs folder, under its original name. */
    fun saveToMusic(ctx: Context, vm: NodeSession, m: Msg): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val name = m.aName.ifBlank { "CyberTxTs_${System.currentTimeMillis()}.audio" }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, m.aMime.ifBlank { "audio/mpeg" })
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CyberTxTs")
                if (m.aTitle.isNotBlank()) put(MediaStore.Audio.Media.TITLE, m.aTitle)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val ok = ctx.contentResolver.openOutputStream(uri)?.use { vm.copyAudio(m, it) } ?: false
            if (!ok) ctx.contentResolver.delete(uri, null, null)
            ok
        } catch (e: Exception) { false }
    }
}

/**
 * The one music player. It keeps going when you scroll away or leave the chat, pauses for calls,
 * voice notes and other apps (audio focus), and pauses when headphones are unplugged.
 * The file is decrypted to a private cache copy only while it's loaded, and that copy is deleted on stop.
 */
object AudioPlayer {
    data class Now(val id: String, val tag: String, val title: String, val artist: String, val durMs: Long, val playing: Boolean)

    val now = MutableStateFlow<Now?>(null)
    val position = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var mp: MediaPlayer? = null
    private var file: File? = null
    private var ticker: Job? = null
    private var focus: AudioFocusRequest? = null
    private var pausedForFocus = false
    private var noisyOn = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }

    private fun am(): AudioManager? = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Play [m] from [session], or pause / resume it if it's the one loaded. */
    fun toggle(ctx: Context, session: NodeSession, m: Msg) {
        val id = m.audio ?: return
        val cur = now.value
        if (cur != null && cur.id == id) { if (cur.playing) pause() else resume(); return }
        stop()
        app = ctx.applicationContext
        VoicePlayer.stop()
        val c = app ?: return
        now.value = Now(id, session.tag, m.aTitle.ifBlank { "Audio" }, m.aArtist, m.durMs, playing = false)
        scope.launch {
            val f = File(c.cacheDir, "aplay_$id")
            c.cacheDir.listFiles { _, n -> n.startsWith("aplay_") && n != f.name }?.forEach { it.delete() }
            val ok = withContext(Dispatchers.IO) { f.exists() && f.length() > 0 || session.exportAudio(m, f) }
            if (now.value?.id != id) { f.delete(); return@launch }
            if (!ok) { stop(); Toast.makeText(c, "That audio file isn't stored on this phone.", Toast.LENGTH_SHORT).show(); return@launch }
            file = f
            try {
                val p = MediaPlayer()
                mp = p
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                p.setDataSource(f.path)
                p.setOnCompletionListener { stop() }
                p.setOnErrorListener { _, _, _ -> stop(); true }
                p.setOnPreparedListener { resume() }
                p.prepareAsync()
            } catch (e: Exception) {
                stop()
                Toast.makeText(c, "This phone can't play that audio format.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun resume() {
        val p = mp ?: return
        if (!requestFocus()) return
        runCatching { p.setVolume(1f, 1f); p.start() }
        pausedForFocus = false
        if (!noisyOn) {
            runCatching { app?.let { ContextCompat.registerReceiver(it, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) } }
            noisyOn = true
        }
        now.value = now.value?.copy(playing = true)
        if (ticker?.isActive != true) ticker = scope.launch {
            while (isActive) {
                position.value = runCatching { mp?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
                delay(250)
            }
        }
    }

    fun pause() {
        runCatching { if (mp?.isPlaying == true) mp?.pause() }
        now.value = now.value?.copy(playing = false)
        position.value = runCatching { mp?.currentPosition?.toLong() ?: position.value }.getOrDefault(position.value)
        ticker?.cancel(); ticker = null
    }

    fun seekTo(ms: Long) {
        val p = mp ?: return
        runCatching { p.seekTo(ms.coerceIn(0L, (now.value?.durMs ?: 0L)).toInt()) }
        position.value = ms
    }

    /** Stop and unload; the decrypted cache copy is deleted. */
    fun stop() {
        ticker?.cancel(); ticker = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        file?.delete(); file = null
        if (noisyOn) { runCatching { app?.unregisterReceiver(noisy) }; noisyOn = false }
        focus?.let { f -> runCatching { am()?.abandonAudioFocusRequest(f) } }
        focus = null
        pausedForFocus = false
        now.value = null
        position.value = 0L
    }

    /** A message was retracted or expired: stop if it's the one playing. */
    fun forget(id: String?) { if (id != null && now.value?.id == id) scope.launch { stop() } }

    private fun requestFocus(): Boolean {
        val m = am() ?: return true
        val f = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (now.value?.playing == true) { pause(); pausedForFocus = true }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { mp?.setVolume(0.25f, 0.25f) }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        runCatching { mp?.setVolume(1f, 1f) }
                        if (pausedForFocus) resume()
                    }
                }
            }.build().also { focus = it }
        return m.requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}

/** An audio file in a chat: title, artist, length, play / pause and a seek bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioBubble(vm: NodeSession, m: Msg, progress: Float?, mine: Boolean, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val now by AudioPlayer.now.collectAsStateWithLifecycle()
    val pos by AudioPlayer.position.collectAsStateWithLifecycle()
    val stored by produceState(false, m.audio, progress) {
        value = (mine || progress == null) && withContext(Dispatchers.IO) { vm.hasAudio(m) }
    }
    val loaded = now?.id == m.audio
    val playing = loaded && now?.playing == true
    val total = m.durMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.width(260.dp).combinedClickable(onClick = {}, onLongClick = onLongClick)
            .semantics { contentDescription = "Audio file: ${m.aTitle}" + (if (m.aArtist.isNotBlank()) " by ${m.aArtist}" else "") + ", ${fmtDur(m.durMs)}" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton(if (playing) "PAUSE" else "PLAY", Modifier.width(72.dp), color = if (stored) Holo.Primary else Holo.Muted,
                description = if (playing) "Pause ${m.aTitle}" else "Play ${m.aTitle}") {
                if (stored) AudioPlayer.toggle(ctx, vm, m)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.aTitle.ifBlank { "Audio" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.aArtist.isNotBlank()) Text(m.aArtist, fontSize = 14.sp, color = Holo.InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (loaded) {
            Slider(
                value = dragging ?: (pos.toFloat() / total).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { AudioPlayer.seekTo((it * total).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Holo.Primary, activeTrackColor = Holo.Primary, inactiveTrackColor = Holo.Primary.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth().height(36.dp).semantics { stateDescription = "${fmtDur(pos)} of ${fmtDur(m.durMs)}" }
            )
        } else Spacer(Modifier.height(6.dp))
        MonoLabel("AUD · " + (if (loaded) "${fmtDur(dragging?.let { (it * total).toLong() } ?: pos)} / " else "") + fmtDur(m.durMs) +
            (if (m.aSize > 0) " · ${fmtSize(m.aSize)}" else "") + (m.aName.substringAfterLast('.', "").takeIf { it.length in 2..5 }?.let { " · ${it.uppercase()}" } ?: ""))
        TransferBar(progress, mine, vm, m)
        if (!stored && progress == null) {
            Text("Audio no longer stored on this phone", style = MaterialTheme.typography.labelSmall, color = Holo.InkDim, fontStyle = FontStyle.Italic)
        }
    }
}

/** A thin "now playing" strip shown on every screen while music is loaded. */
@Composable
fun NowPlayingBar() {
    val now by AudioPlayer.now.collectAsStateWithLifecycle()
    val pos by AudioPlayer.position.collectAsStateWithLifecycle()
    val n = now ?: return
    Column(Modifier.fillMaxWidth().background(Holo.Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GhostButton(if (n.playing) "PAUSE" else "PLAY", Modifier.width(72.dp), height = 40, color = Holo.Primary,
                description = if (n.playing) "Pause ${n.title}" else "Play ${n.title}") {
                if (n.playing) AudioPlayer.pause() else AudioPlayer.resume()
            }
            Column(Modifier.weight(1f)) {
                Text(n.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MonoLabel((if (n.artist.isNotBlank()) n.artist.uppercase() + " · " else "") + "${fmtDur(pos)} / ${fmtDur(n.durMs)}", Holo.InkDim, size = 10)
            }
            GhostButton("✕", Modifier.width(44.dp), height = 40, color = Holo.Muted, description = "Stop playing") { AudioPlayer.stop() }
        }
        LinearProgressIndicator(
            progress = { (pos.toFloat() / n.durMs.coerceAtLeast(1L)).coerceIn(0f, 1f) },
            color = Holo.Primary, trackColor = Holo.Primary.copy(alpha = 0.15f), strokeCap = StrokeCap.Butt,
            modifier = Modifier.fillMaxWidth().height(2.dp)
        )
    }
}
