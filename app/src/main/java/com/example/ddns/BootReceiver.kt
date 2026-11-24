package com.example.ddns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * После перезагрузки телефона или обновления приложения заново запускает фоновую службу
 * (для частых интервалов). Задачи WorkManager восстанавливаются сами.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Store.isEnabled(context) || Store.intervalSeconds(context) >= PERIODIC_MIN_SECONDS) return

        try {
            DdnsService.start(context)
        } catch (e: Exception) {
            Store.log(context, "Не удалось запустить проверки после перезагрузки: ${e.message}")
        }
    }
}
