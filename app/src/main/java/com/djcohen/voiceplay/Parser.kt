package com.djcohen.voiceplay

import android.view.KeyEvent

enum class Target(val label: String, val pkg: String) {
    METROLIST("Metrolist", "com.metrolist.music"), AIMP("AIMP", "com.aimp.player"), YMUSIC("YMusic", "io.ymusic.android");

    /** True when this player is installed (needs its package in the manifest <queries>). */
    fun isInstalled(ctx: android.content.Context) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    companion object {
        /** Players that are actually on this phone right now (checked every call, never cached). */
        fun installed(ctx: android.content.Context) = values().filter { it.isInstalled(ctx) }

        /** The saved default, or the first installed player if the saved one is gone; null if none are installed. */
        fun default(ctx: android.content.Context): Target? {
            val have = installed(ctx)
            val saved = ctx.getSharedPreferences("voiceplay", android.content.Context.MODE_PRIVATE).getString("default", null)
            return have.firstOrNull { it.name == saved } ?: have.firstOrNull()
        }
    }
}

sealed class Command {
    /** Start playing [query] in [target] (null = use the default player). */
    data class Play(val query: String, val target: Target?) : Command()
    /** A transport key: pause, resume, next, previous. */
    data class Key(val code: Int, val label: String) : Command()
}

/** Turns a spoken sentence like "play radiohead on metrolist" into a [Command]. */
object Parser {
    // Speech recognition often mishears short names, so accept the common variants.
    private const val AIMP = "(?:a\\.?i\\.?m\\.?p|aim p|amp|ay imp|a imp|aimp)"
    private const val METRO = "(?:metrolist|metro list|metro lyst|metrolyst)"
    private const val YM = "(?:y\\.? ?music|why music|wi music|ymusic)"
    private val onTarget = Regex("\\s+(?:on|in|with|using|from)\\s+(?:the\\s+)?($AIMP|$METRO|$YM)(?:\\s+app)?\\s*$")
    private val prefixTarget = Regex("^($AIMP|$METRO|$YM)\\s+")

    fun parse(raw: String): Command? {
        var t = raw.lowercase().trim().trimEnd('.', '!', '?')
        t = t.removePrefix("hey google").trim().removePrefix("ok google").trim().removePrefix("please").trim()
        if (t.isEmpty()) return null

        when {
            Regex("^(pause|stop|stop playing|pause music|stop music)$").matches(t) ->
                return Command.Key(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
            Regex("^(resume|continue|play|resume music|play music|unpause)$").matches(t) ->
                return Command.Key(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing")
            Regex("^(next|skip|next song|next track|skip song|skip this)$").matches(t) ->
                return Command.Key(KeyEvent.KEYCODE_MEDIA_NEXT, "Next")
            Regex("^(previous|previous song|previous track|last song|go back)$").matches(t) ->
                return Command.Key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous")
        }

        t = t.removePrefix("play ").trim()
        var target: Target? = null
        onTarget.find(t)?.let { target = toTarget(it.groupValues[1]); t = t.removeRange(it.range).trim() }
        if (target == null) prefixTarget.find(t)?.let { target = toTarget(it.groupValues[1]); t = t.removeRange(it.range).trim() }
        if (t.isEmpty()) return null
        // "play songs by radiohead" / "play by radiohead" / "play the song creep by radiohead": keep just the request.
        t = t.replace(Regex("^(?:(?:some|any|a|the)[ ]+)?(?:(?:songs?|music|tracks?|stuff|something|anything)[ ]+)?by[ ]+"), "")
            .replace(Regex("^(?:the[ ]+)?(?:song|track)[ ]+"), "").trim()
        if (t.isEmpty()) return null
        return Command.Play(t, target)
    }

    private fun toTarget(word: String) = when {
        Regex(METRO).matches(word) -> Target.METROLIST
        Regex(YM).matches(word) -> Target.YMUSIC
        else -> Target.AIMP
    }
}
