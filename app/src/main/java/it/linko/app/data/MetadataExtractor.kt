package it.linko.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

/** No login, account, remote metadata API, or video download is needed. */
object MetadataExtractor {
    private val linkPattern = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val pluralLinkPattern = Regex(
        "(?<![\\p{L}\\p{N}_@/-])https?://[^\\s<>\\\"']+|" +
            "(?<![\\p{L}\\p{N}_@./:-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}" +
            "(?![a-z0-9_-]|\\.[a-z0-9_-])(?::[0-9]{1,5})?(?:[/?#][^\\s<>\\\"']*)?",
        RegexOption.IGNORE_CASE
    )
    private val unsafePrefix = Regex("(?:javascript|vbscript|data|file|ftp|intent|mailto):", RegexOption.IGNORE_CASE)
    private val ignoredWords = setOf("www", "com", "net", "org", "html", "htm", "php", "video", "videos", "watch", "shorts", "embed", "index", "the", "and", "with", "from", "for", "this", "that", "dei", "del", "della", "con", "per", "una", "uno", "mp4", "webm", "mov")

    /** Extract a web URL from Chrome's shared text, keeping query strings and video time fragments. */
    fun extractUrl(input: String): String? {
        val text = input.trim().take(65536)
        val match = linkPattern.find(text)
        var found = match?.value
        if (found == null && text.none { it.isWhitespace() } && Regex("(?i)^(www\\.)?[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}(:[0-9]{1,5})?([/?#].*)?$").matches(text)) {
            found = "https://$text"
        }
        val candidate = cleanCandidate(found ?: return null, match?.range?.first?.let { text.substring((it - 64).coerceAtLeast(0), it) }.orEmpty())
        return normalizeWebUrl(candidate)
    }

