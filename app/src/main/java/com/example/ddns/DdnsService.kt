package com.example.ddns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Частые проверки (30 сек, 1 мин, 5 мин). WorkManager не умеет запускать задачи
 * чаще раза в 15 минут, поэтому здесь работает служба переднего плана с постоянным
 * уведомлением и удерживает процессор от засыпания (partial wake lock).
 */
class DdnsService : Service() {

    companion object {
        private const val CHANNEL_ID = "ddns_background"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DdnsService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DdnsService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val interval = Store.intervalSeconds(this)

        // startForeground нужно вызвать сразу после startForegroundService, даже если служба тут же остановится.
        try {
            startInForeground(interval)
        } catch (e: Exception) {
            Store.log(this, "Не удалось запустить фоновую службу: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Store.isEnabled(this) || interval >= PERIODIC_MIN_SECONDS) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        loop?.cancel()
        loop = scope.launch { runLoop(interval, Store.wifiOnly(this@DdnsService)) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private suspend fun runLoop(intervalSeconds: Int, wifiOnly: Boolean) {
        var waitingForWifi = false
        while (true) {
            if (wifiOnly && !isUnmeteredNetwork()) {
                if (!waitingForWifi) {
                    Store.log(this, "Нет Wi-Fi — проверки на паузе до подключения")
                    waitingForWifi = true
                }
            } else {
                waitingForWifi = false
                DdnsChecker.run(applicationContext)
            }
            delay(intervalSeconds * 1000L)
        }
    }

    private fun isUnmeteredNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddns:background").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground(intervalSeconds: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text, intervalTitle(intervalSeconds)))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
