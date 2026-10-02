package com.seth.rvgandroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * API layer mirroring the Windows/Mac RVG protocol (port 8899).
 *
 *   GET  /rvd/status            -> agent status JSON
 *   GET  /rvd/shot              -> PNG screenshot
 *   POST /rvd/input             -> {"tap":[x,y]} | {"swipe":[x1,y1,x2,y2]} |
 *                                  {"key":"home"} | {"type":"text"}
 *   POST /rvd/exec              -> {"command":"..."} limited command set
 *   GET  /rvd/download?path=    -> file bytes (sandbox-scoped)
 *   POST /rvd/upload            -> multipart file -> inbox dir
 *   POST /rvd/notify            -> {"title":"..","text":".."} device notification
 *   GET  /rvd/view              -> simple HTML viewer
 *
 * Auth: X-RVD-Token header must equal the stored token.
 *
 * ANDROID SANDBOX NOTE: /rvd/exec cannot run arbitrary shell. It supports a
 * fixed allowlist of device actions (launch app, open URL, key events,
 * device info). Documented in README.md.
 */
class ApiServer(private val ctx: Context, private val http: HttpServer) {

    private val startTime = SystemClock.elapsedRealtime()
    private val inboxDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "inbox").apply { mkdirs() }
    }

    fun attach() {
        http.handler = { req -> route(req) }
    }

    private fun route(req: HttpServer.Request): HttpServer.Response {
        // Auth (skip for /rvd/view so the browser viewer loads; API calls
        // from the page still carry the token via JS fetch).
        val token = TokenStore.getToken(ctx)
        if (req.path != "/rvd/view") {
            val got = req.headers["x-rvd-token"]
            if (got != token) return HttpServer.Response.jsonErr(401, "bad or missing token")
        }

        return try {
            when {
                req.path == "/rvd/status" && req.method == "GET" -> status()
                req.path == "/rvd/shot" && req.method == "GET" -> shot(req)
                req.path == "/rvd/input" && req.method == "POST" -> input(req)
                req.path == "/rvd/exec" && req.method == "POST" -> exec(req)
                req.path == "/rvd/download" && req.method == "GET" -> download(req)
                req.path == "/rvd/upload" && req.method == "POST" -> upload(req)
                req.path == "/rvd/notify" && req.method == "POST" -> notify(req)
                req.path == "/rvd/view" && req.method == "GET" -> view(req)
                else -> HttpServer.Response.jsonErr(404, "unknown endpoint")
            }
        } catch (e: Exception) {
            HttpServer.Response.jsonErr(500, e.message ?: "error")
        }
    }

    // ---------- endpoints ----------

    private fun status(): HttpServer.Response {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val o = JSONObject()
            .put("ok", true)
            .put("version", "1.0.0")
            .put("platform", "android")
            .put("hostname", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("screenW", metrics.widthPixels)
            .put("screenH", metrics.heightPixels)
            .put("uptime", (SystemClock.elapsedRealtime() - startTime) / 1000)
            .put("accessibility", RvgAccessibilityService.isEnabled())
            .put("screenshotReady", ScreenshotService.instance?.isReady() == true)
        return HttpServer.Response.json(o.toString())
    }

    private fun shot(req: HttpServer.Request): HttpServer.Response {
        val png = ScreenshotService.instance?.capturePng()
            ?: return HttpServer.Response.jsonErr(503, "screenshot not ready (grant screen capture permission in app)")
        return HttpServer.Response(200, png, "image/png")
    }

    private fun input(req: HttpServer.Request): HttpServer.Response {
        val svc = RvgAccessibilityService.instance
            ?: return HttpServer.Response.jsonErr(503, "accessibility service not enabled")
        val j = JSONObject(String(req.body, Charsets.UTF_8))
        val ok = when {
            j.has("tap") -> {
                val a = j.getJSONArray("tap")
                svc.tap(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            }
            j.has("swipe") -> {
                val a = j.getJSONArray("swipe")
                val dur = if (a.length() > 4) a.getLong(4) else 300L
                svc.swipe(
                    a.getDouble(0).toFloat(), a.getDouble(1).toFloat(),
                    a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), dur
                )
            }
            j.has("longpress") -> {
                val a = j.getJSONArray("longpress")
                svc.longPress(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            }
            j.has("key") -> svc.key(j.getString("key"))
            j.has("type") -> svc.typeText(j.getString("type"))
            else -> return HttpServer.Response.jsonErr(400, "unknown input action")
        }
        return HttpServer.Response.json("{\"ok\":$ok}")
    }

    /**
     * Limited command set — Android has no general shell for apps.
     * Allowed: deviceinfo, apps, launch, openurl, key, toast, vibrate,
     * screenshot-state, battery.
     */
    private fun exec(req: HttpServer.Request): HttpServer.Response {
        val j = JSONObject(String(req.body, Charsets.UTF_8))
        val cmd = j.optString("command", "").trim()
        val out = JSONObject().put("ok", true)
        when {
            cmd == "deviceinfo" -> {
                out.put("stdout", JSONObject()
                    .put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("fingerprint", Build.FINGERPRINT).toString())
            }
            cmd == "apps" -> {
                val pm = ctx.packageManager
                val pkgs = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") pm.getInstalledPackages(0)
                }
                val arr = org.json.JSONArray()
                for (p in pkgs) {
                    val ai = p.applicationInfo ?: continue
                    // Skip system apps for brevity
                    if ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                    arr.put(p.packageName)
                }
                out.put("stdout", arr.toString())
            }
            cmd.startsWith("launch ") -> {
                val pkg = cmd.removePrefix("launch ").trim()
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return HttpServer.Response.jsonErr(404, "no launch intent for $pkg")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                out.put("stdout", "launched $pkg")
            }
            cmd.startsWith("openurl ") -> {
                val url = cmd.removePrefix("openurl ").trim()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                out.put("stdout", "opened $url")
            }
            cmd.startsWith("key ") -> {
                val svc = RvgAccessibilityService.instance
                    ?: return HttpServer.Response.jsonErr(503, "accessibility not enabled")
                out.put("stdout", "key -> ${svc.key(cmd.removePrefix("key ").trim())}")
            }
            cmd.startsWith("toast ") -> {
                val msg = cmd.removePrefix("toast ").trim()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                }
                out.put("stdout", "toast shown")
            }
            cmd == "battery" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                out.put("stdout", JSONObject()
                    .put("level", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("charging", bm.isCharging).toString())
            }
            else -> return HttpServer.Response.json(
                JSONObject().put("ok", false)
                    .put("error", "unsupported command (allowed: deviceinfo, apps, launch <pkg>, openurl <url>, key <name>, toast <msg>, battery)")
                    .toString()
            )
        }
        out.put("stderr", "")
        out.put("exitCode", 0)
        return HttpServer.Response.json(out.toString())
    }

    /** File download scoped to app-private dirs + public Downloads/Pictures. */
    private fun download(req: HttpServer.Request): HttpServer.Response {
        val rel = req.query["path"] ?: return HttpServer.Response.jsonErr(400, "missing path")
        val base = allowedBase(rel) ?: return HttpServer.Response.jsonErr(403, "path not allowed")
        val f = File(base, rel.substringAfter('/').substringAfter(':'))
        val canon = f.canonicalFile
        if (!canon.path.startsWith(base.canonicalPath)) {
            return HttpServer.Response.jsonErr(403, "path escape blocked")
        }
        if (!canon.isFile) return HttpServer.Response.jsonErr(404, "not found")
        if (canon.length() > 500L * 1024 * 1024) {
            return HttpServer.Response.jsonErr(413, "file too large (500 MiB cap)")
        }
        return HttpServer.Response(
            200, canon.readBytes(), "application/octet-stream",
            mapOf("Content-Disposition" to "attachment; filename=\"${canon.name}\"")
        )
    }

    /** File upload -> app inbox dir. */
    private fun upload(req: HttpServer.Request): HttpServer.Response {
        val (name, bytes) = http.parseMultipart(req)
            ?: return HttpServer.Response.jsonErr(400, "no file part")
        if (bytes.size > 500 * 1024 * 1024) {
            return HttpServer.Response.jsonErr(413, "file too large (500 MiB cap)")
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(128)
        val dest = File(inboxDir, safe)
        dest.writeBytes(bytes)
        return HttpServer.Response.json(
            JSONObject().put("ok", true).put("path", dest.absolutePath)
                .put("size", bytes.size).toString()
        )
    }

    private fun notify(req: HttpServer.Request): HttpServer.Response {
        val j = JSONObject(String(req.body, Charsets.UTF_8))
        val title = j.optString("title", "RVG")
        val text = j.optString("text", "")
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        val ch = android.app.NotificationChannel("rvg-note", "RVG notifications",
            android.app.NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)
        val n = android.app.Notification.Builder(ctx, "rvg-note")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
        return HttpServer.Response.json("{\"ok\":true}")
    }

    private fun view(req: HttpServer.Request): HttpServer.Response {
        val token = req.query["token"] ?: TokenStore.getToken(ctx)
        val html = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>RVG Android viewer</title>
        <style>body{background:#111;color:#eee;font-family:sans-serif;text-align:center}
        img{max-width:100%;border:1px solid #444}button{margin:4px;padding:8px 14px}</style>
        </head><body>
        <h3>RVG Android — ${Build.MODEL}</h3>
        <img id="shot" src="/rvd/shot"><br>
        <button onclick="tap(event)">tap: click image</button>
        <button onclick="key('back')">back</button>
        <button onclick="key('home')">home</button>
        <button onclick="key('recents')">recents</button>
        <button onclick="refresh()">refresh</button>
        <script>
        const T = ${JSONObject.quote(token)};
        const H = {"X-RVD-Token": T};
        function refresh(){ document.getElementById('shot').src = '/rvd/shot?' + Date.now(); }
        async function tap(e){
          const img = document.getElementById('shot');
          const r = img.getBoundingClientRect();
          const st = await (await fetch('/rvd/status',{headers:H})).json();
          const x = Math.round((e.clientX - r.left) / r.width * st.screenW);
          const y = Math.round((e.clientY - r.top) / r.height * st.screenH);
          await fetch('/rvd/input',{method:'POST',headers:{...H,'Content-Type':'application/json'},
            body: JSON.stringify({tap:[x,y]})});
          setTimeout(refresh, 400);
        }
        async function key(k){
          await fetch('/rvd/input',{method:'POST',headers:{...H,'Content-Type':'application/json'},
            body: JSON.stringify({key:k})});
          setTimeout(refresh, 400);
        }
        setInterval(refresh, 5000);
        </script></body></html>
        """.trimIndent()
        return HttpServer.Response(200, html.toByteArray(Charsets.UTF_8), "text/html")
    }

    /** Map a scoped prefix to a base dir. Prefixes: app:, downloads:, pictures: */
    private fun allowedBase(rel: String): File? {
        return when {
            rel.startsWith("app:") -> ctx.filesDir
            rel.startsWith("downloads:") ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            rel.startsWith("pictures:") ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            else -> null
        }
    }
}
