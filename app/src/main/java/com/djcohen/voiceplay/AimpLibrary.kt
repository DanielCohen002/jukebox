package com.djcohen.voiceplay

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Looks things up in AIMP's own library through its browse tree: a spoken playlist name plays that playlist, a spoken
 * artist plays one random song by them (AIMP then carries on in whatever mode it is set to: shuffle, straight, ...).
 */
object AimpLibrary {
    private const val TAG = "VoicePlay"
    private const val PKG = "com.aimp.player"
    private const val SERVICE = "com.aimp.player.service.AppService"
    private const val ARTISTS = "folder/musicdb://local/v+2+Artists"
    private val main = Handler(Looper.getMainLooper())
    private val notWord = Regex("[^\\p{L}\\p{N} ]")
    private val playlistWord = Regex("^(?:the\\s+)?(.+?)\\s+playlist$|^playlist\\s+(.+)$")

    private fun norm(s: String) = s.lowercase().replace(notWord, " ")
        .split(" ").filter { it.isNotBlank() && it != "the" }.joinToString(" ")

    private fun matches(query: String, name: String): Boolean {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return false
        if (q == n) return true
        val a = q.replace(" ", ""); val b = n.replace(" ", "")
        return a == b || (a.length >= 5 && b.length >= 5 && edit(a, b) <= 1)
    }

    private fun edit(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** [result] gets true if a playlist or artist matched and was started, false to let the caller fall back. */
    fun play(app: Context, rawQuery: String, info: (String) -> Unit, result: (Boolean) -> Unit) {
        // "play the chill playlist" / "play playlist chill" insist on a playlist.
        val pl = playlistWord.find(rawQuery.trim().lowercase())
        val playlistOnly = pl != null
        val query = pl?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: rawQuery

        var browser: MediaBrowser? = null
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return; finished = true
            main.postDelayed({ browser?.disconnect() }, 1500); result(ok)
        }
        fun load(id: String, cb: (List<MediaBrowser.MediaItem>) -> Unit) {
            browser!!.subscribe(id, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                    browser?.unsubscribe(id); cb(children)
                }
                override fun onError(parentId: String) { browser?.unsubscribe(id); cb(emptyList()) }
            })
        }
        fun controller() = MediaController(app, browser!!.sessionToken)
        fun isTrack(it: MediaBrowser.MediaItem) = !it.isBrowsable && it.mediaId?.startsWith("folder.r/") != true &&
            it.description.title.toString() != "Play"
        /** Gathers every track below [id] (artist -> album/genre -> tracks), then hands them over. */
        fun collect(id: String, depth: Int, out: MutableList<MediaBrowser.MediaItem>, done: () -> Unit) {
            load(id) { items ->
                out += items.filter { isTrack(it) }
                val sub = if (depth < 3) items.filter { it.isBrowsable } else emptyList()
                var left = sub.size
                if (left == 0) { done(); return@load }
                sub.forEach { collect(it.mediaId!!, depth + 1, out) { if (--left == 0) done() } }
            }
        }
        fun fromArtists() {
            load(ARTISTS) { artists ->
                val artist = artists.firstOrNull { it.isBrowsable && matches(query, it.description.title.toString()) }
                if (artist == null) { finish(false); return@load }
                val tracks = mutableListOf<MediaBrowser.MediaItem>()
                collect(artist.mediaId!!, 0, tracks) {
                    val pick = tracks.randomOrNull()
                    Log.i(TAG, "AIMP artist=${artist.description.title} tracks=${tracks.size} pick=${pick?.description?.title}")
                    if (pick == null) { finish(false); return@collect }
                    controller().transportControls.playFromMediaId(pick.mediaId!!, null)
                    info("Playing ${pick.description.title} — ${artist.description.title}")
                    finish(true)
                }
            }
        }
        browser = MediaBrowser(app, ComponentName(PKG, SERVICE), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                load(browser!!.root) { root ->
                    val playlist = root.firstOrNull { it.isBrowsable && it.mediaId?.startsWith("playlist/") == true &&
                        matches(query, it.description.title.toString()) }
                    if (playlist != null) {
                        Log.i(TAG, "AIMP playlist=${playlist.description.title}")
                        controller().transportControls.playFromMediaId(playlist.mediaId!!, null)
                        info("Playing playlist ${playlist.description.title}")
                        finish(true)
                    } else if (playlistOnly) { info("No playlist “$query”"); finish(true) }
                    else fromArtists()
                }
            }
            override fun onConnectionFailed() { finish(false) }
        }, null)
        browser.connect()
    }
}
