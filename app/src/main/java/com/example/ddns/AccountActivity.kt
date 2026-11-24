package com.example.ddns

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** Окно настроек одного аккаунта DDNS. */
class AccountActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_INDEX = "account_index"

        fun intent(ctx: Context, index: Int): Intent =
            Intent(ctx, AccountActivity::class.java).putExtra(EXTRA_INDEX, index)
    }

    private var index = 0
    private lateinit var enabled: CheckBox
    private lateinit var provider: Spinner
    private lateinit var url: EditText
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        applyInsets(findViewById(R.id.root))

        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, MAX_ACCOUNTS - 1)

        enabled = findViewById(R.id.accEnabled)
        provider = findViewById(R.id.accProvider)
        url = findViewById(R.id.accUrl)
        host = findViewById(R.id.accHost)
        user = findViewById(R.id.accUser)
        pass = findViewById(R.id.accPass)
        status = findViewById(R.id.accStatus)

        findViewById<TextView>(R.id.title).text = getString(R.string.account_n, index + 1)

        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Provider.entries.map { it.title })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        provider.onItemSelectedListener = onSelected { position ->
            url.visibility = if (Provider.entries[position] == Provider.CUSTOM) View.VISIBLE else View.GONE
        }

        // При повороте экрана введённый текст Android восстановит сам поверх этих значений.
        fill(Store.loadSettings(this).accounts[index])
        renderStatus()

        findViewById<Button>(R.id.saveAccount).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
    }

    private fun fill(a: Account) {
        enabled.isChecked = a.enabled
        provider.setSelection(a.provider.ordinal)
        url.setText(a.customUrl)
        host.setText(a.hostname)
        user.setText(a.username)
        pass.setText(a.password)
    }

    private fun readForm() = Account(
        index = index,
        enabled = enabled.isChecked,
        provider = Provider.entries[provider.selectedItemPosition.coerceAtLeast(0)],
        customUrl = url.text.toString().trim(),
        hostname = host.text.toString().trim(),
        username = user.text.toString().trim(),
        password = pass.text.toString(),
    )

    private fun save() {
        val a = readForm()
        if (a.enabled && !a.isComplete) {
            val message = if (!a.hasValidUrl) R.string.err_url else R.string.err_fields
            Toast.makeText(this, getString(message, a.number), Toast.LENGTH_LONG).show()
            return
        }
        Store.saveAccount(this, a)
        Store.log(this, "Аккаунт ${a.number}: настройки сохранены" + if (a.enabled) "" else " (выключен)")
        if (Store.isEnabled(this) && a.enabled) Scheduler.runNow(this)
        finish()
    }

    private fun renderStatus() {
        val s = Store.loadStatus(this).accounts[index]
        status.text = buildString {
            append("В DNS: ${s.dnsIp ?: "—"}\nОтвет сервера: ${s.answer ?: "—"}")
            s.halt?.let { append("\nОСТАНОВЛЕН ($it): исправьте настройки и нажмите «Сохранить»") }
        }
    }
}
