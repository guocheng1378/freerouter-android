package com.eta.freerouter

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.registry.loadProviders
import com.eta.freerouter.core.state.HealthStatus

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var keyContainer: LinearLayout
    private lateinit var providerContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var trafficText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var refreshBtn: Button
    private var providers: List<Provider> = emptyList()
    private lateinit var modelSpinner: Spinner
    private lateinit var frSwitch: Switch
    private lateinit var modelSettingsContainer: LinearLayout
    private var spinnerReady: Boolean = false
    private val keyInputs = mutableMapOf<String, EditText>()
    private lateinit var refresher: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("fr_state", MODE_PRIVATE)
        Keys.init(this)
        val catalogJson = assets.open("catalog.json").bufferedReader().use { it.readText() }
        providers = loadProviders(catalogJson)
        keyContainer = findViewById(R.id.keyContainer)
        providerContainer = findViewById(R.id.providerContainer)
        statusText = findViewById(R.id.statusText)
        trafficText = findViewById(R.id.trafficText)
        toggleBtn = findViewById(R.id.toggleBtn)
        refreshBtn = findViewById(R.id.refreshBtn)
        modelSpinner = findViewById(R.id.modelSpinner)
        frSwitch = findViewById(R.id.frSwitch)
        modelSettingsContainer = findViewById(R.id.modelSettingsContainer)
        frSwitch.isChecked = prefs.getBoolean("fr_enabled", true)
        frSwitch.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("fr_enabled", c).apply()
            RouterService.engineRef?.freeRouterEnabled = c
            syncModelsUI()
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (!spinnerReady) return
                val sel = parent.getItemAtPosition(pos).toString()
                prefs.edit().putString("default_model", sel).apply()
                RouterService.engineRef?.defaultModel = sel
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        buildKeyRows()
        toggleBtn.setOnClickListener { toggle() }
        refreshBtn.setOnClickListener { refresh() }
        applyDebugInjection(intent)
        updateStatus()
        buildProviderRows()
        refresher = Handler(Looper.getMainLooper())
        scheduleRefresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildKeyRows() {
        keyContainer.removeAllViews()
        keyInputs.clear()
        for (p in providers) {
            val env = p.credential ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(this).apply {
                text = p.nameZh
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = EditText(this).apply {
                hint = env
                setText(Keys.get(env) ?: "")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            keyInputs[env] = edit
            row.addView(label); row.addView(edit)
            keyContainer.addView(row)
        }
    }

    private fun buildProviderRows() {
        providerContainer.removeAllViews()
        val engine = RouterService.engineRef
        for (p in providers) {
            val title = TextView(this).apply {
                text = p.nameZh + " (" + p.id + ") · " + (if (p.routable) "routable" else p.status)
                setPadding(0, 12, 0, 4)
            }
            providerContainer.addView(title)
            val models = engine?.discovered?.get(p.id) ?: emptyList()
            if (models.isEmpty()) {
                providerContainer.addView(TextView(this).apply { text = "  未发现模型（缺 key 或未启动）" })
            } else {
                for (m in models) {
                    val st = engine?.health?.get(p.id, m)?.status ?: HealthStatus.UNKNOWN
                    providerContainer.addView(TextView(this).apply { text = "  • " + m + " [" + st + "]" })
                }
            }
        }
    }

    private fun refresh() {
        val engine = RouterService.engineRef
        trafficText.text = if (engine != null) {
            val g = engine.traffic.global()
            "请求 " + g.requests + " · 成功 " + g.okRequests + " · 失败 " + g.failedRequests + " · tokens " + g.totalTokens
        } else "（网关未运行）"
        val running = prefs.getBoolean("running", false)
        statusText.text = if (running) "● 运行中\nBase URL: http://127.0.0.1:4000/v1\n模型: free-router" else "○ 已停止"
        toggleBtn.text = if (running) "停止网关" else "启动网关"
        buildProviderRows()
        syncModelsUI()
    }

    private fun updateStatus() {
        val running = prefs.getBoolean("running", false)
        statusText.text = if (running) "● 运行中\nBase URL: http://127.0.0.1:4000/v1\n模型: free-router" else "○ 已停止"
        toggleBtn.text = if (running) "停止网关" else "启动网关"
    }

    private fun saveKeys() {
        for ((env, edit) in keyInputs) {
            Keys.set(env, edit.text.toString().trim())
        }
    }

    private fun toggle() {
        saveKeys()
        val intent = Intent(this, RouterService::class.java)
        if (prefs.getBoolean("running", false)) {
            stopService(intent)
            prefs.edit().putBoolean("running", false).apply()
        } else {
            startService(intent)
            prefs.edit().putBoolean("running", true).apply()
        }
        refresh()
    }

    private fun applyDebugInjection(intent: Intent?) {
        val env = intent?.getStringExtra("env")
        val key = intent?.getStringExtra("key")
        if (!env.isNullOrEmpty() && !key.isNullOrEmpty()) {
            Keys.set(env, key)
            buildKeyRows()
        }
    }

    private fun scheduleRefresh() {
        refresher.postDelayed({
            if (!isFinishing) { refresh(); scheduleRefresh() }
        }, 3000)
    }

    private fun syncModelsUI() {
        val engine = RouterService.engineRef ?: return
        val models = engine.listModels()
        val cur = (modelSpinner.adapter as? ArrayAdapter<String>)?.let { a -> (0 until a.count).map { a.getItem(it).toString() } }
        if (cur == null || cur != models) {
            spinnerReady = false
            val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, models.toMutableList())
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = ad
            val def = prefs.getString("default_model", "free-router") ?: "free-router"
            modelSpinner.setSelection(models.indexOf(def).coerceAtLeast(0))
            spinnerReady = true
            buildModelSettings(models)
        }
        frSwitch.isChecked = engine.freeRouterEnabled
    }

    private fun buildModelSettings(models: List<String>) {
        modelSettingsContainer.removeAllViews()
        val engine = RouterService.engineRef ?: return
        for (alias in models) {
            if (alias == "free-router") continue
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6) }
            val label = TextView(this).apply { text = alias; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val sw = Switch(this)
            sw.isChecked = engine.modelEnabled[alias] != false
            sw.setOnCheckedChangeListener { _, c ->
                prefs.edit().putBoolean("m:" + alias, c).apply()
                RouterService.engineRef?.modelEnabled?.set(alias, c)
            }
            row.addView(label); row.addView(sw)
            modelSettingsContainer.addView(row)
        }
    }

    override fun onDestroy() {
        refresher.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
