package app.cellar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

// The cellar palette (matches cellar.parallex.in).
private val Bg = Color(0xFF07090D)
private val Panel = Color(0xFF0E1219)
private val Line = Color(0xFF1B2230)
private val Ink = Color(0xFFE9E5DA)
private val Muted = Color(0xFF96A0B0)
private val Dim = Color(0xFF7B8698)
private val Amber = Color(0xFFFFB454)
private val Green = Color(0xFF46D47E)
private val Red = Color(0xFFFF6B6B)

private val DISTROS = listOf(
    Triple("alpine", "Alpine 3.24", "~4 MB · smallest"),
    Triple("debian", "Debian trixie", "~90 MB · the agent default"),
    Triple("ubuntu", "Ubuntu noble", "~90 MB · familiar"),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // only for the foreground-service notification; work runs either way
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val engine = Engine(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Amber)) {
                Home(engine, this)
            }
        }
    }
}

@Composable
private fun Home(engine: Engine, context: Context) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("probing engine…") }
    var machines by remember { mutableStateOf<List<Engine.Machine>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    // panel outlives `busy` on purpose: output the user asked for (logs,
    // a create transcript) must not vanish the instant work finishes
    var panel by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var wizard by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Engine.Machine?>(null) }

    suspend fun refresh() {
        machines = engine.list()
    }

    LaunchedEffect(Unit) {
        status = engine.status()
        refresh()
    }

    fun work(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        panel = label
        log.clear()
        CellarService.start(context, label)
        scope.launch {
            try {
                block()
                refresh()
            } finally {
                busy = false
                CellarService.stop(context)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        Text("cellar", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
        Text(
            "the server room in your cellphone",
            color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "● $status",
            color = if (status.contains("ready")) Green else Amber,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(22.dp))

        when {
            panel != null -> WorkPanel(panel!!, log, busy) { panel = null }

            wizard -> CreateWizard(
                onCancel = { wizard = false },
                onPick = { distro ->
                    wizard = false
                    val name = nextName(machines, distro)
                    work("creating $name") {
                        engine.create(name, distro) { line ->
                            scope.launch { log.add(line) }
                        }
                    }
                },
            )

            selected != null -> MachineSheet(
                machine = selected!!,
                onBack = { selected = null },
                onStart = { m ->
                    selected = null
                    work("starting ${m.name}") {
                        // a machine with no init yet gets a plain long-lived shell
                        val r = engine.start(m.name)
                        if (!r.ok) engine.run("start", m.name, "--", "sleep infinity")
                    }
                },
                onStop = { m -> selected = null; work("stopping ${m.name}") { engine.stop(m.name) } },
                onDelete = { m -> selected = null; work("removing ${m.name}") { engine.remove(m.name) } },
                onLogs = { m ->
                    work("logs ${m.name}") {
                        val text = engine.logs(m.name)
                        scope.launch { text.lines().takeLast(60).forEach { log.add(it) } }
                    }
                },
            )

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MACHINES", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Pill("+ new machine", Amber) { wizard = true }
                }
                Spacer(Modifier.height(10.dp))
                if (machines.isEmpty()) {
                    EmptyCard()
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(machines) { m -> MachineCard(m) { selected = m } }
                    }
                }
            }
        }
    }
}

/** alpine, alpine-2, … — a name the user never has to think about. */
private fun nextName(machines: List<Engine.Machine>, distro: String): String {
    val taken = machines.map { it.name }.toSet()
    if (distro !in taken) return distro
    var i = 2
    while ("$distro-$i" in taken) i++
    return "$distro-$i"
}

@Composable
private fun EmptyCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(16.dp),
    ) {
        Text("no machines yet", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text("tap “+ new machine” to unpack a Linux distro", color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun MachineCard(m: Engine.Machine, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(16.dp),
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

@Composable
private fun CreateWizard(onCancel: () -> Unit, onPick: (String) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PICK A DISTRO", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Pill("cancel", Muted, onCancel)
        }
        Spacer(Modifier.height(10.dp))
        DISTROS.forEach { (id, title, sub) ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .clickable { onPick(id) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ink, fontSize = 15.sp)
                    Text(sub, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text("→", color = Amber, fontSize = 18.sp)
            }
        }
        Text(
            "downloads a verified rootfs over your current network",
            color = Dim, fontSize = 11.sp,
        )
    }
}

@Composable
private fun WorkPanel(label: String, log: List<String>, busy: Boolean, onDismiss: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (busy) label else "$label · done",
                color = if (busy) Amber else Green,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            if (!busy) Pill("close", Muted, onDismiss)
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF05070A)).border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(14.dp).verticalScroll(rememberScrollState()),
        ) {
            if (log.isEmpty()) {
                Text("working…", color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            log.forEach {
                Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun MachineSheet(
    machine: Engine.Machine,
    onBack: () -> Unit,
    onStart: (Engine.Machine) -> Unit,
    onStop: (Engine.Machine) -> Unit,
    onDelete: (Engine.Machine) -> Unit,
    onLogs: (Engine.Machine) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(machine.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Pill("back", Muted, onBack)
        }
        Text(
            if (machine.broken) "interrupted create — remove to clean up"
            else "${machine.distro} ${machine.release}",
            color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!machine.broken) {
                if (machine.running) Pill("stop", Amber) { onStop(machine) }
                else Pill("start", Green) { onStart(machine) }
                Pill("logs", Muted) { onLogs(machine) }
            }
            Pill("remove", Red) { onDelete(machine) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "a terminal lands in the next build; today the CLI in a Termux " +
                "session drives the same machines",
            color = Dim, fontSize = 11.sp,
        )
    }
}

@Composable
private fun Pill(label: String, tint: Color, onClick: () -> Unit) {
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
