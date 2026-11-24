package com.example.ddns

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Фоновая задача WorkManager: интервалы от 15 минут и кнопка «Проверить сейчас». */
class DdnsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result =
        when (DdnsChecker.run(applicationContext)) {
            DdnsChecker.Outcome.DONE -> Result.success()
            DdnsChecker.Outcome.RETRY -> Result.retry()
        }
}
