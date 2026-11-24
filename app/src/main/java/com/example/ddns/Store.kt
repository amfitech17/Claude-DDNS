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
                enabled = p.getBoolean(k(i, "enabled"), i == 0),
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
            intervalMinutes = p.getInt("interval", 15),
            wifiOnly = p.getBoolean("wifiOnly", true),
            accounts = accounts,
        )
    }

    fun intervalMinutes(ctx: Context): Int = config(ctx).getInt("interval", 15)

    fun saveSettings(ctx: Context, s: Settings) {
        val e = config(ctx).edit()
            .putInt("interval", s.intervalMinutes)
            .putBoolean("wifiOnly", s.wifiOnly)
        for (a in s.accounts) {
            e.putBoolean(k(a.index, "enabled"), a.enabled)
                .putString(k(a.index, "provider"), a.provider.name)
                .putString(k(a.index, "url"), a.customUrl.trim())
                .putString(k(a.index, "hostname"), a.hostname.trim())
                .putString(k(a.index, "username"), a.username.trim())
                .putString(k(a.index, "password"), if (a.password.isEmpty()) "" else Crypto.encrypt(a.password))
        }
        e.apply()

        // Пользователь сохранил настройки — снимаем остановку после фатальных ошибок и паузы.
        val st = statusPrefs(ctx).edit()
        for (i in 0 until MAX_ACCOUNTS) {
            listOf("halt", "sentIp", "sentAt", "pauseUntil").forEach { st.remove(k(i, it)) }
        }
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

    @Synchronized
    fun log(ctx: Context, message: String) {
        Log.i(TAG, message)
        val p = statusPrefs(ctx)
        val line = "${formatTime(now())}  $message"
        val old = p.getString("log", null).orEmpty().lines().filter { it.isNotBlank() }
        p.edit().putString("log", (listOf(line) + old).take(MAX_LOG_LINES).joinToString("\n")).apply()
    }

    fun clearLog(ctx: Context) = statusPrefs(ctx).edit().remove("log").apply()

    fun formatTime(ms: Long): String =
        if (ms == 0L) "—" else SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
