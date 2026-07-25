package app.cellar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What this phone can and cannot do, checked rather than assumed.
 *
 * An app can never flip a system setting for the user — only detect the
 * state, explain it, and deep-link to the right screen. Every item here
 * follows that shape, and the ones that are genuinely impossible say so
 * instead of pretending (docs/M3.md).
 */
object Setup {

    enum class State { OK, WARN, BLOCKED, INFO }

    data class Check(
        val title: String,
        val detail: String,
        val state: State,
        val actionLabel: String? = null,
        val action: ((Context) -> Unit)? = null,
    )

    fun checks(context: Context, engine: Engine): List<Check> = listOf(
        arch(),
        androidVersion(),
        binaries(engine),
        storage(context),
        battery(context),
        oem(),
        phantom(),
    )

    private fun arch(): Check {
        val ok = Build.SUPPORTED_ABIS.contains("arm64-v8a")
        return Check(
            title = "64-bit ARM processor",
            detail = if (ok) "arm64-v8a — supported"
            else "This phone is ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}. " +
                "Cellar needs arm64; machines cannot run here.",
            state = if (ok) State.OK else State.BLOCKED,
        )
    }

    private fun androidVersion(): Check {
        val ok = Build.VERSION.SDK_INT >= 29
        return Check(
            title = "Android version",
            detail = if (ok) "Android ${Build.VERSION.RELEASE} — supported"
            else "Android ${Build.VERSION.RELEASE} is older than Cellar supports (10+).",
            state = if (ok) State.OK else State.BLOCKED,
        )
    }

    private fun binaries(engine: Engine): Check {
        val ok = engine.isBundled && engine.hasProot
        return Check(
            title = "Engine and proot",
            detail = if (ok) "both native binaries present and executable"
            else "missing from this build — the app cannot create machines",
            state = if (ok) State.OK else State.BLOCKED,
        )
    }

    private fun storage(context: Context): Check {
        val freeMb = context.filesDir.usableSpace / (1024 * 1024)
        val state = when {
            freeMb > 2000 -> State.OK
            freeMb > 500 -> State.WARN
            else -> State.BLOCKED
        }
        return Check(
            title = "Free storage",
            detail = "$freeMb MB available — a Debian machine needs about 400 MB, " +
                "Alpine about 30 MB",
            state = state,
        )
    }

    private fun battery(context: Context): Check {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        return Check(
            title = "Battery optimization",
            detail = if (exempt) "Cellar is exempt — long jobs can finish"
            else "Android may freeze downloads and running machines when the screen " +
                "is off. This is the single most useful setting to change.",
            state = if (exempt) State.OK else State.WARN,
            actionLabel = if (exempt) null else "allow",
            action = if (exempt) null else { ctx ->
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }

    /** Vendor power managers are the biggest real-world difference between phones. */
    private fun oem(): Check {
        val maker = Build.MANUFACTURER.lowercase()
        val advice = when {
            maker.contains("samsung") ->
                "Settings → Battery → Background usage limits: make sure Cellar is not " +
                    "in “Sleeping apps” or “Deep sleeping apps”."
            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") ->
                "Security app → Permissions → Autostart: enable Cellar. Then in Battery " +
                    "saver, set Cellar to “No restrictions”."
            maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus") ->
                "Settings → Battery → App battery management: set Cellar to “Allow " +
                    "background activity” / “Don't optimise”."
            maker.contains("vivo") || maker.contains("iqoo") ->
                "Settings → Battery → High background power consumption: allow Cellar."
            maker.contains("huawei") || maker.contains("honor") ->
                "Settings → Battery → App launch: switch Cellar to “Manage manually” and " +
                    "enable all three toggles."
            maker.contains("google") || maker.contains("motorola") || maker.contains("nothing") ->
                "This phone runs close to stock Android — the battery setting above is " +
                    "usually all that's needed."
            else ->
                "Some manufacturers add their own app-killer beyond Android's. If machines " +
                    "die when the screen is off, look for an “autostart” or “protected apps” " +
                    "setting for ${Build.MANUFACTURER}."
        }
        return Check(
            title = "${Build.MANUFACTURER} power manager",
            detail = advice,
            state = State.INFO,
            actionLabel = "open settings",
            action = { ctx ->
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }

    private fun phantom(): Check = Check(
        title = "Phantom process killer",
        detail = "Android 12+ kills apps that run more than 32 child processes. " +
            "No app can change this — it needs ADB or Shizuku. Cellar keeps one " +
            "process per machine so it rarely matters; if a machine dies for no " +
            "reason with a heavy workload, this is usually why.",
        state = State.INFO,
    )
}
