package com.seth.rvgandroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.ClipboardManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Setup wizard + status screen. Walks Seth through:
 *  1. Accessibility service enable (input AND screenshots via takeScreenshot)
 *  2. Disable battery optimization
 *  3. Notification permission (Android 13+)
 * Shows the token for backup to Drive.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(layout)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "RVG Android Agent v" + UpdateManager.installedVersion(this@MainActivity)
            textSize = 22f
        }
        layout.addView(title)

        statusText = TextView(this).apply { textSize = 15f }
        layout.addView(statusText)

        fun btn(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        btn("1. Enable Accessibility Service") { openAccessibilitySettings() }
        btn("2. Disable Battery Optimization") { requestIgnoreBattery() }
        btn("3. Allow Notifications") { requestNotifications() }
        btn("Start Agent") { RvgService.start(this); refresh() }
        btn("Stop Agent") { RvgService.stop(this); refresh() }
        btn("Copy Token (backup to Drive)") { copyToken() }
        btn("Check for Updates") { UpdateManager.checkNow(this) }

        UpdateManager.onAppLaunch(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val acc = RvgAccessibilityService.isEnabled()
        val svc = RvgService.running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        statusText.text = """
            Agent running: $svc (port 8899)
            Accessibility: ${if (acc) "ENABLED" else "NOT ENABLED"}
            Screenshots: ${if (acc) "READY (via accessibility)" else "need accessibility"}
            Battery optimization disabled: $battOk
            Token: ${TokenStore.getToken(this).take(12)}...
        """.trimIndent()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Toast.makeText(this, "Turn on 'RVG Android Agent'", Toast.LENGTH_LONG).show()
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }

    private fun copyToken() {
        val token = TokenStore.getToken(this)
        @Suppress("DEPRECATION")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).text = token
        Toast.makeText(this, "Token copied — save to Drive as RVG-android-token.txt", Toast.LENGTH_LONG).show()
    }
}
