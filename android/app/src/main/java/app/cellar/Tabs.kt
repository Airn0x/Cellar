package app.cellar

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * Chat drives an agent that is already installed in a machine: the app
 * runs the stack's headless command with the prompt and streams the
 * reply back. No second implementation of tool use, and the app can
 * never disagree with the machine about what's installed (docs/M3.md).
 */
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
    var agent by remember { mutableStateOf(agents.firstOrNull { it.verified } ?: agents.firstOrNull()) }
    val usable = machines.filter { !it.broken && (agent?.runsOn(it.distro) ?: true) }
    var machine by remember { mutableStateOf(usable.firstOrNull()?.name) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("CHAT WITH AN AGENT")
        Spacer(Modifier.height(10.dp))

        if (agents.isEmpty() || usable.isEmpty()) {
            Card {
                Text("nothing to chat with yet", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "install an agent from the catalog (Claude Code, OpenCode, Aider, " +
                        "Goose) and add its API key on the setup tab",
                    color = Dim, fontSize = 12.sp,
                )
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            agents.forEach { a ->
                Pill(a.name, if (a.name == agent?.name) Amber else Muted) { agent = a }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usable.forEach { m ->
                Pill(m.name, if (m.name == machine) Green else Muted) { machine = m.name }
            }
        }
        Spacer(Modifier.height(14.dp))

        OutputPane(
            transcript,
            "ask something — it runs inside ${machine ?: "a machine"} and answers here",
            Modifier.heightIn(min = 200.dp),
        )
        Spacer(Modifier.height(10.dp))

        Field(prompt, "message…", modifier = Modifier.fillMaxWidth()) { prompt = it }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val a = agent
            val m = machine
            if (thinking) {
                Text("thinking…", color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else if (a != null && m != null) {
                Pill("send", Amber) {
                    val text = prompt.trim()
                    if (text.isEmpty()) return@Pill
                    prompt = ""
                    thinking = true
                    transcript.add("> $text")
                    val env = a.needsKey.takeIf { it.isNotEmpty() }
                        ?.let { k -> vault.get(k)?.let { mapOf(k to it) } } ?: emptyMap()
                    scope.launch {
                        if (a.needsKey.isNotEmpty() && env.isEmpty()) {
                            transcript.add("! no ${a.needsKey} saved — add it on the setup tab")
                        } else {
                            val argv = a.chat.split(" ").filter { it.isNotEmpty() } + text
                            engine.exec(m, argv, env) { line ->
                                scope.launch { transcript.add(line) }
                            }
                        }
                        transcript.add("")
                        thinking = false
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (transcript.isNotEmpty()) Pill("clear", Muted) { transcript.clear() }
        }
    }
}

/**
 * Terminal mode, honestly labelled: this runs one command at a time and
 * shows its output. It is not a PTY — no vim, no htop, no interactive
 * prompts. A real terminal is M4 work.
 */
@Composable
fun ConsoleTab(engine: Engine, machines: List<Engine.Machine>) {
    val scope = rememberCoroutineScope()
    val out = remember { mutableStateListOf<String>() }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val usable = machines.filter { !it.broken }
    var machine by remember { mutableStateOf(usable.firstOrNull()?.name) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("COMMAND CONSOLE")
        Spacer(Modifier.height(10.dp))

        if (usable.isEmpty()) {
            Card {
                Text("no machine to run in", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("create one on the machines tab", color = Dim, fontSize = 12.sp)
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usable.forEach { m ->
                Pill(m.name, if (m.name == machine) Green else Muted) { machine = m.name }
            }
        }
        Spacer(Modifier.height(14.dp))

        OutputPane(out, "output appears here", Modifier.heightIn(min = 220.dp))
        Spacer(Modifier.height(10.dp))
        Field(command, "apt install …", modifier = Modifier.fillMaxWidth()) { command = it }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val m = machine
            if (running) {
                Text("running…", color = Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else if (m != null) {
                Pill("run", Amber) {
                    val cmd = command.trim()
                    if (cmd.isEmpty()) return@Pill
                    running = true
                    out.add("$ $cmd")
                    scope.launch {
                        engine.exec(m, listOf(cmd)) { line -> scope.launch { out.add(line) } }
                        out.add("")
                        running = false
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (out.isNotEmpty()) Pill("clear", Muted) { out.clear() }
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

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("THIS PHONE")
            Spacer(Modifier.weight(1f))
            Pill("re-check", Muted) { checks = Setup.checks(context, engine) }
        }
        Spacer(Modifier.height(10.dp))

        checks.forEach { c ->
            Card(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (mark, tint) = when (c.state) {
                        Setup.State.OK -> "✓" to Green
                        Setup.State.WARN -> "!" to Amber
                        Setup.State.BLOCKED -> "✕" to Red
                        Setup.State.INFO -> "i" to Muted
                    }
                    Text(mark, color = tint, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  ${c.title}", color = Ink, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    c.actionLabel?.let { label ->
                        Pill(label, Amber) {
                            c.action?.invoke(context)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(c.detail, color = Muted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("API KEYS")
        Spacer(Modifier.height(6.dp))
        Text(
            "encrypted by the Android Keystore, injected only while a command runs — " +
                "never written into a machine, never included in an export",
            color = Dim, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))

        saved.forEach { name ->
            Card(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
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
        Spacer(Modifier.height(10.dp))
        Pill("save key", Green) {
            if (keyName.isNotBlank() && keyValue.isNotBlank()) {
                vault.put(keyName.trim(), keyValue.trim())
                keyValue = ""
                saved = vault.names()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
