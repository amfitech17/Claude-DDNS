package com.example.ddns

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {

    private class Card(
        val enabled: CheckBox,
        val provider: Spinner,
        val url: EditText,
        val host: EditText,
        val user: EditText,
        val pass: EditText,
        val status: TextView,
    )

    private lateinit var interval: Spinner
    private lateinit var wifiOnly: CheckBox
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val cards = mutableListOf<Card>()

    private val statusListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        interval = findViewById(R.id.interval)
        wifiOnly = findViewById(R.id.wifiOnly)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        interval.adapter = spinnerAdapter(INTERVALS.map { it.second })

        val container = findViewById<LinearLayout>(R.id.accounts)
        repeat(MAX_ACCOUNTS) { i -> cards += createCard(container, i) }

        fill(Store.loadSettings(this))

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.checkNow).setOnClickListener { checkNow() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            Scheduler.stop(this)
            Store.log(this, "Фоновые проверки остановлены")
        }
        findViewById<Button>(R.id.clearLog).setOnClickListener { Store.clearLog(this) }
    }

    override fun onResume() {
        super.onResume()
        Store.statusPrefs(this).registerOnSharedPreferenceChangeListener(statusListener)
        render()
    }

    override fun onPause() {
        Store.statusPrefs(this).unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }

    private fun createCard(container: LinearLayout, i: Int): Card {
        val root = layoutInflater.inflate(R.layout.account_card, container, false)
        container.addView(root)

        val card = Card(
            enabled = root.findViewById(R.id.accEnabled),
            provider = root.findViewById(R.id.accProvider),
            url = root.findViewById(R.id.accUrl),
            host = root.findViewById(R.id.accHost),
            user = root.findViewById(R.id.accUser),
            pass = root.findViewById(R.id.accPass),
            status = root.findViewById(R.id.accStatus),
        )

        // В трёх карточках одинаковые id из разметки. Даём полям уникальные постоянные id,
        // чтобы при повороте экрана Android восстановил введённый текст в нужную карточку.
        listOf(card.enabled, card.provider, card.url, card.host, card.user, card.pass)
            .forEachIndexed { k, view -> view.id = 10_000 + i * 100 + k }

        card.enabled.text = getString(R.string.account_n, i + 1)
        card.provider.adapter = spinnerAdapter(Provider.entries.map { it.title })
        card.provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val custom = Provider.entries[position] == Provider.CUSTOM
                card.url.visibility = if (custom) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        return card
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun fill(s: Settings) {
        val pos = INTERVALS.indexOfFirst { it.first >= s.intervalMinutes }
        interval.setSelection(if (pos >= 0) pos else INTERVALS.lastIndex)
        wifiOnly.isChecked = s.wifiOnly
        s.accounts.forEachIndexed { i, a ->
            val c = cards[i]
            c.enabled.isChecked = a.enabled
            c.provider.setSelection(a.provider.ordinal)
            c.url.setText(a.customUrl)
            c.host.setText(a.hostname)
            c.user.setText(a.username)
            c.pass.setText(a.password)
        }
    }

    private fun readForm(): Settings = Settings(
        intervalMinutes = INTERVALS[interval.selectedItemPosition.coerceAtLeast(0)].first,
        wifiOnly = wifiOnly.isChecked,
        accounts = cards.mapIndexed { i, c ->
            Account(
                index = i,
                enabled = c.enabled.isChecked,
                provider = Provider.entries[c.provider.selectedItemPosition.coerceAtLeast(0)],
                customUrl = c.url.text.toString().trim(),
                hostname = c.host.text.toString().trim(),
                username = c.user.text.toString().trim(),
                password = c.pass.text.toString(),
            )
        },
    )

    private fun validate(s: Settings): String? {
        val enabled = s.accounts.filter { it.enabled }
        if (enabled.isEmpty()) return getString(R.string.err_no_accounts)
        val bad = enabled.firstOrNull { !it.isComplete } ?: return null
        return if (!bad.hasValidUrl) {
            getString(R.string.err_url, bad.number)
        } else {
            getString(R.string.err_fields, bad.number)
        }
    }

    private fun save() {
        val s = readForm()
        validate(s)?.let { toast(it); return }
        Store.saveSettings(this, s)
        Scheduler.start(this, s)
        Store.log(
            this,
            "Настройки сохранены: частота — ${intervalTitle(s.intervalMinutes)}, " +
                "аккаунтов: ${s.accounts.count { it.enabled }}",
        )
        warnIfNoWifi(s)
    }

    private fun checkNow() {
        val s = readForm()
        if (s != Store.loadSettings(this)) {
            toast(getString(R.string.err_save_first))
            return
        }
        validate(s)?.let { toast(it); return }
        Scheduler.runNow(this, s)
        Store.log(this, "Запущена проверка вручную")
        warnIfNoWifi(s)
    }

    private fun warnIfNoWifi(s: Settings) {
        val cm = getSystemService(ConnectivityManager::class.java)
        if (s.wifiOnly && cm.isActiveNetworkMetered) toast(getString(R.string.msg_waiting_wifi))
    }

    private fun render() {
        val st = Store.loadStatus(this)
        status.text = buildString {
            append(
                if (st.enabled) {
                    "Фоновые проверки включены: ${intervalTitle(Store.intervalMinutes(this@MainActivity))}"
                } else {
                    "Фоновые проверки выключены"
                }
            )
            append("\nПоследняя проверка: ${Store.formatTime(st.lastCheck)}")
            append("\nВнешний IP: ${st.currentIp ?: "—"}")
        }
        st.accounts.forEachIndexed { i, a ->
            cards[i].status.text = buildString {
                append("В DNS: ${a.dnsIp ?: "—"}\nОтвет сервера: ${a.answer ?: "—"}")
                a.halt?.let { append("\nОСТАНОВЛЕН ($it): исправьте настройки и нажмите «Сохранить»") }
            }
        }
        log.text = st.log.ifEmpty { "—" }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
