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
import java.security.MessageDigest

/**
 * API layer mirroring the Windows/Mac RVG protocol (port 8899).
 *
 *   GET  /rvd/status            -> agent status JSON
 *   GET  /rvd/shot              -> PNG screenshot
 *   POST /rvd/input             -> {"tap":[x,y]} | {"swipe":[x1,y1,x2,y2]} |
 *                                  {"key":"home"} | {"type":"text"} |
 *                                  {"keytext":"text"} | {"longpress":[x,y]}
 *                                  1.2.0: {"nodeclick":[x,y]} | {"nodequery":[x,y]}
 *                                  1.3.0: {"nodetree":{...}} |
 *                                  {"nodefind":{"selector":{...},"index":n,"unique":bool}} |
 *                                  {"nodetap":{"selector":{...},...}} |
 *                                  {"nodescroll":[x,y,"forward"|"backward"]} |
 *                                  {"nodescroll":{"selector":{...},"direction":...}} |
 *                                  {"nodescrolluntil":{"selector":{...},"direction":...,"maxAttempts":n,"container":[x,y]}} |
 *                                  {"nodewait":{"selector":{...},"timeoutMs":n,"intervalMs":n}} |
 *                                  {"nodeshow":[x,y]} | {"nodeshow":{"selector":{...}}} |
 *                                  {"nodesettext":[x,y,"text"]} | {"nodesettext":{"selector":{...},"text":"..."}} |
 *                                  {"nodelongclick":[x,y]} | {"nodelongclick":{"selector":{...}}} |
 *                                  {"nodewindows":true} | {"imeswitchback":true}
 *                                  Selector object fields: text, textExact,
 *                                  contentDesc, contentDescExact, class, id
 *                                  (full resource name or suffix after '/'),
 *                                  clickable, scrollable, editable, checkable,
 *                                  checked, focused, bounds [l,t,r,b],
 *                                  contains [x,y]. Never XPath.
 *   POST /rvd/update            -> {"check":true} runs the service-side
 *                                  autoUpdate path (same as the daily
 *                                  alarm); {"force":true} retries past a
 *                                  recorded error for that version.
 *   Text input: "type" uses ACTION_SET_TEXT on the focused EditText (fast
 *   path) and FALLS BACK to the RVG keyboard IME when the target isn't an
 *   EditText (e.g. Termux's TerminalView). "keytext" goes straight to the
 *   IME. Single-char {"key":"a"} also uses the IME. The IME path needs
 *   "RVG Keyboard" enabled + selected once in system keyboard settings;
 *   /rvd/status reports "imeEnabled"/"imeActive".
 *   POST /rvd/rebind          -> attempt a programmatic rebind of the
 *                                  accessibility service (1.3.2); returns
 *                                  {"ok","outcome","detail","attemptsInEpisode",
 *                                  "elapsedMs"}. Refuses when the service is
 *                                  not listed (user turned it off) or the
 *                                  WRITE_SECURE_SETTINGS grant is missing.
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

    /** Constant-time token comparison; empty on either side never matches. */
    private fun tokenMatches(got: String?, token: String): Boolean {
        if (got.isNullOrEmpty() || token.isEmpty()) return false
        return MessageDigest.isEqual(
            got.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8))
    }

    private fun route(req: HttpServer.Request): HttpServer.Response {
        // Auth (skip for /rvd/view: the page itself carries no secrets, the
        // token is typed in-page and sent via JS fetch like the Mac viewer).
        val token = TokenStore.getToken(ctx)
        if (req.path != "/rvd/view") {
            val got = req.headers["x-rvd-token"]
            if (!tokenMatches(got, token)) return HttpServer.Response.jsonErr(401, "bad or missing token")
        }

        return try {
            when {
                req.path == "/rvd/status" && req.method == "GET" -> status()
                req.path == "/rvd/shot" && req.method == "GET" -> shot(req)
                req.path == "/rvd/input" && req.method == "POST" -> input(req)
                req.path == "/rvd/update" && req.method == "POST" -> update(req)
                req.path == "/rvd/rebind" && req.method == "POST" -> rebind()
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
            .put("version", appVersion())
            .put("platform", "android")
            .put("hostname", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("screenW", metrics.widthPixels)
            .put("screenH", metrics.heightPixels)
            .put("uptime", (SystemClock.elapsedRealtime() - startTime) / 1000)
            .put("accessibility", RvgAccessibilityService.isEnabled())
            .put("screenshotReady", RvgAccessibilityService.isEnabled())
            // Binding telemetry (1.3.2): distinguishes a system drop
            // (listed but unbound) from never-enabled / user-disabled.
            .put("accessibilityListed", RebindManager.isListed(AndroidSecureSettings(ctx)))
            .put("unbindCount", BindingState.from(ctx).unbindCount)
            .put("lastUnbindAtMs", BindingState.from(ctx).lastUnbindAtMs)
            .put("lastConnectedAtMs", BindingState.from(ctx).lastConnectedAtMs)
            .put("rebindAvailable", AndroidSecureSettings.isGrantHeld(ctx))
            .put("rebindLastOutcome", BindingState.from(ctx).lastOutcome ?: JSONObject.NULL)
            .put("rebindLastAtMs", BindingState.from(ctx).lastAttemptMs())
            .put("imeEnabled", RvgInputMethodService.isEnabled(ctx))
            .put("imeActive", RvgInputMethodService.isActive(ctx))
            // Self-update reporting (existing status path — every
            // check/install/version transition lands here, never silent).
            .put("updateAvailable", UpdateManager.updateAvailable(ctx))
            .put("updateVersion", UpdateManager.updateVersion(ctx) ?: JSONObject.NULL)
            .put("updateError", UpdateManager.lastUpdateError(ctx) ?: JSONObject.NULL)
            .put("lastUpdateCheckMs", UpdateManager.lastCheckMs(ctx))
            .put("lastUpdateResult", UpdateManager.lastUpdateResult(ctx) ?: JSONObject.NULL)
        return HttpServer.Response.json(o.toString())
    }

    /** Real versionName from the installed package — never a stale literal. */
    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** Node info JSON for nodeclick/nodequery responses (1.3.0: full fields). */
    private fun nodeJson(n: NodeSelector.Candidate): JSONObject =
        JSONObject()
            .put("class", n.className)
            .put("bounds", org.json.JSONArray()
                .put(n.bounds.left).put(n.bounds.top)
                .put(n.bounds.right).put(n.bounds.bottom))
            .put("clickable", n.clickable)
            .put("depth", n.depth)
            .put("text", n.text ?: JSONObject.NULL)
            .put("contentDesc", n.contentDesc ?: JSONObject.NULL)
            .put("scrollable", n.scrollable)
            .put("checkable", n.checkable)
            .put("checked", n.checked)
            .put("editable", n.editable)
            .put("focused", n.focused)
            .put("resourceId", n.resourceId ?: JSONObject.NULL)

    /** Parse a selector object: text, contentDesc, class, id, boolean flags, bounds, contains. */
    private fun parseSelector(o: JSONObject): NodeSelector.Selector {
        fun optBool(key: String): Boolean? =
            if (o.has(key) && !o.isNull(key)) o.getBoolean(key) else null
        fun optStr(key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.getString(key) else null
        val bounds = if (o.has("bounds") && !o.isNull("bounds")) {
            val b = o.getJSONArray("bounds")
            NodeSelector.Bounds(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
        } else null
        val contains = if (o.has("contains") && !o.isNull("contains")) {
            val p = o.getJSONArray("contains")
            Pair(p.getInt(0), p.getInt(1))
        } else null
        return NodeSelector.Selector(
            text = optStr("text"),
            textExact = o.optBoolean("textExact", false),
            contentDesc = optStr("contentDesc"),
            contentDescExact = o.optBoolean("contentDescExact", false),
            className = optStr("class"),
            resourceId = optStr("id"),
            clickable = optBool("clickable"),
            scrollable = optBool("scrollable"),
            editable = optBool("editable"),
            checkable = optBool("checkable"),
            checked = optBool("checked"),
            focused = optBool("focused"),
            bounds = bounds,
            containsPoint = contains
        )
    }

    /** Selector + index/unique options shared by nodefind/nodetap. */
    private fun selectorArgs(o: JSONObject): Triple<NodeSelector.Selector, Int, Boolean> {
        val sel = parseSelector(o.getJSONObject("selector"))
        val index = o.optInt("index", 0)
        val unique = o.optBoolean("unique", false)
        return Triple(sel, index, unique)
    }

    /** Direction string: "backward" or anything else (forward). */
    private fun isBackward(o: JSONObject): Boolean =
        o.optString("direction", "forward").lowercase() == "backward"

    /** Response for a node action: ok + node + error string on failure. */
    private fun nodeActionResponse(
        node: NodeSelector.Candidate?,
        performed: Boolean,
        noNodeError: String
    ): HttpServer.Response {
        val o = JSONObject().put("ok", node != null && performed)
        if (node != null) o.put("node", nodeJson(node))
        if (node == null) o.put("error", noNodeError)
        else if (!performed) o.put("error", "node action not performed")
        return HttpServer.Response.json(o.toString())
    }

    private fun shot(req: HttpServer.Request): HttpServer.Response {
        val svc = RvgAccessibilityService.instance
            ?: return HttpServer.Response.jsonErr(503, "accessibility service not enabled (screenshots need it)")
        val png = svc.takeScreenshotPng()
            ?: return HttpServer.Response.jsonErr(503, "screenshot capture failed")
        return HttpServer.Response(200, png, "image/png")
    }

    private fun input(req: HttpServer.Request): HttpServer.Response {
        val svc = RvgAccessibilityService.instance
            ?: return HttpServer.Response.jsonErr(503, "accessibility service not enabled")
        val j = JSONObject(String(req.body, Charsets.UTF_8))
        // Node shapes return their own response (they carry node info,
        // not just ok) — handled before the plain ok-boolean shapes.
        if (j.has("nodeclick")) {
            val a = j.getJSONArray("nodeclick")
            val r = svc.clickNodeAt(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            val o = JSONObject().put("ok", r.found && r.performed)
            if (r.node != null) o.put("node", nodeJson(r.node))
            if (!r.found) o.put("error", "no node")
            else if (!r.performed) o.put("error", "node click not performed")
            return HttpServer.Response.json(o.toString())
        }
        if (j.has("nodequery")) {
            val a = j.getJSONArray("nodequery")
            val n = svc.queryNodeAt(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            val o = JSONObject().put("ok", true).put("found", n != null)
            if (n != null) o.put("node", nodeJson(n)) else o.put("node", JSONObject.NULL)
            return HttpServer.Response.json(o.toString())
        }
        // ----- 1.3.0 node shapes (selector language is a plain JSON
        // object — see parseSelector; never XPath) -----
        if (j.has("nodetree")) {
            val p = if (j.opt("nodetree") is JSONObject) j.getJSONObject("nodetree") else JSONObject()
            val maxNodes = p.optInt("maxNodes", 2000).coerceIn(1, 20000)
            val maxDepth = p.optInt("maxDepth", 50).coerceIn(1, 100)
            val interactiveOnly = p.optString("filter", "all") == "interactive"
            val dump = svc.dumpTree(maxNodes, maxDepth, interactiveOnly)
                ?: return HttpServer.Response.jsonErr(503, "no active window")
            val arr = org.json.JSONArray()
            for (i in dump.nodes.indices) {
                val n = dump.nodes[i]
                arr.put(nodeJson(n)
                    .put("childCount", dump.childCounts[i]))
            }
            val o = JSONObject()
                .put("ok", true)
                .put("nodes", arr)
                .put("nodeCount", dump.nodes.size)
                .put("totalVisited", dump.totalVisited)
                .put("truncated", dump.truncated)
            return HttpServer.Response.json(o.toString())
        }
        if (j.has("nodefind")) {
            val (sel, index, unique) = selectorArgs(j.getJSONObject("nodefind"))
            val found = svc.findBySelector(sel, index)
                ?: return HttpServer.Response.jsonErr(503, "no active window")
            val (node, count) = found
            if (unique && count != 1) {
                return HttpServer.Response.json(
                    JSONObject().put("ok", false).put("found", false)
                        .put("matchCount", count)
                        .put("error", "selector must match exactly one node (matched $count)")
                        .toString()
                )
            }
            val o = JSONObject().put("ok", true).put("found", node != null)
                .put("matchCount", count)
            if (node != null) o.put("node", nodeJson(node))
            else o.put("node", JSONObject.NULL)
            return HttpServer.Response.json(o.toString())
        }
        if (j.has("nodetap")) {
            val (sel, index, unique) = selectorArgs(j.getJSONObject("nodetap"))
            val pick = svc.clickBySelector(sel, index)
            if (unique && pick.matchCount != 1) {
                return HttpServer.Response.json(
                    JSONObject().put("ok", false)
                        .put("matchCount", pick.matchCount)
                        .put("error", "selector must match exactly one node (matched ${pick.matchCount})")
                        .toString()
                )
            }
            return nodeActionResponse(pick.node, pick.performed, "no node")
        }
        if (j.has("nodescroll")) {
            val v = j.get("nodescroll")
            val backward: Boolean
            val pick: RvgAccessibilityService.SelectorPick
            if (v is org.json.JSONArray) {
                val a = v
                backward = a.length() > 2 && a.getString(2).lowercase() == "backward"
                pick = svc.scrollNodeAtPoint(a.getInt(0), a.getInt(1), backward)
            } else {
                val o = j.getJSONObject("nodescroll")
                backward = isBackward(o)
                pick = svc.scrollNodeBySelector(parseSelector(o.getJSONObject("selector")), backward)
            }
            return nodeActionResponse(pick.node, pick.performed, "no scrollable node")
        }
        if (j.has("nodescrolluntil")) {
            val o = j.getJSONObject("nodescrolluntil")
            val sel = parseSelector(o.getJSONObject("selector"))
            val backward = isBackward(o)
            val maxAttempts = o.optInt("maxAttempts", 10).coerceIn(0, 50)
            val container = if (o.has("container") && !o.isNull("container")) {
                val c = o.getJSONArray("container")
                Pair(c.getInt(0), c.getInt(1))
            } else null
            val r = svc.scrollUntilVisible(sel, backward, maxAttempts, container)
            val out = JSONObject().put("ok", r.found).put("found", r.found)
                .put("scrolls", r.scrolls)
            if (r.node != null) out.put("node", nodeJson(r.node))
            else out.put("node", JSONObject.NULL)
            return HttpServer.Response.json(out.toString())
        }
        if (j.has("nodewait")) {
            val o = j.getJSONObject("nodewait")
            val sel = parseSelector(o.getJSONObject("selector"))
            val timeout = o.optLong("timeoutMs", 10000).coerceIn(0, 120000)
            val interval = o.optLong("intervalMs", 500).coerceIn(100, 10000)
            val r = svc.waitForElement(sel, timeout, interval)
            val out = JSONObject().put("ok", r.found).put("found", r.found)
                .put("attempts", r.attempts).put("elapsedMs", r.elapsedMs)
            if (r.node != null) out.put("node", nodeJson(r.node))
            else out.put("node", JSONObject.NULL)
            return HttpServer.Response.json(out.toString())
        }
        if (j.has("nodeshow")) {
            val v = j.get("nodeshow")
            val pick: RvgAccessibilityService.SelectorPick
            if (v is org.json.JSONArray) {
                val a = v
                pick = svc.showNodeAtPoint(a.getInt(0), a.getInt(1))
            } else {
                val o = j.getJSONObject("nodeshow")
                pick = svc.showNodeBySelector(parseSelector(o.getJSONObject("selector")))
            }
            return nodeActionResponse(pick.node, pick.performed, "no node")
        }
        if (j.has("nodesettext")) {
            val v = j.get("nodesettext")
            val pick: RvgAccessibilityService.SelectorPick
            var notEditable = false
            if (v is org.json.JSONArray) {
                val a = v
                pick = svc.setTextAtPoint(a.getInt(0), a.getInt(1), a.getString(2))
            } else {
                val o = j.getJSONObject("nodesettext")
                val sel = parseSelector(o.getJSONObject("selector"))
                pick = svc.setTextBySelector(sel, o.getString("text"))
                if (pick.node == null && pick.matchCount > 0) notEditable = true
            }
            if (notEditable) {
                return HttpServer.Response.json(
                    JSONObject().put("ok", false)
                        .put("error", "matched node not editable").toString()
                )
            }
            return nodeActionResponse(pick.node, pick.performed, "no editable node")
        }
        if (j.has("nodelongclick")) {
            val v = j.get("nodelongclick")
            val pick: RvgAccessibilityService.SelectorPick
            if (v is org.json.JSONArray) {
                val a = v
                pick = svc.longClickAtPoint(a.getInt(0), a.getInt(1))
            } else {
                val o = j.getJSONObject("nodelongclick")
                pick = svc.longClickBySelector(parseSelector(o.getJSONObject("selector")))
            }
            return nodeActionResponse(pick.node, pick.performed, "no node")
        }
        if (j.has("nodewindows")) {
            val arr = org.json.JSONArray()
            for (w in svc.windowsWalk()) {
                arr.put(JSONObject()
                    .put("id", w.id).put("type", w.type)
                    .put("focused", w.focused).put("active", w.active)
                    .put("bounds", org.json.JSONArray()
                        .put(w.bounds.left).put(w.bounds.top)
                        .put(w.bounds.right).put(w.bounds.bottom))
                    .put("rootClass", w.rootClass ?: JSONObject.NULL)
                    .put("nodeCount", w.nodeCount)
                    .put("truncated", w.truncated))
            }
            return HttpServer.Response.json(
                JSONObject().put("ok", true).put("windows", arr).toString()
            )
        }
        if (j.has("imeswitchback")) {
            // Teardown only: switch AWAY from the RVG keyboard to the
            // next IME (normally Gboard). Switching TO RVG still needs
            // the user — no system permission exists for a normal app
            // to select itself. False when RVG IME is not active.
            val ok = RvgInputMethodService.switchBack()
            return HttpServer.Response.json("{\"ok\":$ok}")
        }
        val ok = when {
            j.has("tap") -> {
                val a = j.getJSONArray("tap")
                // Optional 3rd element: stroke duration ms (default 50,
                // the v1.1.0 value). Longpress stays 800 ms, unchanged.
                val dur = if (a.length() > 2) a.getLong(2) else 50L
                svc.tap(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), dur)
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
            j.has("type") -> {
                // Fast path first (EditText), then the RVG keyboard IME so
                // terminals and other non-EditText views work transparently.
                val t = j.getString("type")
                svc.typeText(t) || RvgInputMethodService.typeText(t)
            }
            j.has("keytext") -> RvgInputMethodService.typeText(j.getString("keytext"))
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

    /**
     * Remote self-update check trigger (1.3.0).
     *
     * Runs the SAME service-side autoUpdate path the daily alarm runs
     * (UpdateCheckReceiver -> autoUpdate): check the releases API, and
     * when a newer release exists, download + verify + stage a
     * PackageInstaller session. This is NOT a metadata-only check: if
     * a genuine newer release exists, the full existing auto-update
     * flow runs, exactly as the daily alarm would run it. Call it only
     * when an update is actually wanted. Never faked: no release, no
     * install — the status fields simply record the check.
     */
    private fun update(req: HttpServer.Request): HttpServer.Response {
        val j = JSONObject(String(req.body, Charsets.UTF_8))
        if (!j.optBoolean("check", false)) {
            return HttpServer.Response.jsonErr(400, "unknown update action (want {\"check\":true})")
        }
        val force = j.optBoolean("force", false)
        UpdateManager.autoUpdate(ctx, force, userInitiated = false)
        return HttpServer.Response.json(
            JSONObject().put("ok", true).put("check", "started")
                .put("force", force).toString()
        )
    }

    /**
     * Manual rebind attempt (1.3.2). Runs on the HTTP worker thread (the
     * attempt sleeps/polls up to ~11 s — never call this from the main
     * thread). Token-auth applies like every other endpoint (see route()).
     */
    private fun rebind(): HttpServer.Response {
        val state = BindingState.from(ctx)
        val manager = RebindManager(
            AndroidSecureSettings(ctx), state,
            { RvgAccessibilityService.isEnabled() }
        )
        val result = manager.rebind()
        return HttpServer.Response.json(
            JSONObject()
                .put("ok", result.outcome == "REBOUND" || result.outcome == "ALREADY_BOUND")
                .put("outcome", result.outcome)
                .put("detail", result.detail)
                .put("attemptsInEpisode", result.attemptsInEpisode)
                .put("elapsedMs", result.elapsedMs)
                .toString()
        )
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

    /**
     * Viewer page with an in-page token gate (mirrors the Mac viewer).
     * The server never embeds the token in the HTML: the user types it
     * once, JS keeps it in a variable and sends it as the X-RVD-Token
     * header on every API call. Screenshots are fetched as blobs because
     * an <img> tag cannot send custom headers.
     */
    private fun view(req: HttpServer.Request): HttpServer.Response {
        val html = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>RVG Android viewer</title>
        <style>body{background:#111;color:#eee;font-family:sans-serif;text-align:center}
        img{max-width:100%;border:1px solid #444}button{margin:4px;padding:8px 14px}
        #gate{margin:32px auto;max-width:300px}#gate input{width:100%;padding:8px;margin:6px 0}
        #app{display:none}</style>
        </head><body>
        <h3>RVG Android — ${Build.MODEL}</h3>
        <div id="gate">
          <p>Enter the RVG token to view and control this device.</p>
          <input id="tok" type="password" placeholder="token" autocomplete="off">
          <br><button onclick="connect()">Connect</button>
        </div>
        <div id="app">
          <img id="shot" alt="screenshot"><br>
          <button onclick="tap(event)">tap: click image</button>
          <button onclick="key('back')">back</button>
          <button onclick="key('home')">home</button>
          <button onclick="key('recents')">recents</button>
          <button onclick="refresh()">refresh</button>
        </div>
        <script>
        let T = '';
        function H(){ return {"X-RVD-Token": T}; }
        function connect(){
          T = document.getElementById('tok').value.trim();
          document.getElementById('tok').value = '';
          if(!T) return;
          fetch('/rvd/status',{headers:H()}).then(r => {
            if(r.ok){
              document.getElementById('gate').style.display='none';
              document.getElementById('app').style.display='block';
              refresh();
              setInterval(refresh, 5000);
            } else {
              T = '';
              alert('bad token');
            }
          });
        }
        async function refresh(){
          if(!T) return;
          const r = await fetch('/rvd/shot',{headers:H()});
          if(r.status === 401){ location.reload(); return; }
          if(!r.ok) return;
          const b = await r.blob();
          const img = document.getElementById('shot');
          const old = img.src;
          img.src = URL.createObjectURL(b);
          if(old.startsWith('blob:')) URL.revokeObjectURL(old);
        }
        async function tap(e){
          const img = document.getElementById('shot');
          const r = img.getBoundingClientRect();
          const st = await (await fetch('/rvd/status',{headers:H()})).json();
          const x = Math.round((e.clientX - r.left) / r.width * st.screenW);
          const y = Math.round((e.clientY - r.top) / r.height * st.screenH);
          await fetch('/rvd/input',{method:'POST',headers:{...H(),'Content-Type':'application/json'},
            body: JSON.stringify({tap:[x,y]})});
          setTimeout(refresh, 400);
        }
        async function key(k){
          await fetch('/rvd/input',{method:'POST',headers:{...H(),'Content-Type':'application/json'},
            body: JSON.stringify({key:k})});
          setTimeout(refresh, 400);
        }
        document.getElementById('tok').addEventListener('keydown', e => {
          if(e.key === 'Enter') connect();
        });
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
