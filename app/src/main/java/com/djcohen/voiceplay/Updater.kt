package com.djcohen.voiceplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Looks for a newer Jukebox release on GitHub when asked, and opens its download page. Never downloads or installs anything itself. */
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
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                ?: j.getString("html_url")
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
            .setPositiveButton("Download") { _, _ ->
                try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl))) } catch (_: Exception) {}
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
