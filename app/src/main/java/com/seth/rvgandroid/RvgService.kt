package com.seth.rvgandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service hosting the RVG HTTP API on port 8899.
 * Keeps the agent alive; Android may still kill it under extreme
 * memory pressure — battery optimization must be disabled (see INSTALL.md).
 */
class RvgService : Service() {

    companion object {
        const val PORT = 8899

        fun start(ctx: Context) {
            val i = Intent(ctx, RvgService::class.java)
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RvgService::class.java))
        }

        @Volatile
        var running = false
            private set
    }

    private var http: HttpServer? = null

    // Binding watchdog (1.3.2): the system can drop the accessibility
    // binding while the setting stays ON ("enabled but unbound"). Every 15 s
    // check for listed-but-unbound and, when the WRITE_SECURE_SETTINGS grant
    // is held, toggle the setting to force a rebind. Attempts run on a
    // background thread (the toggle sleeps/polls ~11 s); at most one at a
    // time; capped per episode by RebindManager. Never runs when the service
    // is not listed (user turned it off) or the grant is missing.
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile
    private var rebindAttemptRunning = false
    private val bindingWatchdog = object : Runnable {
        override fun run() {
            try {
                checkBinding()
            } catch (_: Exception) {
            }
            watchdogHandler.postDelayed(this, 15_000)
        }
    }

    private fun checkBinding() {
        if (RvgAccessibilityService.isEnabled()) return
        if (rebindAttemptRunning) return
        if (!AndroidSecureSettings.isGrantHeld(this)) return
        val state = BindingState.from(this)
        val manager = RebindManager(
            AndroidSecureSettings(this), state,
            { RvgAccessibilityService.isEnabled() }
        )
        if (!manager.isListed()) return
        if (state.attemptsInEpisode() >= RebindManager.MAX_ATTEMPTS_PER_EPISODE) return
        val last = state.lastAttemptMs()
        if (last > 0 && System.currentTimeMillis() - last < RebindManager.MIN_INTERVAL_MS) return
        rebindAttemptRunning = true
        Thread {
            try {
                val result = manager.rebind()
                android.util.Log.i("RVG", "auto-rebind: ${result.outcome} ${result.detail}")
            } catch (_: Exception) {
            } finally {
                rebindAttemptRunning = false
            }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("rvg-svc", "RVG service", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "rvg-svc")
            .setContentTitle("RVG Android agent")
            .setContentText("Listening on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        // startForeground(2, n) // Disabled - not needed, causes Android 14+ crash
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (http == null) {
            http = HttpServer(PORT)
            ApiServer(this, http!!).attach()
            try {
                http!!.start()
                running = true
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
        watchdogHandler.removeCallbacks(bindingWatchdog)
        watchdogHandler.postDelayed(bindingWatchdog, 15_000)
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(bindingWatchdog)
        // 1.3.4: never strand the phone on the keyless RVG keyboard when
        // the agent stops. Best-effort only — onDestroy is not guaranteed
        // if the process is killed, so POST /rvd/ime (target gboard)
        // stays the documented teardown path. Never throws.
        try {
            if (AndroidSecureSettings.isGrantHeld(this)) {
                val t = ImeToggleAndroid.create(this)
                if (t.state().rvgActive) t.switchAwayFromRvg()
            }
        } catch (_: Exception) {
        }
        http?.stop()
        http = null
        running = false
        super.onDestroy()
    }
}
