package no.meltveit.cuecomm.core

import java.text.Normalizer
import java.util.regex.Pattern

object Text {
    /** "go, standby" -> ["go", "standby"] */
    fun split(phrases: String): List<String> =
        phrases.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** Whole-word matching ignores case; loose matching also ignores accents. */
    fun matches(text: String, phrase: String, wholeWord: Boolean): Boolean {
        val p = phrase.trim()
        if (p.isEmpty()) return false
        if (!wholeWord) return fold(text).contains(fold(p))
        val pattern = "(?<![\\p{L}\\p{N}])" + Pattern.quote(p) + "(?![\\p{L}\\p{N}])"
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(text).find()
    }

    /** Character ranges of every case-insensitive occurrence of any term, for highlighting. */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (t in terms) {
            if (t.isEmpty()) continue
            var from = 0
            while (true) {
                val i = text.indexOf(t, from, ignoreCase = true)
                if (i < 0) break
                out += i until (i + t.length)
                from = i + t.length
            }
        }
        return out
    }

    fun slug(word: String): String =
        word.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("-")
}
