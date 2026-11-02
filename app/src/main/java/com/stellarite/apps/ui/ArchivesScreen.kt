package com.stellarite.apps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.ui.theme.SA
import java.text.NumberFormat

/** The Archives tab: one card per large open-source repository. */
@Composable
fun ArchivesScreen(
    cards: List<ArchiveCard>,
    onOpen: (ArchiveCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Wordmark("ARCHIVES")
            Mono("LARGE OPEN-SOURCE LISTS", spacing = 1.5.sp)
        }

        cards.forEach { card ->
            Column(
                Modifier.fillMaxWidth().panel(border = SA.Accent, accent = true).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(SA.Void, SA.tileShape)
                            .border(SA.tileBorder, if (SA.isSteam) SA.Line3 else SA.Line2, SA.tileShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Storage, null, tint = SA.Accent, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(card.label, color = SA.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Mono(card.repoUrl.removePrefix("https://"), maxLines = 1)
                    }
                    when (card.verified) {
                        true -> StatusChip("● VERIFIED", SA.Installed)
                        false -> StatusChip("UNSIGNED", SA.Update)
                        null -> {}
                    }
                }
                Text(card.blurb, color = SA.Muted, fontSize = 14.sp, lineHeight = 20.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("APPS", card.count?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—", Modifier.weight(1f))
                    Stat("SYNCED", syncedText(card.syncedAt), Modifier.weight(1f))
                    Stat("YOURS", "${card.picked} PICKED", Modifier.weight(1f))
                }
                SaButton("OPEN ARCHIVE", { onOpen(card) }, Modifier.fillMaxWidth(), BtnStyle.Accent, height = 54.dp, glow = true)
            }
        }

        Text(
            "Large archives list thousands of apps. Open one to browse it A to Z, filter it for your device " +
                "and pick what joins your catalog. More archives can be added from Core > Sources.",
            color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )

        val ctx = LocalContext.current
        Column(
            Modifier.fillMaxWidth().panel(border = SA.Line2).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("ABOUT THESE ARCHIVES")
            Text(
                "F-Droid's app repository belongs to the F-Droid project. Stellarite Apps only gives you a way " +
                    "into it from your own launcher, which was made mainly for its maker's own apps. It is not " +
                    "made by or affiliated with F-Droid and is not meant to replace their app store. " +
                    "If you would rather use theirs, you can get the official F-Droid app.",
                color = SA.Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            SaButton(
                "GET THE F-DROID APP",
                {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                },
                Modifier.fillMaxWidth(), BtnStyle.OutlineLight, height = 48.dp,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Mono(label, spacing = 1.3.sp)
        Text(value, color = SA.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun syncedText(at: Long): String {
    if (at <= 0) return "NEVER"
    val days = (System.currentTimeMillis() - at) / 86_400_000L
    return when {
        days < 1 -> "TODAY"
        days == 1L -> "1 DAY AGO"
        else -> "$days DAYS AGO"
    }
}
