package app.cellar

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

/**
 * Chat drives an agent already installed in a machine: the app runs the
 * stack's headless command with the prompt and streams the reply back.
 * No second implementation of tool use, and the app can never disagree
 * with the machine about what's installed (docs/M3.md).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatTab(
    engine: Engine,
    vault: Vault,
    machines: List<Engine.Machine>,
    stacks: List<Engine.Stack>,
) {
    val scope = rememberCoroutineScope()
    val transcript = remember { mutableStateListOf<String>() }
    var prompt by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val agents = stacks.filter { it.chatCapable }
    var agent by remember {
        mutableStateOf(agents.firstOrNull { it.verified } ?: agents.firstOrNull())
    }
    val usable = machines.filter { !it.broken && (agent?.runsOn(it.distro) ?: true) }
    var machine by remember { mutableStateOf(usable.firstOrNull()?.name) }

    Column {
        SectionTitle("CHAT") {
            if (transcript.isNotEmpty()) Pill("clear", Muted) { transcript.clear() }
        }
        Spacer(Modifier.height(12.dp))

        if (agents.isEmpty() || usable.isEmpty()) {
            Card {
                Text("nothing to chat with yet", color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "install an agent from the catalog — Claude Code, OpenCode, Aider or " +
                        "Goose — then add its API key on the setup tab",
                    color = Muted, fontSize = 12.sp,
                )
            }
            return@Column
        }

        Card {
            Text("agent", color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                agents.forEach { a ->
                    Pill(a.name, Amber, filled = a.name == agent?.name) { agent = a }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("machine", color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usable.forEach { m ->
                    Pill(m.name, Green, filled = m.name == machine) { machine = m.name }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        OutputPane(
            transcript,
            "ask something — it runs inside ${machine ?: "a machine"} and answers here",
            Modifier.heightIn(min = 180.dp),
        )
        Spacer(Modifier.height(12.dp))

        Field(prompt, "message…", modifier = Modifier.fillMaxWidth()) { prompt = it }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val a = agent
            val m = machine
            if (thinking) {
                StatusDot(Amber, pulsing = true)
                Spacer(Modifier.width(8.dp))
                Text(
                    "thinking…", color = Amber, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (a != null && m != null) {
                Pill("send", Amber, filled = true) {
                    val text = prompt.trim()
                    if (text.isEmpty()) return@Pill
                    prompt = ""
                    thinking = true
                    transcript.add("> $text")
                    val env = a.needsKey.takeIf { it.isNotEmpty() }
                        ?.let { k -> vault.get(k)?.let { mapOf(k to it) } } ?: emptyMap()
                    scope.launch {
                        try {
                            if (a.needsKey.isNotEmpty() && env.isEmpty()) {
                                transcript.add("! no ${a.needsKey} saved — add it on the setup tab")
                            } else {
                                val argv = a.chat.split(" ").filter { it.isNotEmpty() } + text
                                engine.exec(m, argv, env) { line ->
                                    scope.launch { transcript.add(line) }
                                }
                            }
                        } catch (e: Exception) {
                            transcript.add("! ${e.message ?: e.toString()}")
                        } finally {
                            transcript.add("")
                            thinking = false
                        }
                    }
                }
            }
        }
    }
}

private val QUICK = listOf("ls -la", "df -h /", "free -m", "apt update", "ps aux | head")

/**
 * Terminal mode. One command at a time, shown as a history of cards
 * rather than a wall of scrollback — a phone screen is not an 80×24
 * terminal, and pretending otherwise was the first version's mistake.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsoleTab(engine: Engine, machines: List<Engine.Machine>) {
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<Pair<String, MutableList<String>>>() }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val usable = machines.filter { !it.broken }
    var machine by remember { mutableStateOf(usable.firstOrNull()?.name) }

    fun run(cmd: String) {
        val m = machine ?: return
        if (cmd.isBlank() || running) return
        running = true
        val out = mutableStateListOf<String>()
        history.add(cmd to out)
        scope.launch {
            try {
                engine.exec(m, listOf(cmd)) { line -> scope.launch { out.add(line) } }
            } catch (e: Exception) {
                out.add("! ${e.message ?: e.toString()}")
            } finally {
                running = false
            }
        }
    }

    Column {
        SectionTitle("CONSOLE") {
            if (history.isNotEmpty()) Pill("clear", Muted) { history.clear() }
        }
        Spacer(Modifier.height(12.dp))

        if (usable.isEmpty()) {
            Card {
                Text("no machine to run in", color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text("create one on the machines tab", color = Muted, fontSize = 12.sp)
            }
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usable.forEach { m ->
                Pill(m.name, Green, filled = m.name == machine) { machine = m.name }
            }
        }
        Spacer(Modifier.height(14.dp))

        history.forEach { (cmd, out) ->
            Card(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$", color = Green, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        cmd, color = Ink, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    )
                }
                if (out.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    out.takeLast(40).forEach {
                        Text(
                            it, color = Muted, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        Field(command, "type a command…", modifier = Modifier.fillMaxWidth()) { command = it }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                StatusDot(Amber, pulsing = true)
                Spacer(Modifier.width(8.dp))
                Text(
                    "running…", color = Amber, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Pill("run", Amber, filled = true) {
                    val c = command.trim()
                    command = ""
                    run(c)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK.forEach { q -> Chip(q) { command = q } }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "one command at a time — not a full terminal yet (no vim/htop)",
            color = Dim, fontSize = 10.sp,
        )
    }
}

/** Device compatibility, the settings Android lets us ask for, and keys. */
@Composable
fun SetupTab(engine: Engine, vault: Vault, context: Context) {
    var checks by remember { mutableStateOf(Setup.checks(context, engine)) }
    var keyName by remember { mutableStateOf("ANTHROPIC_API_KEY") }
    var keyValue by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(vault.names()) }

    Column {
        SectionTitle("THIS PHONE") {
            Pill("re-check", Muted) { checks = Setup.checks(context, engine) }
        }
        Spacer(Modifier.height(12.dp))

        checks.forEach { c ->
            val tint = when (c.state) {
                Setup.State.OK -> Green
                Setup.State.WARN -> Amber
                Setup.State.BLOCKED -> Red
                Setup.State.INFO -> Muted
            }
            Card(Modifier.padding(bottom = 10.dp), accent = if (c.state == Setup.State.OK) null else tint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tint)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        c.title, color = Ink, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    c.actionLabel?.let { label ->
                        Pill(label, Amber, filled = c.state == Setup.State.WARN) {
                            c.action?.invoke(context)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(c.detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("API KEYS")
        Spacer(Modifier.height(8.dp))
        Text(
            "encrypted by the Android Keystore and injected only while a command runs — " +
                "never written into a machine, never included in an export",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))

        saved.forEach { name ->
            Card(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, color = Ink, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            vault.preview(name), color = Dim, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Pill("forget", Red) {
                        vault.remove(name)
                        saved = vault.names()
                    }
                }
            }
        }

        Field(keyName, "KEY_NAME", modifier = Modifier.fillMaxWidth()) { keyName = it }
        Spacer(Modifier.height(8.dp))
        Field(keyValue, "paste the key", secret = true, modifier = Modifier.fillMaxWidth()) {
            keyValue = it
        }
        Spacer(Modifier.height(12.dp))
        Pill("save key", Green, filled = true) {
            if (keyName.isNotBlank() && keyValue.isNotBlank()) {
                vault.put(keyName.trim(), keyValue.trim())
                keyValue = ""
                saved = vault.names()
            }
        }
    }
}
