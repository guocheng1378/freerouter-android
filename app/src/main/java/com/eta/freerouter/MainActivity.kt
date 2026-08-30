package com.eta.freerouter

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.registry.loadProviders
import com.eta.freerouter.core.registry.Tier
import com.eta.freerouter.core.state.HealthStatus
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var keyContainer: LinearLayout
    private lateinit var providerContainer: LinearLayout
    private lateinit var changelogContainer: LinearLayout
    private lateinit var callLogContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var trafficText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var refreshBtn: Button
    private var providers: List<Provider> = emptyList()
    private lateinit var modelSpinner: Spinner
    private lateinit var frSwitch: SwitchCompat
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
        changelogContainer = findViewById(R.id.changelogContainer)
        callLogContainer = findViewById(R.id.callLogContainer)
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
        refreshBtn.setOnClickListener {
            val engine = RouterService.engineRef
            if (engine != null) {
                Toast.makeText(this, "正在重新探测…", Toast.LENGTH_SHORT).show()
                engine.recheck()
            } else {
                Toast.makeText(this, "请先启动网关", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
        applyDebugInjection(intent)
        updateStatus()
        buildProviderRows()
        buildChangelog()
        refresher = Handler(Looper.getMainLooper())
        scheduleRefresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun fmtRetry(ms: Long): String {
        if (ms <= 0L) return ""
        val rem = ms - System.currentTimeMillis()
        if (rem <= 0) return ""
        val min = (rem / 60000).toInt()
        return " · 重试" + (if (min > 0) min.toString() + "分后" else "稍后")
    }

    private fun offerSummary(p: Provider): String {
        val parts = mutableListOf<String>()
        if (p.tier != Tier.FREE) parts.add("tier=" + p.tier)
        p.offer.endsOn?.let { parts.add("活动至" + it) }
        p.limits.rpm?.let { parts.add("rpm=" + it) }
        if (p.freeBasis.isNotEmpty()) parts.add(p.freeBasis)
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(" ")
    }

    private fun buildKeyRows() {
        keyContainer.removeAllViews()
        keyInputs.clear()
        for (p in providers) {
            val env = p.credential ?: continue
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = p.nameZh
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.card_title))
            }
            val edit = EditText(this).apply {
                hint = env
                setText(Keys.get(env) ?: "")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            keyInputs[env] = edit
            row.addView(label); row.addView(edit)
            box.addView(row)
            // 注册/邀请链接（上游 cli keys 等价）
            val linkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var added = false
            p.consoleUrl?.let { url ->
                linkRow.addView(linkView("注册", url)); added = true
            }
            p.referralUrl?.let { url ->
                linkRow.addView(linkView("邀请", url)); added = true
            }
            if (added) box.addView(linkRow)
            keyContainer.addView(box)
        }
    }

    private fun linkView(text: String, url: String): TextView = TextView(this).apply {
        this.text = "  " + text + " ↗"
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_healthy))
        setPadding(0, 2, 16, 2)
        setOnClickListener { openUrl(url) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildProviderRows() {
        providerContainer.removeAllViews()
        val engine = RouterService.engineRef
        for (p in providers) {
            val title = TextView(this).apply {
                text = p.nameZh + " (" + p.id + ")" + (if (p.routable) " · 可用" else " · " + p.status) + offerSummary(p)
                setPadding(0, 12, 0, 4)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.card_title))
                setTypeface(null, Typeface.BOLD)
            }
            providerContainer.addView(title)
            val models = engine?.discovered?.get(p.id) ?: emptyList()
            if (models.isEmpty()) {
                providerContainer.addView(TextView(this).apply {
                    text = "  未发现模型（缺 key 或未启动）"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                })
            } else {
                for (m in models) {
                    val h = engine?.health?.get(p.id, m)
                    val st = h?.status ?: HealthStatus.UNKNOWN
                    val retry = fmtRetry(h?.retryAfter ?: 0L)
                    val detail = if (!h?.detail.isNullOrEmpty()) " · " + h?.detail else ""
                    providerContainer.addView(TextView(this).apply {
                        text = "● " + m + " [" + st + "]" + detail + retry
                        textSize = 12f
                        setPadding(0, 2, 0, 2)
                        setTextColor(statusColor(st, h?.retryAfter ?: 0L))
                    })
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
        buildChangelog()
        buildCallLog()
        syncModelsUI()
    }

    private fun updateStatus() {
        val running = prefs.getBoolean("running", false)
        statusText.text = if (running) "● 运行中\nBase URL: http://127.0.0.1:4000/v1\n模型: free-router" else "○ 已停止"
        toggleBtn.text = if (running) "停止网关" else "启动网关"
    }

    private fun buildCallLog() {
        callLogContainer.removeAllViews()
        val engine = RouterService.engineRef ?: return
        val calls = engine.traffic.recentCalls(20)
        if (calls.isEmpty()) {
            callLogContainer.addView(TextView(this).apply {
                text = "  （暂无调用记录）"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            })
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        for (c in calls) {
            val ts = fmt.format(Date(c.time))
            val src = if (c.isProbe) "探测" else "调用"
            val ok = if (c.ok) "✓" else "✗"
            val line = "  [" + ts + "] " + src + " " + ok + " " + c.alias + " → " + c.realModel + "  " + c.durationMs + "ms  " + c.promptTokens + "+" + c.completionTokens + "tok"
            callLogContainer.addView(TextView(this).apply {
                text = line
                textSize = 11f
                setPadding(0, 2, 0, 2)
                setTextColor(ContextCompat.getColor(this@MainActivity, if (c.ok) R.color.status_healthy else R.color.status_quarantined))
            })
        }
    }

    private fun saveKeys() {
        for ((env, edit) in keyInputs) Keys.set(env, edit.text.toString().trim())
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
        if (!env.isNullOrEmpty() && !key.isNullOrEmpty()) { Keys.set(env, key); buildKeyRows() }
        val dm = intent?.getStringExtra("default_model")
        if (!dm.isNullOrEmpty()) {
            prefs.edit().putString("default_model", dm).apply()
            RouterService.engineRef?.defaultModel = dm
        }
        if (intent?.hasExtra("fr_enabled") == true) {
            val en = intent.getBooleanExtra("fr_enabled", true)
            prefs.edit().putBoolean("fr_enabled", en).apply()
            RouterService.engineRef?.freeRouterEnabled = en
        }
        val dis = intent?.getStringExtra("disable_model")
        if (!dis.isNullOrEmpty()) {
            prefs.edit().putBoolean("m:" + dis, false).apply()
            RouterService.engineRef?.modelEnabled?.set(dis, false)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyDebugInjection(intent)
    }

    private fun scheduleRefresh() {
        refresher.postDelayed({ if (!isFinishing) { refresh(); scheduleRefresh() } }, 3000)
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
            val label = TextView(this).apply {
                text = alias
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.card_title))
            }
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

    private fun buildChangelog() {
        changelogContainer.removeAllViews()
        val engine = RouterService.engineRef ?: return
        val entries = engine.changelog.recent(30)
        if (entries.isEmpty()) {
            changelogContainer.addView(TextView(this).apply {
                text = "  （暂无变更）"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            })
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        for (e in entries.reversed()) {
            val ts = fmt.format(Date(e.ts))
            changelogContainer.addView(TextView(this).apply {
                text = "  [" + ts + "] " + e.kind + " " + e.text
                textSize = 12f
                setPadding(0, 2, 0, 2)
                setTextColor(ContextCompat.getColor(this@MainActivity, changelogColor(e.kind)))
            })
        }
    }

    private fun statusColor(status: HealthStatus, retryAfter: Long): Int {
        val now = System.currentTimeMillis()
        val c = when {
            status == HealthStatus.HEALTHY -> R.color.status_healthy
            status == HealthStatus.QUARANTINED -> R.color.status_quarantined
            retryAfter > now -> R.color.status_warn
            else -> R.color.status_unknown
        }
        return ContextCompat.getColor(this, c)
    }

    private fun changelogColor(kind: String): Int {
        val c = when {
            kind.contains("QUARANTINED") || kind.contains("REMOVED") || kind.contains("EXPIRED") -> R.color.status_quarantined
            kind.contains("ADDED") || kind.contains("REVIVED") || kind.contains("TRAFFIC_OK") -> R.color.status_healthy
            kind.contains("OFFER") -> R.color.status_warn
            else -> R.color.text_secondary
        }
        return ContextCompat.getColor(this, c)
    }

    override fun onDestroy() {
        refresher.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
