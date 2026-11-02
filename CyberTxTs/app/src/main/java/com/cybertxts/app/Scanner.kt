package com.cybertxts.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * QR invite scanning, with no Google services involved.
 * The camera feed comes from ZXing Android Embedded (open source, runs entirely inside the app);
 * everything drawn on top of it (frame, sweep line, text, buttons) is ours.
 */

/** Fixed scan-screen colours: the camera screen is always dark, even on Day shift. */
private val ScanCyan = Color(0xFF00E5FF)
private val ScanCrimson = Color(0xFFFF2A4D)
private val ScanInk = Color(0xFFE6F1FF)
private val ScanDim = Color(0xFF9AA6CC)
private val ScanShade = Color(0xD907060F)
private val ScanVoid = Color(0xFF07060F)

/** Size of the square scan window, in dp. The camera only decodes what's inside it. */
private const val WINDOW_DP = 260

/** Reads a QR code out of a picture (gallery screenshot or photo). Works with light-on-dark codes too. */
object QrDecode {
    fun fromUri(ctx: Context, uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            bmp?.let { b -> fromBitmap(b).also { b.recycle() } }
        }
    } catch (e: Exception) { null }

    fun fromBitmap(b: Bitmap): String? {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val src: LuminanceSource = RGBLuminanceSource(b.width, b.height, px)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        val reader = QRCodeReader()
        for (s in listOf(src, src.invert())) {
            try { return reader.decode(BinaryBitmap(HybridBinarizer(s)), hints).text } catch (e: Exception) { reader.reset() }
        }
        return null
    }
}

/**
 * Full-screen scanner. Calls [onInvite] once with the first valid CyberTxTs invite it sees,
 * either from the camera or from a picture picked from the gallery.
 */
@Composable
fun ScanInviteScreen(onInvite: (Invite) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var lastBad by remember { mutableStateOf<String?>(null) }

    fun handle(text: String?, fromGallery: Boolean) {
        if (done) return
        val inv = InviteLink.parse(text)
        if (inv != null) {
            done = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onInvite(inv)
        } else if (fromGallery) {
            warning = if (text == null) "NO QR CODE FOUND IN THAT PICTURE" else "NOT A CYBERTXTS INVITE"
        } else if (text != lastBad) {
            lastBad = text
            warning = "NOT A CYBERTXTS INVITE"
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted; asked = true
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { QrDecode.fromUri(ctx, uri) }
            handle(text, fromGallery = true)
        }
    }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(warning) { if (warning != null) { delay(2600); warning = null } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(ScanVoid)) {
            if (hasCamera) CameraFeed(torch = torch, onText = { handle(it, fromGallery = false) })
            else NoCamera(asked = asked, onAsk = { permission.launch(Manifest.permission.CAMERA) })

            if (hasCamera) ScanOverlay()

            // Header
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("SCAN INVITE", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    letterSpacing = 0.1.em, color = ScanCyan)
                Spacer(Modifier.height(2.dp))
                Text("QR · NODE CODE + ACCESS KEY + HOST FINGERPRINT", fontFamily = Mono, fontSize = 11.sp,
                    letterSpacing = 0.08.em, color = ScanDim)
            }

            // Footer
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val w = warning
                Text(
                    w ?: if (hasCamera) "ALIGN THE INVITE QR INSIDE THE FRAME" else "OR PICK A SCREENSHOT OF THE INVITE",
                    fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.08.em,
                    color = if (w != null) ScanCrimson else ScanInk, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasCamera) ScanButton(if (torch) "LIGHT ON" else "LIGHT", ScanCyan, Modifier.weight(1f)) { torch = !torch }
                    ScanButton("FROM GALLERY", ScanCyan, Modifier.weight(1.4f)) {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    ScanButton("CANCEL", ScanCrimson, Modifier.weight(1f), onClose)
                }
            }
        }
    }
}

