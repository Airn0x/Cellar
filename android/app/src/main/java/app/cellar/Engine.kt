package app.cellar

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * The engine is the source of truth: this app renders what the CLI
 * reports rather than keeping its own copy of machine state.
 *
 * Both native binaries live in nativeLibraryDir — the only place Android
 * allows exec from (W^X) — and proot needs its loader passed explicitly,
 * since it cannot extract its embedded one there (see proot/README.md).
 */
class Engine(context: Context) {

    private val libs = File(context.applicationInfo.nativeLibraryDir)
    private val engineBin = File(libs, "libcellar.so")
    private val prootBin = File(libs, "libproot.so")
    private val loaderBin = File(libs, "libproot_loader.so")
    private val home = File(context.filesDir, "cellar")
    private val tmp = File(context.cacheDir, "cellar-tmp")

    val isBundled: Boolean get() = engineBin.exists()
    val hasProot: Boolean get() = prootBin.exists() && loaderBin.exists()

    data class Result(val ok: Boolean, val output: String)

    data class Machine(
        val name: String,
        val distro: String,
        val release: String,
        val running: Boolean,
        val pid: Int,
        val broken: Boolean,
    )

    /** Runs the engine and returns its combined output. */
    suspend fun run(vararg args: String): Result = withContext(Dispatchers.IO) {
        if (!isBundled) return@withContext Result(false, "engine binary missing")
        home.mkdirs(); tmp.mkdirs()
        try {
            val pb = ProcessBuilder(listOf(engineBin.absolutePath) + args).redirectErrorStream(true)
            pb.environment().apply {
                put("CELLAR_HOME", home.absolutePath)
                put("CELLAR_PROOT", prootBin.absolutePath)
                put("PROOT_LOADER", loaderBin.absolutePath)
                put("TMPDIR", tmp.absolutePath)
            }
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            Result(p.waitFor() == 0, out)
        } catch (e: Exception) {
            Result(false, e.message ?: e.toString())
        }
    }

    suspend fun version(): String? = run("version").let { if (it.ok) it.output else null }

    suspend fun list(): List<Machine> {
        val r = run("ls", "--json")
        if (!r.ok) return emptyList()
        return try {
            val arr = JSONArray(r.output.ifEmpty { "[]" })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Machine(
                    name = o.optString("name"),
                    distro = o.optString("distro"),
                    release = o.optString("release"),
                    running = o.optBoolean("running"),
                    pid = o.optInt("pid"),
                    broken = o.optBoolean("broken"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One-line health summary for the dashboard. */
    suspend fun status(): String {
        if (!isBundled) return "engine not bundled (local dev build)"
        val v = version() ?: return "engine exec failed — W^X or packaging problem"
        if (!hasProot) return "$v · proot not bundled"
        // `ls` proves the app-private home is usable; proot only spawns
        // once a machine exists, so this stays cheap at startup.
        val r = run("ls", "--json")
        if (!r.ok) return "$v · cannot use app-private home"
        return "$v · engine + proot ready"
    }

    companion object {
        fun debuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
