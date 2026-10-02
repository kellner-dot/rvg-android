package com.seth.rvgandroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
 *  1. Accessibility service enable
 *  2. Screen-capture consent
 *  3. Disable battery optimization
 *  4. Notification permission (Android 13+)
 * Shows the token for backup to Drive.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val shotRequestCode = 1001

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
            text = "RVG Android Agent v1.0.0"
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
        btn("2. Grant Screen Capture") { requestScreenCapture() }
        btn("3. Disable Battery Optimization") { requestIgnoreBattery() }
        btn("4. Allow Notifications") { requestNotifications() }
        btn("Start Agent") { RvgService.start(this); refresh() }
        btn("Stop Agent") { RvgService.stop(this); refresh() }
        btn("Copy Token (backup to Drive)") { copyToken() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val acc = RvgAccessibilityService.isEnabled()
        val shot = ScreenshotService.instance?.isReady() == true
        val svc = RvgService.running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        statusText.text = """
            Agent running: $svc (port 8899)
            Accessibility: ${if (acc) "ENABLED" else "NOT ENABLED"}
            Screen capture: ${if (shot) "READY" else "NOT GRANTED"}
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

    private fun requestScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), shotRequestCode)
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

    @Deprecated("use Activity Result API in production")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == shotRequestCode && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, ScreenshotService::class.java).apply {
                action = ScreenshotService.ACTION_START
                putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenshotService.EXTRA_DATA, data)
            }
            startForegroundService(i)
            Toast.makeText(this, "Screen capture granted", Toast.LENGTH_SHORT).show()
        } else if (requestCode == shotRequestCode) {
            Toast.makeText(this, "Screen capture denied — screenshots won't work", Toast.LENGTH_LONG).show()
        }
        refresh()
    }
}
