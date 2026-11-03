package com.cybertxts.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Every sound CyberTxTs makes, and the choices behind them.
 *
 * A sound setting is one of:
 *   a bundled tone's key ("chirp", "comm", ...), "default" (the phone's own default), "none" (silent),
 *   or "uri:<content uri>" (a tone picked from the phone's sounds).
 * Message alerts go through Android notification channels (so Do Not Disturb and per-app settings still apply);
 * each tone gets its own channel, because a channel's sound can't be changed once it exists.
 */

data class SoundConf(
    val msgTone: String = "chirp",
    val callTone: String = "comm",
    val callVibrate: Boolean = true,
    val callTones: Boolean = true,       // ringback while calling, connect / end / mute tones
    val chatSounds: Boolean = false,     // quiet sent / received sounds inside an open chat
    val perChat: Map<String, String> = emptyMap()   // node or contact tag -> message tone
)

object Sounds {
    data class Tone(val key: String, val label: String, val res: Int)

    val messageTones = listOf(
        Tone("chirp", "Comm chirp", R.raw.comm_chirp),
        Tone("pulse", "Pulse", R.raw.tone_pulse),
        Tone("beacon", "Beacon", R.raw.tone_beacon),
        Tone("sonar", "Sonar", R.raw.tone_sonar),
        Tone("glint", "Glint", R.raw.tone_glint)
    )
    val ringtones = listOf(
        Tone("comm", "Comm ring", R.raw.ring_comm),
        Tone("orbit", "Orbit", R.raw.ring_orbit)
    )

    val conf = MutableStateFlow(SoundConf())
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("sounds", Context.MODE_PRIVATE)
        prefs = p
        val per = p.all.filterKeys { it.startsWith("chat_") }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix("chat_") to it } }.toMap()
        conf.value = SoundConf(
            msgTone = p.getString("msg", "chirp") ?: "chirp",
            callTone = p.getString("call", "comm") ?: "comm",
            callVibrate = p.getBoolean("vib", true),
            callTones = p.getBoolean("ctones", true),
            chatSounds = p.getBoolean("chat", false),
            perChat = per
        )
    }

    fun update(c: SoundConf) {
        val old = conf.value
        conf.value = c
        prefs?.edit()?.apply {
            putString("msg", c.msgTone); putString("call", c.callTone)
            putBoolean("vib", c.callVibrate); putBoolean("ctones", c.callTones); putBoolean("chat", c.chatSounds)
            old.perChat.keys.filter { it !in c.perChat }.forEach { remove("chat_$it") }
            c.perChat.forEach { (k, v) -> putString("chat_$k", v) }
        }?.apply()
    }

    /** A chat's own message tone (null = use the default). */
    fun setChatTone(tag: String, value: String?) {
        val c = conf.value
        update(c.copy(perChat = if (value == null) c.perChat - tag else c.perChat + (tag to value)))
    }

    // ---------- names and sources ----------

    fun uriOf(ctx: Context, value: String, ring: Boolean): Uri? = when {
        value == "none" -> null
        value == "default" -> RingtoneManager.getDefaultUri(if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
        value.startsWith("uri:") -> runCatching { Uri.parse(value.removePrefix("uri:")) }.getOrNull()
        else -> (if (ring) ringtones else messageTones).firstOrNull { it.key == value }?.let { raw(ctx, it.res) }
            ?: raw(ctx, if (ring) R.raw.ring_comm else R.raw.comm_chirp)
    }

    private fun raw(ctx: Context, res: Int) = Uri.parse("android.resource://${ctx.packageName}/$res")

    fun label(ctx: Context, value: String, ring: Boolean): String = when {
        value == "none" -> "Silent"
        value == "default" -> "Phone default"
        value.startsWith("uri:") -> runCatching {
            RingtoneManager.getRingtone(ctx, Uri.parse(value.removePrefix("uri:")))?.getTitle(ctx)
        }.getOrNull() ?: "From your phone"
        else -> (if (ring) ringtones else messageTones).firstOrNull { it.key == value }?.label ?: if (ring) "Comm ring" else "Comm chirp"
    }

    // ---------- message alerts ----------

    /** The notification channel for a chat's alerts, made on first use. */
    fun channelFor(ctx: Context, tag: String): String {
        val c = conf.value
        val v = c.perChat[tag] ?: c.msgTone
        if (v == "chirp") return Notifier.CH_MESSAGES
        val id = "msg_" + Crypto.sha256Hex(v.toByteArray()).take(12)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) runCatching {
            nm.createNotificationChannel(NotificationChannel(id, "Messages · ${label(ctx, v, false)}", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New messages, with this alert tone"
                val u = uriOf(ctx, v, false)
                if (u == null) setSound(null, null)
                else setSound(u, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
        }
        return if (nm.getNotificationChannel(id) != null) id else Notifier.CH_MESSAGES
    }

    // ---------- incoming call ----------

    private var ringer: MediaPlayer? = null
    private var vibrating: Vibrator? = null

    private fun quietNow(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val f = nm.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    /** Ring for an incoming call: the chosen ringtone (looping) and vibration, following silent mode and Do Not Disturb. */
    fun startRinging(ctx: Context) {
        stopRinging()
        val c = conf.value
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dnd = quietNow(ctx)
        val mode = am.ringerMode
        val sound = !dnd && mode == AudioManager.RINGER_MODE_NORMAL && c.callTone != "none"
        val buzz = !dnd && mode != AudioManager.RINGER_MODE_SILENT && c.callVibrate
        if (sound) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            fun tryPlay(u: Uri?): Boolean = u != null && runCatching {
                val p = MediaPlayer()
                p.setAudioAttributes(attrs)
                p.setDataSource(ctx, u)
                p.isLooping = true
                p.setOnPreparedListener { it.start() }
                p.prepareAsync()
                ringer = p
            }.isSuccess
            if (!tryPlay(uriOf(ctx, c.callTone, true))) tryPlay(raw(ctx, R.raw.ring_comm))
        }
        if (buzz) runCatching {
            val v = vibrator(ctx)
            if (v != null && v.hasVibrator()) {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                vibrating = v
            }
        }
    }

    fun stopRinging() {
        runCatching { ringer?.stop() }
        runCatching { ringer?.release() }
        ringer = null
        runCatching { vibrating?.cancel() }
        vibrating = null
    }

    // ---------- in-call tones ----------

    private var ringback: MediaPlayer? = null
    private val callAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** The "it's ringing at their end" tone while you wait for them to answer. */
    fun ringback(ctx: Context, on: Boolean) {
        if (!on || !conf.value.callTones) {
            runCatching { ringback?.stop() }; runCatching { ringback?.release() }; ringback = null
            return
        }
        if (ringback != null) return
        ringback = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(callAttrs)
                setDataSource(ctx, raw(ctx, R.raw.call_ringback))
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.getOrNull()
    }

    /** "connect", "end", "mute" or "unmute". */
    fun callTone(ctx: Context, which: String) {
        if (!conf.value.callTones) return
        val res = when (which) {
            "connect" -> R.raw.call_connect; "end" -> R.raw.call_end
            "mute" -> R.raw.call_mute; else -> R.raw.call_unmute
        }
        playOnce(ctx, raw(ctx, res), callAttrs)
    }

    // ---------- in-chat ----------

    /** A quiet sound when you send, or something arrives, in the chat that's open. Only in normal ringer mode. */
    fun chat(ctx: Context, sent: Boolean) {
        if (!conf.value.chatSounds) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL || quietNow(ctx)) return
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) return    // not during a call
        playOnce(ctx, raw(ctx, if (sent) R.raw.chat_sent else R.raw.chat_recv),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }

    // ---------- previews and one-shots ----------

    private var preview: MediaPlayer? = null

    /** Play a tone once so you can hear it before choosing. */
    fun preview(ctx: Context, value: String, ring: Boolean) {
        stopPreview()
        val u = uriOf(ctx, value, ring) ?: return
        preview = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (ring) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, u)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopPreview() }
                prepareAsync()
            }
        }.getOrNull()
    }

    fun stopPreview() {
        runCatching { preview?.stop() }; runCatching { preview?.release() }; preview = null
    }

    private fun playOnce(ctx: Context, u: Uri, attrs: AudioAttributes) {
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(ctx, u)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepareAsync()
            }
        }
    }
}
