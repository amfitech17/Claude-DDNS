package com.example.ddns

const val MAX_ACCOUNTS = 3

/**
 * Интервалы от 15 минут — периодическая задача WorkManager (меньше Android не позволяет).
 * Интервалы короче — фоновая служба с постоянным уведомлением (DdnsService).
 */
const val PERIODIC_MIN_SECONDS = 15 * 60

/** Сервисы, понимающие протокол DynDNS2 (/nic/update). */
enum class Provider(val title: String, val updateUrl: String) {
    DYNU("Dynu", "https://api.dynu.com/nic/update"),
    NOIP("No-IP", "https://dynupdate.no-ip.com/nic/update"),
    DYN("Dyn (DynDNS)", "https://members.dyndns.org/nic/update"),
    CUSTOM("Свой сервер (DynDNS2)", ""),
}

data class Account(
    val index: Int,
    val enabled: Boolean,
    val provider: Provider,
    val customUrl: String,
    val hostname: String,
    val username: String,
    val password: String,
) {
    val number: Int get() = index + 1

    val updateUrl: String
        get() = if (provider == Provider.CUSTOM) customUrl.trim() else provider.updateUrl

    val hasValidUrl: Boolean
        get() = updateUrl.startsWith("https://") || updateUrl.startsWith("http://")

    val isComplete: Boolean
        get() = hasValidUrl && hostname.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
}

data class Settings(
    val intervalSeconds: Int,
    val wifiOnly: Boolean,
    val accounts: List<Account>,
)

data class AccountStatus(
    val dnsIp: String?,
    val answer: String?,
    val halt: String?,
)

data class Status(
    val enabled: Boolean,
    val lastCheck: Long,
    val currentIp: String?,
    val accounts: List<AccountStatus>,
    val log: String,
)

/** Варианты частоты проверки, в секундах. */
val INTERVALS: List<Pair<Int, String>> = listOf(
    30 to "30 секунд",
    60 to "1 минута",
    5 * 60 to "5 минут",
    15 * 60 to "15 минут",
    60 * 60 to "1 час",
    2 * 60 * 60 to "2 часа",
    8 * 60 * 60 to "8 часов",
    24 * 60 * 60 to "24 часа",
)

const val DEFAULT_INTERVAL_SECONDS = 15 * 60

fun intervalTitle(seconds: Int): String =
    INTERVALS.firstOrNull { it.first == seconds }?.second ?: "$seconds сек"

/** Позиция в списке INTERVALS для сохранённого значения (ближайшая не меньше). */
fun intervalPosition(seconds: Int): Int {
    val pos = INTERVALS.indexOfFirst { it.first >= seconds }
    return if (pos >= 0) pos else INTERVALS.lastIndex
}
