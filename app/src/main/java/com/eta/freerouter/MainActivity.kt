package com.eta.freerouter

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var keyContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Catalog.load(this)
        Keys.init(this)
        keyContainer = findViewById(R.id.keyContainer)
        statusText = findViewById(R.id.statusText)
        toggleBtn = findViewById(R.id.toggleBtn)
        buildKeyRows()
        updateStatus()
        toggleBtn.setOnClickListener { toggle() }
        applyDebugInjection(intent)
    }

    private fun buildKeyRows() {
        keyContainer.removeAllViews()
        for (p in Catalog.providers) {
            val env = p.credentialEnv ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            val label = TextView(this).apply {
                text = "${p.nameZh ?: p.name} ($env)"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = EditText(this).apply {
                hint = "API Key"
                setText(Keys.get(env) ?: "")
                tag = env
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(edit)
            keyContainer.addView(row)
        }
    }

    private fun saveKeys() {
        for (i in 0 until keyContainer.childCount) {
            val row = keyContainer.getChildAt(i) as LinearLayout
            val edit = row.getChildAt(1) as EditText
            val env = edit.tag as? String ?: continue
            val v = edit.text.toString().trim()
            if (v.isNotEmpty()) Keys.set(env, v)
        }
        Toast.makeText(this, "已保存密钥", Toast.LENGTH_SHORT).show()
    }

    private fun running(): Boolean =
        getSharedPreferences("fr_state", MODE_PRIVATE).getBoolean("running", false)

    private fun toggle() {
        saveKeys()
        val intent = Intent(this, RouterService::class.java)
        val prefs = getSharedPreferences("fr_state", MODE_PRIVATE)
        if (running()) {
            stopService(intent)
            prefs.edit().putBoolean("running", false).apply()
        } else {
            ContextCompat.startForegroundService(this, intent)
            prefs.edit().putBoolean("running", true).apply()
        }
        updateStatus()
    }

    private fun updateStatus() {
        val r = running()
        statusText.text = if (r) {
            "● 运行中\nBase URL: http://127.0.0.1:4000/v1\n模型填: free-router"
        } else {
            "○ 已停止"
        }
        toggleBtn.text = if (r) "停止" else "启动"
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyDebugInjection(intent)
    }

    private fun applyDebugInjection(intent: Intent?) {
        val env = intent?.getStringExtra("env")
        val key = intent?.getStringExtra("key")
        if (!env.isNullOrBlank() && !key.isNullOrBlank()) {
            Keys.set(env, key)
            Toast.makeText(this, "debug: injected $env", Toast.LENGTH_SHORT).show()
            updateStatus()
        }
    }
}
