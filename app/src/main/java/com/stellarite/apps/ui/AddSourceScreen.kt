package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.data.SourceType
import com.stellarite.apps.ui.theme.SA

@Composable
fun AddSourceScreen(
    ui: AddSourceUi,
    onInput: (String) -> Unit,
    onQuick: (String) -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detected = ui.detected
    val ready = detected != null && !ui.checking && ui.error == null

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = SA.Text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Wordmark("ADD SOURCE")
                Mono("PASTE A GITHUB OR F-DROID LINK", spacing = 1.5.sp)
            }
        }

        Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("NEW SOURCE")

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono("PASTE A LINK", spacing = 1.3.sp)
                SaField(ui.input, onInput, Modifier.fillMaxWidth(), placeholder = "github.com/owner or repo address")
            }

            if (ui.checking) {
                Mono("CHECKING…", color = SA.Accent, spacing = 1.4.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SA.Accent, trackColor = SA.Line)
            }

            if (ui.error != null) {
                Text(ui.error, color = SA.Alert, fontSize = 14.sp, lineHeight = 20.sp)
            } else if (detected != null && !ui.checking) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SA.Void, SA.controlShape)
                        .border(1.dp, SA.Accent, SA.controlShape)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip("● ${typeWord(detected.type)}", SA.Installed)
                        Mono("DETECTED")
                    }
                    Text(detected.label, color = SA.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    ui.summary?.let { Text(it, color = SA.Muted, fontSize = 14.sp, lineHeight = 20.sp) }
                }
                ui.preview.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .background(SA.Void, SA.controlShape)
                            .border(1.dp, SA.Line, SA.controlShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(p.name, color = SA.Text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        StatusChip(p.detail, SA.Installed)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(SA.Line))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("OR ADD IN ONE TAP", spacing = 1.3.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaButton("STARS", { onQuick("stars") }, Modifier.weight(1f), BtnStyle.OutlineLight)
                    SaButton("F-DROID", { onQuick("fdroid") }, Modifier.weight(1f), BtnStyle.OutlineLight)
                    SaButton("IZZY", { onQuick("izzy") }, Modifier.weight(1f), BtnStyle.OutlineLight)
                }
            }

            Text(
                "Links that work: github.com/owner, github.com/owner/repo, or an F-Droid-style repo address. " +
                    "Stars and IzzyOnDroid are one-tap shortcuts.",
                color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SaButton("CANCEL", onCancel, Modifier.weight(1f), BtnStyle.OutlineLight, height = 54.dp)
                SaButton(
                    if (detected?.type == SourceType.FDROID) "PICK APPS" else "ADD SOURCE",
                    onAdd, Modifier.weight(1f), BtnStyle.Accent, enabled = ready, height = 54.dp, glow = true,
                )
            }
        }
    }
}

private fun typeWord(type: SourceType) = when (type) {
    SourceType.ACCOUNT -> "ACCOUNT"
    SourceType.REPO -> "REPO"
    SourceType.STARS -> "STARS"
    SourceType.FDROID -> "INDEX"
}
