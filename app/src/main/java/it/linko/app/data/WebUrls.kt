package it.linko.app.data

import java.net.URI
import java.util.Locale

internal const val MAX_WEB_URL_LENGTH = 8192

/** Normalize an HTTP URL without interpreting punctuation as surrounding prose. */
internal fun normalizeWebUrl(candidate: String): String? {
    if (candidate.length > MAX_WEB_URL_LENGTH) return null
    return runCatching {
        val uri = URI(candidate)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https")
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        require(uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0)

        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        URI("$scheme://$host$port$path$query$fragment").toASCIIString()
            .takeIf { it.length <= MAX_WEB_URL_LENGTH }
    }.getOrNull()
}
