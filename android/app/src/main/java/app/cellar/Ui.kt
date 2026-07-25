package app.cellar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The cellar palette (matches cellar.parallex.in).
val Bg = Color(0xFF07090D)
val PanelBg = Color(0xFF0E1219)
val ConsoleBg = Color(0xFF05070A)
val LineColor = Color(0xFF1B2230)
val Ink = Color(0xFFE9E5DA)
val Muted = Color(0xFF96A0B0)
val Dim = Color(0xFF7B8698)
val Amber = Color(0xFFFFB454)
val Green = Color(0xFF46D47E)
val Red = Color(0xFFFF6B6B)

@Composable
fun Pill(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        color = tint,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelBg)
            .border(1.dp, LineColor, RoundedCornerShape(12.dp)).padding(16.dp),
    ) { content() }
}

@Composable
fun Field(
    value: String,
    placeholder: String,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(ConsoleBg)
            .border(1.dp, LineColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Amber),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A monospace output pane — used by the console, chat and progress views. */
@Composable
fun OutputPane(lines: List<String>, empty: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ConsoleBg)
            .border(1.dp, LineColor, RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        if (lines.isEmpty()) {
            Text(empty, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        lines.forEach {
            Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
fun TabBar(tabs: List<String>, current: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelBg)
            .border(1.dp, LineColor, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val active = t == current
            Text(
                t,
                color = if (active) Bg else Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (active) Amber else Color.Transparent)
                    .clickable { onPick(t) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}
