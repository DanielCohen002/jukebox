package com.djcohen.voiceplay

import androidx.media3.common.MediaItem

/**
 * Ranks search results against what was said. Unlike Metrolist's built-in voice matcher this looks at the artist too,
 * understands "title by artist", and can match an artist-only request ("play radiohead").
 */
object Matcher {
    data class Ranked(val item: MediaItem, val score: Double, val artistOnly: Boolean)

    private fun tokens(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.isNotBlank() }

    private fun same(a: String, b: String) =
        a == b || (a.length >= 5 && b.length >= 5 && editDistance(a, b) <= 1)

    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** Harmonic mean of "how much of the query is in the text" and "how much of the text is in the query". */
    private fun coverage(q: List<String>, t: List<String>): Double {
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val hit = q.count { qt -> t.any { same(qt, it) } }.toDouble()
        val tHit = t.count { tt -> q.any { same(tt, it) } }.toDouble()
        val p = hit / q.size
        val r = tHit / t.size
        return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
    }

    fun rank(query: String, items: List<MediaItem>): List<Ranked> {
        val by = Regex("^(.+?)\\s+by\\s+(.+)$").find(query.lowercase().trim())
        val all = tokens(query)
        return items.map { item ->
            val title = tokens(item.mediaMetadata.title?.toString().orEmpty())
            val artist = tokens(item.mediaMetadata.artist?.toString().orEmpty())
            var artistOnly = false
            val score: Double
            if (by != null) {
                // "bohemian rhapsody by queen": title and artist each count.
                score = 0.7 * coverage(tokens(by.groupValues[1]), title) + 0.3 * coverage(tokens(by.groupValues[2]), artist)
            } else {
                val artistMatched = all.filter { qt -> artist.any { same(qt, it) } }
                val remaining = all - artistMatched.toSet()
                val asTitle = coverage(all, title)
                val withArtist = when {
                    artistMatched.isEmpty() -> 0.0
                    remaining.isEmpty() -> { artistOnly = true; 0.8 }   // "play radiohead"
                    else -> 0.4 + 0.6 * coverage(remaining, title)       // "creep radiohead"
                }
                score = maxOf(asTitle, withArtist)
                if (asTitle >= withArtist) artistOnly = false
            }
            Ranked(item, score, artistOnly)
        }.sortedByDescending { it.score }
    }
}
