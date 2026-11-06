package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var testField: EditText
    private var testBrain: Brain? = null

    private val bg = Color.parseColor("#0B0F1A")
    private val fg = Color.parseColor("#E6F1FF")
    private val accent = Color.parseColor("#4FD1FF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S"
            textSize = 30f
            setTextColor(accent)
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(fg)
            setPadding(0, dp(8), 0, dp(12))
            gravity = Gravity.CENTER
        }
        root.addView(status)

        keyField = EditText(this).apply {
            hint = "FREE Gemini API key (AIza...)"
            setText(Prefs.apiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(fg); setHintTextColor(Color.GRAY)
        }
        root.addView(keyField)

        modelField = EditText(this).apply {
            hint = "Model"
            setText(Prefs.model(this@MainActivity))
            setTextColor(fg); setHintTextColor(Color.GRAY)
        }
        root.addView(modelField)

        root.addView(button("💾 Save key") { save(); toast("Saved") })

        root.addView(section("Step 1 — Permissions"))
        root.addView(button("1. Mic / Call / Contacts / SMS permissions") { askPermissions() })
        root.addView(button("2. Accessibility ON (phone control)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Installed apps/Downloaded apps → Jarvis → ON")
        })
        root.addView(button("3. Display over other apps (background launch)") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(button("4. Modify system settings (brightness)") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(button("5. Battery: don't restrict Jarvis") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        root.addView(section("Step 2 — Run"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("▶ Start Jarvis", 1f) { startJarvis() })
        row.addView(button("■ Stop", 1f) {
            stopService(Intent(this, JarvisService::class.java))
            ui.postDelayed({ refresh() }, 300)
        })
        root.addView(row)

        root.addView(section("Type a command to test"))
        testField = EditText(this).apply {
            hint = "e.g. open WhatsApp"
            setTextColor(fg); setHintTextColor(Color.GRAY)
        }
        root.addView(testField)
        root.addView(button("Send") { runTest() })

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9FB3C8"))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(accent)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun button(label: String, weight: Float = 0f, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = if (weight > 0f) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        } else {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun save() = Prefs.save(this, keyField.text.toString(), modelField.text.toString())

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun startJarvis() {
        save()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions()
            toast("पहले mic permission दीजिए, फिर Start दबाइए")
            return
        }
        if (Prefs.apiKey(this).isBlank()) {
            toast("पहले API key डालिए"); return
        }
        startForegroundService(Intent(this, JarvisService::class.java))
        ui.postDelayed({ refresh() }, 500)
    }

    private fun runTest() {
        save()
        val cmd = testField.text.toString().trim()
        if (cmd.isEmpty()) return
        val brain = testBrain ?: Brain(applicationContext).also { testBrain = it }
        JarvisLog.add("⌨ $cmd")
        Thread {
            try {
                JarvisLog.add("🤖 " + brain.ask(cmd))
            } catch (e: Exception) {
                JarvisLog.add("❌ ${e.message}")
            }
        }.start()
        testField.setText("")
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = "Jarvis: ${if (JarvisService.running) "ON 🟢" else "OFF"}   •   " +
            "Accessibility: ${if (JarvisAccessibilityService.instance != null) "ON 🟢" else "OFF"}   •   " +
            "Mic: ${if (mic) "OK" else "NO"}"
        logView.text = JarvisLog.text()
    }

    override fun onResume() {
        super.onResume()
        JarvisLog.listener = { ui.post { refresh() } }
        refresh()
    }

    override fun onPause() {
        JarvisLog.listener = null
        super.onPause()
    }
}
