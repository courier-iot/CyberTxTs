package com.stellarite.apps.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.ui.theme.SA

/** Rectangle with the top-right and bottom-left corners cut at 45 degrees. */
fun chamfer(cut: Dp): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width - c, 0f)
            lineTo(size.width, c)
            lineTo(size.width, size.height)
            lineTo(c, size.height)
            lineTo(0f, size.height - c)
            close()
        }
        return Outline.Generic(p)
    }
}

/**
 * Themed surface. Cyber: chamfered corners with a hairline border.
 * Steampunk: rounded, double brass border with a rivet in each corner.
 */
fun Modifier.panel(border: Color = SA.CardLine, cut: Dp = 12.dp, fill: Color = SA.Panel, accent: Boolean = false): Modifier {
    if (!SA.isSteam) {
        val shape = chamfer(cut)
        val base = this.clip(shape).background(fill, shape).border(1.dp, border, shape)
        if (!accent) return base
        // Cyber only: crimson along the cut corner, a glowing edge tick and a short bottom bar.
        return base.drawBehind {
            val w = size.width
            val h = size.height
            drawLine(SA.Update, Offset(w - cut.toPx(), 0f), Offset(w, cut.toPx()), 1.5.dp.toPx())
            drawRect(SA.Update, Offset(0f, 14.dp.toPx()), Size(2.dp.toPx(), 22.dp.toPx()))
            drawRect(SA.Update, Offset(w - 40.dp.toPx(), h - 2.dp.toPx()), Size(26.dp.toPx(), 2.dp.toPx()))
        }
    }
    val shape = RoundedCornerShape(4.dp)
    return this
        .clip(shape)
        .background(fill, shape)
        .border(1.dp, border, shape)
        .drawBehind {
            val inset = 3.dp.toPx()
            drawRoundRect(
                SA.Line,
                Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset),
                CornerRadius(2.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
            val r = 2.dp.toPx()
            val o = 7.dp.toPx()
            listOf(
                Offset(o, o), Offset(size.width - o, o),
                Offset(o, size.height - o), Offset(size.width - o, size.height - o),
            ).forEach { drawCircle(SA.Line3, r, it) }
        }
}

/** Small outlined status tag. */
fun Modifier.borderBadge(color: Color): Modifier =
    this.border(1.dp, color, SA.controlShape).padding(horizontal = 10.dp, vertical = 4.dp)

/** Themed backdrop. Cyber: circuit grid, stars and orbit rings. Steampunk: leather vignette and a big gear. */
fun Modifier.spaceBackground(): Modifier = this.background(SA.Void).drawBehind {
    if (SA.isSteam) {
        drawRect(
            Brush.radialGradient(
                listOf(SA.Decor, Color.Transparent),
                center = Offset(size.width / 2, 0f),
                radius = size.height * 0.8f,
            )
        )
        val c = Offset(size.width - 50.dp.toPx(), 60.dp.toPx())
        val tooth = 23.5.dp.toPx()
        drawCircle(
            SA.Decor, 180.dp.toPx(), c,
            style = Stroke(22.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(tooth, tooth))),
        )
        drawCircle(SA.Decor, 160.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(SA.Decor, 60.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(SA.Decor, 12.dp.toPx(), c)
    } else {
        val step = 44.dp.toPx()
        val grid = SA.Accent.copy(alpha = 0.035f)
        var x = 0f
        while (x < size.width) {
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        val stars = listOf(
            0.06f to 0.05f, 0.31f to 0.12f, 0.58f to 0.04f, 0.83f to 0.2f, 0.12f to 0.33f,
            0.7f to 0.45f, 0.92f to 0.62f, 0.2f to 0.7f, 0.48f to 0.82f, 0.78f to 0.9f,
        )
        stars.forEach { (fx, fy) ->
            drawCircle(Color(0x73C8D7F0), 1.2.dp.toPx(), Offset(fx * size.width, fy * size.height))
        }
        val center = Offset(size.width + 20.dp.toPx(), -10.dp.toPx())
        drawCircle(SA.Decor, 210.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        drawCircle(SA.Decor, 150.dp.toPx(), center, style = Stroke(1.dp.toPx()))
    }
}

/** Orbit mark (cyber) or gear mark (steampunk). */
@Composable
fun BrandLogo(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        if (SA.isSteam) {
            val s = this.size.width / 40f
            val c = Offset(20f * s, 20f * s)
            drawCircle(
                SA.Line3, 15f * s, c,
                style = Stroke(5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.7f * s, 4.7f * s))),
            )
            drawCircle(SA.Line3, 11f * s, c, style = Stroke(2f * s))
            drawCircle(SA.Accent, 4f * s, c)
        } else {
            val s = this.size.width / 28f
            val c = Offset(14f * s, 14f * s)
            val stroke = Stroke(1.4f * s)
            drawCircle(SA.Accent, 4f * s, c, style = stroke)
            rotate(-30f, c) {
                drawOval(
                    SA.Accent,
                    topLeft = Offset(c.x - 12f * s, c.y - 5f * s),
                    size = Size(24f * s, 10f * s),
                    style = stroke,
                )
            }
            drawCircle(SA.Update, 1.7f * s, Offset(23f * s, 9f * s))
        }
    }
}

@Composable
fun Wordmark(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = if (SA.isSteam) modifier else modifier.drawBehind {
            // Short crimson underline that fades out.
            val y = size.height + 3.dp.toPx()
            val t = 2.dp.toPx()
            drawRect(SA.Update, Offset(0f, y), Size(26.dp.toPx(), t))
            drawRect(SA.Update.copy(alpha = 0.5f), Offset(29.dp.toPx(), y), Size(6.dp.toPx(), t))
            drawRect(SA.Update.copy(alpha = 0.25f), Offset(38.dp.toPx(), y), Size(3.dp.toPx(), t))
        },
        color = SA.wordmark,
        style = TextStyle(
            fontFamily = SA.bodyFont,
            fontSize = if (SA.isSteam) 20.sp else 18.sp,
            fontWeight = if (SA.isSteam) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = if (SA.isSteam) 4.sp else 4.7.sp,
            shadow = if (SA.glow) Shadow(SA.Accent.copy(alpha = 0.35f), blurRadius = 24f) else null,
        ),
    )
}