    /**
     * Find links in a pasted list or paragraph, including bare domains. Order is retained.
     * 201 unique results is a deliberate overflow sentinel for the UI's 100-link batch limit.
     * Oversized input is rejected instead of silently dropping the tail of the user's text.
     */
    fun extractUrls(input: String): List<String> {
        require(input.length <= 256 * 1024 && input.toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
            "Il testo supera 256 KB. Incolla un elenco più piccolo."
        }
        val results = linkedSetOf<String>()
        for (match in pluralLinkPattern.findAll(input)) {
            val start = match.range.first
            val tokenStart = input.lastIndexOfAny(charArrayOf(' ', '\t', '\n', '\r'), startIndex = (start - 1).coerceAtLeast(0)) + 1
            val prefix = input.substring(tokenStart.coerceAtMost(start), start)
            if (unsafePrefix.containsMatchIn(prefix)) continue
            val raw = match.value
            // A comma followed by another full URL is a list separator outside a query.
            val parts = if ('?' !in raw && '#' !in raw) raw.split(Regex("[,;](?=https?://)", RegexOption.IGNORE_CASE)) else listOf(raw)
            for ((index, part) in parts.withIndex()) {
                // Wrapper recognition only needs the immediate prefix, even for large pastes.
                val cleaned = cleanCandidate(part, if (index == 0) input.substring((start - 64).coerceAtLeast(0), start) else "")
                val candidate = if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) cleaned else "https://$cleaned"
                normalizeWebUrl(candidate)?.let(results::add)
                if (results.size >= 201) return results.toList()
            }
        }
        return results.toList()
    }

    private fun cleanCandidate(raw: String, precedingText: String): String {
        var candidate = raw
        val hasQuery = '?' in candidate || '#' in candidate
        // Query punctuation may belong to a signature; strip only a surrounding wrapper.
        if (!hasQuery) candidate = candidate.trimEnd('.', ',', ';', '!', '"', '\'')
        val wrappers = precedingText.trimEnd().takeLastWhile { it == '(' || it == '[' || it == '{' }
        if (hasQuery && wrappers.isNotEmpty()) {
            val withoutSentencePunctuation = candidate.trimEnd('.', ',', ';', '!')
            val close = withoutSentencePunctuation.lastOrNull()
            val open = when (close) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
            if (open != null && open in wrappers && withoutSentencePunctuation.count { it == close } > withoutSentencePunctuation.count { it == open }) {
                candidate = withoutSentencePunctuation
            }
        }
        val removed = mutableMapOf<Char, Int>()
        while (candidate.isNotEmpty()) {
            val close = candidate.last()
            val open = when (close) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> break }
            if (candidate.count { it == close } <= candidate.count { it == open }) break
            val removedCount = removed[open] ?: 0
            if (hasQuery && removedCount >= wrappers.count { it == open }) break
            candidate = candidate.dropLast(1)
            removed[open] = removedCount + 1
        }
        return candidate
    }

    /** A useful, deterministic preview even when a website blocks metadata access. */
    fun fromUrl(input: String): LinkMetadata {
        val url = extractUrl(input) ?: throw IllegalArgumentException("Inserisci un link HTTP o HTTPS valido")
        val uri = URI(url)
        val query = queryParameters(uri)
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val slug = query["title"] ?: query["name"] ?: segments.lastOrNull { segment ->
            val decoded = decode(segment)
            decoded.contains('-') || decoded.contains('_') || decoded.contains(' ')
        }?.let(::decode).orEmpty()
        val words = slug.replace(Regex("\\.(?:html?|php|mp4|webm|mov)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\b(?:2160p|1440p|1080p|720p|480p|360p|4k|8k)\\b"), " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length in 2..40 && it.lowercase(Locale.ROOT) !in ignoredWords && !it.all(Char::isDigit) }
            .take(16)
        val keywords = words.map { it.lowercase(Locale.ROOT) }.distinct()
        val titleWords = slug.replace(Regex("\\.(?:html?|php|mp4|webm|mov)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[-_]+"), " ").trim()
        val title = if (titleWords.isNotBlank()) titleWords.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
            else "Video su ${uri.host.removePrefix("www.")}"
        return LinkMetadata(url, title.take(500), keywords = keywords, suggestedCategory = suggestCategory(keywords.joinToString(" ")),
            quality = detectQuality(decode(uri.rawPath.orEmpty()) + " " + query.entries.joinToString(" ") { "${it.key} ${it.value}" }))
    }

    suspend fun extract(input: String): LinkMetadata = withContext(Dispatchers.IO) {
        val fallback = fromUrl(input)
        try {
            val (html, finalUrl) = fetch(fallback.url)
            parseHtml(fallback, html, finalUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fallback.copy(warning = "Questo sito non fornisce un'anteprima accessibile. Titolo e suggerimenti ricavati dal link: puoi modificarli.")
        }
    }

    internal fun parseHtml(fallback: LinkMetadata, html: String, finalUrl: String = fallback.url): LinkMetadata {
        val document = Jsoup.parse(html, finalUrl)
        val metadata = readMetaValues(document)
        fun meta(vararg names: String): String? = names.firstNotNullOfOrNull(metadata::get)

        val video = findVideoJson(document)
        val title = meta("og:title", "twitter:title") ?: video?.text("name", "headline") ?: document.title().takeIf { it.isNotBlank() }
        val description = meta("og:description", "twitter:description", "description") ?: video?.text("description").orEmpty()
        val image = meta("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
            ?: video?.let { stringValues(it.opt("thumbnailUrl")).firstOrNull() }
        val thumbnail = image?.let { runCatching { URI(finalUrl).resolve(it).toString() }.getOrNull() }?.let(::normalizeWebUrl)
        val explicitKeys = mutableListOf<String>()
        meta("keywords", "news_keywords")?.split(',', ';')?.let(explicitKeys::addAll)
        document.select("meta[property=video:tag],meta[property=article:tag]").map { it.attr("content") }.let(explicitKeys::addAll)
        video?.let { explicitKeys.addAll(stringValues(it.opt("keywords")).flatMap { value -> value.split(',', ';') }) }
        val keywords = (explicitKeys + fallback.keywords).map { it.trim().removePrefix("#").lowercase(Locale.ROOT) }
            .filter { it.length in 2..50 && it.none(Char::isISOControl) }.distinct().take(24)
        val genre = meta("video:category", "article:section", "category") ?: video?.text("genre", "category")
        val resolution = meta("video:quality", "og:video:quality") ?: video?.text("videoQuality")
        val height = meta("og:video:height")?.toIntOrNull() ?: video?.optInt("height")?.takeIf { it > 0 }
        val pageQuality = resolution?.let(::qualityFromMetadata) ?: height?.takeIf { it in 100..10000 }?.let { "${it}p" }
        val duration = meta("video:duration", "og:video:duration")?.toDoubleOrNull()?.toLong()?.let(::formatSeconds)
            ?: video?.text("duration")?.let(::formatDuration)
        val metadataFound = title != null || description.isNotBlank() || thumbnail != null ||
            explicitKeys.any { it.isNotBlank() } || genre != null || pageQuality != null || duration != null
        return fallback.copy(
            title = title?.trim()?.take(500)?.takeIf { it.isNotBlank() } ?: fallback.title,
            description = description.trim().take(10000), thumbnail = thumbnail, keywords = keywords,
            suggestedCategory = genre?.take(60) ?: suggestCategory("${title.orEmpty()} $description ${keywords.joinToString(" ")}") ?: fallback.suggestedCategory,
            quality = pageQuality ?: fallback.quality, duration = duration,
            source = if (metadataFound) "Metadati della pagina" else "Indirizzo del link",
            warning = if (metadataFound) null else "Anteprima non disponibile. Suggerimenti ricavati dall'indirizzo."
        )
    }

    private fun fetch(initialUrl: String): Pair<String, String> {
        var current = initialUrl
        repeat(4) {
            val url = URL(current)
            requirePublicHost(url.host)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Linko/1.0; Android) LinkPreview")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val target = connection.getHeaderField("Location") ?: error("Redirect senza destinazione")
                    current = normalizeWebUrl(URI(current).resolve(target).toString()) ?: error("Redirect non valido")
                } else {
                    require(code in 200..299) { "Pagina non accessibile" }
                    val contentType = connection.contentType.orEmpty()
                    require(contentType.contains("html", true) || contentType.isBlank()) { "Contenuto non HTML" }
                    val maxBytes = 1024 * 1024
                    val bytes = connection.inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        val output = java.io.ByteArrayOutputStream()
                        while (output.size() < maxBytes) {
                            val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    val charsetName = Regex("charset\\s*=\\s*[\"']?([^\\s;\"']+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
                    val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
                    return bytes.toString(charset) to current
                }
            } finally { connection.disconnect() }
        }
        error("Troppi reindirizzamenti")
    }

    private fun requirePublicHost(host: String) {
        require(host.isNotBlank() && !host.equals("localhost", true) && !host.endsWith(".local", true))
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.none { address ->
            val bytes = address.address
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
                (bytes.size == 4 && ((bytes[0].toInt() and 255) == 0 || (bytes[0].toInt() and 255) == 127 ||
                    ((bytes[0].toInt() and 255) == 100 && (bytes[1].toInt() and 255) in 64..127) || (bytes[0].toInt() and 255) >= 224)) ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
        }) { "Anteprime disattivate per gli indirizzi della rete privata" }
    }

    private fun readMetaValues(document: Document): Map<String, String> = buildMap {
        for (element in document.select("meta[content]")) {
            val value = element.attr("content").trim()
            if (value.isEmpty()) continue

            val property = element.attr("property").lowercase(Locale.ROOT)
            val name = element.attr("name").lowercase(Locale.ROOT)
            if (property.isNotEmpty()) putIfAbsent(property, value)
            if (name.isNotEmpty()) putIfAbsent(name, value)
        }
    }
    private fun findVideoJson(document: Document): JSONObject? {
        document.select("script[type=application/ld+json]").take(12).forEach { script ->
            val text = script.data().trim()
            if (text.length > 300000) return@forEach
            val parsed = runCatching { if (text.startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull()
            findVideo(parsed, 0)?.let { return it }
        }
        return null
    }
    private fun findVideo(value: Any?, depth: Int): JSONObject? {
        if (depth > 10) return null
        when (value) {
            is JSONObject -> {
                if (stringValues(value.opt("@type")).any { it.equals("VideoObject", true) }) return value
                value.keys().asSequence().take(100).forEach { key -> findVideo(value.opt(key), depth + 1)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) findVideo(value.opt(i), depth + 1)?.let { return it }
        }
        return null
    }
    private fun JSONObject.text(vararg names: String): String? {
        names.forEach { name -> stringValues(opt(name)).firstOrNull { it.isNotBlank() }?.let { return it } }
        return null
    }
    private fun stringValues(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until minOf(value.length(), 100)).mapNotNull { value.opt(it) as? String }
        else -> emptyList()
    }
    private fun queryParameters(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val split = part.split('=', limit = 2)
        if (split.size == 2) decode(split[0]).lowercase(Locale.ROOT) to decode(split[1]) else null
    }.toMap()
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    private fun detectQuality(value: String): String? {
        val match = Regex("(?i)(?<![a-z0-9])(4320p|2160p|1440p|1080p|720p|480p|360p|240p|8k|4k|uhd|fhd|hd)(?![a-z0-9])").find(value) ?: return null
        return when (val token = match.value.lowercase(Locale.ROOT)) {
            "4k", "8k" -> token.uppercase(Locale.ROOT)
            "uhd", "fhd", "hd" -> token.uppercase(Locale.ROOT)
            else -> token
        }
    }
    private fun qualityFromMetadata(value: String): String? {
        val clean = value.trim()
        clean.toIntOrNull()?.takeIf { it in 100..10000 }?.let { return "${it}p" }
        Regex("(?i)^\\d{2,5}\\s*[x×]\\s*(\\d{2,5})$").matchEntire(clean)?.groupValues?.get(1)
            ?.toIntOrNull()?.takeIf { it in 100..10000 }?.let { return "${it}p" }
        return detectQuality(clean)
    }
    private fun suggestCategory(value: String): String? {
        val tokens = value.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+" )).toSet()
        val categories = linkedMapOf(
            "Cucina" to setOf("food", "eat", "cooking", "cook", "recipe", "recipes", "cucina", "ricetta", "ricette", "pasta", "pizza", "chef", "asian", "mangiare"),
            "Tecnologia" to setOf("tech", "coding", "code", "programming", "android", "software", "computer", "javascript", "kotlin", "python", "tecnologia", "tutorial"),
            "Viaggi" to setOf("travel", "trip", "viaggio", "viaggi", "tour", "hiking", "mountain", "beach", "vacanza", "vacanze"),
            "Ispirazione" to setOf("art", "design", "creative", "creativity", "arte", "music", "musica", "inspiration", "ispirazione", "drawing", "photography")
        )
        return categories.map { (name, words) -> name to words.count { it in tokens } }.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
    }
    private fun formatDuration(value: String): String? {
        value.toLongOrNull()?.let { return formatSeconds(it) }
        val match = Regex("(?i)^P(?:(\\d+)D)?T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$").matchEntire(value) ?: return null
        val groups = match.groupValues
        val seconds = (groups[1].toLongOrNull() ?: 0) * 86400 + (groups[2].toLongOrNull() ?: 0) * 3600 + (groups[3].toLongOrNull() ?: 0) * 60 + (groups[4].toDoubleOrNull()?.toLong() ?: 0)
        return formatSeconds(seconds)
    }
    private fun formatSeconds(seconds: Long): String? {
        if (seconds <= 0 || seconds > 604800) return null
        return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
}
