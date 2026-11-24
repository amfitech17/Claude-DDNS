package com.example.ddns

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Аналог /system scheduler.
 * От 15 минут — периодическая задача WorkManager (переживает перезагрузку сама).
 * 30 сек, 1 мин, 5 мин — фоновая служба DdnsService с уведомлением.
 */
object Scheduler {
    private const val PERIODIC = "ddns-periodic"
    private const val CHAIN = "ddns-chain" // режим из версии 1, больше не используется
    private const val NOW = "ddns-now"

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun start(ctx: Context, intervalSeconds: Int, wifiOnly: Boolean) {
        Store.setEnabled(ctx, true)
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(CHAIN)

        if (intervalSeconds >= PERIODIC_MIN_SECONDS) {
            DdnsService.stop(ctx)
            val request = PeriodicWorkRequestBuilder<DdnsWorker>(intervalSeconds / 60L, TimeUnit.MINUTES)
                .setConstraints(constraints(wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            // Перезапуск сразу применяет новый интервал и делает первую проверку немедленно.
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        } else {
            wm.cancelUniqueWork(PERIODIC)
            // Повторный запуск службы перечитывает настройки и перезапускает цикл.
            DdnsService.start(ctx)
        }
    }

    /** Разовая проверка: кнопка «Проверить сейчас» и сохранение аккаунта. */
    fun runNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<DdnsWorker>()
            .setConstraints(constraints(Store.wifiOnly(ctx)))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun stop(ctx: Context) {
        Store.setEnabled(ctx, false)
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(CHAIN)
        wm.cancelUniqueWork(NOW)
        DdnsService.stop(ctx)
    }
}
