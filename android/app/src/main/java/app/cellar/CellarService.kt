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
 * The lifecycle anchor. Android will freeze or kill background work —
 * and a rootfs download or a running machine is exactly that — so any
 * long-lived engine work runs while this foreground service holds a
 * notification and a wake lock.
 *
 * Deliberately not a binder/AIDL surface: the UI calls the engine
 * directly and uses this only to say "keep us alive, and here's why".
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
        return START_NOT_STICKY // work is resumed by the user, never silently
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
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

        fun start(context: Context, status: String) {
            val i = Intent(context, CellarService::class.java).putExtra(EXTRA_STATUS, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CellarService::class.java))
        }
    }
}
