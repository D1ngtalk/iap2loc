package io.github.d1ngtalk.iap2loc.app.engine

import android.annotation.SuppressLint
import android.app.Notification
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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.d1ngtalk.iap2loc.R
import io.github.d1ngtalk.iap2loc.app.ui.ConsoleActivity
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the engine alive while the phone is locked or another app is in
 * front: an iAP2 session must answer the iPhone within seconds, and NMEA goes out every 250 ms.
 */
class EngineService : Service() {
    companion object {
        const val ACTION_RESTART = "io.github.d1ngtalk.iap2loc.RESTART"
        const val ACTION_STOP = "io.github.d1ngtalk.iap2loc.STOP"
        private const val CHANNEL = "engine"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 2_000L
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "engine-host") }
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "定位发送服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "iap2loc 在后台连接 iPhone 并发送定位时显示"
                setShowBadge(false)
            },
        )

        promote("正在启动控制台…")
        worker.execute { EngineHost.open(applicationContext) }
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        promote(lastText.ifEmpty { "正在启动控制台…" })
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RESTART -> worker.execute {
                EngineHost.close(restarting = true)
                EngineHost.open(applicationContext)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        releaseWakeLock()
        worker.execute { EngineHost.close() }
        worker.shutdown()
        super.onDestroy()
    }

    /** Notification text and the wake lock follow the sessions. */
    private fun refresh() {
        val sessions = EngineHost.engine?.manager?.list().orEmpty()
        val running = sessions.filter { it.isRunning }
        val text = when {
            EngineHost.engine == null -> "正在启动控制台…"
            running.isEmpty() -> "控制台已就绪，尚未连接 iPhone"
            else -> {
                val streaming = running.count { it.subscribed }
                val sent = running.sumOf { it.sent }
                "${running.size} 台设备已连接 · $streaming 台正在接收定位 · 已发送 $sent 条"
            }
        }

        if (running.isEmpty()) releaseWakeLock() else acquireWakeLock()
        if (text != lastText) promote(text)
    }

    private fun promote(text: String) {
        lastText = text
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ConsoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("iap2loc 正在运行")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止并退出", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // Partial only: the CPU keeps the 250 ms NMEA timer running, the screen may turn off.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iap2loc:streaming")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
