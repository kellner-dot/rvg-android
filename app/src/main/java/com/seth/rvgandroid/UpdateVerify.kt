package com.seth.rvgandroid

/**
 * Pure self-update verification logic — no Android types — so it is
 * unit-testable on the JVM without a device.
 *
 * Three gates, all must pass before any install is attempted:
 *  1. Source: the APK URL's host must be a Gitea release host. The
 *     release lookup itself is pinned to the Gitea releases API for
 *     kellner-dot/rvg-android (tailnet host), but release ASSETS are
 *     served from the tailnet host itself — api.github.com is NOT an
 *     allowed asset host and never was.
 *  2. Signer: the downloaded APK's signing certificates must equal
 *     the installed app's, as sets (order-insensitive). This is what
 *     makes the update an UPDATE: Android refuses to install over a
 *     differently-signed package, and we refuse before even trying.
 *  3. Upgrade: the downloaded versionCode must be strictly greater
 *     than the installed one (downgrade protection).
 */
object UpdateVerify {

    private val ALLOWED_ASSET_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "kavivpn.tail1cffd9.ts.net"
    )

    fun allowedAssetHost(host: String): Boolean =
        host.lowercase() in ALLOWED_ASSET_HOSTS

    fun signaturesMatch(
        installed: List<ByteArray>,
        downloaded: List<ByteArray>
    ): Boolean {
        if (installed.isEmpty() || downloaded.isEmpty()) return false
        val a = installed.map { it.toHex() }.toSet()
        val b = downloaded.map { it.toHex() }.toSet()
        return a == b
    }

    fun isUpgrade(downloadedCode: Long, installedCode: Long): Boolean =
        downloadedCode > installedCode

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }
}
