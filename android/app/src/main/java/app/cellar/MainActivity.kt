package app.cellar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The cellar palette (matches the site).
private val Bg = Color(0xFF07090D)
private val Ink = Color(0xFFE9E5DA)
private val Muted = Color(0xFF96A0B0)
private val Amber = Color(0xFFFFB454)
private val Green = Color(0xFF46D47E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Amber)) {
                Home(::probeEngine)
            }
        }
    }

    // M2 step 3's whole point: prove the Go engine executes inside this
    // app's sandbox, from nativeLibraryDir, before any real UI exists.
    private fun probeEngine(): String {
        val engine = File(applicationInfo.nativeLibraryDir, "libcellar.so")
        if (!engine.exists()) return "engine not bundled (local dev build)"
        return try {
            val p = ProcessBuilder(engine.absolutePath, "version")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (out.isNotEmpty()) "$out — alive inside the app sandbox" else "engine ran, empty output"
        } catch (e: Exception) {
            "engine exec failed: ${e.message}"
        }
    }
}

@androidx.compose.runtime.Composable
private fun Home(probe: () -> String) {
    var status by remember { mutableStateOf("probing engine…") }
    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { probe() }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("cellar", color = Ink, fontSize = 34.sp, fontFamily = FontFamily.Monospace)
        Text(
            "the server room in your cellphone",
            color = Amber, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
        )
        Text("● $status", color = Green, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Text(
            "machines · catalog · schedules — under construction",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
