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
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RvgService::class.java))
        }

        @Volatile
        var running = false
            private set
    }

    private var http: HttpServer? = null

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
        startForeground(2, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
        return START_STICKY
    }

    override fun onDestroy() {
        http?.stop()
        http = null
        running = false
        super.onDestroy()
    }
}
