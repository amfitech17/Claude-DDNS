package com.example.ddns

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.provider.Settings as AndroidSettings

/** Главный экран: частота, Wi-Fi, выбор аккаунта, запуск/остановка, состояние и журнал. */
class MainActivity : ComponentActivity() {

    private lateinit var interval: Spinner
    private lateinit var intervalHint: TextView
    private lateinit var wifiOnly: CheckBox
    private lateinit var accountPicker: Spinner
    private lateinit var battery: Button
    private lateinit var status: TextView
    private lateinit var log: TextView

    /** Аккаунты перечитываются при возврате на экран (их меняет только AccountActivity). */
    private var accounts: List<Account> = emptyList()

    private val statusListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root))

        interval = findViewById(R.id.interval)
        intervalHint = findViewById(R.id.intervalHint)
        wifiOnly = findViewById(R.id.wifiOnly)
        accountPicker = findViewById(R.id.accountPicker)
        battery = findViewById(R.id.battery)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        interval.adapter = spinnerAdapter(INTERVALS.map { it.second })
        interval.setSelection(intervalPosition(Store.intervalSeconds(this)))
        interval.onItemSelectedListener = onSelected { position -> updateIntervalHint(INTERVALS[position].first) }
        wifiOnly.isChecked = Store.wifiOnly(this)

        // Выбор аккаунта сразу открывает окно его настроек; сам список возвращается к подсказке.
        accountPicker.onItemSelectedListener = onSelected { position ->
            if (position > 0) {
                accountPicker.setSelection(0)
                startActivity(AccountActivity.intent(this, position - 1))
            }
        }

        findViewById<Button>(R.id.save).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.checkNow).setOnClickListener { checkNow() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            Scheduler.stop(this)
            Store.log(this, "Фоновые проверки остановлены")
        }
        findViewById<Button>(R.id.clearLog).setOnClickListener { Store.clearLog(this) }
        battery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        accounts = Store.loadSettings(this).accounts
        refreshAccountPicker()
        updateBatteryButton()
        Store.statusPrefs(this).registerOnSharedPreferenceChangeListener(statusListener)
        render()
    }

    override fun onPause() {
        Store.statusPrefs(this).unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }

    // ---------- Действия ----------

    private fun selectedInterval(): Int = INTERVALS[interval.selectedItemPosition.coerceAtLeast(0)].first

    private fun hasUsableAccount(): Boolean =
        Store.loadSettings(this).accounts.any { it.enabled && it.isComplete }

    private fun saveAndStart() {
        if (!hasUsableAccount()) {
            toast(getString(R.string.err_need_account))
            return
        }
        val seconds = selectedInterval()
        Store.saveGeneral(this, seconds, wifiOnly.isChecked)
        if (seconds < PERIODIC_MIN_SECONDS) requestNotificationPermission()
        Scheduler.start(this, seconds, wifiOnly.isChecked)
        Store.log(this, "Запущено: частота — ${intervalTitle(seconds)}")
        warnIfNoWifi()
    }

    private fun checkNow() {
        if (selectedInterval() != Store.intervalSeconds(this) || wifiOnly.isChecked != Store.wifiOnly(this)) {
            toast(getString(R.string.err_save_first))
            return
        }
        if (!hasUsableAccount()) {
            toast(getString(R.string.err_need_account))
            return
        }
        Scheduler.runNow(this)
        Store.log(this, "Запущена проверка вручную")
        warnIfNoWifi()
    }

    private fun warnIfNoWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        if (Store.wifiOnly(this) && cm.isActiveNetworkMetered) toast(getString(R.string.msg_waiting_wifi))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(PowerManager::class.java)
        battery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- Отображение ----------

    private fun refreshAccountPicker() {
        val items = listOf(getString(R.string.account_pick)) + accounts.map { a ->
            val state = when {
                !a.enabled -> getString(R.string.state_off)
                !a.isComplete -> getString(R.string.state_incomplete)
                else -> getString(R.string.state_on)
            }
            getString(R.string.account_item, a.number, state)
        }
        accountPicker.adapter = spinnerAdapter(items)
        accountPicker.setSelection(0)
    }

    private fun updateIntervalHint(seconds: Int) {
        intervalHint.text = getString(
            if (seconds < PERIODIC_MIN_SECONDS) R.string.hint_interval_fast else R.string.hint_interval_slow
        )
    }

    private fun render() {
        val st = Store.loadStatus(this)
        status.text = buildString {
            append(
                if (st.enabled) {
                    "Фоновые проверки включены: ${intervalTitle(Store.intervalSeconds(this@MainActivity))}"
                } else {
                    "Фоновые проверки выключены"
                }
            )
            append("\nПоследняя проверка: ${Store.formatTime(st.lastCheck)}")
            append("\nВнешний IP: ${st.currentIp ?: "—"}")
            accounts.forEachIndexed { i, a ->
                val s = st.accounts[i]
                append("\nАккаунт ${a.number}: ")
                append(
                    when {
                        !a.enabled -> "выключен"
                        !a.isComplete -> "не настроен"
                        s.halt != null -> "ОСТАНОВЛЕН (${s.halt}) — откройте настройки аккаунта"
                        else -> "в DNS ${s.dnsIp ?: "—"}, ответ ${s.answer ?: "—"}"
                    }
                )
            }
        }
        log.text = st.log.ifEmpty { "—" }
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

/** Отступы под системные панели и клавиатуру (edge-to-edge). */
fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/** Короткая запись слушателя выбора в Spinner. */
fun onSelected(action: (position: Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
