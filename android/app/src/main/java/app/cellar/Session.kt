package app.cellar

import android.util.Base64
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * A live terminal session: one `cellar attach` process holding a PTY.
 *
 * Sessions outlive the screen that shows them. Leaving the terminal tab —
 * or the whole app — must not kill a shell that is mid-compile, so these
 * live in a process-scoped store and the foreground service keeps the
 * process itself alive.
 */
class TerminalSession(private val engine: Engine, val machine: String) {

    private val proc: Process = engine.attachProcess(machine, 80, 24)
    private val writer = OutputStreamWriter(proc.outputStream)

    /** Recent output, replayed into a fresh WebView on return. */
    private val scrollback = ArrayDeque<String>()
    private var scrollbackBytes = 0

    @Volatile
    private var sink: ((String) -> Unit)? = null

    val alive: Boolean get() = proc.isAlive

    init {
        thread(isDaemon = true, name = "cellar-pty-$machine") {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = proc.inputStream.read(buf)
                    if (n <= 0) break
                    val b64 = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
                    remember(b64)
                    sink?.invoke(b64)
                }
            } catch (e: Exception) {
                // process gone; the screen shows it as ended
            }
        }
    }

    private fun remember(b64: String) = synchronized(scrollback) {
        scrollback.addLast(b64)
        scrollbackBytes += b64.length
        while (scrollbackBytes > MAX_SCROLLBACK && scrollback.isNotEmpty()) {
            scrollbackBytes -= scrollback.removeFirst().length
        }
    }

    /** Connects a view, replaying what it missed. */
    fun connect(onOutput: (String) -> Unit) {
        val history = synchronized(scrollback) { scrollback.toList() }
        history.forEach(onOutput)
        sink = onOutput
    }

    fun disconnect() {
        sink = null
    }

    fun send(b64: String) = runCatching {
        synchronized(writer) { writer.write("i $b64\n"); writer.flush() }
    }

    fun resize(cols: Int, rows: Int) = runCatching {
        synchronized(writer) { writer.write("r $cols $rows\n"); writer.flush() }
    }

    fun close() = runCatching {
        sink = null
        writer.close()
        proc.destroy()
    }

    private companion object {
        const val MAX_SCROLLBACK = 400_000 // base64 chars ≈ 300 KB of output
    }
}

/** Process-scoped: sessions survive screens, tabs and app backgrounding. */
object SessionStore {
    private val sessions = mutableMapOf<String, TerminalSession>()

    @Synchronized
    fun open(engine: Engine, machine: String): TerminalSession {
        val existing = sessions[machine]
        if (existing != null && existing.alive) return existing
        existing?.close()
        return TerminalSession(engine, machine).also { sessions[machine] = it }
    }

    @Synchronized
    fun peek(machine: String): TerminalSession? = sessions[machine]?.takeIf { it.alive }

    @Synchronized
    fun close(machine: String) {
        sessions.remove(machine)?.close()
    }

    @Synchronized
    fun openCount(): Int = sessions.values.count { it.alive }
}
