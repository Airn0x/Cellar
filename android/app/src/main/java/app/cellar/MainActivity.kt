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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private val DISTROS = listOf(
    Triple("alpine", "Alpine 3.24", "~30 MB · smallest, musl"),
    Triple("debian", "Debian trixie", "~400 MB · the agent default"),
    Triple("ubuntu", "Ubuntu noble", "~400 MB · familiar"),
)

private const val TAB_MACHINES = "machines"
private const val TAB_CATALOG = "catalog"
private const val TAB_CHAT = "chat"
private const val TAB_CONSOLE = "console"
private const val TAB_SETUP = "setup"

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
        val vault = Vault(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Amber)) {
                App(engine, vault, this)
            }
        }
    }
}

@Composable
private fun App(engine: Engine, vault: Vault, context: Context) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(TAB_MACHINES) }
    var status by remember { mutableStateOf("probing engine…") }
    var machines by remember { mutableStateOf<List<Engine.Machine>>(emptyList()) }
    var stacks by remember { mutableStateOf<List<Engine.Stack>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var panelLabel by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }

    suspend fun refresh() {
        machines = engine.list()
        if (stacks.isEmpty()) stacks = engine.catalog()
    }

    LaunchedEffect(Unit) {
        status = engine.status()
        refresh()
    }

    // Every long job runs behind the foreground service, and its output
    // stays on screen after it finishes until dismissed.
    fun work(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        panelLabel = label
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

    fun logLine(line: String) {
        scope.launch { if (log.size > 400) log.removeRange(0, 200); log.add(line) }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("cellar", color = Ink, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                Text(
                    status,
                    color = if (status.contains("ready")) Green else Amber,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TabBar(
            listOf(TAB_MACHINES, TAB_CATALOG, TAB_CHAT, TAB_CONSOLE, TAB_SETUP),
            tab,
        ) { tab = it }
        Spacer(Modifier.height(16.dp))

        if (panelLabel != null) {
            WorkPanel(panelLabel!!, log, busy) { panelLabel = null }
            return@Column
        }

        when (tab) {
            TAB_MACHINES -> MachinesTab(
                machines = machines,
                onCreate = { distro ->
                    val name = nextName(machines, distro)
                    work("creating $name") { engine.create(name, distro, ::logLine) }
                },
                onStart = { m ->
                    work("starting ${m.name}") {
                        val r = engine.start(m.name)
                        // a machine that has never been started has no init
                        // command yet; give it a long-lived idle one
                        if (!r.ok) engine.run("start", m.name, "--", "sleep infinity")
                    }
                },
                onStop = { m -> work("stopping ${m.name}") { engine.stop(m.name) } },
                onRemove = { m -> work("removing ${m.name}") { engine.remove(m.name) } },
                onLogs = { m ->
                    work("logs ${m.name}") {
                        engine.logs(m.name).lines().takeLast(80).forEach(::logLine)
                    }
                },
            )

            TAB_CATALOG -> CatalogTab(
                stacks = stacks,
                machines = machines,
                onInstall = { stack, machine ->
                    work("installing ${stack.name} → $machine") {
                        engine.apply(machine, stack.name, ::logLine)
                    }
                },
            )

            TAB_CHAT -> ChatTab(engine, vault, machines, stacks)

            TAB_CONSOLE -> ConsoleTab(engine, machines)

            TAB_SETUP -> SetupTab(engine, vault, context)
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
private fun WorkPanel(label: String, log: List<String>, busy: Boolean, onDismiss: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (busy) label else "$label · done",
                color = if (busy) Amber else Green,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            if (!busy) Pill("close", Muted, onDismiss)
        }
        Spacer(Modifier.height(10.dp))
        OutputPane(
            log, "working…",
            Modifier.heightIn(min = 140.dp).verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun MachinesTab(
    machines: List<Engine.Machine>,
    onCreate: (String) -> Unit,
    onStart: (Engine.Machine) -> Unit,
    onStop: (Engine.Machine) -> Unit,
    onRemove: (Engine.Machine) -> Unit,
    onLogs: (Engine.Machine) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(if (creating) "PICK A DISTRO" else "MACHINES")
            Spacer(Modifier.weight(1f))
            if (creating) Pill("cancel", Muted) { creating = false }
            else Pill("+ new machine", Amber) { creating = true }
        }
        Spacer(Modifier.height(10.dp))

        if (creating) {
            DISTROS.forEach { (id, title, sub) ->
                Card(Modifier.padding(bottom = 10.dp).clickable {
                    creating = false
                    onCreate(id)
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Ink, fontSize = 15.sp)
                            Text(sub, color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text("→", color = Amber, fontSize = 18.sp)
                    }
                }
            }
            Text(
                "downloads a verified rootfs over your current network",
                color = Dim, fontSize = 11.sp,
            )
            return@Column
        }

        if (machines.isEmpty()) {
            Card {
                Text("no machines yet", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "tap “+ new machine”, or open the catalog and pick something to run",
                    color = Dim, fontSize = 12.sp,
                )
            }
            return@Column
        }

        machines.forEach { m ->
            Card(Modifier.padding(bottom = 10.dp).clickable {
                open = if (open == m.name) null else m.name
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (m.broken) "interrupted create" else "${m.distro} ${m.release}",
                            color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    val (label, tint) = when {
                        m.broken -> "broken" to Red
                        m.running -> "up:${m.pid}" to Green
                        else -> "stopped" to Muted
                    }
                    Text(label, color = tint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (open == m.name) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!m.broken) {
                            if (m.running) Pill("stop", Amber) { onStop(m) }
                            else Pill("start", Green) { onStart(m) }
                            Pill("logs", Muted) { onLogs(m) }
                        }
                        Pill("remove", Red) { onRemove(m) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogTab(
    stacks: List<Engine.Stack>,
    machines: List<Engine.Machine>,
    onInstall: (Engine.Stack, String) -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
    val verified = stacks.filter { it.verified }
    val unverified = stacks.filterNot { it.verified }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("ONE-TAP STACKS")
        Spacer(Modifier.height(10.dp))

        if (machines.isEmpty()) {
            Card {
                Text("create a machine first", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("stacks install into a machine — make one on the machines tab", color = Dim, fontSize = 12.sp)
            }
            return@Column
        }

        verified.forEach { s ->
            StackCard(s, machines, open == s.name, { open = if (open == s.name) null else s.name }, onInstall)
        }

        if (unverified.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionTitle("NOT YET VERIFIED ON A PHONE")
            Spacer(Modifier.height(6.dp))
            Text(
                "these should work, but nobody has run them on a real device yet",
                color = Dim, fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            unverified.forEach { s ->
                StackCard(s, machines, open == s.name, { open = if (open == s.name) null else s.name }, onInstall)
            }
        }
    }
}

@Composable
private fun StackCard(
    s: Engine.Stack,
    machines: List<Engine.Machine>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onInstall: (Engine.Stack, String) -> Unit,
) {
    Card(Modifier.padding(bottom = 10.dp).clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(s.description, color = Muted, fontSize = 12.sp)
                val notes = listOfNotNull(
                    s.category.takeIf { it.isNotEmpty() },
                    s.size.takeIf { it.isNotEmpty() },
                    s.needsKey.takeIf { it.isNotEmpty() }?.let { "needs $it" },
                ).joinToString(" · ")
                if (notes.isNotEmpty()) {
                    Text(notes, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(if (s.verified) "✓" else "?", color = if (s.verified) Green else Amber, fontSize = 14.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            val usable = machines.filter { !it.broken && s.runsOn(it.distro) }
            if (usable.isEmpty()) {
                Text(
                    "needs a ${s.distros.joinToString("/")} machine — create one first",
                    color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            } else {
                Text("install into:", color = Dim, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    usable.forEach { m -> Pill(m.name, Green) { onInstall(s, m.name) } }
                }
            }
        }
    }
}
