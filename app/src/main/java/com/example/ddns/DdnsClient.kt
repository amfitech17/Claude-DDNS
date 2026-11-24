package com.example.ddns

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** Сетевая часть: те же три шага, что в скрипте MikroTik. */
object DdnsClient {
    private const val USER_AGENT = "AndroidDDNS/1.0"
    private const val TIMEOUT_MS = 15_000

    /**
     * Сервисы, возвращающие внешний IPv4. Аналог checkip.dyndns.org,
     * но по HTTPS: Android по умолчанию блокирует обычный HTTP.
     */
    private val IP_SOURCES = listOf(
        "https://api4.ipify.org",
        "https://ipv4.icanhazip.com",
        "https://checkip.amazonaws.com",
    )

    private val IPV4 =
        Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")

    /** Шаг 1 — /tool fetch checkip… + :find/:pick: узнаём внешний IP. */
    fun publicIPv4(): String {
        var lastError: IOException? = null
        for (source in IP_SOURCES) {
            try {
                val (code, body) = get(source)
                if (code == 200) IPV4.find(body)?.let { return it.value }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("все сервисы проверки IP недоступны", lastError)
    }

    /** Шаг 2 — :resolve $ddnshost: какой IPv4 сейчас записан в DNS. */
    fun resolveIPv4(hostname: String): String? = try {
        InetAddress.getAllByName(hostname)
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    } catch (e: UnknownHostException) {
        null
    }

    /**
     * Шаг 3 — /tool fetch … src-path=/nic/update?hostname=…&myip=… user=… password=…
     * Возвращает первую строку ответа сервера: "good 1.2.3.4", "nochg 1.2.3.4", "badauth" и т.д.
     */
    fun update(account: Account, ip: String): String {
        val base = account.updateUrl
        val separator = if ('?' in base) "&" else "?"
        // Dynu без myipv6 сам подставит IPv6 телефона — запрещаем менять AAAA-запись.
        val extra = if ("dynu.com" in base) "&myipv6=no" else ""
        val url = "$base${separator}hostname=${enc(account.hostname)}&myip=${enc(ip)}$extra"

        val credentials = "${account.username}:${account.password}".toByteArray(Charsets.UTF_8)
        val auth = "Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP)

        val (code, body) = get(url, auth)
        val answer = body.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            answer.isNotEmpty() -> answer
            code == 401 || code == 403 -> "badauth"
            else -> throw IOException("HTTP $code, пустой ответ")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, authorization: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            authorization?.let { conn.setRequestProperty("Authorization", it) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn.disconnect()
        }
    }
}
