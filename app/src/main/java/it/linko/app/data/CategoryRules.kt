package it.linko.app.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** User-defined URL aliases take priority over generic page/slug suggestions. */
object CategoryRules {
    private val separators = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val percentRuns = Regex("(?:%[0-9a-fA-F]{2})+")
    /** Any alias may select a category; more matched aliases win, then the lowest ID. */
    fun suggest(url: String, categories: List<Category>, metadataText: String = ""): Category? {
        val decoded = decodeUrlText(url)
        // Keep sources separate so a phrase cannot start in the URL and end in metadata.
        val sources = listOf(" ${phrase(decoded)} ", " ${phrase(metadataText)} ")
        return categories.mapNotNull { category ->
            val matched = category.matchKeywords.map(::phrase).filter(String::isNotEmpty).distinct()
                .count { alias -> sources.any { it.contains(" $alias ") } }
            if (matched == 0) null else category to matched
        }.sortedWith(compareByDescending<Pair<Category, Int>> { it.second }.thenBy { it.first.id })
            .firstOrNull()?.first
    }

    /** One canonical representation for editor input, stored rules, and imported rules. */
    internal fun normalizeKeywords(keywords: List<String>): List<String> {
        require(keywords.size <= 100) { "Massimo 100 parole o frasi per categoria" }
        return keywords.map { keyword ->
            val clean = keyword.trim()
            require(clean.isNotEmpty() && clean.length <= 60 && clean.none(Char::isISOControl)) {
                "Ogni parola o frase deve contenere da 1 a 60 caratteri"
            }
            phrase(clean).also { normalized ->
                require(normalized.isNotEmpty() && normalized.length <= 60) { "Inserisci parole o frasi valide (massimo 60 caratteri)" }
            }
        }.distinct()
    }

    private fun phrase(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(separators, " ").trim()

    private fun decodeUrlText(value: String): String = percentRuns.replace(value.replace('+', ' ')) { match ->
        val bytes = ByteArray(match.value.length / 3) { index -> match.value.substring(index * 3 + 1, index * 3 + 3).toInt(16).toByte() }
        // A literal percent word such as "%female" is not valid UTF-8 escaping.
        // Retain it so '%' remains a word separator instead of swallowing "fe".
        runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrDefault(match.value)
    }
}
