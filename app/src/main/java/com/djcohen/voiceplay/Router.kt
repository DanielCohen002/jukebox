package com.djcohen.voiceplay

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** Sends a parsed [Command] to AIMP or Metrolist using the same hooks Google Assistant uses for media apps. */
object Router {
    private const val TAG = "VoicePlay"
    private const val METROLIST_PKG = "com.metrolist.music"
    private const val METROLIST_SERVICE = "com.metrolist.music.playback.MusicService"
    private const val AIMP_PKG = "com.aimp.player"
    private const val AIMP_SERVICE = "com.aimp.player.service.AppService"
    private const val AIMP_ACTIVITY = "com.aimp.player.ui.activities.main.MainActivity"
    private val main = Handler(Looper.getMainLooper())
    private val onMain = java.util.concurrent.Executor { main.post(it) }

    /** [info] reports what is happening; [done] runs once it is safe to close the screen. */
    fun run(ctx: Context, cmd: Command, defaultTarget: Target, info: (String) -> Unit, done: () -> Unit) {
        when (cmd) {
            is Command.Key -> { sendKey(ctx, cmd.code); info(cmd.label); done() }
            is Command.Play -> {
                val target = cmd.target ?: defaultTarget
                info("Playing “${cmd.query}” on ${target.label}…")
                when (target) {
                    Target.AIMP -> playInAimp(ctx.applicationContext, cmd.query, done)
                    Target.METROLIST -> playInMetrolist(ctx.applicationContext, cmd.query, info, done)
                }
            }
        }
    }

    private fun sendKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /**
     * AIMP: connect to its media service and send "play from search" (works in the background and on the lock screen).
     * If that doesn't start playback, fall back to the standard intent, which needs the screen unlocked.
     */
    private fun playInAimp(app: Context, query: String, done: () -> Unit) {
        var browser: android.media.browse.MediaBrowser? = null
        fun fallback() {
            runCatching {
                app.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .setComponent(ComponentName(AIMP_PKG, AIMP_ACTIVITY))
                    .putExtra(SearchManager.QUERY, query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "AIMP intent fallback failed", it) }
            browser?.disconnect(); done()
        }
        browser = android.media.browse.MediaBrowser(app, ComponentName(AIMP_PKG, AIMP_SERVICE),
            object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val c = android.media.session.MediaController(app, browser!!.sessionToken)
                    c.transportControls.playFromSearch(query, Bundle())
                    main.postDelayed({
                        val playing = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        Log.i(TAG, "AIMP via session: playing=$playing")
                        if (playing) { browser?.disconnect(); done() } else fallback()
                    }, 2500)
                }
                override fun onConnectionFailed() { Log.w(TAG, "AIMP session connect failed"); fallback() }
            }, null)
        browser.connect()
    }

    /**
     * Metrolist: ask its media service for search results (library + YouTube Music), pick the best one ourselves
     * (title and artist), and play it. If the search comes back empty, fall back to Metrolist's own voice matching.
     */
    private fun playInMetrolist(app: Context, query: String, info: (String) -> Unit, done: () -> Unit) {
        val token = SessionToken(app, ComponentName(METROLIST_PKG, METROLIST_SERVICE))
        val future = MediaBrowser.Builder(app, token).buildAsync()
        fun finish(b: MediaBrowser) { main.postDelayed({ b.release(); done() }, 3000) }
        future.addListener({
            val browser = runCatching { future.get() }.onFailure { Log.e(TAG, "browser connect failed", it) }.getOrNull()
            if (browser == null) { info("Couldn’t reach Metrolist"); done(); return@addListener }
            val search = browser.search(query, null)
            search.addListener({
                val result = browser.getSearchResult(query, 0, 40, null)
                result.addListener({
                    val items = runCatching { result.get().value?.toList() }.getOrNull().orEmpty()
                    val ranked = Matcher.rank(query, items)
                    val best = ranked.firstOrNull()
                    Log.i(TAG, "query=\"$query\" results=${items.size} top=" +
                        ranked.take(3).joinToString { "${it.item.mediaMetadata.title} - ${it.item.mediaMetadata.artist} (${"%.2f".format(it.score)})" })
                    if (best == null || best.score < 0.5) {
                        // Nothing convincing: let Metrolist try its own voice matching.
                        playByVoiceQuery(browser, query); info("Playing “$query”"); finish(browser)
                    } else {
                        // An artist-only request queues several of that artist's songs; otherwise just the best match.
                        val queue = if (best.artistOnly) ranked.filter { it.artistOnly && it.score >= 0.5 }.take(10).map { it.item } else listOf(best.item)
                        browser.setMediaItems(queue)
                        browser.prepare(); browser.play()
                        info("Playing ${best.item.mediaMetadata.title} — ${best.item.mediaMetadata.artist}")
                        finish(browser)
                    }
                }, onMain)
            }, onMain)
        }, onMain)
    }

    private fun playByVoiceQuery(c: MediaController, query: String) {
        c.setMediaItem(MediaItem.Builder().setMediaId("voice-search")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build())
        c.prepare(); c.play()
    }
}
