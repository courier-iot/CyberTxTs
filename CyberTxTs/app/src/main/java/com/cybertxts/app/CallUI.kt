package com.cybertxts.app

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

fun callPermissions(video: Boolean): Array<String> =
    if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

/** Runs an action once the needed permissions are granted (asking if necessary). */
@Composable
fun rememberPermissionGate(): (Array<String>, () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val p = pending; pending = null
        if (res.values.all { it }) p?.invoke()
        else Toast.makeText(ctx, "Calls need the microphone (and the camera for video).", Toast.LENGTH_LONG).show()
    }
    return { perms, action ->
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) action()
        else { pending = action; AppLock.holdOpen(); launcher.launch(perms) }
    }
}

@Composable
private fun VideoView(sink: ProxySink, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(CallMedia.egl.eglBaseContext, null)
                setMirror(mirror)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                if (overlay) setZOrderMediaOverlay(true)
                sink.target = this
            }
        },
        onRelease = { r -> if (sink.target === r) sink.target = null; runCatching { r.release() } }
    )
}

/** Full-screen call: ringing, calling, in call. Shown above everything while a call exists. */
@Composable
fun CallOverlay(vm: ChatViewModel) {
    val c by vm.calls.ui.collectAsStateWithLifecycle()
    val engine by vm.calls.engineState.collectAsStateWithLifecycle()
    val call = c ?: return
    val gate = rememberPermissionGate()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (call.phase == "active") { now = System.currentTimeMillis(); delay(1_000) } }

    val status = when (call.phase) {
        "ringing" -> if (call.video) "INCOMING VIDEO CALL" else "INCOMING CALL"
        "calling" -> "CALLING…"
        "connecting" -> "CONNECTING…"
        "reconnecting" -> "RECONNECTING…"
        "active" -> fmtDur(now - call.startedAt)
        else -> (call.why ?: "CALL ENDED").uppercase()
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Holo.Void)) {
            val e = engine
            if (call.video && call.remoteVideo && e != null && call.phase != "ended") {
                VideoView(e.remoteSink, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
            } else {
                Starfield(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Orbit(120.dp, Holo.Primary, spin = call.phase == "ringing" || call.phase == "calling")
                }
            }
            if (call.video && call.camOn && e?.videoTrack != null && call.phase != "ended") {
                VideoView(e.localSink, mirror = true, overlay = true,
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp).size(width = 108.dp, height = 156.dp))
            }
            Column(
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 40.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(call.name, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Holo.Ink, textAlign = TextAlign.Center)
                MonoLabel(opTag(vm.people.alias(call.peer, call.name), call.peer) + " · SEALED CALL", Holo.Primary, size = 12)
                Spacer(Modifier.height(8.dp))
                Text(status, fontFamily = Mono, fontSize = 15.sp, letterSpacing = 0.1.em,
                    color = if (call.phase == "ended") Holo.Accent else Holo.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (call.phase) {
                    "ringing" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CallButton("Decline", Holo.Danger, Modifier.weight(1f)) { vm.calls.decline() }
                        CallButton(if (call.video) "Voice only" else "Answer", Holo.Primary, Modifier.weight(1f), solid = !call.video) {
                            gate(callPermissions(false)) { vm.calls.accept(false) }
                        }
                        if (call.video) CallButton("Video", Holo.Primary, Modifier.weight(1f), solid = true) {
                            gate(callPermissions(true)) { vm.calls.accept(true) }
                        }
                    }
                    "ended" -> {}
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CallButton(if (call.muted) "Unmute" else "Mute", if (call.muted) Holo.Accent else Holo.Primary, Modifier.weight(1f)) { vm.calls.toggleMute() }
                            CallButton(if (call.speaker) "Earpiece" else "Speaker", Holo.Primary, Modifier.weight(1f)) { vm.calls.toggleSpeaker() }
                            if (call.video) {
                                CallButton(if (call.camOn) "Cam off" else "Cam on", Holo.Primary, Modifier.weight(1f)) { vm.calls.toggleCamera() }
                                CallButton("Flip", Holo.Primary, Modifier.weight(1f)) { vm.calls.switchCamera() }
                            }
                        }
                        CallButton("End call", Holo.Danger, Modifier.fillMaxWidth(), solid = true) { vm.calls.hangUp() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallButton(label: String, color: Color, modifier: Modifier, solid: Boolean = false, onClick: () -> Unit) {
    if (solid) {
        androidx.compose.material3.Button(
            onClick = onClick, modifier = modifier.heightIn(min = 56.dp), shape = cutShape(9.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = color, contentColor = Holo.Void)
        ) { Text(label.uppercase(), fontFamily = Mono, fontSize = 14.sp, letterSpacing = 0.08.em, maxLines = 1) }
    } else GhostButton(label, modifier, color = color, height = 56, onClick = onClick)
}
