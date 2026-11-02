package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.ui.theme.SA

/** Two-option segmented control, used for FILE | GITHUB and AS RECORDED | LATEST. */
@Composable
fun PairToggle(
    first: String,
    second: String,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SA.Panel, SA.controlShape)
            .border(1.dp, if (SA.isSteam) SA.CardLine else SA.Line2, SA.controlShape)
    ) {
        listOf(first, second).forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (on) SA.PanelRaised else Color.Transparent)
                    .drawBehind {
                        if (on) {
                            val h = 2.dp.toPx()
                            drawRect(SA.Accent, Offset(0f, size.height - h), Size(size.width, h))
                        }
                    }
                    .clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Mono(
                    label,
                    size = 12.sp,
                    color = if (on) SA.Text else SA.Muted,
                    spacing = 1.sp,
                    weight = if (SA.isSteam) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Small outlined status tag such as "● LINKED" or "MISSING". */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Mono(
        text,
        modifier.border(1.dp, color, SA.controlShape).padding(horizontal = 8.dp, vertical = 3.dp),
        size = 10.sp,
        color = color,
        spacing = 1.sp,
        weight = FontWeight.SemiBold,
        maxLines = 1,
    )
}
