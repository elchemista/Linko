package it.linko.app.data

import java.text.Normalizer
import java.util.Locale

/** A live subgroup of its parent category, derived from distinct saved videos sharing a key. */
data class SmartCollection(val categoryId: Long, val key: String, val count: Int)

/** No category or link is moved or written when these collections are calculated. */
object SmartCollections {
    const val DEFAULT_THRESHOLD = 3
    const val MIN_THRESHOLD = 2
    const val MAX_THRESHOLD = 20
    private val whitespace = Regex("\\s+")

    fun build(library: LibraryState): List<SmartCollection> {
        val threshold = library.smartThreshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        val categoryIds = library.categories.map { it.id }.toSet()
        return library.links.filter { it.categoryId in categoryIds }.groupBy { it.categoryId }
            .flatMap { (categoryId, entries) ->
                val videos = entries.mapNotNull { link -> normalizeWebUrl(link.url.trim())?.let { it to link } }
                val parents = IntArray(videos.size) { it }
                fun representative(index: Int): Int {
                    var current = index
                    while (parents[current] != current) {
                        parents[current] = parents[parents[current]]
                        current = parents[current]
                    }
                    return current
                }
                fun join(first: Int, second: Int) {
                    val a = representative(first)
                    val b = representative(second)
                    if (a != b) parents[b] = a
                }
                val firstUrl = mutableMapOf<String, Int>()
                val firstId = mutableMapOf<Long, Int>()
                videos.forEachIndexed { index, (url, link) ->
                    firstUrl.putIfAbsent(url, index)?.let { join(index, it) }
                    if (link.id > 0) firstId.putIfAbsent(link.id, index)?.let { join(index, it) }
                }
                val videoKeys = mutableMapOf<Int, MutableSet<String>>()
                videos.forEachIndexed { index, (_, link) ->
                    videoKeys.getOrPut(representative(index)) { linkedSetOf() }.addAll(
                        link.keys.map(::normalizeKey).filter { key -> key.any(Char::isLetterOrDigit) }
                    )
                }
                val counts = mutableMapOf<String, Int>()
                videoKeys.values.forEach { keys -> keys.forEach { key -> counts[key] = (counts[key] ?: 0) + 1 } }
                counts.filterValues { it >= threshold }.map { (key, count) -> SmartCollection(categoryId, key, count) }
            }.sortedWith(compareByDescending<SmartCollection> { it.count }.thenBy { it.key }.thenBy { it.categoryId })
    }

    internal fun normalizeKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().trimStart('#').trim().replace(whitespace, " ").lowercase(Locale.ROOT)

    internal fun requireThreshold(value: Int): Int {
        require(value in MIN_THRESHOLD..MAX_THRESHOLD) { "Scegli una soglia tra 2 e 20 video" }
        return value
    }
}
