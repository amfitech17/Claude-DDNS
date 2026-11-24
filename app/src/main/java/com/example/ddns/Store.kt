package com.example.ddns

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки и состояние — аналог :global-переменных из скрипта MikroTik. */
object Store {
    private const val TAG = "DDNS"
    private const val MAX_LOG_LINES = 100

    /** Сколько ждать, пока DNS-кэш увидит только что отправленный IP, прежде чем слать его снова. */
    private const val RECENT_SEND_MS = 15 * 60 * 1000L

    private fun config(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun statusPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("status", Context.MODE_PRIVATE)

    private fun k(index: Int, name: String) = "acc${index}_$name"

    private fun now() = System.currentTimeMillis()

    // ---------- Настройки ----------

    fun loadSettings(ctx: Context): Settings {
        val p = config(ctx)
        val accounts = (0 until MAX_ACCOUNTS).map { i ->
            val providerName = p.getString(k(i, "provider"), null)
            Account(
                index = i,
                enabled = p.getBoolean(k(i, "enabled"), false),
                provider = Provider.entries.firstOrNull { it.name == providerName } ?: Provider.DYNU,
                customUrl = p.getString(k(i, "url"), null).orEmpty(),
                hostname = p.getString(k(i, "hostname"), null).orEmpty(),
                username = p.getString(k(i, "username"), null).orEmpty(),
                password = p.getString(k(i, "password"), null)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
                    .orEmpty(),
            )
        }
        return Settings(
            intervalSeconds = intervalSeconds(ctx),
            wifiOnly = p.getBoolean("wifiOnly", true),
            accounts = accounts,
        )
    }

    /** Частота проверки в секундах. Версия 1 хранила минуты в ключе "interval". */
    fun intervalSeconds(ctx: Context): Int {
        val p = config(ctx)
        val seconds = p.getInt("intervalSec", -1)
        if (seconds > 0) return seconds
        val oldMinutes = p.getInt("interval", -1)
        return if (oldMinutes > 0) oldMinutes * 60 else DEFAULT_INTERVAL_SECONDS
    }

    fun wifiOnly(ctx: Context): Boolean = config(ctx).getBoolean("wifiOnly", true)

    fun saveGeneral(ctx: Context, intervalSeconds: Int, wifiOnly: Boolean) {
        config(ctx).edit()
            .putInt("intervalSec", intervalSeconds)
            .putBoolean("wifiOnly", wifiOnly)
            .apply()
    }

    fun saveAccount(ctx: Context, a: Account) {
        config(ctx).edit()
            .putBoolean(k(a.index, "enabled"), a.enabled)
            .putString(k(a.index, "provider"), a.provider.name)
            .putString(k(a.index, "url"), a.customUrl.trim())
            .putString(k(a.index, "hostname"), a.hostname.trim())
            .putString(k(a.index, "username"), a.username.trim())
            .putString(k(a.index, "password"), if (a.password.isEmpty()) "" else Crypto.encrypt(a.password))
            .apply()

        // Новые настройки аккаунта — сбрасываем его прошлое состояние, остановку и паузу.
        val st = statusPrefs(ctx).edit()
        listOf("halt", "sentIp", "sentAt", "pauseUntil", "dnsIp", "answer")
            .forEach { st.remove(k(a.index, it)) }
        st.apply()
    }

    // ---------- Состояние ----------

    fun isEnabled(ctx: Context): Boolean = statusPrefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, value: Boolean) =
        statusPrefs(ctx).edit().putBoolean("enabled", value).apply()

    fun saveCurrentIp(ctx: Context, ip: String) =
        statusPrefs(ctx).edit().putString("currentIp", ip).putLong("lastCheck", now()).apply()

    fun saveDnsIp(ctx: Context, index: Int, ip: String?) =
        statusPrefs(ctx).edit().putString(k(index, "dnsIp"), ip ?: "нет записи").apply()

    fun saveAnswer(ctx: Context, index: Int, answer: String) =
        statusPrefs(ctx).edit().putString(k(index, "answer"), answer).apply()

    fun haltReason(ctx: Context, index: Int): String? =
        statusPrefs(ctx).getString(k(index, "halt"), null)

    fun halt(ctx: Context, index: Int, reason: String) =
        statusPrefs(ctx).edit().putString(k(index, "halt"), reason).apply()

    fun pause(ctx: Context, index: Int, minutes: Int) =
        statusPrefs(ctx).edit().putLong(k(index, "pauseUntil"), now() + minutes * 60_000L).apply()

    fun isPaused(ctx: Context, index: Int): Boolean =
        now() < statusPrefs(ctx).getLong(k(index, "pauseUntil"), 0L)

    fun markSent(ctx: Context, index: Int, ip: String) =
        statusPrefs(ctx).edit()
            .putString(k(index, "sentIp"), ip)
            .putLong(k(index, "sentAt"), now())
            .apply()

    fun sentRecently(ctx: Context, index: Int, ip: String): Boolean {
        val p = statusPrefs(ctx)
        return p.getString(k(index, "sentIp"), null) == ip &&
            now() - p.getLong(k(index, "sentAt"), 0L) < RECENT_SEND_MS
    }

    fun loadStatus(ctx: Context): Status {
        val p = statusPrefs(ctx)
        return Status(
            enabled = p.getBoolean("enabled", false),
            lastCheck = p.getLong("lastCheck", 0L),
            currentIp = p.getString("currentIp", null),
            accounts = (0 until MAX_ACCOUNTS).map { i ->
                AccountStatus(
                    dnsIp = p.getString(k(i, "dnsIp"), null),
                    answer = p.getString(k(i, "answer"), null),
                    halt = p.getString(k(i, "halt"), null),
                )
            },
            log = p.getString("log", null).orEmpty(),
        )
    }

    // ---------- Журнал (аналог :log info) ----------

    /** Одинаковые сообщения подряд не плодят строки: обновляется время и счётчик (×N). */
    @Synchronized
    fun log(ctx: Context, message: String) {
        Log.i(TAG, message)
        val p = statusPrefs(ctx)
        val time = formatTime(now())
        val lines = p.getString("log", null).orEmpty().lines().filter { it.isNotBlank() }.toMutableList()

        if (lines.isNotEmpty() && p.getString("lastLogMsg", null) == message) {
            val count = p.getInt("lastLogCount", 1) + 1
            lines[0] = "$time  $message (×$count)"
            p.edit()
                .putString("log", lines.joinToString("\n"))
                .putInt("lastLogCount", count)
                .apply()
        } else {
            lines.add(0, "$time  $message")
            p.edit()
                .putString("log", lines.take(MAX_LOG_LINES).joinToString("\n"))
                .putString("lastLogMsg", message)
                .putInt("lastLogCount", 1)
                .apply()
        }
    }

    fun clearLog(ctx: Context) =
        statusPrefs(ctx).edit().remove("log").remove("lastLogMsg").remove("lastLogCount").apply()

    fun formatTime(ms: Long): String =
        if (ms == 0L) "—" else SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
