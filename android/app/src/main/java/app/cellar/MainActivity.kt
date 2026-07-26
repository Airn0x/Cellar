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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DISTROS = listOf(
    Triple("alpine", "Alpine 3.24", "~30 MB · smallest, musl"),
    Triple("debian", "Debian trixie", "~400 MB · the agent default"),
    Triple("ubuntu", "Ubuntu noble", "~400 MB · familiar"),
)

private const val MACHINES = "machines"
private const val CATALOG = "catalog"
private const val CHAT = "chat"
private const val TERMINAL = "terminal"
private const val SETUP = "setup"

private val NAV = listOf(
    MACHINES to "▤",
    CATALOG to "◈",
    CHAT to "✦",
    TERMINAL to "❯",
    SETUP to "⚙",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 forces edge-to-edge; without this the UI draws
        // underneath the status bar clock and the gesture bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
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
    var tab by remember { mutableStateOf(MACHINES) }
    var status by remember { mutableStateOf("starting…") }
    var machines by remember { mutableStateOf<List<Engine.Machine>>(emptyList()) }
    var stacks by remember { mutableStateOf<List<Engine.Stack>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var catalogMachine by remember { mutableStateOf<String?>(null) }

    // Work state. `busy` is a label while something runs; the log stays
    // visible afterwards in a strip the user can dismiss — nothing ever
    // takes over the screen.
    var terminalMachine by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var stripVisible by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    suspend fun refresh() {
        machines = engine.list()
        if (stacks.isEmpty()) stacks = engine.catalog()
        val target = catalogMachine ?: machines.firstOrNull { !it.broken }?.name
        if (catalogMachine != target) catalogMachine = target
        if (target != null) installed = engine.installed(target)
        CellarService.sync(context, machines.count { it.running }, busy)
    }

    LaunchedEffect(Unit) {
        status = engine.status()
        refresh()
    }

    // keep the machine list honest while the app is open
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            if (busy == null) refresh()
        }
    }

    fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        stripVisible = true
        log.clear()
        CellarService.sync(context, machines.count { it.running }, label)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                log.add("! ${e.message ?: e.toString()}")
            } finally {
                busy = null
                refresh()
            }
            // A finished job shouldn't keep eating a phone screen. Fade
            // the strip out on its own — unless the log is open, which
            // means the user is reading it.
            delay(6000)
            if (busy == null && !showLog) stripVisible = false
        }
    }

    fun logLine(line: String) {
        scope.launch {
            if (log.size > 300) log.removeRange(0, 150)
            log.add(line)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().imePadding()) {

        // ---- header ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_mark),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("cellar", color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                Text(
                    status, color = if (status.contains("ready")) Green else Amber,
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                )
            }
            val up = machines.count { it.running }
            if (up > 0) {
                StatusDot(Green, pulsing = true)
                Spacer(Modifier.width(6.dp))
                Text(
                    "$up up", color = Green, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ---- content ----
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            if (stripVisible) {
                ActivityStrip(
                    label = busy,
                    lines = log,
                    expanded = showLog,
                    onToggle = { showLog = !showLog },
                    onDismiss = { stripVisible = false; showLog = false },
                )
                Spacer(Modifier.height(16.dp))
            }

            when (tab) {
                MACHINES -> MachinesTab(
                    machines = machines,
                    onTerminal = { m -> terminalMachine = m.name; tab = TERMINAL },
                    onCreate = { distro ->
                        val name = nextName(machines, distro)
                        work("creating $name") { engine.create(name, distro, ::logLine) }
                    },
                    onStart = { m ->
                        work("starting ${m.name}") {
                            val r = engine.start(m.name)
                            // never started before: no init command stored yet
                            if (!r.ok) engine.run("start", m.name, "--", "sleep infinity")
                        }
                    },
                    onStop = { m -> work("stopping ${m.name}") { engine.stop(m.name) } },
                    onRemove = { m -> work("removing ${m.name}") { engine.remove(m.name) } },
                    onLogs = { m ->
                        work("logs ${m.name}") {
                            showLog = true
                            engine.logs(m.name).lines().takeLast(80).forEach(::logLine)
                        }
                    },
                )

                CATALOG -> CatalogTab(
                    stacks = stacks,
                    machines = machines,
                    installed = installed,
                    selected = catalogMachine,
                    onSelect = { name ->
                        catalogMachine = name
                        scope.launch { installed = engine.installed(name) }
                    },
                    onOpenTerminal = { m -> terminalMachine = m; tab = TERMINAL },
                    onInstall = { stack, machine ->
                        work("installing ${stack.name} → $machine") {
                            showLog = true
                            val r = engine.apply(machine, stack.name, ::logLine)
                            logLine(if (r.ok) "✓ ${stack.name} installed" else "✕ ${stack.name} failed")
                            installed = engine.installed(machine)
                        }
                    },
                )

                CHAT -> ChatTab(engine, vault, machines, stacks)
                TERMINAL -> TerminalTab(engine, machines, terminalMachine) { terminalMachine = it }
                SETUP -> SetupTab(engine, vault, context)
            }
            Spacer(Modifier.height(24.dp))
        }

        // ---- bottom nav ----
        Column(Modifier.navigationBarsPadding()) {
            BottomNav(NAV, tab) { tab = it }
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
private fun MachinesTab(
    machines: List<Engine.Machine>,
    onTerminal: (Engine.Machine) -> Unit,
    onCreate: (String) -> Unit,
    onStart: (Engine.Machine) -> Unit,
    onStop: (Engine.Machine) -> Unit,
    onRemove: (Engine.Machine) -> Unit,
    onLogs: (Engine.Machine) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }

    Column {
        // Controls stay put while work runs — hiding them made the app
        // look broken. work() already refuses to start a second job.
        SectionTitle(if (creating) "PICK A DISTRO" else "MACHINES") {
            if (creating) Pill("cancel", Muted) { creating = false }
            else Pill("+ new", Amber, filled = true) { creating = true }
        }
        Spacer(Modifier.height(12.dp))

        if (creating) {
            DISTROS.forEach { (id, title, sub) ->
                Card(Modifier.padding(bottom = 10.dp).clickable {
                    creating = false
                    onCreate(id)
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Ink, fontSize = 15.sp)
                            Text(
                                sub, color = Dim, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text("→", color = Amber, fontSize = 20.sp)
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
                Text("no machines yet", color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "tap “+ new” for a Linux machine, or open the catalog and pick " +
                        "something to run",
                    color = Muted, fontSize = 12.sp,
                )
            }
            return@Column
        }

        machines.forEach { m ->
            Card(
                Modifier.padding(bottom = 12.dp),
                accent = if (m.running) Green else null,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DistroBadge(m.distro)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name, color = Ink, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                m.broken -> "interrupted create — remove to clean up"
                                m.running -> "${m.distro} ${m.release} · pid ${m.pid}"
                                else -> "${m.distro} ${m.release} · stopped"
                            },
                            color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    StatusDot(
                        when {
                            m.broken -> Red
                            m.running -> Green
                            else -> Dim
                        },
                        pulsing = m.running,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!m.broken) {
                        Pill("terminal", Green, filled = true) { onTerminal(m) }
                        if (m.running) Pill("stop", Amber) { onStop(m) }
                        else Pill("start", Muted) { onStart(m) }
                        Pill("logs", Muted) { onLogs(m) }
                    }
                    Pill("remove", Red) { onRemove(m) }
                }
            }
        }
    }
}

@Composable
private fun CatalogTab(
    stacks: List<Engine.Stack>,
    machines: List<Engine.Machine>,
    installed: Set<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onInstall: (Engine.Stack, String) -> Unit,
) {
    val usable = machines.filter { !it.broken }
    val target = selected ?: usable.firstOrNull()?.name

    Column {
        SectionTitle("CATALOG")
        Spacer(Modifier.height(12.dp))

        if (usable.isEmpty()) {
            Card {
                Text("create a machine first", color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "stacks install into a machine — make one on the machines tab",
                    color = Muted, fontSize = 12.sp,
                )
            }
            return@Column
        }

        // "installed" is a fact about a machine, so the machine is part of
        // the question the page answers.
        Text("showing what's installed in:", color = Dim, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usable.forEach { m ->
                Pill(m.name, Green, filled = m.name == target) { onSelect(m.name) }
            }
        }
        Spacer(Modifier.height(16.dp))

        val fits = stacks.filter { s -> usable.any { it.name == target && s.runsOn(it.distro) } }
        val (have, rest) = fits.partition { it.name in installed }

        if (have.isNotEmpty()) {
            SectionTitle("INSTALLED HERE")
            Spacer(Modifier.height(10.dp))
            have.forEach { s ->
                StackCard(s, true, target) { onOpenTerminal(target ?: return@StackCard) }
            }
            Spacer(Modifier.height(18.dp))
        }

        SectionTitle("AVAILABLE")
        Spacer(Modifier.height(10.dp))
        rest.forEach { s ->
            StackCard(s, false, target) { m -> onInstall(s, m) }
        }
        if (rest.isEmpty()) {
            Text("everything in the catalog is installed here", color = Dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StackCard(
    s: Engine.Stack,
    isInstalled: Boolean,
    machine: String?,
    onAction: (String) -> Unit,
) {
    Card(
        Modifier.padding(bottom = 10.dp),
        accent = if (isInstalled) Green else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.description, color = Muted, fontSize = 12.sp)
                val notes = listOfNotNull(
                    s.category.takeIf { it.isNotEmpty() },
                    s.size.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(notes, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                // Only the unusual case is flagged. No badge means nothing
                // is wrong — a tick on everything told users nothing.
                if (!s.verified) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "not yet verified on a phone",
                        color = Amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (machine != null) {
                if (isInstalled) Pill("open", Green, filled = true) { onAction(machine) }
                else Pill("install", Amber, filled = true) { onAction(machine) }
            }
        }
    }
}
