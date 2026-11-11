package com.djcohen.voiceplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Looks for a newer Jukebox release on GitHub, downloads it and hands it to the installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/DanielCohen002/jukebox/releases/latest"
    private const val TIMEOUT = 15_000

    private class Release(val version: String, val notes: String, val apkUrl: String)

    fun currentVersion(a: Activity): String =
        a.packageManager.getPackageInfo(a.packageName, 0).versionName.orEmpty()

    fun check(a: Activity) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = runCatching { fetch() }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                r.onSuccess { show(a, it) }
                    .onFailure { Toast.makeText(a, "Couldn’t check for updates", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun fetch(): Release {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Jukebox")
            check(c.responseCode == 200) { "GitHub answered ${c.responseCode}" }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("url")
                ?: error("release has no apk")
            return Release(j.getString("tag_name").removePrefix("v"), j.optString("body"), apk)
        } finally { c.disconnect() }
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val d = l.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun show(a: Activity, r: Release) {
        val cur = currentVersion(a)
        if (!isNewer(r.version, cur)) {
            Toast.makeText(a, "Jukebox $cur is up to date", Toast.LENGTH_LONG).show(); return
        }
        AlertDialog.Builder(a)
            .setTitle("Jukebox ${r.version} available")
            .setMessage(r.notes.ifBlank { "No release notes." })
            .setPositiveButton("Download & install") { _, _ -> download(a, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(a: Activity, r: Release) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            // come back and tap the button again once this is allowed
            Toast.makeText(a, "Allow installs from Jukebox, then check again", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        Toast.makeText(a, "Downloading ${r.version}…", Toast.LENGTH_LONG).show()
        Thread {
            val apk = runCatching { downloadApk(a, r) }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                apk.onSuccess {
                    val uri = FileProvider.getUriForFile(a, "${a.packageName}.updates", it)
                    a.startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { Toast.makeText(a, "Download failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun downloadApk(a: Activity, r: Release): File {
        val dir = File(a.getExternalFilesDir(null), "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "jukebox-${r.version}.apk")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT
            c.setRequestProperty("User-Agent", "Jukebox")
            c.setRequestProperty("Accept", "application/octet-stream")
            check(c.responseCode == 200) { "download answered ${c.responseCode}" }
            val total = c.contentLengthLong
            var got = 0L
            c.inputStream.use { i -> target.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); got += n }
            } }
            check(total <= 0 || got == total) { "download cut short" }
        } finally { c.disconnect() }
        return target
    }
}
