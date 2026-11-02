package com.cybertxts.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Is the screen open right now? Used to decide whether to show message notifications. */
object AppState {
    @Volatile var foreground = false
    /** App lock is on with "hide message text in alerts": notifications say only that something arrived. */
    @Volatile var hideAlertText = false
}

object Notifier {
    private const val CH_SESSION = "session"
    const val CH_MESSAGES = "messages_chirp"
    private const val ID_MESSAGES = 2
    private var unread = 0

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, "Node link", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while CyberTxTs holds your nodes linked"
            })
                val chirp = Uri.parse("android.resource://${ctx.packageName}/${R.raw.comm_chirp}")
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New messages while the app is in the background"
                setSound(
                    chirp,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            })
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun session(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SESSION)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle("CyberTxTs")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @Synchronized
    fun message(ctx: Context, node: String, sender: String, text: String, tag: String = "", dm: String? = null) {
        unread++
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hidden = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle("CyberTxTs")
            .setContentText("New message")
            .build()
        val masked = AppState.hideAlertText
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle(if (masked) "CyberTxTs" else if (unread == 1) "$sender · $node" else "$unread new messages")
            .setContentText(if (masked) (if (unread == 1) "New transmission" else "$unread new transmissions")
                            else if (unread == 1) text else "Latest from $sender")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (masked) "Unlock CyberTxTs to read it." else text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)   // text hidden on the lock screen
            .setPublicVersion(hidden)
        // Reply / mark read / mute right from the notification (not while app lock is on: that would bypass it)
        if (tag.isNotEmpty() && !AppLock.enabled.value) {
            fun action(kind: String, req: Int) = PendingIntent.getBroadcast(
                ctx, req,
                Intent(ctx, NotifyActions::class.java).setAction("cybertxts.$kind").putExtra("tag", tag).putExtra("dm", dm ?: ""),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (kind == "reply") PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            )
            val input = RemoteInput.Builder(NotifyActions.KEY_TEXT).setLabel("Transmit to $sender").build()
            n.addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_node, "Reply", action("reply", 11))
                .addRemoteInput(input).setAllowGeneratedReplies(false).setShowsUserInterface(false).build())
            n.addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_node, "Mark read", action("read", 12)).build())
            if (dm == null) n.addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_node, "Mute node", action("mute", 13)).build())
        }
        runCatching { nm.notify(ID_MESSAGES, n.build()) }
    }

    /** After replying from the notification: show it was sent, quietly. */
    @Synchronized
    fun replied(ctx: Context, text: String) {
        unread = 0
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_node).setContentTitle("Transmitted").setContentText(text)
            .setOnlyAlertOnce(true).setSilent(true).setTimeoutAfter(4_000).setContentIntent(openApp(ctx)).setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID_MESSAGES, n) }
    }

    @Synchronized
    fun clearMessages(ctx: Context) {
        unread = 0
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_MESSAGES)
    }
}

/**
 * Foreground service: keeps CyberTxTs alive and connected while the screen is off or the app is in the
 * background. Holds a CPU wake lock and a WiFi lock so heartbeats keep flowing. If Android ever kills it,
 * START_STICKY brings it back and your saved nodges are rejoined automatically.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires us to go foreground quickly, so do it before anything else
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, Notifier.session(this, "Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val vm = (application as CyberApp).chatVm()   // also restores saved nodges if the app was restarted
        if (!vm.wantsSession()) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        if (!collecting) {
            collecting = true
            scope.launch {
                combine(vm.status, vm.joining) { st, j -> st to j }.collect { (st, j) ->
                    val count = st.first
                    val others = st.second
                    if (count == 0 && !j) {
                        stopSelf()
                    } else if (count > 0) {
                        val text = (if (count == 1) "1 node" else "$count nodes") + " · $others linked"
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIF_ID, Notifier.session(this@SessionService, text))
                    }
                }
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cybertxts:session")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cybertxts:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { wake?.release() }
        runCatching { wifiLock?.release() }
        wake = null; wifiLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
        @Volatile var running = false

        fun start(ctx: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
            } catch (e: Exception) { /* Android refused a background start; the screen-on path will retry */ }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}


/** Handles Reply / Mark read / Mute from a message notification. */
class NotifyActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as? CyberApp ?: return
        val vm = app.chatVm()
        val tag = intent.getStringExtra("tag") ?: return
        val dm = intent.getStringExtra("dm")?.ifEmpty { null }
        val s = vm.sessionByTag(tag) ?: return
        when (intent.action) {
            "cybertxts.reply" -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    s.send(text, null, dm)
                    s.markRead(dm); s.unread.value = 0
                    Notifier.replied(ctx, text)
                }
            }
            "cybertxts.read" -> { s.markRead(dm); s.unread.value = 0; Notifier.clearMessages(ctx) }
            "cybertxts.mute" -> { s.setMuted(true); Notifier.clearMessages(ctx) }
        }
    }

    companion object { const val KEY_TEXT = "reply_text" }
}
