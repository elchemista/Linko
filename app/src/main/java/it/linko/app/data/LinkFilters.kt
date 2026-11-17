package it.linko.app.data

import java.net.URI
import java.util.Locale

/** All keywords, any selected quality, any selected domain; groups combine with AND. */
data class LinkFilters(
    val query: String = "",
    val categoryId: Long? = null,
    val keys: Set<String> = emptySet(),
    val qualities: Set<String> = emptySet(),
    val domains: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false
)

/** Shared by the Android library and its PC web view; contains no Android dependencies. */
object LinkMatcher {
    fun matches(link: VideoLink, filters: LinkFilters, categoryName: String = ""): Boolean {
        if (filters.categoryId != null && link.categoryId != filters.categoryId) return false
        if (filters.favoritesOnly && !link.favorite) return false

        val requiredKeys = filters.keys.map(::normalizeKey).filter(String::isNotEmpty).toSet()
        val linkKeys = link.keys.map(::normalizeKey).toSet()
        if (!linkKeys.containsAll(requiredKeys)) return false

        val qualities = filters.qualities.map { it.trim() }.filter(String::isNotEmpty)
        if (qualities.isNotEmpty() && qualities.none { it.equals(link.quality?.trim(), ignoreCase = true) }) return false

        val domains = filters.domains.map { it.trim().lowercase(Locale.ROOT).trimEnd('.') }.filter(String::isNotEmpty)
        if (domains.isNotEmpty()) {
            val host = domainOf(link.url) ?: return false
            if (domains.none { domain ->
                if (domain.startsWith('.')) domain.length >= 2 && host.endsWith(domain)
                else host == domain.removePrefix("www.")
            }) return false
        }

        val query = filters.query.trim()
        if (query.isNotEmpty()) {
            val searchable = listOf(link.title, link.url, link.description, categoryName) + link.keys
            if (searchable.none { it.contains(query, ignoreCase = true) }) return false
        }
        return true
    }

    /** Source domains ignore a leading www, case, ports, and a terminal DNS dot. */
    fun domainOf(url: String): String? = runCatching {
        val uri = URI(url.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.")?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun normalizeKey(key: String): String = SmartCollections.normalizeKey(key)
}
