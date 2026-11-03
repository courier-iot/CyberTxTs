package com.cybertxts.app

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat

/**
 * Choose a tone. Tapping one plays it; DONE keeps it.
 * [ring] true lists ringtones, false message tones. With [sameAsDefault], the first choice is
 * "Same as default" (picked as null), for a chat's own tone.
 */
@Composable
fun ToneDialog(ring: Boolean, current: String?, sameAsDefault: Boolean = false, onPick: (String?) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(current) }
    DisposableEffect(Unit) { onDispose { Sounds.stopPreview() } }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val u = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            if (u != null) chosen = "uri:$u"
        }
    }
    val bundled = if (ring) Sounds.ringtones else Sounds.messageTones
    val options = buildList {
        if (sameAsDefault) add(null to "Same as default (" + Sounds.label(ctx, Sounds.conf.value.let { if (ring) it.callTone else it.msgTone }, ring) + ")")
        bundled.forEach { add(it.key to it.label) }
        add("default" to "Phone default")
        add("none" to "Silent")
        val c = chosen
        if (c != null && c.startsWith("uri:")) add(c to Sounds.label(ctx, c, ring))
    }
    AlertDialog(
        onDismissRequest = onClose,
        shape = cutShape(14.dp), containerColor = Holo.Panel, titleContentColor = Holo.Primary, textContentColor = Holo.Ink,
        title = { Text(if (ring) "Ringtone" else "Message tone") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (key, label) ->
                    val on = key == chosen
                    GhostButton((if (on) "✓ " else "") + label, Modifier.fillMaxWidth(), color = if (on) Holo.Primary else Holo.Muted,
                        description = "$label${if (on) ", selected" else ""}. Plays a preview.") {
                        chosen = key
                        if (key == null) Sounds.stopPreview() else Sounds.preview(ctx, key, ring)
                    }
                }
                GhostButton("Pick from your phone's sounds…", Modifier.fillMaxWidth(), color = Holo.Primary) {
                    Sounds.stopPreview()
                    AppLock.holdOpen()
                    val existing = chosen?.let { Sounds.uriOf(ctx, it, ring) }
                    runCatching {
                        phonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, if (ring) "CyberTxTs ringtone" else "CyberTxTs message tone")
                            .apply { if (existing != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing) })
                    }
                }
                if (!ring) Text("Your phone may let you change it further in Android's notification settings.",
                    style = MaterialTheme.typography.bodySmall, color = Holo.InkDim)
            }
        },
        confirmButton = { TextButton(onClick = { Sounds.stopPreview(); onPick(chosen); onClose() }) { Text("DONE", fontFamily = Mono, color = Holo.Primary) } },
        dismissButton = { TextButton(onClick = { Sounds.stopPreview(); onClose() }) { Text("CANCEL", fontFamily = Mono, color = Holo.Muted) } }
    )
}
