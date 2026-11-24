package com.example.ddns

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Аналог /system scheduler. Интервал 15 минут и больше — обычная периодическая задача;
 * 5 и 10 минут — цепочка разовых задач, где каждый запуск ставит следующий.
 * Задачи переживают перезагрузку телефона.
 */
object Scheduler {
    private const val PERIODIC = "ddns-periodic"
    private const val CHAIN = "ddns-chain"
    private const val NOW = "ddns-now"
    private const val PERIODIC_MIN_MINUTES = 15

    private fun constraints(s: Settings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun start(ctx: Context, s: Settings) {
        Store.setEnabled(ctx, true)
        val wm = WorkManager.getInstance(ctx)
        if (s.intervalMinutes >= PERIODIC_MIN_MINUTES) {
            wm.cancelUniqueWork(CHAIN)
            val request = PeriodicWorkRequestBuilder<DdnsWorker>(s.intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints(s))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            // Перезапуск сразу применяет новый интервал и делает первую проверку немедленно.
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        } else {
            wm.cancelUniqueWork(PERIODIC)
            wm.enqueueUniqueWork(CHAIN, ExistingWorkPolicy.REPLACE, chainRequest(s, delayMinutes = 0))
        }
    }

    /** Вызывается из DdnsWorker в конце запуска в режиме цепочки. */
    fun scheduleNext(ctx: Context) {
        val s = Store.loadSettings(ctx)
        if (!Store.isEnabled(ctx) || s.intervalMinutes >= PERIODIC_MIN_MINUTES) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CHAIN,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            chainRequest(s, delayMinutes = s.intervalMinutes.toLong()),
        )
    }

    /** Разовая проверка по кнопке «Проверить сейчас» (с теми же ограничениями по сети). */
    fun runNow(ctx: Context, s: Settings) {
        val request = OneTimeWorkRequestBuilder<DdnsWorker>()
            .setConstraints(constraints(s))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun stop(ctx: Context) {
        Store.setEnabled(ctx, false)
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(CHAIN)
        wm.cancelUniqueWork(NOW)
    }

    private fun chainRequest(s: Settings, delayMinutes: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DdnsWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints(s))
            .setInputData(workDataOf(DdnsWorker.KEY_CHAIN to true))
            .build()
}
