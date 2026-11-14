package com.cococopi.dualaudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cococopi.dualaudio.routing.ShizukuManager

/**
 * Keeps the optional forced-routing session visible and reapplies it after a
 * reconnect or a Shizuku restart. The actual routing runs in the Shizuku user
 * service; this service only orchestrates it.
 */
class DualAudioService : Service() {

    private lateinit var prefs: AppPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var nameA: String = ""
    private var nameB: String = ""

    private val ticker = object : Runnable {
        override fun run() {
            reapplyIfNeeded()
            handler.postDelayed(this, REAPPLY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        ShizukuManager.init(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRouting()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                nameA = intent.getStringExtra(EXTRA_NAME_A).orEmpty()
                nameB = intent.getStringExtra(EXTRA_NAME_B).orEmpty()
                startForegroundNotification()
                handler.removeCallbacks(ticker)
                if (prefs.autoApply) {
                    handler.postDelayed(ticker, REAPPLY_INTERVAL_MS)
                }
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_dual)
        .setContentTitle(getString(R.string.notification_title_active))
        .setContentText(
            if (nameA.isNotBlank() && nameB.isNotBlank()) {
                getString(R.string.notification_body_active, nameA, nameB)
            } else {
                getString(R.string.notification_body_waiting)
            },
        )
        .setContentIntent(contentIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(
            R.drawable.ic_stop,
            getString(R.string.notification_stop),
            stopIntent(),
        )
        .build()

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, DualAudioService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopRouting() {
        handler.removeCallbacks(ticker)
        prefs.routingActive = false
        ShizukuManager.clearRouting()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reapplyIfNeeded() {
        if (!prefs.autoApply || !prefs.routingActive) return
        if (!ShizukuManager.isReady) {
            ShizukuManager.refresh()
            return
        }
        val addressA = prefs.deviceAAddress ?: return
        val addressB = prefs.deviceBAddress ?: return
        ShizukuManager.applyRouting(listOf(addressA, addressB)) { result ->
            result.onFailure {
                // Keep the service alive; the next tick may succeed.
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.cococopi.dualaudio.action.START"
        const val ACTION_STOP = "com.cococopi.dualaudio.action.STOP"
        const val EXTRA_NAME_A = "name_a"
        const val EXTRA_NAME_B = "name_b"

        private const val CHANNEL_ID = "dual_audio_routing"
        private const val NOTIFICATION_ID = 41
        private const val REAPPLY_INTERVAL_MS = 30_000L

        fun start(context: Context, nameA: String, nameB: String) {
            val intent = Intent(context, DualAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NAME_A, nameA)
                putExtra(EXTRA_NAME_B, nameB)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DualAudioService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
