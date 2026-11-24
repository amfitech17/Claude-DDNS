package com.example.ddns

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Перенос скрипта MikroTik на Android:
 *   1) узнать внешний IP, 2) :resolve каждого хоста, 3) если отличаются — /nic/update.
 * Добавлено то, чего в скрипте не было: разбор ответа сервера и остановка
 * аккаунта при фатальных ошибках (badauth, nohost, abuse…), чтобы сервис не заблокировал хост.
 *
 * Вызывается и из DdnsWorker (интервалы от 15 минут), и из DdnsService (30 сек — 5 мин).
 */
object DdnsChecker {

    enum class Outcome { DONE, RETRY }

    /** Ответы, после которых повторять запрос нельзя, пока пользователь не исправит настройки. */
    private val FATAL = setOf(
        "badauth", "notfqdn", "nohost", "numhost", "abuse", "badagent", "!donator", "!yours",
    )

    /** Пауза после 911/dnserr — так требует протокол DynDNS2. */
    private const val SERVER_ERROR_PAUSE_MIN = 30

    /** Не даём фоновой службе и кнопке «Проверить сейчас» работать одновременно. */
    private val mutex = Mutex()

    private enum class AccountOutcome { UP_TO_DATE, SKIPPED, HANDLED, RETRY }

    suspend fun run(ctx: Context): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                check(ctx, Store.loadSettings(ctx))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Store.log(ctx, "Ошибка проверки: ${e.javaClass.simpleName}: ${e.message}")
                Outcome.RETRY
            }
        }
    }

    private fun check(ctx: Context, settings: Settings): Outcome {
        val accounts = settings.accounts.filter { it.enabled && it.isComplete }
        if (accounts.isEmpty()) {
            Store.log(ctx, "Нет включённых аккаунтов с заполненными настройками")
            return Outcome.DONE
        }

        val currentIp = try {
            DdnsClient.publicIPv4()
        } catch (e: IOException) {
            Store.log(ctx, "Не удалось узнать внешний IP: ${e.message}")
            return Outcome.RETRY
        }
        Store.saveCurrentIp(ctx, currentIp)

        var retry = false
        var anyAction = false
        val skipped = mutableListOf<Int>()

        for (account in accounts) {
            when (checkAccount(ctx, account, currentIp)) {
                AccountOutcome.UP_TO_DATE -> Unit
                AccountOutcome.SKIPPED -> skipped += account.number
                AccountOutcome.HANDLED -> anyAction = true
                AccountOutcome.RETRY -> {
                    anyAction = true
                    retry = true
                }
            }
        }

        if (!anyAction) {
            val note = if (skipped.isEmpty()) "" else " (пропущены аккаунты: ${skipped.joinToString()})"
            Store.log(ctx, "IP $currentIp — изменений нет$note")
        }
        return if (retry) Outcome.RETRY else Outcome.DONE
    }

    private fun checkAccount(ctx: Context, account: Account, currentIp: String): AccountOutcome {
        val i = account.index
        val tag = "[${account.number}] ${account.hostname}"

        if (Store.haltReason(ctx, i) != null || Store.isPaused(ctx, i)) return AccountOutcome.SKIPPED

        // :global ddnsIP [:resolve $ddnshost]
        val dnsIp = DdnsClient.resolveIPv4(account.hostname)
        Store.saveDnsIp(ctx, i, dnsIp)

        // :if ($ddnsIP != $currentIP) do={ … }
        if (dnsIp == currentIp) return AccountOutcome.UP_TO_DATE
        if (Store.sentRecently(ctx, i, currentIp)) return AccountOutcome.UP_TO_DATE // ждём обновления DNS-кэша

        Store.log(ctx, "$tag: в DNS ${dnsIp ?: "нет записи"}, текущий $currentIp — отправляю обновление")
        if (account.updateUrl.startsWith("http://")) {
            Store.log(ctx, "$tag: внимание — сервер без HTTPS, пароль передаётся открытым текстом")
        }

        val answer = try {
            DdnsClient.update(account, currentIp)
        } catch (e: IOException) {
            Store.log(ctx, "$tag: сетевая ошибка: ${e.message}")
            return AccountOutcome.RETRY
        }
        Store.saveAnswer(ctx, i, answer)

        val code = answer.substringBefore(' ').lowercase()
        return when {
            code == "good" || code == "nochg" -> {
                Store.markSent(ctx, i, currentIp)
                Store.log(ctx, "$tag: готово — $answer")
                AccountOutcome.HANDLED
            }
            code in FATAL -> {
                Store.halt(ctx, i, answer)
                Store.log(ctx, "$tag: ошибка «$answer» — аккаунт остановлен до исправления настроек")
                AccountOutcome.HANDLED
            }
            code == "911" || code == "dnserr" -> {
                Store.pause(ctx, i, SERVER_ERROR_PAUSE_MIN)
                Store.log(ctx, "$tag: сбой на сервере ($answer), пауза $SERVER_ERROR_PAUSE_MIN мин")
                AccountOutcome.HANDLED
            }
            else -> {
                Store.log(ctx, "$tag: непонятный ответ «${answer.take(80)}», повторю позже")
                AccountOutcome.RETRY
            }
        }
    }
}
