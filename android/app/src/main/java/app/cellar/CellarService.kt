package app.cellar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps Cellar alive while it has something to keep alive.
 *
 * This matters more than it looks: every proot process is a child of the
 * app process, so if Android freezes or kills the app, running machines
 * die with it. The service therefore lives as long as *any* machine is
 * running — not just for the duration of a button press, which is what
 * an earlier build did (machines silently stopped in the background).
 */
class CellarService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "working"
        startForeground(NOTIFICATION_ID, notification(status))
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cellar:work").apply {
                setReferenceCounted(false)
                acquire(WAKE_LIMIT_MS)
            }
        }
        return START_NOT_STICKY // work resumes when the user asks, never silently
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(status: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Cellar", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Machines and long-running work"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("cellar")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "cellar.work"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_STATUS = "status"

        // A phone is not a datacenter: never hold the CPU awake forever.
        private const val WAKE_LIMIT_MS = 60L * 60 * 1000

        /**
         * Single entry point: the UI reports what's happening and the
         * service decides whether it should exist.
         */
        fun sync(context: Context, running: Int, busyLabel: String?) {
            val status = when {
                busyLabel != null && running > 0 -> "$busyLabel · $running running"
                busyLabel != null -> busyLabel
                running == 1 -> "1 machine running"
                running > 1 -> "$running machines running"
                else -> null
            }
            if (status == null) {
                context.stopService(Intent(context, CellarService::class.java))
                return
            }
            val i = Intent(context, CellarService::class.java).putExtra(EXTRA_STATUS, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
