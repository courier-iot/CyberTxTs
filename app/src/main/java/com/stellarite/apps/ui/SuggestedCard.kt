package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.data.AppState
import com.stellarite.apps.data.Suggestion
import com.stellarite.apps.data.GhRepo
import com.stellarite.apps.data.StoreApp
import com.stellarite.apps.data.displayRepo
import com.stellarite.apps.data.Transfer
import com.stellarite.apps.ui.theme.SA

/** A recommended app on the home page. [app] is known once the repository's index has been read. */
data class SuggestedItem(
    val s: Suggestion,
    val app: StoreApp? = null,
    val preparing: Boolean = false,
    /** True when the app is already on the device, even if its version isn't known yet. */
    val installed: Boolean = false,
)

@Composable
fun SuggestedCard(
    item: SuggestedItem,
    transfer: Transfer?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = item.app
    val versionLine = when {
        app != null -> listOf(app.release.tagName, formatSize(app.apk.size)).filter { it.isNotEmpty() }.joinToString(" · ")
        item.installed -> "Installed"
        else -> "Version shown after the first sync"
    }
    Column(
        modifier.fillMaxWidth().panel(accent = true).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(SA.Void, SA.tileShape)
                    .border(SA.tileBorder, if (SA.isSteam) SA.Line3 else SA.Line2, SA.tileShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Storage, null, tint = SA.Installed, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.s.title,
                    color = SA.Text,
                    fontSize = if (SA.isSteam) 18.sp else 16.sp,
                    fontWeight = if (SA.isSteam) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Mono(item.s.sub, Modifier.padding(top = 2.dp), maxLines = 1)
                Mono(versionLine, Modifier.padding(top = 6.dp), maxLines = 1)
            }
            val m = Modifier.widthIn(min = 88.dp)
            when {
                item.preparing -> SaButton("…", {}, m, BtnStyle.OutlineLight, enabled = false)
                app != null -> ActionButton(app, transfer, onAction)
                item.installed -> SaButton("OPEN", onAction, m, BtnStyle.OutlineLight)
                else -> SaButton("INSTALL", onAction, m, BtnStyle.Accent)
            }
        }
        Text(item.s.blurb, color = SA.Muted, fontSize = 13.sp, lineHeight = 19.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("● VERIFIED INDEX", SA.Installed)
            StatusChip("OPEN SOURCE", SA.Muted)
            if (app?.state == AppState.UPDATE_AVAILABLE) StatusChip("UPDATE", SA.Update)
        }
    }
}


/** One of the owner's repos that has no APK release yet, shown greyed out. */
@Composable
fun PendingCard(repo: GhRepo, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .alpha(0.78f)
            .background(SA.Panel, SA.controlShape)
            .border(1.dp, SA.Line2, SA.controlShape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .background(SA.Void, SA.tileShape)
                .border(SA.tileBorder, SA.Line2, SA.tileShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(repo.name), null, tint = SA.Muted, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(repo.name, color = SA.Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Mono(displayRepo(repo.fullName), Modifier.padding(top = 2.dp), maxLines = 1)
            Mono("In development", Modifier.padding(top = 6.dp), maxLines = 1)
        }
        StatusChip("NO RELEASE YET", SA.Muted)
    }
}
