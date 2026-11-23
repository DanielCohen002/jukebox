package com.djcohen.voiceplay

import android.app.NotificationManager
import android.app.SearchManager
import android.provider.Settings
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
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

/** Sends a parsed [Command] to AIMP or Metrolist using the same hooks Google Assistant uses for media apps. */
object Router {
    private const val TAG = "VoicePlay"
    private const val METROLIST_SERVICE = "com.metrolist.music.playback.MusicService"   // old name; looked up at runtime now
    private const val AIMP_PKG = "com.aimp.player"
    private const val AIMP_SERVICE = "com.aimp.player.service.AppService"
    private const val AIMP_ACTIVITY = "com.aimp.player.ui.activities.main.MainActivity"
    private val main = Handler(Looper.getMainLooper())
    private val onMain = java.util.concurrent.Executor { main.post(it) }

    /** [info] reports what is happening; [done] runs once it is safe to close the screen. */
    fun run(ctx: Context, cmd: Command, defaultTarget: Target?, info: (String) -> Unit, done: () -> Unit) {
        when (cmd) {
            is Command.Key -> { sendKey(ctx, cmd.code); info(cmd.label); done() }
            is Command.Play -> {
                val target = cmd.target ?: defaultTarget
                if (target == null) { info("No music app installed"); done(); return }
                if (!target.isInstalled(ctx)) { info("Couldn’t find ${target.label} (is it installed?)"); done(); return }
                if (target != Target.AIMP && !hasTrust(ctx)) {
                    // Metrolist 14+ ignores searches from apps without notification access ("trusted for media control").
                    info("No notification access: turn on Jukebox")
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                ComponentName(ctx, TrustListener::class.java).flattenToString())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Log.w(TAG, "couldn't open notification access settings", it) }
                    done(); return
                }
                info("Playing “${cmd.query}” on ${target.label}…")
                when (target) {
                    Target.AIMP -> playInAimp(ctx.applicationContext, cmd.query, info, done)
                    Target.METROLIST, Target.YMUSIC -> playInMediaLibrary(ctx.applicationContext, target, cmd.query, info, done)
                }
            }
        }
    }

    private fun hasTrust(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(ctx, TrustListener::class.java))

    private fun sendKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /**
     * AIMP: connect to its media service and send "play from search" (works in the background and on the lock screen).
     * If that doesn't start playback, fall back to the standard intent, which needs the screen unlocked.
     */
    private fun playInAimp(app: Context, query: String, info: (String) -> Unit, done: () -> Unit) {
        AimpLibrary.play(app, query, info) { handled -> if (handled) done() else playInAimpBySearch(app, query.replace(Regex("[ ]+by[ ]+"), " "), info, done) }
    }

    private fun playInAimpBySearch(app: Context, query: String, info: (String) -> Unit, done: () -> Unit) {
        var browser: android.media.browse.MediaBrowser? = null
        fun fallback() {
            runCatching {
                app.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .setComponent(ComponentName(AIMP_PKG, AIMP_ACTIVITY))
                    .putExtra(SearchManager.QUERY, query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "AIMP intent fallback failed", it) }
            // AIMP's own search either starts something or does nothing; check, and say so if it found nothing.
            main.postDelayed({
                val playing = runCatching {
                    android.media.session.MediaController(app, browser!!.sessionToken).playbackState?.state ==
                        android.media.session.PlaybackState.STATE_PLAYING
                }.getOrDefault(false)
                if (playing) AimpLibrary.watch(app, browser)
                else { browser?.disconnect(); info("Couldn’t find “$query” in AIMP") }
                done()
            }, 3000)
        }
        browser = android.media.browse.MediaBrowser(app, ComponentName(AIMP_PKG, AIMP_SERVICE),
            object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val c = android.media.session.MediaController(app, browser!!.sessionToken)
                    c.transportControls.playFromSearch(query, Bundle())
                    main.postDelayed({
                        val playing = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        Log.i(TAG, "AIMP via session: playing=$playing")
                        // AIMP plays *something* for any search (even nonsense), so check the track actually fits the request.
                        val md = c.metadata
                        val got = listOfNotNull(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                            md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                            md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)).joinToString(" ").lowercase()
                        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it != "the" }
                        val fits = words.isEmpty() || words.any { got.contains(it) }
                        if (playing && fits) { AimpLibrary.watch(app, browser); done() }
                        else if (playing) {
                            c.transportControls.pause(); browser?.disconnect()
                            info("Couldn’t find “$query” in AIMP"); done()
                        } else fallback()
                    }, 2500)
                }
                override fun onConnectionFailed() { Log.w(TAG, "AIMP session connect failed"); fallback() }
            }, null)
        browser.connect()
    }

    /**
     * Metrolist and YMusic both expose a standard media3 library service: ask it for search results, pick the best one
     * ourselves (title and artist), and play it. If the search comes back empty, fall back to the app's own voice matching.
     */
    private fun playInMediaLibrary(app: Context, target: Target, query: String, info: (String) -> Unit, done: () -> Unit) {
        val name = target.label
        // These apps have renamed their service between versions, so ask the system which one they have now.
        val component = app.packageManager.queryIntentServices(
            Intent("androidx.media3.session.MediaLibraryService").setPackage(target.pkg), 0
        ).firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        if (component == null) { info("Couldn’t find $name (is it installed?)"); done(); return }
        val token = runCatching { SessionToken(app, component) }.onFailure { Log.e(TAG, "Metrolist token", it) }.getOrNull()
        if (token == null) { info("Couldn’t reach $name"); done(); return }
        val future = MediaBrowser.Builder(app, token).buildAsync()
        fun finish(b: MediaBrowser) { main.postDelayed({ b.release(); done() }, 3000) }
        future.addListener({
            val browser = runCatching { future.get() }.onFailure { Log.e(TAG, "browser connect failed", it) }.getOrNull()
            if (browser == null) { info("Couldn’t reach $name"); done(); return@addListener }
            val search = browser.search(query, null)
            search.addListener({
                val result = browser.getSearchResult(query, 0, 40, null)
                result.addListener({
                    val lr = runCatching { result.get() }.onFailure { Log.e(TAG, "search result failed", it) }.getOrNull()
                    Log.i(TAG, "search code=${lr?.resultCode} (0=ok)")
                    val items = lr?.value?.toList().orEmpty()
                    val ranked = Matcher.rank(query, items)
                    val best = ranked.firstOrNull()
                    Log.i(TAG, "query=\"$query\" results=${items.size} top=" +
                        ranked.take(3).joinToString { "${it.item.mediaMetadata.title} - ${it.item.mediaMetadata.artist} (${"%.2f".format(it.score)})" })
                    if (best == null || best.score < 0.5) {
                        // Nothing convincing: let the app try its own voice matching.
                        playByVoiceQuery(browser, query); info("Playing “$query”"); finish(browser)
                    } else {
                        val radio = target == Target.METROLIST
                        // Metrolist: play the chosen song (or, for "play <artist>", three of that artist's songs), then let its own
                        // "Start radio" build the queue of similar songs. Other apps have no such command, so an artist request queues ten.
                        val artistSongs = if (best.artistOnly) ranked.filter { it.artistOnly && it.score >= 0.5 }.map { it.item } else emptyList()
                        val queue = when {
                            !best.artistOnly -> listOf(best.item)
                            radio -> artistSongs.take(3)
                            else -> artistSongs.take(10)
                        }
                        browser.setMediaItems(queue)
                        browser.prepare(); browser.play()
                        info("Playing ${best.item.mediaMetadata.title} — ${best.item.mediaMetadata.artist}")
                        if (radio && queue.size > 1) {
                            // Radio rebuilds the queue from the current song, so wait until the last of the three starts.
                            var sent = false
                            fun startRadio() {
                                if (sent) return; sent = true
                                runCatching { browser.sendCustomCommand(SessionCommand("TOGGLE_START_RADIO", Bundle.EMPTY), Bundle.EMPTY) }
                                    .onFailure { Log.w(TAG, "start radio failed", it) }
                                main.postDelayed({ browser.release() }, 3000)
                            }
                            browser.addListener(object : Player.Listener {
                                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                                    if (browser.currentMediaItemIndex >= queue.size - 1) startRadio()
                                }
                            })
                            main.postDelayed({ if (!sent) { sent = true; browser.release() } }, 30 * 60_000L)   // give up if never reached
                            main.postDelayed({ done() }, 3000)
                            return@addListener
                        }
                        if (radio) main.postDelayed({
                            runCatching { browser.sendCustomCommand(SessionCommand("TOGGLE_START_RADIO", Bundle.EMPTY), Bundle.EMPTY) }
                                .onFailure { Log.w(TAG, "start radio failed", it) }
                        }, 1500)
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