/** The live camera feed. Starts and stops with the screen so the camera is never left running. */
@Composable
private fun CameraFeed(torch: Boolean, onText: (String?) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val windowPx = with(LocalDensity.current) { WINDOW_DP.dp.roundToPx() }
    val view = remember { mutableStateOf<BarcodeView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera viewfinder" },
        factory = { c ->
            BarcodeView(c).apply {
                // MIXED_SCAN (2): reads normal and light-on-dark (inverted) QR codes.
                setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE), null, null, 2))
                setFramingRectSize(com.journeyapps.barcodescanner.Size(windowPx, windowPx))
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) { onText(result.text) }
                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
                })
                view.value = this
            }
        }
    )
    LaunchedEffect(torch) { view.value?.setTorch(torch) }
    // Adding the observer while the screen is already open delivers ON_RESUME straight away, which starts the camera.
    val v = view.value
    DisposableEffect(owner, v) {
        if (v == null) return@DisposableEffect onDispose { }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { v.resume(); if (torch) v.setTorch(true) }
                Lifecycle.Event.ON_PAUSE -> v.pause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); v.pause() }
    }
}

/** Shaded surround, open-corner frame and a sweeping scan line, in the Holo Glass style. */
@Composable
private fun ScanOverlay() {
    val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "sweep"
    )
    Canvas(Modifier.fillMaxSize()) {
        val side = WINDOW_DP.dp.toPx()
        val win = Rect(Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side))

        // Shade everything except the window.
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(win)
        }
        drawPath(shade, ScanShade)

        // Faint window outline, then bright open corners (gaps in the middle of each side, like the app's panels).
        drawRect(ScanCyan.copy(alpha = 0.22f), win.topLeft, win.size, style = Stroke(1.dp.toPx()))
        val arm = side * 0.2f
        val w = 3.dp.toPx()
        fun corner(o: Offset, dx: Float, dy: Float) {
            drawLine(ScanCyan, o, Offset(o.x + dx * arm, o.y), w, StrokeCap.Square)
            drawLine(ScanCyan, o, Offset(o.x, o.y + dy * arm), w, StrokeCap.Square)
        }
        corner(win.topLeft, 1f, 1f)
        corner(Offset(win.right, win.top), -1f, 1f)
        corner(Offset(win.left, win.bottom), 1f, -1f)
        corner(Offset(win.right, win.bottom), -1f, -1f)

        // Clipped corner tick, top-right, echoing the panel notch.
        val n = 10.dp.toPx()
        drawLine(ScanCrimson, Offset(win.right + 6.dp.toPx(), win.top - 6.dp.toPx() + n),
            Offset(win.right + 6.dp.toPx() - n, win.top - 6.dp.toPx()), 2.dp.toPx())

        // Sweep line with a soft glow trail.
        val y = win.top + 8.dp.toPx() + (side - 16.dp.toPx()) * sweep
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, ScanCyan.copy(alpha = 0.18f)), startY = y - 36.dp.toPx(), endY = y),
            Offset(win.left + 6.dp.toPx(), y - 36.dp.toPx()), Size(side - 12.dp.toPx(), 36.dp.toPx())
        )
        drawLine(ScanCyan, Offset(win.left + 6.dp.toPx(), y), Offset(win.right - 6.dp.toPx(), y), 2.dp.toPx())
    }
}

@Composable
private fun NoCamera(asked: Boolean, onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CAMERA OFFLINE", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            letterSpacing = 0.1.em, color = ScanCrimson)
        Spacer(Modifier.height(10.dp))
        Text(
            if (asked) "Camera access was declined. You can still pick a screenshot of the invite, or allow the camera in Android settings."
            else "CyberTxTs needs the camera to read the invite QR. Nothing is recorded or sent anywhere.",
            fontFamily = Body, fontSize = 16.sp, color = ScanInk, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        ScanButton("ALLOW CAMERA", ScanCyan, Modifier, onAsk)
    }
}

@Composable
private fun ScanButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).openButton(color).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontFamily = Mono, fontSize = 13.sp, letterSpacing = 0.1.em, color = color, maxLines = 1)
    }
}
