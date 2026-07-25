package app.cellar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The cellar palette (matches cellar.parallex.in).
private val Bg = Color(0xFF07090D)
private val Panel = Color(0xFF0E1219)
private val Line = Color(0xFF1B2230)
private val Ink = Color(0xFFE9E5DA)
private val Muted = Color(0xFF96A0B0)
private val Amber = Color(0xFFFFB454)
private val Green = Color(0xFF46D47E)
private val Red = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = Engine(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Amber)) {
                Home(engine)
            }
        }
    }
}

@Composable
private fun Home(engine: Engine) {
    var status by remember { mutableStateOf("probing engine…") }
    var machines by remember { mutableStateOf<List<Engine.Machine>>(emptyList()) }

    LaunchedEffect(Unit) {
        status = engine.status()
        machines = engine.list()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(22.dp),
    ) {
        Text("cellar", color = Ink, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
        Text(
            "the server room in your cellphone",
            color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(20.dp))

        Text(
            "● $status",
            color = if (status.contains("ready")) Green else Amber,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(26.dp))

        Text("MACHINES", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(10.dp))

        if (machines.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Panel).border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("no machines yet", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "the create wizard lands next",
                    color = Color(0xFF7B8698), fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(machines) { m -> MachineCard(m) }
            }
        }
    }
}

@Composable
private fun MachineCard(m: Engine.Machine) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Panel).border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (m.broken) "interrupted create" else "${m.distro} ${m.release}",
                color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
        val (label, tint) = when {
            m.broken -> "broken" to Red
            m.running -> "up:${m.pid}" to Green
            else -> "stopped" to Muted
        }
        Text(label, color = tint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
