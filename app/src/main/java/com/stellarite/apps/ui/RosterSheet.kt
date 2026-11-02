package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.data.StoreApp
import com.stellarite.apps.data.displayRepo
import com.stellarite.apps.ui.theme.SA

/**
 * Pick which modules patch themselves.
 * The same sheet serves both Auto-Patch modes; only the wording differs:
 * ON lists exceptions to untick, OFF lists the few modules to tick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterSheet(
    apps: List<StoreApp>,
    autoPatchOn: Boolean,
    armedInitial: Set<String>,
    onDismiss: () -> Unit,
    onCommit: (Set<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var armed by remember { mutableStateOf(armedInitial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SA.Nav,
        contentColor = SA.Text,
        scrimColor = Color(0xB8050709),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .background(SA.Line3, RoundedCornerShape(2.dp))
            )
        },
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PATCH ROSTER",
                    color = SA.wordmark,
                    style = TextStyle(
                        fontFamily = SA.bodyFont,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 4.sp,
                    ),
                )
                Text(
                    if (autoPatchOn) "Auto-Patch is on. Untick modules to exclude them."
                    else "Auto-Patch is off. Tick the modules that should still patch themselves.",
                    color = SA.Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Mono("${armed.size} OF ${apps.size} MODULES ARMED", color = SA.Accent, spacing = 1.3.sp)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (apps.isEmpty()) {
                    Text("No modules found yet.", color = SA.Muted, fontSize = 14.sp)
                }
                apps.forEach { app ->
                    val key = app.repo.fullName
                    val on = key in armed
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .panel(border = if (on) SA.Accent else SA.CardLine, cut = 8.dp)
                            .toggleable(
                                value = on,
                                role = Role.Checkbox,
                                onValueChange = { armed = if (it) armed + key else armed - key },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = SA.Accent,
                                uncheckedColor = SA.Muted,
                                checkmarkColor = SA.Void,
                            ),
                        )
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(SA.Void, SA.tileShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(iconFor(app.repo.name), null, tint = SA.Installed, modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(app.repo.name, color = SA.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Mono(displayRepo(app.repo.fullName), maxLines = 1)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SaButton("CANCEL", onDismiss, Modifier.weight(1f), BtnStyle.OutlineLight, height = 54.dp)
                SaButton("COMMIT ROSTER", { onCommit(armed) }, Modifier.weight(1f), BtnStyle.Accent, height = 54.dp, glow = true)
            }
        }
    }
}
