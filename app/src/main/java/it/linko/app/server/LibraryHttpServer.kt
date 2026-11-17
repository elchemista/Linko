package it.linko.app.server

import it.linko.app.data.LibraryState
import it.linko.app.data.LinkFilters
import it.linko.app.data.LinkMatcher
import it.linko.app.data.SmartCollections
import it.linko.app.data.VideoLink
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Local/VPN server with optional editing. Plain HTTP is intentional: use a trusted hotspot/LAN or an encrypted
 * VPN such as Tailscale. The password protects access, but HTTP itself does not encrypt traffic.
 * Passwords and sessions exist only in this instance and are discarded when the server stops.
 */
class LibraryHttpServer(
    password: String,
    categoryIds: Set<Long>,
    private val snapshot: () -> LibraryState,
    private val port: Int = 8787,
    private val bindAddress: InetAddress? = null,
    private val sessionLifetimeMillis: Long = TimeUnit.HOURS.toMillis(8),
    allowEditing: Boolean = false,
    writer: LibraryWriter? = null
) : Closeable {
    private val random = SecureRandom()
    @Volatile private var accessPassword: String? = password
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sharedCategoryIds = ConcurrentHashMap.newKeySet<Long>().apply { addAll(categoryIds) }
    private val editing = if (allowEditing && writer != null) WebEditing(writer, snapshot, sharedCategoryIds, ::randomToken) else null
    private val attempts = Collections.synchronizedMap(object : LinkedHashMap<String, Attempts>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attempts>?) = size > 256
    })
    private val connections = mutableSetOf<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "linko-http-worker").apply { isDaemon = true } })
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false
    val actualPort: Int get() = server?.localPort ?: port

    init {
        require(password.length in 8..128) { "Usa una password di almeno 8 caratteri (massimo 128)." }
    }

    @Synchronized fun start(): Int {
        check(server == null && !closed) { "Server già avviato o arrestato." }
        val listener = ServerSocket()
        try {
            listener.reuseAddress = false
            listener.bind(InetSocketAddress(bindAddress, port), 20)
        } catch (failure: Exception) {
            runCatching { listener.close() }
            throw failure
        }
        server = listener
        Thread({
            while (!closed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                val registered = synchronized(connections) {
                    if (closed) false else connections.add(socket)
                }
                if (!registered) {
                    runCatching { socket.close() }
                    break
                }
                try {
                    workers.execute {
                        try { handle(socket) } finally {
                            closeConnection(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    closeConnection(socket)
                }
            }
        }, "linko-http-accept").apply { isDaemon = true; start() }
        return listener.localPort
    }

    @Synchronized override fun close() {
        closed = true
        runCatching { server?.close() }
        val openConnections = synchronized(connections) {
            connections.toList().also { connections.clear() }
        }
        openConnections.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        sessions.clear()
        attempts.clear()
        accessPassword = null
        editing?.close()
    }

    private fun closeConnection(socket: Socket) {
        runCatching { socket.close() }
        synchronized(connections) { connections.remove(socket) }
    }

    private fun handle(socket: Socket) {
        try {
            val request = readRequest(socket)
            val host = request.headers["host"] ?: throw HttpFailure(400)
            // Only the IP used by this TCP connection is accepted; reject DNS-rebinding Host names.
            val expected = socket.localAddress.hostAddress.orEmpty().substringBefore('%')
            val hostUri = runCatching { URI("http://$host") }.getOrNull() ?: throw HttpFailure(400)
            val requestHost = hostUri.host?.removePrefix("[")?.removeSuffix("]")
            if (requestHost != expected && !(socket.localAddress.isLoopbackAddress && requestHost == "localhost")) {
                throw HttpFailure(400)
            }
            if (hostUri.port != actualPort || hostUri.rawUserInfo != null || hostUri.rawPath.orEmpty().isNotEmpty() || hostUri.rawQuery != null || hostUri.rawFragment != null) {
                throw HttpFailure(400)
            }
            if (request.method == "POST") {
                if (request.headers["origin"]?.let { it != "http://$host" } == true) throw HttpFailure(403)
                if (request.headers["sec-fetch-site"] in setOf("cross-site", "same-site")) throw HttpFailure(403)
                if (!request.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded")) throw HttpFailure(415)
            }
            val uri = runCatching { URI(request.target) }.getOrNull() ?: throw HttpFailure(400)
            if (uri.isAbsolute || uri.rawAuthority != null || !request.target.startsWith("/")) throw HttpFailure(400)
            val path = uri.path
            if (path == "/favicon.ico") { respond(socket, 204, "", "image/x-icon"); return }
            if (path !in setOf("/", "/login", "/logout") && path !in WebEditing.routes) throw HttpFailure(404)
            if (path == "/login" && request.method == "POST") {
                login(socket, request)
                return
            }
            val token = request.headers["cookie"].orEmpty().split(';').map { it.trim().split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "linko_session" }?.get(1)
            val session = token?.let(sessions::get)?.takeIf { it.expires > System.currentTimeMillis() }
            if (session == null) {
                if (token != null) sessions.remove(token)
                respond(socket, if (request.method == "POST" && path in WebEditing.routes) 401 else 200, loginPage())
                return
            }
            if (path == "/logout" && request.method == "POST") {
                val body = decodeForm(request.body)
                if (!secureEqual(body["csrf"].orEmpty(), session.csrf)) throw HttpFailure(403)
                token?.let { sessions.remove(it); editing?.discardOwner(it) }
                respond(socket, 303, "", headers = mapOf("Location" to "/", "Set-Cookie" to "linko_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"))
                return
            }
            if (path in WebEditing.routes) {
                val editor = editing ?: throw HttpFailure(403)
                val parameters = if (request.method == "POST") decodeForm(request.body) else decodeForm(uri.rawQuery.orEmpty())
                if (request.method == "POST" && !secureEqual(parameters["csrf"].orEmpty(), session.csrf)) throw HttpFailure(403)
                val result = editor.handle(request.method, path, parameters, token.orEmpty(), session.csrf)
                respond(socket, result.status, if (result.location == null) shell(result.title, result.body) else "",
                    headers = result.location?.let { mapOf("Location" to it) }.orEmpty())
                return
            }
            if (request.method != "GET" || path != "/") throw HttpFailure(405)
            val parameters = decodeParameters(uri.rawQuery.orEmpty())
            val values = parameters.toMap()
            val filters = LinkFilters(
                query = values["q"].orEmpty().trim().take(200),
                categoryId = values["category"]?.toLongOrNull(),
                keys = parameters.filter { it.first == "key" }.map { it.second.trim().take(100) }.filter { it.isNotBlank() }.take(100).toSet(),
                qualities = parameters.filter { it.first == "quality" }.map { it.second.trim().take(100) }.filter { it.isNotBlank() }.take(100).toSet(),
                domains = parameters.filter { it.first == "domain" }.map { it.second.trim().take(253) }.filter { it.isNotBlank() }.take(100).toSet(),
                favoritesOnly = values["favorites"] == "1"
            )
            respond(socket, 200, libraryPage(snapshot(), filters, session.csrf))
        } catch (failure: HttpFailure) {
            runCatching { respond(socket, failure.code, shell("Richiesta non disponibile", "<main class=\"login\"><h1>Richiesta non disponibile</h1><p>Riapri l’indirizzo mostrato nell’app.</p><a class=\"button\" href=\"/\">Torna a Linko</a></main>")) }
        } catch (_: Exception) {
            runCatching { respond(socket, 500, shell("Riprova", "<main class=\"login\"><h1>Qualcosa non ha funzionato</h1><p>Attendi un momento e ricarica la pagina.</p></main>")) }
        }
    }

    private fun login(socket: Socket, request: Request) {
        val ip = socket.inetAddress.hostAddress.orEmpty()
        val now = System.currentTimeMillis()
        val count = synchronized(attempts) {
            attempts[ip]?.takeIf { now - it.since < TimeUnit.MINUTES.toMillis(5) }
                ?: Attempts(now).also { attempts[ip] = it }
        }
        if (count.failures >= 6) {
            respond(socket, 429, loginPage("Troppi tentativi. Riprova tra cinque minuti."), headers = mapOf("Retry-After" to "300"))
            return
        }
        val supplied = decodeForm(request.body)["password"].orEmpty()
        val valid = supplied.length in 8..128 && supplied == accessPassword
        if (!valid) {
            synchronized(attempts) { count.failures++ }
            respond(socket, 401, loginPage("Password non corretta. Riprova."))
            return
        }
        synchronized(attempts) { attempts.remove(ip) }
        sessions.entries.removeAll { it.value.expires <= now }
        if (sessions.size >= 32) sessions.keys.firstOrNull()?.let(sessions::remove)
        val token = randomToken()
        sessions[token] = Session(now + sessionLifetimeMillis, randomToken())
        respond(socket, 303, "", headers = mapOf("Location" to "/", "Set-Cookie" to "linko_session=$token; Max-Age=${sessionLifetimeMillis / 1000}; Path=/; HttpOnly; SameSite=Strict"))
    }

    private fun readRequest(socket: Socket): Request {
        val input = BufferedInputStream(socket.getInputStream())
        var total = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun limitReadTime() {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw HttpFailure(400)
            socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
        }
        fun line(): String {
            val result = StringBuilder()
            while (true) {
                limitReadTime()
                val byte = input.read()
                if (byte == -1) throw HttpFailure(400)
                if (++total > 16384 || result.length > 8192) throw HttpFailure(431)
                if (byte == 10) return result.toString().removeSuffix("\r")
                if (byte == 0) throw HttpFailure(400)
                result.append(byte.toChar())
            }
        }
        val first = line().split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1") throw HttpFailure(400)
        if (first[0] !in setOf("GET", "POST")) throw HttpFailure(405)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val value = line()
            if (value.isEmpty()) break
            val colon = value.indexOf(':')
            if (colon <= 0 || value.startsWith(' ') || value.startsWith('\t')) throw HttpFailure(400)
            val key = value.substring(0, colon).lowercase()
            if (headers.containsKey(key)) throw HttpFailure(400)
            headers[key] = value.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw HttpFailure(400)
        val length = headers["content-length"]?.toIntOrNull() ?: if (headers.containsKey("content-length")) throw HttpFailure(400) else 0
        val maximumBody = if (first[1].substringBefore('?') in WebEditing.routes) 4 * 1024 * 1024 else 4096
        if (length !in 0..maximumBody) throw HttpFailure(413)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            limitReadTime()
            val count = input.read(bytes, read, length - read)
            if (count < 0) throw HttpFailure(400)
            read += count
        }
        return Request(first[0], first[1], headers, bytes.toString(Charsets.UTF_8))
    }

    private fun respond(socket: Socket, code: Int, content: String, contentType: String = "text/html; charset=utf-8", headers: Map<String, String> = emptyMap()) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val reason = mapOf(200 to "OK", 204 to "No Content", 303 to "See Other", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large", 415 to "Unsupported Media Type", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error")[code].orEmpty()
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            // no-referrer makes browser form POSTs send Origin: null, which our origin check rejects.
            // same-origin permits our own login/logout forms without exposing referrers to other sites.
            append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: same-origin\r\n")
            append("Content-Security-Policy: default-src 'none'; img-src https: http:; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    private fun loginPage(error: String? = null) = shell("Accedi alla tua raccolta", """
        <main class="login"><a class="brand" href="/"><span class="logo">↗</span>linko<span class="dot">.</span></a>
        <span class="eyebrow">IL TUO SPAZIO, ANCHE SUL PC</span><h1>I tuoi link.<br>Un po’ più vicini.</h1>
        <p>Accedi alla raccolta condivisa dal tuo telefono.</p>
        <form action="/login" method="post"><label for="password">Password della raccolta</label>
        <input id="password" name="password" type="password" autocomplete="current-password" minlength="8" maxlength="128" placeholder="Inserisci la password" required autofocus>
        ${error?.let { "<p class=\"error\" role=\"alert\">${escape(it)}</p>" }.orEmpty()}
        <button type="submit">Apri la raccolta <span>↗</span></button></form>
        <div class="footnote">◈ Connessione al tuo telefono · Nessun account</div>
        <p class="notice">Usa una rete locale fidata o una VPN: questa pagina usa HTTP.</p></main>
    """.trimIndent())

    private fun libraryPage(state: LibraryState, filters: LinkFilters, csrf: String): String {
        val categories = state.categories.filter { it.id in sharedCategoryIds }
        val categoriesById = categories.associateBy { it.id }
        val all = state.links.filter { it.categoryId in categoriesById && safeUrl(it.url) != null }
        val smart = SmartCollections.build(state.copy(categories = categories, links = all))
            .groupBy { it.categoryId }
        val acrossCategories = filters.copy(categoryId = null)
        val matches = all.filter { link ->
            LinkMatcher.matches(link, acrossCategories, categoriesById.getValue(link.categoryId).name)
        }
        val counts = matches.groupingBy { it.categoryId }.eachCount()
        val selected = filters.categoryId
        val links = if (selected == null) matches else matches.filter { it.categoryId == selected }
        val query = filters.query
        val title = selected?.let(categoriesById::get)?.name ?: "La tua raccolta"
        val active = query.isNotBlank() || selected != null || filters.keys.isNotEmpty() || filters.qualities.isNotEmpty() || filters.domains.isNotEmpty() || filters.favoritesOnly
        return shell(title, buildString {
            append("<div class=\"layout\"><aside><a class=\"brand\" href=\"/\"><span class=\"logo\">↗</span>linko<span class=\"dot\">.</span></a><div class=\"device\"><i></i> Condiviso dal telefono</div><span class=\"eyebrow\">LA TUA LIBRERIA</span>")
            append("<a class=\"nav ${if (selected == null) "active" else ""}\" href=\"${escape(filterUrl(acrossCategories))}\"><span>▦ Tutti i link</span><small>${matches.size}</small></a><div class=\"eyebrow divider\">CATEGORIE</div>")
            categories.forEach { category ->
                val color = "#%06X".format(category.color and 0xFFFFFF)
                val changed = filters.copy(categoryId = category.id)
                append("<a class=\"nav ${if (selected == category.id) "active" else ""}\" href=\"${escape(filterUrl(changed))}\"><span><b style=\"color:$color\">●</b> ${escape(category.name)}</span><small>${counts[category.id] ?: 0}</small></a>")
                smart[category.id].orEmpty().forEach { collection ->
                    val grouped = filters.copy(categoryId = category.id, keys = setOf(collection.key))
                    append("<a class=\"nav smartnav\" href=\"${escape(filterUrl(grouped))}\"><span>↳ #${escape(collection.key)}</span><small>${collection.count}</small></a>")
                }
            }
            if (editing != null) append("<a class=\"managecategories\" href=\"/categories\">+ Crea o modifica categorie</a>")
            append("<div class=\"sidebottom\"><p>Le cose belle meritano<br>di essere ritrovate.</p><form method=\"post\" action=\"/logout\"><input type=\"hidden\" name=\"csrf\" value=\"${escape(csrf)}\"><button class=\"logout\">Esci dalla raccolta ↗</button></form></div></aside><main class=\"collection\"><div class=\"topline\"><span class=\"eyebrow\">SALVA. ORGANIZZA. RITROVA.</span><span class=\"readonly\">◈ ${if (editing == null) "Sola lettura" else "Modifiche abilitate"}</span></div><h1>${escape(title)}<span class=\"dot\">.</span></h1><p class=\"intro\">${links.size} link${if (active) " trovati su ${all.size}" else " da guardare, imparare e riscoprire"}.</p>")
            if (editing != null) append("<a class=\"managecategories\" href=\"/categories\">Gestisci categorie ↗</a>")
            append(filterForm(all, filters))
            if (active) {
                append("<div class=\"activefilters\"><span>Filtri attivi</span>")
                fun pill(label: String, changed: LinkFilters) { append("<a href=\"${escape(filterUrl(changed))}\" aria-label=\"Rimuovi filtro ${escape(label)}\">${escape(label)} <b>×</b></a>") }
                if (query.isNotBlank()) pill("Cerca: $query", filters.copy(query = ""))
                selected?.let(categoriesById::get)?.let { pill(it.name, acrossCategories) }
                filters.keys.sorted().forEach { pill("#$it", filters.copy(keys = filters.keys - it)) }
                filters.qualities.sorted().forEach { pill(it, filters.copy(qualities = filters.qualities - it)) }
                filters.domains.sorted().forEach { pill(it, filters.copy(domains = filters.domains - it)) }
                if (filters.favoritesOnly) pill("Preferiti", filters.copy(favoritesOnly = false))
                append("<a class=\"clearfilters\" href=\"/\">Azzera tutti</a></div>")
            }
            append("<div class=\"sectiontitle\"><h2>${if (active) "Risultati della ricerca" else "Da rivedere, quando vuoi"}</h2><span>${links.size} link</span></div><div class=\"cards\">")
            links.sortedByDescending { it.createdAt }.forEach { link ->
                append(linkCard(link, categoriesById.getValue(link.categoryId).name))
            }
            append("</div>")
            if (links.isEmpty()) append("<div class=\"empty\"><span>↗</span><h2>${if (active) "Nessun link trovato" else "Qui c’è spazio per le tue scoperte"}</h2><p>${if (active) "Prova a rimuovere un filtro. Le keys selezionate devono essere tutte presenti." else "Salva un link nell’app e scegli una delle categorie condivise."}</p></div>")
            if (editing != null) append(WebEditing.composer(csrf))
            append("<footer>linko. <span>La tua raccolta, dal telefono al browser.</span></footer></main></div>")
        })
    }

    private fun filterForm(links: List<VideoLink>, filters: LinkFilters): String = buildString {
        val qualities = links.mapNotNull { it.quality?.takeIf(String::isNotBlank) }.distinctBy { it.lowercase() }.sorted()
        val domains = links.mapNotNull { LinkMatcher.domainOf(it.url) }.distinct().sorted()
        val suffixes = domains.mapNotNull { domain -> domain.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.all(Char::isLetter) }?.let { ".$it" } }.distinct().sorted()
        val keys = links.flatMap { it.keys }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        append("<form class=\"filterform\" method=\"get\" action=\"/\">")
        filters.categoryId?.let { append("<input type=\"hidden\" name=\"category\" value=\"$it\">") }
        append("<div class=\"search\"><input name=\"q\" value=\"${escape(filters.query)}\" placeholder=\"Cerca un titolo, un sito o una key…\" aria-label=\"Cerca nei link\"><button type=\"submit\">Applica filtri ↗</button><a class=\"filterreset\" href=\"/\">Azzera</a></div><div class=\"filterrow\">")
        fun choice(name: String, value: String, label: String, selected: Set<String>) {
            append("<label><input type=\"checkbox\" name=\"$name\" value=\"${escape(value)}\"${if (selected.any { it.equals(value, true) }) " checked" else ""}><span>${escape(label)}</span></label>")
        }
        append("<details class=\"keyfilter facet\"${if (filters.qualities.isNotEmpty()) " open" else ""}><summary>Qualità${if (filters.qualities.isNotEmpty()) " · ${filters.qualities.size}" else " · tutte"}</summary><p>Scegli una o più qualità.</p><div class=\"keychoices\">")
        qualities.forEach { choice("quality", it, it, filters.qualities) }
        if (qualities.isEmpty()) append("<span class=\"nokeys\">Nessuna qualità indicata nei link condivisi.</span>")
        filters.qualities.filter { selected -> qualities.none { it.equals(selected, true) } }.forEach { append("<input type=\"hidden\" name=\"quality\" value=\"${escape(it)}\">") }
        append("</div></details><details class=\"keyfilter facet\"${if (filters.domains.isNotEmpty()) " open" else ""}><summary>Origine${if (filters.domains.isNotEmpty()) " · ${filters.domains.size}" else " · tutti i siti"}</summary><p>Scegli uno o più siti o estensioni.</p><div class=\"keychoices\">")
        domains.forEach { choice("domain", it, it, filters.domains) }
        suffixes.forEach { choice("domain", it, "Tutti i siti $it", filters.domains) }
        if (domains.isEmpty()) append("<span class=\"nokeys\">Nessun sito nei link condivisi.</span>")
        filters.domains.filter { selected -> (domains + suffixes).none { it.equals(selected, true) } }.forEach { append("<input type=\"hidden\" name=\"domain\" value=\"${escape(it)}\">") }
        append("</div></details><label class=\"favoritefilter\"><input type=\"checkbox\" name=\"favorites\" value=\"1\"${if (filters.favoritesOnly) " checked" else ""}> Solo preferiti</label></div>")
        append("<details class=\"keyfilter\"${if (filters.keys.isNotEmpty()) " open" else ""}><summary>Keys${if (filters.keys.isNotEmpty()) " · ${filters.keys.size} selezionate" else " · scegli le parole chiave"}<span>Tutte insieme</span></summary><p>Il video deve avere tutte le keys selezionate. Puoi combinarle con qualità e origine.</p><div class=\"keychoices\">")
        keys.forEach { key -> append("<label><input type=\"checkbox\" name=\"key\" value=\"${escape(key)}\"${if (filters.keys.any { it.equals(key, true) }) " checked" else ""}><span>#${escape(key)}</span></label>") }
        if (keys.isEmpty()) append("<span class=\"nokeys\">Le keys dei link condivisi appariranno qui.</span>")
        filters.keys.filter { selected -> keys.none { it.equals(selected, true) } }.forEach { append("<input type=\"hidden\" name=\"key\" value=\"${escape(it)}\">") }
        append("</div></details></form>")
    }

    private fun filterUrl(filters: LinkFilters): String {
        val values = mutableListOf<Pair<String, String>>()
        filters.categoryId?.let { values += "category" to it.toString() }
        if (filters.query.isNotBlank()) values += "q" to filters.query
        filters.keys.sorted().forEach { values += "key" to it }
        filters.qualities.sorted().forEach { values += "quality" to it }
        filters.domains.sorted().forEach { values += "domain" to it }
        if (filters.favoritesOnly) values += "favorites" to "1"
        return if (values.isEmpty()) "/" else "/?" + values.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }

    private fun linkCard(link: VideoLink, category: String): String {
        val url = safeUrl(link.url) ?: return ""
        val image = link.thumbnail?.let(::safeUrl)
        val domain = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault("Video")
        return buildString {
            append("<article class=\"card\"><a class=\"preview\" href=\"${escape(url)}\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"Apri ${escape(link.title)}\">")
            if (image != null) append("<img src=\"${escape(image)}\" loading=\"lazy\" alt=\"\" referrerpolicy=\"no-referrer\">")
            append("<span class=\"play\">▶</span>${link.duration?.let { "<span class=\"duration\">${escape(it)}</span>" }.orEmpty()}${link.quality?.let { "<span class=\"quality\">${escape(it)}</span>" }.orEmpty()}</a><div class=\"cardbody\"><div class=\"cardmeta\"><span>${escape(domain)}</span><span>${escape(category)}</span></div>")
            append("<h3><a href=\"${escape(url)}\" target=\"_blank\" rel=\"noopener noreferrer\">${escape(link.title)}</a></h3><div class=\"keys\">")
            link.keys.take(4).forEach { append("<span>#${escape(it)}</span>") }
            if (link.keys.size > 4) append("<span>+${link.keys.size - 4}</span>")
            append("</div><a class=\"openlink\" href=\"${escape(url)}\" target=\"_blank\" rel=\"noopener noreferrer\">Apri il video <span>↗</span></a></div></article>")
            if (editing != null) {
                val actions = "<div class=\"editactions\"><a href=\"/link/edit?id=${link.id}\">Modifica link</a><a href=\"/link/delete?id=${link.id}\">Elimina</a></div>"
                val position = lastIndexOf("</div></article>")
                if (position >= 0) insert(position, actions)
            }
        }
    }

    private fun shell(title: String, body: String) = "<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escape(title)} · Linko</title><style>$CSS\n${WebEditing.css}</style></head><body>$body</body></html>"
    private fun randomToken() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun secureEqual(first: String, second: String) = MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
    private fun decodeForm(form: String): Map<String, String> = decodeParameters(form).toMap()
    private fun decodeParameters(form: String): List<Pair<String, String>> = try {
        form.split('&').filter { it.isNotBlank() }.map { part ->
            val values = part.split('=', limit = 2)
            URLDecoder.decode(values[0], "UTF-8") to URLDecoder.decode(values.getOrElse(1) { "" }, "UTF-8")
        }
    } catch (_: IllegalArgumentException) { throw HttpFailure(400) }

    private data class Request(val method: String, val target: String, val headers: Map<String, String>, val body: String)
    private data class Session(val expires: Long, val csrf: String)
    private data class Attempts(val since: Long, var failures: Int = 0)
    private class HttpFailure(val code: Int) : RuntimeException()

    companion object {
        internal fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        internal fun safeUrl(value: String): String? = runCatching {
            val uri = URI(value)
            value.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
        }.getOrNull()
        private val CSS = """
            .filterrow>.facet{flex:1;min-width:190px;align-self:stretch}.filterrow{align-items:flex-start}
            select{font:inherit;color:var(--ink);background:#19191f;border:1px solid #37333f;border-radius:10px;padding:10px 12px;max-width:100%;width:100%;margin-top:5px}select:focus-visible,summary:focus-visible{outline:2px solid var(--purple);outline-offset:4px}.filterform{max-width:900px}.filterrow{display:flex;align-items:end;gap:14px;flex-wrap:wrap;margin:17px 0}.filterrow>label{font-size:10px;color:var(--muted);min-width:165px;flex:1}.filterrow .favoritefilter{display:flex;align-items:center;gap:8px;min-width:125px;flex:0 1 auto;padding-bottom:12px;font-size:11px}.favoritefilter input,.keychoices input{width:16px;height:16px;padding:0;accent-color:var(--purple);flex:0 0 auto}.filterreset{font-size:11px;color:#cbbbf0;padding:12px 5px;white-space:nowrap}.keyfilter{border:1px solid var(--line);border-radius:12px;background:#17161d;padding:13px 15px}.keyfilter summary{font-size:12px;cursor:pointer;user-select:none}.keyfilter summary>span{float:right;color:#a698c2;font-size:10px;margin-top:2px}.keyfilter p{font-size:11px;color:var(--muted);margin:12px 0}.keychoices{display:flex;gap:8px;flex-wrap:wrap;max-height:250px;overflow:auto}.keychoices label{display:flex;align-items:center;gap:6px;padding:7px 10px;background:#211f29;border:1px solid #39333f;border-radius:9px;cursor:pointer;font-size:11px;color:#c5bfce;overflow-wrap:anywhere}.keychoices label:has(input:checked){border-color:#a58ad8;background:#32263f;color:#e1d2ff}.keychoices input{margin:0}.nokeys{font-size:11px;color:var(--muted)}.activefilters{display:flex;gap:7px;align-items:center;flex-wrap:wrap;margin-top:19px;font-size:10px}.activefilters>span{color:var(--muted);margin-right:4px}.activefilters>a{color:#d3bfff;border:1px solid #50405f;background:#251e30;border-radius:8px;padding:5px 8px;max-width:100%;overflow-wrap:anywhere}.activefilters b{margin-left:5px}.activefilters .clearfilters{background:none;border-color:transparent;color:var(--muted)}@media(max-width:600px){.filterform .search{flex-wrap:wrap}.filterform .search>input{flex:1 0 100%}.filterform .search>button{flex:1}.filterrow>label{min-width:130px}.filterrow .favoritefilter{padding-bottom:4px;flex-basis:100%}.keyfilter summary>span{float:none;display:block;margin:5px 0 0 17px}}
            :root{color-scheme:dark;--bg:#101014;--panel:#19191f;--line:#2a2933;--muted:#93919f;--ink:#f5f3fc;--purple:#b9a7ff}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;line-height:1.5}a{color:inherit;text-decoration:none}button,input{font:inherit}button,.button{cursor:pointer;border:0;border-radius:13px;padding:15px 20px;background:var(--purple);color:#231b39;font-weight:650}button:hover,.button:hover{background:#ccbeff}button:focus-visible,a:focus-visible,input:focus-visible{outline:2px solid var(--purple);outline-offset:4px}input{border:1px solid #37333f;background:#17161d;border-radius:12px;padding:15px 17px;color:var(--ink);width:100%;min-width:0}.brand{display:inline-flex;align-items:center;font-size:35px;font-weight:800;letter-spacing:-1.7px}.logo{display:inline-grid;place-items:center;width:37px;height:37px;margin-right:11px;border:2px solid var(--purple);border-radius:12px;color:var(--purple);font-size:27px;font-weight:500}.dot{color:var(--purple)}.eyebrow{display:block;color:var(--muted);font-size:10px;font-weight:650;letter-spacing:1.8px}.login{max-width:470px;margin:8vh auto;padding:25px}.login .brand{margin-bottom:65px}.login h1{font-size:45px;letter-spacing:-2px;line-height:1.15;margin:14px 0 20px}.login p{color:var(--muted);font-size:14px}.login form{margin-top:34px}.login label{display:block;font-size:12px;margin-bottom:10px}.login button{display:flex;justify-content:space-between;width:100%;margin-top:16px}.footnote{margin-top:27px;font-size:11px;color:var(--muted);text-align:center}.notice{font-size:11px!important;text-align:center;margin-top:20px}.error{color:#ffb7b7!important}.layout{min-height:100vh;display:grid;grid-template-columns:255px 1fr}aside{border-right:1px solid var(--line);padding:38px 23px;display:flex;flex-direction:column;position:sticky;top:0;height:100vh}.device{font-size:10px;color:var(--muted);margin:20px 0 48px;display:flex;align-items:center;gap:7px}.device i{width:6px;height:6px;border-radius:50%;background:#b8d3a4}.nav{display:flex;justify-content:space-between;align-items:center;padding:12px 13px;border-radius:10px;font-size:12px;margin:4px 0;color:#bbb8c8}.nav span{min-width:0;overflow-wrap:anywhere}.nav.active{background:#282134;color:#d7caff}.nav:hover{background:#24222c}.nav small{font-size:10px;margin-left:10px;opacity:.65}.nav b{font-size:10px;margin-right:8px}.divider{margin:26px 13px 12px}.sidebottom{margin-top:auto;padding:35px 12px 0}.sidebottom p{font-size:11px;color:var(--muted)}.logout{padding:10px 0;background:none;color:#c4b2ff;font-size:10px}.logout:hover{background:none;color:white}.collection{padding:47px 5vw 25px;max-width:1500px;width:100%;margin:auto}.topline{display:flex;justify-content:space-between;align-items:center;gap:10px}.readonly{color:#a99abd;border:1px solid #3b3148;border-radius:20px;padding:5px 10px;font-size:10px}.collection h1{font-size:46px;line-height:1.15;letter-spacing:-2px;margin:36px 0 12px;overflow-wrap:anywhere}.intro{font-size:13px;color:var(--muted);margin-bottom:30px}.search{display:flex;align-items:center;gap:10px;max-width:750px}.search input{font-size:12px;background:var(--panel);padding:14px 17px}.search button{font-size:11px;white-space:nowrap;padding:15px 22px}.sectiontitle{display:flex;align-items:center;justify-content:space-between;margin-top:38px;margin-bottom:19px}.sectiontitle h2{font-size:15px;font-weight:550;margin:0}.sectiontitle>span{font-size:10px;color:var(--muted)}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(235px,1fr));gap:22px}.card{border:1px solid var(--line);border-radius:16px;overflow:hidden;background:var(--panel);transition:transform .2s,border-color .2s}.card:hover{border-color:#62527e;transform:translateY(-3px)}.preview{position:relative;display:flex;align-items:center;justify-content:center;background:linear-gradient(140deg,#332843,#1b2633);aspect-ratio:16/9;overflow:hidden}.preview img{height:100%;width:100%;object-fit:cover;position:absolute;inset:0}.play{position:relative;font-size:13px;display:grid;place-items:center;border:1px solid #ffffff40;background:#10101480;backdrop-filter:blur(8px);width:39px;height:39px;border-radius:50%;padding-left:2px}.duration,.quality{position:absolute;background:#111116c9;border-radius:5px;padding:2px 5px;font-size:9px;bottom:9px;right:9px}.quality{right:auto;left:9px}.cardbody{padding:16px}.cardmeta{font-size:9px;color:var(--muted);display:flex;justify-content:space-between;gap:8px;overflow-wrap:anywhere}.cardmeta span:last-child{color:#bba8e2;text-align:right}.card h3{font-size:14px;line-height:1.5;margin:11px 0 13px;overflow-wrap:anywhere;font-weight:600;min-height:42px}.keys{display:flex;gap:5px;flex-wrap:wrap;min-height:22px}.keys span{padding:3px 7px;border:1px solid #39333f;border-radius:5px;font-size:9px;color:#b8afc7;overflow-wrap:anywhere}.openlink{display:flex;justify-content:space-between;align-items:center;border-top:1px solid var(--line);margin-top:17px;padding-top:12px;font-size:10px;color:#bfb2dd}.openlink span{font-size:17px}.empty{border:1px dashed #3a3344;border-radius:17px;text-align:center;padding:60px 20px;color:var(--muted)}.empty>span{font-size:40px;color:var(--purple)}.empty h2{font-size:19px;color:var(--ink)}.empty p{font-size:12px}footer{display:flex;justify-content:space-between;gap:15px;margin-top:60px;padding-top:20px;border-top:1px solid var(--line);color:#736d80;font-size:12px}footer>span{font-size:10px}@media(min-width:1700px){.cards{grid-template-columns:repeat(4,1fr)}}@media(max-width:850px){.layout{grid-template-columns:200px 1fr}aside{padding:30px 14px}.collection{padding:35px 25px}.collection h1{font-size:37px}.cards{grid-template-columns:repeat(auto-fill,minmax(215px,1fr))}}@media(max-width:600px){.layout{display:block}aside{height:auto;position:static;border:0;border-bottom:1px solid var(--line);padding:20px;display:flex;flex-flow:row wrap;gap:5px}.brand{font-size:27px;margin-right:auto}.logo{height:30px;width:30px;font-size:24px}.device{margin:0;align-self:center}.nav{margin:0;padding:8px 10px;font-size:11px;flex:0 0 auto}.nav small{margin-left:10px}aside>.eyebrow,.divider,.sidebottom{display:none}aside>.nav:first-of-type{clear:both}aside .brand{flex-basis:45%}.device{flex-basis:45%;justify-content:flex-end}.collection{padding:28px 20px}.collection h1{font-size:36px;margin-top:26px}.topline>.eyebrow{font-size:8px;letter-spacing:1.3px}.readonly{font-size:9px}.search{gap:7px}.search button{padding:15px 14px}.cards{grid-template-columns:1fr;gap:19px}.preview{aspect-ratio:16/8}.login{margin:4vh auto}.login h1{font-size:39px}.login .brand{margin-bottom:45px}footer{margin-top:35px}}
        """.trimIndent()
    }
}
