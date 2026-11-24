package com.example.ddns

const val MAX_ACCOUNTS = 3

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
    val intervalMinutes: Int,
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

/**
 * Варианты частоты проверки. Периодическая задача WorkManager не может
 * запускаться чаще раза в 15 минут, поэтому для 5 и 10 минут используется
 * цепочка разовых задач (см. Scheduler).
 */
val INTERVALS: List<Pair<Int, String>> = listOf(
    5 to "5 минут",
    10 to "10 минут",
    15 to "15 минут",
    30 to "30 минут",
    60 to "1 час",
    120 to "2 часа",
    360 to "6 часов",
    720 to "12 часов",
    1440 to "24 часа",
)

fun intervalTitle(minutes: Int): String =
    INTERVALS.firstOrNull { it.first == minutes }?.second ?: "$minutes мин"
