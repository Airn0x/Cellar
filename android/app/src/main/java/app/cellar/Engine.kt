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

    private val app = context.applicationContext
    private val libs = File(context.applicationInfo.nativeLibraryDir)
    private val engineBin = File(libs, "libcellar.so")
    private val prootBin = File(libs, "libproot.so")
    private val loaderBin = File(libs, "libproot_loader.so")
    private val home = File(context.filesDir, "cellar")
    private val tmp = File(context.cacheDir, "cellar-tmp")
    private val catalogDir = File(context.filesDir, "catalog")

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

    /** A catalog stack, as the engine describes it. */
    data class Stack(
        val name: String,
        val description: String,
        val category: String,
        val size: String,
        val verified: Boolean,
        val needsKey: String,
        val chat: String,
        val check: String,
        val distros: List<String>,
    ) {
        val chatCapable: Boolean get() = chat.isNotEmpty()
        fun runsOn(distro: String) = distros.isEmpty() || distro in distros
    }

    /**
     * Unpacks the bundled catalog into app storage. The engine reads
     * stacks from disk, and an APK's assets are not a filesystem path —
     * without this the catalog is simply empty inside the app.
     * Re-extracted whenever the app version changes.
     */
    private fun ensureCatalog(): File {
        val stamp = File(catalogDir, ".version")
        val want = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
        if (stamp.isFile && stamp.readText() == want) return catalogDir
        catalogDir.deleteRecursively()
        catalogDir.mkdirs()
        copyAsset("catalog", catalogDir)
        runCatching { stamp.writeText(want) }
        return catalogDir
    }

    private fun copyAsset(assetPath: String, dest: File) {
        val children = try {
            app.assets.list(assetPath) ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        if (children.isEmpty()) { // a file
            runCatching {
                app.assets.open(assetPath).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
            return
        }
        dest.mkdirs()
        children.forEach { child ->
            copyAsset("$assetPath/$child", File(dest, child))
        }
    }

    private fun processBuilder(args: List<String>, mergeStderr: Boolean = true): ProcessBuilder {
        home.mkdirs(); tmp.mkdirs()
        val pb = ProcessBuilder(listOf(engineBin.absolutePath) + args)
            .redirectErrorStream(mergeStderr)
        pb.environment().apply {
            put("CELLAR_HOME", home.absolutePath)
            put("CELLAR_PROOT", prootBin.absolutePath)
            put("PROOT_LOADER", loaderBin.absolutePath)
            put("CELLAR_CATALOG", ensureCatalog().absolutePath)
            put("TMPDIR", tmp.absolutePath)
        }
        return pb
    }

    /** Runs the engine and returns its combined output. */
    suspend fun run(vararg args: String): Result = withContext(Dispatchers.IO) {
        if (!isBundled) return@withContext Result(false, "engine binary missing")
        try {
            val p = processBuilder(args.toList()).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            Result(p.waitFor() == 0, out)
        } catch (e: Exception) {
            Result(false, e.message ?: e.toString())
        }
    }

    /**
     * Runs the engine, delivering each output line as it arrives — the
     * engine's own progress lines ("downloaded 90.3 MB, sha256 ok") are
     * the create wizard's progress UI. Honest beats invented percentages.
     */
    suspend fun stream(vararg args: String, onLine: (String) -> Unit): Result =
        withContext(Dispatchers.IO) {
            if (!isBundled) return@withContext Result(false, "engine binary missing")
            try {
                val p = processBuilder(args.toList()).start()
                val last = StringBuilder()
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        onLine(line)
                        last.setLength(0)
                        last.append(line)
                    }
                }
                Result(p.waitFor() == 0, last.toString())
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

    /**
     * Starts a PTY session. Raw handle rather than a suspend call: the
     * session outlives any one screen, and stderr must NOT be merged —
     * engine warnings would corrupt the terminal byte stream.
     */
    fun attachProcess(machine: String, cols: Int, rows: Int): Process =
        processBuilder(
            listOf("attach", machine, "--cols", cols.toString(), "--rows", rows.toString()),
            mergeStderr = false,
        ).start()

    suspend fun catalog(): List<Stack> {
        val r = run("catalog", "--json")
        if (!r.ok) return emptyList()
        return try {
            val arr = JSONArray(r.output.ifEmpty { "[]" })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val d = o.optJSONArray("distros")
                Stack(
                    name = o.optString("name"),
                    description = o.optString("description"),
                    category = o.optString("category", "other"),
                    size = o.optString("size"),
                    verified = o.optBoolean("verified"),
                    needsKey = o.optString("needs_key"),
                    chat = o.optString("chat"),
                    check = o.optString("check"),
                    distros = if (d == null) emptyList() else (0 until d.length()).map { d.getString(it) },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Which stacks this machine already has — the fact users care about. */
    suspend fun installed(machine: String): Set<String> {
        val r = run("installed", machine, "--json")
        if (!r.ok) return emptySet()
        return try {
            val o = org.json.JSONObject(r.output.ifEmpty { "{}" })
            o.keys().asSequence().filter { o.optBoolean(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun apply(machine: String, stack: String, onLine: (String) -> Unit): Result =
        stream("apply", machine, stack, onLine = onLine)

    /**
     * Runs a command inside a machine. Secrets arrive as `-e KEY=VALUE`
     * and live only for this process — never written into the rootfs.
     */
    suspend fun exec(
        machine: String,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Result {
        val args = mutableListOf("exec", machine)
        env.forEach { (k, v) -> args += listOf("-e", "$k=$v") }
        args += "--"
        args += argv
        return stream(*args.toTypedArray(), onLine = onLine)
    }

    suspend fun create(name: String, distro: String, onLine: (String) -> Unit): Result =
        stream("create", name, "--distro", distro, onLine = onLine)

    suspend fun start(name: String): Result = run("start", name)

    suspend fun stop(name: String): Result = run("stop", name)

    suspend fun remove(name: String): Result = run("rm", name, "--force")

    suspend fun logs(name: String): String = run("logs", name).output

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
