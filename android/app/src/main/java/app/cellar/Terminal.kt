package app.cellar

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The terminal: xterm.js in a WebView, wired to a PTY-backed session.
 *
 * The bridge is deliberately tiny — bytes in, bytes out, plus a window
 * size. All the terminal behaviour (escape sequences, colours, history,
 * selection) belongs to xterm.js, and the exact same HTML/JS is tested
 * in a browser against a real machine before it ships.
 */
class TerminalBridge(private val session: TerminalSession) {
    @JavascriptInterface
    fun send(b64: String) {
        session.send(b64)
    }

    @JavascriptInterface
    fun resize(cols: Int, rows: Int) {
        session.resize(cols, rows)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(session: TerminalSession, modifier: Modifier = Modifier) {
    var web by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(session) {
        onDispose {
            // the session keeps running; only this view detaches
            session.disconnect()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0xFF05070A.toInt())
                addJavascriptInterface(TerminalBridge(session), "CellarBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        session.connect { b64 ->
                            view.post {
                                view.evaluateJavascript("window.termWrite('$b64')", null)
                            }
                        }
                        view.evaluateJavascript("window.termFit && window.termFit()", null)
                    }
                }
                loadUrl("file:///android_asset/term/index.html")
                web = this
            }
        },
    )
}

@Composable
fun TerminalTab(
    engine: Engine,
    machines: List<Engine.Machine>,
    initialMachine: String?,
    onPick: (String) -> Unit,
) {
    val usable = machines.filter { !it.broken }
    var machine by remember(initialMachine, usable.size) {
        mutableStateOf(initialMachine ?: usable.firstOrNull()?.name)
    }

    Column(Modifier.fillMaxSize()) {
        SectionTitle("TERMINAL") {
            machine?.let { m ->
                Pill("restart", Muted) {
                    SessionStore.close(m)
                    onPick(m)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (usable.isEmpty()) {
            Card {
                Text("no machine to open", color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text("create one on the machines tab", color = Muted, fontSize = 12.sp)
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            usable.forEach { m ->
                Pill(m.name, Green, filled = m.name == machine) {
                    machine = m.name
                    onPick(m.name)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val active = machine
        if (active != null) {
            val session = remember(active) { SessionStore.open(engine, active) }
            Card(Modifier.fillMaxWidth().height(520.dp), accent = Green) {
                TerminalView(session, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "a real shell — sessions keep running when you leave the app",
                color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}
