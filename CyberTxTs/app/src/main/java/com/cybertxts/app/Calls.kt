package com.cybertxts.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import kotlin.coroutines.resume

/*
 * Voice and video calls, 1-to-1, with any contact or any operative in a node you share.
 *
 * The call is its own WebRTC connection straight between the two phones (same STUN setup as internet links),
 * encrypted end to end by WebRTC (DTLS-SRTP). The offer and answer, which carry each side's encryption
 * fingerprint, travel over the node's already-encrypted links and are signed with your identity key, so nobody
 * else in the node can slip into the middle of a call.
 */

/** The media engine: audio device, camera and video codecs, shared by every call. */
object CallMedia {
    val egl: EglBase by lazy { EglBase.create() }
    @Volatile private var factory: PeerConnectionFactory? = null

    fun factory(ctx: Context): PeerConnectionFactory = synchronized(this) {
        factory ?: run {
            Rtc.factory(ctx)            // makes sure WebRTC is initialised
            val adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory().also { factory = it }
        }
    }
}

/** Lets the screen attach and detach a renderer without touching the call. */
class ProxySink : VideoSink {
    @Volatile var target: VideoSink? = null
    override fun onFrame(frame: VideoFrame) { target?.onFrame(frame) }
}

/** One call's peer connection, microphone and (optionally) camera. */
class CallEngine(ctx: Context, video: Boolean, private val onIce: (PeerConnection.IceConnectionState) -> Unit, private val onRemoteVideo: () -> Unit) {
    private val app = ctx.applicationContext
    private val f = CallMedia.factory(app)
    val localSink = ProxySink()
    val remoteSink = ProxySink()
    private val gathered = CompletableDeferred<Unit>()
    private val audioSource: AudioSource = f.createAudioSource(MediaConstraints())
    val audioTrack: AudioTrack = f.createAudioTrack("a0", audioSource)
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null; private set
    @Volatile private var disposed = false

    private val pc: PeerConnection? = f.createPeerConnection(
        PeerConnection.RTCConfiguration(Rtc.iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        },
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { onIce(state) }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val t = receiver.track()
                if (t is VideoTrack) { t.addSink(remoteSink); onRemoteVideo() }
            }
        }
    )

    init {
        pc?.addTrack(audioTrack, listOf("cybertxts"))
        if (video) startCamera()
    }

    val ok: Boolean get() = pc != null

    private fun startCamera() {
        runCatching {
            val en = Camera2Enumerator(app)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
            val cap = en.createCapturer(name, null) ?: return
            val h = SurfaceTextureHelper.create("cybertxts-cam", CallMedia.egl.eglBaseContext)
            val vs = f.createVideoSource(false)
            cap.initialize(h, app, vs.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val vt = f.createVideoTrack("v0", vs)
            vt.addSink(localSink)
            pc?.addTrack(vt, listOf("cybertxts"))
            capturer = cap; helper = h; videoSource = vs; videoTrack = vt
        }
    }

    fun setMuted(muted: Boolean) { runCatching { audioTrack.setEnabled(!muted) } }
    fun setCamera(on: Boolean) { runCatching { videoTrack?.setEnabled(on) } }
    fun switchCamera() { runCatching { capturer?.switchCamera(null) } }

    // ---- SDP: wait for all candidates, then send one complete description (like internet links) ----

    private suspend fun create(offer: Boolean): SessionDescription? = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resume(null) }
            override fun onSetFailure(error: String?) {}
        }
        val p = pc ?: run { cont.resume(null); return@suspendCancellableCoroutine }
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        if (offer) p.createOffer(obs, mc) else p.createAnswer(obs, mc)
    }

    private suspend fun set(local: Boolean, sdp: SessionDescription): Boolean = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resume(false) }
        }
        val p = pc ?: run { cont.resume(false); return@suspendCancellableCoroutine }
        if (local) p.setLocalDescription(obs, sdp) else p.setRemoteDescription(obs, sdp)
    }

    private suspend fun gathered(): String? {
        withTimeoutOrNull(7_000) { gathered.await() }
        return pc?.localDescription?.description
    }

    suspend fun makeOffer(): String? {
        val o = create(true) ?: return null
        return if (set(true, o)) gathered() else null
    }

    suspend fun answer(remote: String): String? {
        if (!set(false, SessionDescription(SessionDescription.Type.OFFER, remote))) return null
        val a = create(false) ?: return null
        return if (set(true, a)) gathered() else null
    }

    suspend fun acceptAnswer(remote: String): Boolean = set(false, SessionDescription(SessionDescription.Type.ANSWER, remote))

    fun dispose() {
        if (disposed) return
        disposed = true
        localSink.target = null; remoteSink.target = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource.dispose() }
        runCatching { helper?.dispose() }
    }
}

