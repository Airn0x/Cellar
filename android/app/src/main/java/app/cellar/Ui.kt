package app.cellar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The cellar palette — a warm bulb in a dark basement (cellar.parallex.in).
val Bg = Color(0xFF07090D)
val PanelBg = Color(0xFF0E1219)
val PanelHi = Color(0xFF141A24)
val ConsoleBg = Color(0xFF05070A)
val LineColor = Color(0xFF1B2230)
val Ink = Color(0xFFE9E5DA)
val Muted = Color(0xFF96A0B0)
val Dim = Color(0xFF7B8698)
val Amber = Color(0xFFFFB454)
val Green = Color(0xFF46D47E)
val Red = Color(0xFFFF6B6B)

val CardShape = RoundedCornerShape(16.dp)

/** A soft top-lit card — the app's one repeating surface. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(CardShape)
            .background(Brush.verticalGradient(listOf(PanelHi, PanelBg)))
            .border(1.dp, accent?.copy(alpha = 0.35f) ?: LineColor, CardShape)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false) {
    val alpha = if (!pulsing) 1f else {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "alpha",
        )
        a
    }
    Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(color))
}

@Composable
fun Pill(label: String, tint: Color, filled: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (filled) Bg else tint,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (filled) tint else Color.Transparent)
            .border(1.dp, tint.copy(alpha = if (filled) 1f else 0.4f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Small tappable suggestion — used for quick commands. */
@Composable
fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Muted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(PanelBg)
            .border(1.dp, LineColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
fun Field(
    value: String,
    placeholder: String,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(ConsoleBg)
            .border(1.dp, LineColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Amber),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Monospace output — console, chat replies, install logs. */
@Composable
fun OutputPane(lines: List<String>, empty: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(ConsoleBg)
            .border(1.dp, LineColor, CardShape).padding(14.dp),
    ) {
        if (lines.isEmpty()) {
            Text(empty, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        lines.forEach {
            Text(
                it, color = if (it.startsWith("$") || it.startsWith(">")) Green else Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
            )
        }
    }
}

/**
 * Non-blocking activity strip. Long jobs report here while every tab
 * stays usable — an earlier build put this in a modal panel that
 * swallowed the whole screen, which made the app feel frozen.
 */
@Composable
fun ActivityStrip(
    label: String?,
    lines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = label != null
    Card(accent = if (busy) Amber else Green) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (busy) Amber else Green, pulsing = busy)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label ?: "done",
                    color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                )
                lines.lastOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it.take(70), color = Dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
            }
            Pill(if (expanded) "hide" else "log", Muted, onClick = onToggle)
            if (!busy) {
                Spacer(Modifier.width(8.dp))
                Pill("✕", Muted, onClick = onDismiss)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            OutputPane(lines.takeLast(60), "working…")
        }
    }
}

/** Bottom navigation: thumb-reachable, which top tabs were not. */
@Composable
fun BottomNav(items: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(PanelBg)
            .border(1.dp, LineColor, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (id, glyph) ->
            val active = id == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(id) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(glyph, color = if (active) Amber else Dim, fontSize = 17.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    id,
                    color = if (active) Amber else Dim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
