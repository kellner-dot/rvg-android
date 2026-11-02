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
        btn("Re-enable accessibility now") { rebindNow() }
        btn("Switch keyboard (RVG ⇄ Gboard)") { toggleKeyboard() }
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
        val bs = BindingState.from(this)
        val listed = RebindManager.isListed(AndroidSecureSettings(this))
        val grant = AndroidSecureSettings.isGrantHeld(this)
        val imeState = ImeToggleAndroid.state(this)
        statusText.text = """
            Agent running: $svc (port 8899)
            Accessibility: ${if (acc) "ENABLED" else "NOT ENABLED"}${if (!acc && listed) " (dropped — still listed)" else ""}
            Screenshots: ${if (acc) "READY (via accessibility)" else "need accessibility"}
            Battery optimization disabled: $battOk
            Keyboard: ${ImeToggleAndroid.shortName(imeState.current)} (RVG enabled: ${if (imeState.rvgEnabled) "yes" else "no"}${if (!grant) ", one-tap switch needs the adb grant" else ""})
            Auto-rebind: ${if (grant) "available (grant held)" else "unavailable — one-time adb grant needed"}
            Unbinds: ${bs.unbindCount}${if (bs.lastUnbindAtMs > 0) " (last ${java.util.Date(bs.lastUnbindAtMs)})" else ""}
            Last rebind: ${bs.lastOutcome ?: "—"}
            Update: ${UpdateManager.updateVersion(this)?.let { "v$it available" } ?: "latest"}${UpdateManager.lastUpdateError(this)?.let { " (last error: $it)" } ?: ""}
            Token: ${TokenStore.getToken(this).take(12)}...
        """.trimIndent()
    }

    /** 1.3.4: one-tap keyboard toggle (RVG ⇄ Gboard). Without the grant
     *  the toggle cannot write the secure settings, so open the system
     *  input-method settings instead — never a silent no-op. */
    private fun toggleKeyboard() {
        if (!AndroidSecureSettings.isGrantHeld(this)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Toast.makeText(
                this,
                "One-time adb grant needed for one-tap switch — enable the keyboard in Settings",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Thread {
            val result = ImeToggleAndroid.create(this).toggle()
            runOnUiThread {
                Toast.makeText(
                    this, "Keyboard: ${result.outcome} — ${result.detail}",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        }.start()
    }

    /** 1.3.2: programmatic rebind when the grant is held (background thread —
     *  the attempt sleeps/polls ~11 s); otherwise today's Settings path. */
    private fun rebindNow() {
        if (!AndroidSecureSettings.isGrantHeld(this)) {
            openAccessibilitySettings()
            return
        }
        Thread {
            val result = RebindManager(
                AndroidSecureSettings(this), BindingState.from(this),
                { RvgAccessibilityService.isEnabled() }
            ).rebind()
            runOnUiThread {
                Toast.makeText(this, "Rebind: ${result.outcome}", Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
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
        val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        // 1.3.0 clipboard hygiene (Seth's word, Oct 26 16:19): the
        // clipboard is cleared ONLY immediately after this app's own
        // copyToken action, and only to remove a token this app itself
        // just placed — never on launch, never on a timer, never
        // user-copied content. The copy and the clear happen in this
        // same call, so the token never lingers for other apps to read.
        // Consequence, stated plainly: after this tap the clipboard
        // holds neutral text, NOT the token — pasting the token from
        // the clipboard no longer works; the Drive backup path is the
        // API replacement, not a clipboard paste.
        @Suppress("DEPRECATION")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).text = token
        cm.setPrimaryClip(android.content.ClipData.newPlainText("RVG", ""))
        Toast.makeText(this, "Token clipboard cleared — token never lingers on the clipboard", Toast.LENGTH_LONG).show()
    }
}
