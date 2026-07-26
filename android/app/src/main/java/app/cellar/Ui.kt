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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    // An accented card is tinted, not just outlined — a running machine
    // should be obvious from across the room.
    val fill = if (accent == null) listOf(PanelHi, PanelBg)
    else listOf(accent.copy(alpha = 0.07f).compositeOver(PanelHi), PanelBg)
    Column(
        modifier.fillMaxWidth().clip(CardShape)
            .background(Brush.verticalGradient(fill))
            .border(1.dp, accent?.copy(alpha = 0.35f) ?: LineColor, CardShape)
            .padding(16.dp),
    ) { content() }
}

/**
 * Distro identity, so a list of machines is scannable before it's read.
 * These tints are card-only and never collide with the status colours
 * (green = running, red = broken).
 */
fun distroTint(distro: String): Color = when (distro) {
    "alpine" -> Color(0xFF4EA8DE)
    "debian" -> Color(0xFFE05A7A)
    "ubuntu" -> Color(0xFFE8843C)
    else -> Dim
}

@Composable
fun DistroBadge(distro: String) {
    val tint = distroTint(distro)
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            distro.firstOrNull()?.uppercase() ?: "?",
            color = tint, fontSize = 15.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        )
    }
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

/**
 * Monospace output — console, chat replies, install logs.
 * Text is selectable: on a phone, output you cannot copy is output you
 * cannot use.
 */
@Composable
fun OutputPane(lines: List<String>, empty: String, modifier: Modifier = Modifier) {
    SelectionContainer {
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
}

/**
 * Output with the two things a phone screen needs: copy, and a way to
 * escape the little box. Fullscreen is a real terminal-shaped view —
 * whole screen, monospace, scrolled to the newest line.
 */
@Composable
fun TerminalBlock(
    lines: List<String>,
    empty: String,
    title: String,
    minHeight: Dp = 200.dp,
    input: (@Composable () -> Unit)? = null,
) {
    var full by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title, color = Dim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
            )
            if (lines.isNotEmpty()) {
                Pill("copy", Muted) {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                }
                Spacer(Modifier.width(8.dp))
            }
            Pill("⛶ full", Amber) { full = true }
        }
        Spacer(Modifier.height(8.dp))
        OutputPane(
            lines, empty,
            Modifier.heightIn(min = minHeight, max = 320.dp).verticalScroll(scroll),
        )
    }

    if (full) {
        Dialog(
            onDismissRequest = { full = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val fullScroll = rememberScrollState()
            LaunchedEffect(lines.size) { fullScroll.animateScrollTo(fullScroll.maxValue) }
            Column(
                Modifier.fillMaxSize().background(Bg).statusBarsPadding().imePadding()
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, color = Amber, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    )
                    if (lines.isNotEmpty()) {
                        Pill("copy", Muted) {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Pill("close", Muted) { full = false }
                }
                Spacer(Modifier.height(10.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxSize().clip(CardShape).background(ConsoleBg)
                            .border(1.dp, LineColor, CardShape)
                            .padding(12.dp).verticalScroll(fullScroll),
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                empty, color = Dim, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        lines.forEach {
                            Text(
                                it,
                                color = if (it.startsWith("$") || it.startsWith(">")) Green else Muted,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
                if (input != null) {
                    Spacer(Modifier.height(10.dp))
                    input()
                }
            }
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
            // capped and self-scrolling: an install log must never grow
            // until it owns the whole screen
            OutputPane(
                lines.takeLast(200), "working…",
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
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
