package com.seth.rvgandroid

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server built on java.net — zero third-party dependencies.
 * Supports: GET/POST, query strings, headers, JSON bodies, multipart file
 * upload (single file part), binary download responses.
 */
class HttpServer(private val port: Int) {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val contentType: String?
    )

    data class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/json",
        val extraHeaders: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun json(s: String) = Response(200, s.toByteArray(Charsets.UTF_8))
            fun jsonErr(code: Int, msg: String) =
                Response(code, "{\"ok\":false,\"error\":${quote(msg)}}".toByteArray(Charsets.UTF_8))

            fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    var handler: ((Request) -> Response)? = null

    fun start() {
        server = ServerSocket(port)
        running = true
        thread(name = "http-accept", isDaemon = true) {
            while (running) {
                try {
                    val sock = server!!.accept()
                    pool.execute { handle(sock) }
                } catch (e: IOException) {
                    if (running) e.printStackTrace()
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))

                // Skip leading blank lines (some clients/proxies send them); a
                // null read means the client went away — nothing to respond to.
                var requestLine: String? = null
                for (i in 0 until 5) {
                    val line = reader.readLine() ?: return
                    if (line.isNotEmpty()) { requestLine = line; break }
                }
                val rl = requestLine ?: return
                val parts = rl.split(" ")
                if (parts.size < 2) {
                    // Malformed request: answer 400 instead of closing silently,
                    // so the client gets a diagnosable response, not an empty reply.
                    writeResponse(output, Response.jsonErr(400, "malformed request line"))
                    return
                }
                val method = parts[0].uppercase()
                val target = extractPath(parts[1])

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                // Read body as raw bytes: BufferedReader already buffered some bytes,
                // so read remaining via the underlying stream carefully.
                val body = readBody(reader, input, contentLength)

                val qIdx = target.indexOf('?')
                val path = if (qIdx >= 0) target.substring(0, qIdx) else target
                val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()

                val req = Request(method, path, query, headers, body, headers["content-type"])
                val resp = try {
                    handler?.invoke(req) ?: Response.jsonErr(404, "no handler")
                } catch (e: Exception) {
                    Response.jsonErr(500, e.message ?: "internal error")
                }
                writeResponse(output, resp)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Extract the origin-form path from a request target. Proxies send the
     * absolute URI (GET http://host:port/path HTTP/1.1); without this the
     * path never matches a route and every proxied request 404s.
     */
    private fun extractPath(target: String): String {
        var t = target
        val schemeIdx = t.indexOf("://")
        if (schemeIdx >= 0) {
            val slashIdx = t.indexOf('/', schemeIdx + 3)
            t = if (slashIdx >= 0) t.substring(slashIdx) else "/"
        }
        return t
    }

    /** Read exactly contentLength bytes, accounting for BufferedReader buffering. */
    private fun readBody(reader: BufferedReader, raw: InputStream, contentLength: Int): ByteArray {
        if (contentLength <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream()
        // Drain any chars already buffered in the reader first.
        val cbuf = CharArray(8192)
        var remaining = contentLength
        // BufferedReader works in chars; bodies here are bytes. For JSON bodies
        // (ASCII/UTF-8 text) char reads are fine. For multipart binary, the
        // reader may have buffered raw bytes as ISO-8859-1 chars (1:1 mapping).
        while (remaining > 0 && reader.ready()) {
            val n = reader.read(cbuf, 0, minOf(cbuf.size, remaining))
            if (n <= 0) break
            val s = String(cbuf, 0, n)
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            out.write(bytes, 0, minOf(bytes.size, remaining))
            remaining -= minOf(bytes.size, remaining)
            if (bytes.size < n) break
        }
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = raw.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun writeResponse(out: OutputStream, resp: Response) {
        val statusText = when (resp.status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
            else -> "OK"
        }
        val w = PrintWriter(OutputStreamWriter(out, Charsets.ISO_8859_1))
        w.print("HTTP/1.1 ${resp.status} $statusText\r\n")
        w.print("Content-Type: ${resp.contentType}\r\n")
        w.print("Content-Length: ${resp.body.size}\r\n")
        for ((k, v) in resp.extraHeaders) w.print("$k: $v\r\n")
        w.print("Connection: close\r\n\r\n")
        w.flush()
        out.write(resp.body)
        out.flush()
    }

    /** Extract a single file part from multipart/form-data. Returns (filename, bytes). */
    fun parseMultipart(req: Request): Pair<String, ByteArray>? {
        val ct = req.contentType ?: return null
        if (!ct.startsWith("multipart/form-data")) return null
        val boundary = ct.substringAfter("boundary=", "").trim()
        if (boundary.isEmpty()) return null
        val bodyStr = String(req.body, Charsets.ISO_8859_1)
        val delim = "--$boundary"
        val sections = bodyStr.split(delim)
        for (sec in sections) {
            if (!sec.contains("filename=")) continue
            val nameMatch = Regex("filename=\"([^\"]*)\"").find(sec) ?: continue
            val filename = nameMatch.groupValues[1].substringAfterLast('/').substringAfterLast('\\')
            val headerEnd = sec.indexOf("\r\n\r\n")
            if (headerEnd < 0) continue
            var data = sec.substring(headerEnd + 4)
            if (data.endsWith("\r\n")) data = data.dropLast(2)
            if (data.endsWith("--")) data = data.dropLast(2)
            return filename.ifEmpty { "upload.bin" } to data.toByteArray(Charsets.ISO_8859_1)
        }
        return null
    }
}
