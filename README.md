# DDNS Updater для Android

Порт скрипта DDNS для MikroTik на Android. Приложение узнаёт внешний IP, резолвит каждый хост и при расхождении отправляет `/nic/update`.

## Возможности

- До трёх аккаунтов DDNS, у каждого свой сервис: Dynu, No-IP, Dyn или свой сервер с протоколом DynDNS2 (URL вида `https://ddns.example.com/nic/update`).
- Частота проверки: 5, 10, 15, 30 минут, 1, 2, 6, 12 или 24 часа.
- Режим «Только через Wi-Fi» (включён по умолчанию): IP мобильного интернета не отправляется.
- Пароли шифруются ключом из Android Keystore.
- Для каждого аккаунта показываются IP в DNS и последний ответ сервера, ведётся общий журнал.
- При `badauth`, `nohost`, `notfqdn`, `abuse`, `badagent` аккаунт останавливается до исправления настроек, чтобы сервис не заблокировал хост. При `911`/`dnserr` — пауза 30 минут.
- Фоновые проверки продолжаются после перезагрузки телефона.

## Соответствие скрипту MikroTik

| MikroTik | Приложение |
|---|---|
| `/tool fetch checkip.dyndns.org` + `:find`/`:pick` | `DdnsClient.publicIPv4()` — по HTTPS (api4.ipify.org и два запасных сервиса), IP ищется регуляркой |
| `:resolve $ddnshost` | `DdnsClient.resolveIPv4()` |
| `:if ($ddnsIP != $currentIP)` | `DdnsWorker.checkAccount()` |
| `/tool fetch … /nic/update?hostname=…&myip=… user= password=` | `DdnsClient.update()` — Basic Auth, для Dynu добавляется `myipv6=no` |
| `:log info` | Журнал на экране и logcat (тег `DDNS`) |
| `/system scheduler` | `Scheduler` (WorkManager) |

## Сборка

1. Установите Android Studio и откройте папку `DynuDdns` (File → Open).
2. Дождитесь Gradle Sync: скачаются Gradle 8.11.1, Android Gradle Plugin 8.7.3 и библиотеки. Если Studio предложит обновить AGP, можно согласиться.
3. Подключите телефон с включённой отладкой по USB и нажмите Run. Другой вариант: Build → Build App Bundle(s) / APK(s) → Build APK(s), готовый файл лежит в `app/build/outputs/apk/debug/app-debug.apk`.

Требования: Android 8.0 и новее.

### Сборка без Android Studio (GitHub Actions)

В проекте есть файл `.github/workflows/build-apk.yml`. При каждом изменении в ветке `main` GitHub сам собирает APK, это занимает около 5 минут. Готовый файл `DDNS-Updater.apk` появляется на странице Releases репозитория, последняя сборка всегда доступна по адресу `…/releases/latest`. Если сборка упала, хвост лога сохраняется в ветку `ci-log`, файл `ci-build.log`. Повторно запустить сборку можно в Actions → Build APK → Run workflow.

## Настройка

1. Выберите частоту проверки.
2. Для каждого нужного аккаунта поставьте галочку, выберите сервис и заполните хост, логин и пароль. Для Dynu вместо пароля можно указать его MD5 или SHA-256.
3. Нажмите «Сохранить и запустить». Первая проверка выполняется сразу, дальше — с выбранной частотой.

## Чтобы проверки не «засыпали»

Android откладывает фоновые задачи, когда телефон спит, особенно на Xiaomi, Huawei, Honor и Samsung. Откройте Настройки → Приложения → DDNS Updater → Батарея и выберите «Без ограничений». На Xiaomi дополнительно включите «Автозапуск».

## Ограничения

- «Только через Wi-Fi» означает любую безлимитную сеть, а не только домашнюю. В чужом Wi-Fi приложение отправит IP этой сети.
- Свой сервер по `http://` работает, но пароль тогда передаётся открытым текстом. Используйте HTTPS.
- Обновляется только A-запись (IPv4).

## Структура

```
app/src/main/java/com/example/ddns/
  MainActivity.kt   экран настроек и статуса
  DdnsWorker.kt     логика проверки (аналог скрипта)
  DdnsClient.kt     HTTP-запросы и DNS
  Scheduler.kt      расписание (WorkManager)
  Store.kt          настройки, состояние, журнал
  Crypto.kt         шифрование паролей (Android Keystore)
  Model.kt          модели, список сервисов и интервалов
app/src/main/res/layout/
  activity_main.xml, account_card.xml
```
