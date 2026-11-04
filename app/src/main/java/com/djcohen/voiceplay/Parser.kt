package com.djcohen.voiceplay

import android.view.KeyEvent

enum class Target(val label: String) { METROLIST("Metrolist"), AIMP("AIMP") }

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
    private val onTarget = Regex("\\s+(?:on|in|with|using|from)\\s+(?:the\\s+)?($AIMP|$METRO)(?:\\s+app)?\\s*$")
    private val prefixTarget = Regex("^($AIMP|$METRO)\\s+")

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
        return Command.Play(t, target)
    }

    private fun toTarget(word: String) =
        if (Regex(METRO).matches(word)) Target.METROLIST else Target.AIMP
}