@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 11.sp,
    color: Color = SA.Muted,
    spacing: TextUnit = 1.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        maxLines = maxLines,
        style = TextStyle(
            fontFamily = SA.monoFont,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = spacing,
        ),
    )
}

enum class BtnStyle { Accent, Update, OutlineLight, OutlineAlert }

@Composable
fun SaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Accent,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    glow: Boolean = false,
) {
    val (bg, fg, border) = when (style) {
        BtnStyle.Accent -> Triple<Color, Color, Color?>(SA.Accent, SA.Void, null)
        BtnStyle.Update -> Triple<Color, Color, Color?>(SA.Update, SA.Void, null)
        BtnStyle.OutlineLight -> Triple<Color, Color, Color?>(Color.Transparent, SA.Text, SA.Line3)
        BtnStyle.OutlineAlert -> Triple<Color, Color, Color?>(Color.Transparent, SA.Alert, SA.Alert)
    }
    val shape = SA.controlShape
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = height)
            .then(
                if (glow && enabled && SA.glow) Modifier.shadow(10.dp, shape, ambientColor = bg, spotColor = bg)
                else Modifier
            ),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = SA.Panel,
            disabledContentColor = SA.Muted,
        ),
        border = border?.let { BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Mono(
            text,
            size = 12.sp,
            color = if (enabled) fg else SA.Muted,
            spacing = 1.2.sp,
            weight = if (SA.isSteam) FontWeight.Bold else FontWeight.Medium,
            maxLines = if ('\n' in text) 2 else 1,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mono(
            text,
            size = 12.sp,
            color = if (SA.isSteam) Color(0xFFD9B26F) else SA.Muted,
            spacing = 1.6.sp,
            weight = if (SA.isSteam) FontWeight.SemiBold else FontWeight.Normal,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        if (SA.isSteam) listOf(SA.Line3, SA.Line, Color.Transparent)
                        else listOf(SA.Update, Color(0xFF2F7983), SA.Line, Color.Transparent)
                    )
                )
        )
    }
}

@Composable
fun SaField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    mono: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        placeholder = placeholder?.let { { Text(it, color = SA.Muted) } },
        leadingIcon = leading,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        textStyle = TextStyle(
            fontFamily = if (mono) SA.monoFont else SA.bodyFont,
            fontSize = if (SA.isSteam) 16.sp else 14.sp,
            color = SA.Text,
        ),
        shape = SA.controlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SA.Text,
            unfocusedTextColor = SA.Text,
            focusedBorderColor = SA.Accent,
            unfocusedBorderColor = if (SA.isSteam) SA.Line3 else SA.Line2,
            focusedContainerColor = SA.Void,
            unfocusedContainerColor = SA.Void,
            cursorColor = SA.Accent,
        ),
    )
}

/** Themed on/off switch with a 44dp touch target. */
@Composable
fun SaSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val trackShape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(64.dp, 44.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .size(52.dp, 28.dp)
                .then(
                    if (checked && SA.glow) Modifier.shadow(8.dp, trackShape, ambientColor = SA.Accent, spotColor = SA.Accent)
                    else Modifier
                )
                .background(if (checked) SA.Accent else SA.Line, trackShape),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(22.dp)
                    .background(if (checked) SA.Void else SA.Muted, CircleShape)
            )
        }
    }
}
