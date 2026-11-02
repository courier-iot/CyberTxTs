package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stellarite.apps.data.FDroidDetails
import com.stellarite.apps.ui.theme.SA
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun day(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

private fun mb(bytes: Long): String = if (bytes <= 0) "" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

private fun niceAntiFeature(raw: String) = raw.replace(Regex("([a-z])([A-Z])"), "$1 $2")

/** The extra sections on an F-Droid app's page, read from the verified index. */
@Composable
fun FDroidSections(
    state: FDroidDetailsState?,
    holdImages: Boolean,
    onLoad: (force: Boolean) -> Unit,
    sourceHost: String,
) {
    when (state) {
        null, FDroidDetailsState.Loading -> Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("ABOUT")
            Mono("READING THE INDEX…", color = SA.Accent, size = 12.sp, spacing = 1.4.sp)
        }

        FDroidDetailsState.NeedsWifi -> Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("ABOUT")
            Text(
                "The details come from F-Droid's large index, which is waiting for Wi-Fi.",
                color = SA.Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            SaButton("LOAD ANYWAY", { onLoad(true) }, Modifier.fillMaxWidth(), BtnStyle.OutlineLight, height = 48.dp)
        }

        is FDroidDetailsState.Failed -> Column(Modifier.fillMaxWidth().panel(border = SA.Alert).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("ABOUT")
            Text(state.message, color = SA.Text, fontSize = 14.sp)
            SaButton("RETRY", { onLoad(true) }, style = BtnStyle.OutlineAlert)
        }

        is FDroidDetailsState.Loaded -> Loaded(state.details, holdImages, sourceHost)
    }
}

@Composable
private fun Loaded(d: FDroidDetails, holdImages: Boolean, sourceHost: String) {
    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("ABOUT")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            d.categories.forEach { StatusChip(it.uppercase(Locale.US), SA.Muted) }
            if (d.antiFeatures.isEmpty()) StatusChip("● NO ANTI-FEATURES", SA.Installed)
            else d.antiFeatures.forEach { StatusChip(niceAntiFeature(it).uppercase(Locale.US), SA.Update) }
        }
        if (d.description.isNotBlank()) {
            Text(d.description.take(1500), color = SA.Text, fontSize = 14.sp, lineHeight = 21.sp)
        }
        Mono(
            listOfNotNull(
                d.license.takeIf { it.isNotBlank() }?.let { "LICENSE · $it" },
                "UPDATED · ${day(d.updatedMs)}",
                d.minSdk.takeIf { it > 0 }?.let { "MIN ANDROID API · $it" },
            ).joinToString("\n"),
            spacing = 1.sp,
        )
    }

    if (d.screenshots.isNotEmpty()) {
        var show by remember { mutableStateOf(!holdImages) }
        Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("SCREENSHOTS")
            if (!show) {
                Text("Screenshots wait for Wi-Fi.", color = SA.Muted, fontSize = 14.sp)
                SaButton("LOAD SCREENSHOTS", { show = true }, Modifier.fillMaxWidth(), BtnStyle.OutlineLight, height = 48.dp)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.screenshots.forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(110.dp)
                                .height(196.dp)
                                .background(SA.Void)
                                .border(1.dp, SA.Line2),
                        )
                    }
                }
            }
        }
    }

    if (d.versions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("VERSION HISTORY")
            d.versions.forEachIndexed { i, v ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("v${v.name}", color = SA.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Mono(listOf(day(v.addedMs), mb(v.size)).filter { it.isNotEmpty() }.joinToString(" · "))
                    }
                    StatusChip(if (i == 0) "LATEST" else "OLDER", if (i == 0) SA.Accent else SA.Line3)
                }
            }
            Text(
                "Only the latest version can be installed. Android will not install an older one over a newer one.",
                color = SA.Muted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }

    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("SOURCE")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("● VERIFIED INDEX", SA.Installed)
            Mono(sourceHost, maxLines = 1)
        }
        Text(
            "Made by its authors and published through this repository. Not made by Stellarite.",
            color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}