/** What the call screen shows. */
data class CallUi(
    val id: String, val peer: String, val name: String, val video: Boolean, val incoming: Boolean,
    val phase: String,                      // ringing (incoming), calling, connecting, active, reconnecting, ended
    val startedAt: Long = 0L, val muted: Boolean = false, val speaker: Boolean = false,
    val camOn: Boolean = true, val remoteVideo: Boolean = false, val why: String? = null
)

class CallManager(
    private val app: Context,
    private val scope: CoroutineScope,
    private val nameOf: (String) -> String
) {
    val ui = kotlinx.coroutines.flow.MutableStateFlow<CallUi?>(null)
    @Volatile var engine: CallEngine? = null; private set
    /** The current call's media (the screen attaches its video views to it). */
    val engineState = kotlinx.coroutines.flow.MutableStateFlow<CallEngine?>(null)
    private var session: NodeSession? = null
    private var pendingOffer: String? = null
    private var timeout: Job? = null
    private var ringtone: Ringtone? = null
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun update(f: (CallUi) -> CallUi) { ui.value?.let { ui.value = f(it) } }

    private fun newEngine(video: Boolean): CallEngine = CallEngine(app, video,
        onIce = { st ->
            when (st) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                    update { if (it.phase == "active") it else it.copy(phase = "active", startedAt = if (it.startedAt > 0) it.startedAt else System.currentTimeMillis()) }
                PeerConnection.IceConnectionState.DISCONNECTED -> update { it.copy(phase = "reconnecting") }
                PeerConnection.IceConnectionState.FAILED -> scope.launch { finish("Connection lost", notify = true) }
                else -> {}
            }
        },
        onRemoteVideo = { update { it.copy(remoteVideo = true) } }
    )

    private fun audioOn(speaker: Boolean) {
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = speaker
        }
    }

    private fun audioOff() {
        runCatching {
            audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    /** Ring someone. Needs the microphone (and camera for video) permission first. */
    fun start(s: NodeSession, peer: String, video: Boolean) {
        if (ui.value != null) return
        val id = UUID.randomUUID().toString()
        session = s
        ui.value = CallUi(id, peer, nameOf(peer), video, incoming = false, phase = "calling", camOn = video, speaker = video)
        scope.launch(Dispatchers.IO) {
            val e = newEngine(video)
            engine = e; engineState.value = e
            if (!e.ok) { finish("Couldn't start the call on this phone"); return@launch }
            audioOn(video)
            val sdp = e.makeOffer() ?: run { finish("Couldn't start the call"); return@launch }
            if (ui.value?.id != id) return@launch
            s.sendCallSignal(peer, JSONObject().put("k", "offer").put("cid", id).put("sdp", sdp).put("video", video))
            timeout = scope.launch { delay(45_000); if (ui.value?.id == id && ui.value?.phase == "calling") finish("No answer", signal = true) }
        }
    }

    /** A call frame arrived in some node or contact chat. */
    fun onSignal(s: NodeSession, j: JSONObject) {
        val p = s.openCallSignal(j) ?: return
        val from = j.optString("sid")
        val cid = p.optString("cid")
        val cur = ui.value
        when (p.optString("k")) {
            "offer" -> {
                if (cur != null) {
                    if (cur.id != cid) s.sendCallSignal(from, JSONObject().put("k", "end").put("cid", cid).put("why", "busy"))
                    return
                }
                session = s
                pendingOffer = p.optString("sdp")
                val video = p.optBoolean("video")
                ui.value = CallUi(cid, from, nameOf(from), video, incoming = true, phase = "ringing", camOn = video, speaker = video)
                ring(true)
                if (!AppState.foreground) incomingNotification(nameOf(from), video)
                timeout = scope.launch { delay(45_000); if (ui.value?.id == cid && ui.value?.phase == "ringing") finish("Missed call", signal = false, notify = true) }
            }
            "answer" -> {
                if (cur == null || cur.id != cid || cur.incoming || from != cur.peer) return
                timeout?.cancel()
                update { it.copy(phase = "connecting") }
                scope.launch(Dispatchers.IO) {
                    if (engine?.acceptAnswer(p.optString("sdp")) != true) finish("Call failed", signal = true)
                }
            }
            "end" -> {
                if (cur == null || cur.id != cid || from != cur.peer) return
                val why = when (p.optString("why")) {
                    "busy" -> "Busy on another call"; "declined" -> "Declined"; else -> if (cur.phase == "ringing") "Missed call" else "Call ended"
                }
                scope.launch { finish(why, signal = false, notify = cur.phase == "ringing") }
            }
        }
    }

    /** Pick up. [withVideo] false answers a video call with voice only. */
    fun accept(withVideo: Boolean) {
        val cur = ui.value ?: return
        val offer = pendingOffer ?: return
        val s = session ?: return
        if (!cur.incoming || cur.phase != "ringing") return
        ring(false); timeout?.cancel(); clearIncomingNotification()
        val video = cur.video && withVideo
        ui.value = cur.copy(phase = "connecting", video = video, camOn = video, speaker = video)
        scope.launch(Dispatchers.IO) {
            val e = newEngine(video)
            engine = e; engineState.value = e
            audioOn(video)
            val sdp = e.answer(offer) ?: run { finish("Call failed", signal = true); return@launch }
            s.sendCallSignal(cur.peer, JSONObject().put("k", "answer").put("cid", cur.id).put("sdp", sdp))
        }
    }

    fun decline() {
        val cur = ui.value ?: return
        session?.sendCallSignal(cur.peer, JSONObject().put("k", "end").put("cid", cur.id).put("why", "declined"))
        scope.launch { finish(null) }
    }

    fun hangUp() { scope.launch { finish(null, signal = true) } }

    fun toggleMute() { val c = ui.value ?: return; engine?.setMuted(!c.muted); update { it.copy(muted = !c.muted) } }
    fun toggleCamera() { val c = ui.value ?: return; engine?.setCamera(!c.camOn); update { it.copy(camOn = !c.camOn) } }
    fun switchCamera() { engine?.switchCamera() }
    fun toggleSpeaker() {
        val c = ui.value ?: return
        runCatching { audio.isSpeakerphoneOn = !c.speaker }
        update { it.copy(speaker = !c.speaker) }
    }

    private suspend fun finish(why: String?, signal: Boolean = false, notify: Boolean = false) {
        val cur = ui.value ?: return
        timeout?.cancel(); ring(false); clearIncomingNotification()
        if (signal) session?.sendCallSignal(cur.peer, JSONObject().put("k", "end").put("cid", cur.id))
        val e = engine; engine = null; engineState.value = null; pendingOffer = null
        kotlinx.coroutines.withContext(Dispatchers.IO) { e?.dispose() }
        audioOff()
        if (why != null) {
            ui.value = cur.copy(phase = "ended", why = why)
            if (notify && !AppState.foreground) missedNotification(cur.name, why)
            delay(1_800)
        }
        if (ui.value?.id == cur.id) ui.value = null
        session = null
    }

    // ---- ringing and call notifications ----

    private fun ring(on: Boolean) {
        runCatching {
            if (on) {
                ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    play()
                }
            } else { ringtone?.stop(); ringtone = null }
        }
    }

    private fun channel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CH_CALLS) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and missed calls"
                setSound(null, null)       // the app plays the ringtone itself
            })
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 7, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun incomingNotification(name: String, video: Boolean) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(nm)
        val n = NotificationCompat.Builder(app, CH_CALLS).setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle(if (AppState.hideAlertText) "Incoming call" else "Incoming ${if (video) "video " else ""}call · $name")
            .setContentText("Tap to answer in CyberTxTs").setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX).setContentIntent(openApp()).setAutoCancel(true).setOngoing(true)
            .build()
        runCatching { nm.notify(ID_CALL, n) }
    }

    private fun clearIncomingNotification() {
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_CALL) }
    }

    private fun missedNotification(name: String, why: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(nm)
        val n = NotificationCompat.Builder(app, CH_CALLS).setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle(if (AppState.hideAlertText) why else "$why · $name").setContentIntent(openApp()).setAutoCancel(true).build()
        runCatching { nm.notify(ID_CALL + 1, n) }
    }

    companion object {
        private const val CH_CALLS = "calls"
        private const val ID_CALL = 4100
    }
}
