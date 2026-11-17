package it.linko.app.server

import it.linko.app.data.Category
import it.linko.app.data.CategoryRules
import it.linko.app.data.LibraryState
import it.linko.app.data.LinkMatcher
import it.linko.app.data.LinkMetadata
import it.linko.app.data.MetadataExtractor
import it.linko.app.data.VideoLink
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class WebEditResponse(val status: Int, val title: String = "Linko", val body: String = "", val location: String? = null)

/** Auth and CSRF are checked by LibraryHttpServer before any method here is reached. */
internal class WebEditing(
    private val writer: LibraryWriter,
    private val snapshot: () -> LibraryState,
    private val allowed: MutableSet<Long>,
    private val randomToken: () -> String
) : Closeable {
    private class Draft(val owner: String, val expires: Long, val items: MutableList<LinkMetadata>) {
        var active = true
        val futures = mutableListOf<Future<*>>()
    }
    private class AccessFailure(val status: Int) : RuntimeException()
    private val drafts = ConcurrentHashMap<String, Draft>()
    private val analyses = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, ArrayBlockingQueue(100),
        { runnable -> Thread(runnable, "linko-web-preview").apply { isDaemon = true } })
    private val writeLock = Any()

    fun handle(method: String, path: String, params: Map<String, String>, owner: String, csrf: String): WebEditResponse {
        return try {
            when (method to path) {
                "POST" to "/import" -> preview(params, owner, csrf)
                "GET" to "/import" -> review(params["draft"].orEmpty(), owner, csrf)
                "POST" to "/import/save" -> saveImport(params, owner, csrf)
                "POST" to "/import/cancel" -> cancelImport(params, owner)
                "GET" to "/link/edit" -> editLinkPage(params, csrf)
                "POST" to "/link/save" -> saveLink(params, csrf)
                "GET" to "/link/delete" -> deletePage(params, csrf)
                "POST" to "/link/delete" -> deleteLink(params)
                "GET" to "/categories" -> categoriesPage(params, csrf)
                "POST" to "/category/save" -> saveCategory(params, csrf)
                else -> throw AccessFailure(405)
            }
        } catch (failure: AccessFailure) {
            WebEditResponse(failure.status, "Pagina non disponibile", frame("Pagina non disponibile", "<p>La pagina o l’elemento richiesto non è disponibile in questa condivisione.</p><a class=\"button\" href=\"/\">Torna alla raccolta</a>"))
        } catch (failure: IllegalArgumentException) {
            WebEditResponse(400, "Controlla i dati", frame("Controlla i dati", error(failure.message ?: "Controlla i dati inseriti.") + "<a class=\"button\" href=\"/\">Torna alla raccolta</a>"))
        }
    }

    private fun sharedState(): LibraryState {
        val state = snapshot()
        val categories = state.categories.filter { it.id in allowed }
        val ids = categories.map { it.id }.toSet()
        return state.copy(categories = categories, links = state.links.filter { it.categoryId in ids })
    }
    private fun visibleLink(id: Long): VideoLink = sharedState().links.firstOrNull { it.id == id } ?: throw AccessFailure(404)
    private fun visibleCategory(id: Long): Category = sharedState().categories.firstOrNull { it.id == id } ?: throw AccessFailure(404)
    private fun targetCategory(id: Long) {
        if (sharedState().categories.none { it.id == id }) throw IllegalArgumentException("Scegli una delle categorie condivise.")
    }
    private fun protectUrls(links: List<VideoLink>) {
        val hidden = snapshot().links.filter { it.categoryId !in allowed }.map { it.url }.toSet()
        if (links.any { it.url in hidden }) throw AccessFailure(403)
    }
    private fun validateUrl(value: String): String = MetadataExtractor.extractUrls(value).singleOrNull()
        ?: throw IllegalArgumentException("Inserisci un solo indirizzo HTTP o HTTPS valido.")
    private fun keys(value: String): List<String> = value.lineSequence().map { it.trim().removePrefix("#").trim() }
        .filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }.toList().also { values ->
            require(values.size <= 100 && values.all { it.length <= 50 && it.none(Char::isISOControl) }) { "Massimo 100 keys, una per riga e con massimo 50 caratteri." }
        }
    private fun cleanTitle(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 500) { "Inserisci un titolo di massimo 500 caratteri." } }
    private fun string(value: String?, max: Int): String = value.orEmpty().also { require(it.length <= max) { "Uno dei campi è troppo lungo." } }
    private fun id(params: Map<String, String>, key: String = "id", default: Long? = null): Long {
        val raw = params[key]
        if (raw.isNullOrBlank()) return default ?: throw IllegalArgumentException("Identificativo non valido.")
        return (raw.toLongOrNull() ?: throw IllegalArgumentException("Identificativo non valido.")).also {
            require(it >= 0) { "Identificativo non valido." }
        }
    }

    private fun preview(params: Map<String, String>, owner: String, csrf: String): WebEditResponse {
        val raw = params["text"].orEmpty()
        val urls = try {
            MetadataExtractor.extractUrls(raw).also { require(it.isNotEmpty()) { "Nel testo non ho trovato link validi." }; require(it.size <= 100) { "Puoi organizzare al massimo 100 link per gruppo. Dividi il testo in gruppi più piccoli." } }
        } catch (failure: IllegalArgumentException) {
            return WebEditResponse(400, "Incolla i link", frame("Incolla i tuoi link", error(failure.message.orEmpty()) + composer(csrf, raw)))
        }
        protectUrls(urls.map { VideoLink(url = it, title = "Link") })
        val (token, draft) = synchronized(drafts) {
            cleanDrafts()
            require(drafts.size < 8) { "Hai già diverse importazioni aperte. Completa un gruppo o riprova tra qualche minuto." }
            val token = randomToken()
            val draft = Draft(owner, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10), urls.map { MetadataExtractor.fromUrl(it) }.toMutableList())
            drafts[token] = draft
            token to draft
        }
        if (params["skipAnalysis"] != "1") {
            urls.forEachIndexed { index, url ->
                runCatching {
                    val future = analyses.submit {
                        val result = runCatching { runBlocking { MetadataExtractor.extract(url) } }.getOrNull() ?: return@submit
                        synchronized(draft) { if (draft.active) draft.items[index] = result }
                    }
                    synchronized(draft) { draft.futures += future }
                }
            }
            // Give fast sites a chance; slow/blocked sites never prevent reviewing or saving the batch.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200)
            synchronized(draft) { draft.futures.toList() }.forEach { future ->
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }
            }
        }
        return review(token, owner, csrf)
    }
    private fun cleanDrafts() {
        val now = System.currentTimeMillis()
        drafts.entries.filter { it.value.expires <= now }.forEach { (token, draft) -> drafts.remove(token); stop(draft) }
    }
    private fun draft(token: String, owner: String): Draft {
        cleanDrafts()
        return drafts[token]?.takeIf { it.owner == owner } ?: throw AccessFailure(404)
    }
    private fun stop(draft: Draft) = synchronized(draft) { draft.active = false; draft.futures.forEach { it.cancel(true) } }
    private fun proposedCategory(item: LinkMetadata, state: LibraryState): Long {
        val metadata = listOf(item.title, item.description, item.keywords.joinToString(" "), item.suggestedCategory.orEmpty()).joinToString(" ")
        return CategoryRules.suggest(item.url, state.categories, metadata)?.id
            ?: state.categories.firstOrNull { it.name.equals(item.suggestedCategory, true) }?.id
            ?: state.categories.firstOrNull { it.id == 1L }?.id ?: state.categories.firstOrNull()?.id ?: 0
    }
    private fun review(token: String, owner: String, csrf: String, errorText: String? = null, submitted: Map<String, String>? = null): WebEditResponse {
        val draft = draft(token, owner)
        val state = sharedState()
        val items = synchronized(draft) { draft.items.toList() }
        val pending = synchronized(draft) { draft.futures.any { !it.isDone } }
        val body = buildString {
            append("<p class=\"intro\">${items.size} link distinti. Scegli una categoria per tutti oppure controllali uno alla volta.</p>")
            if (errorText != null) append(error(errorText))
            if (pending) append("<p class=\"hint\">Alcune anteprime stanno arrivando. Puoi salvare subito; <a href=\"/import?draft=${escape(token)}\">rileggi i dettagli prima di modificarli</a>.</p>")
            append("<form method=\"post\" action=\"/import/save\">${csrf(csrf)}${hidden("draft", token)}<div class=\"editpanel\"><label for=\"globalCategory\">Dove li mettiamo?</label><select id=\"globalCategory\" name=\"globalCategory\"><option value=\"\">Categorie dei singoli link</option>")
            state.categories.forEach { category -> append("<option value=\"${category.id}\"${if (submitted?.get("globalCategory") == category.id.toString()) " selected" else ""}>Tutti in ${escape(category.name)}</option>") }
            append("</select><p class=\"hint\">Le categorie proposte usano le tue regole su URL e metadati.</p></div><div class=\"importrows\">")
            items.forEachIndexed { index, item ->
                val values = submitted ?: emptyMap()
                val chosenCategory = values["category.$index"]?.toLongOrNull() ?: proposedCategory(item, state)
                val included = submitted == null || values["include.$index"] == "1"
                append("<section class=\"editpanel importrow\"><div class=\"rowhead\"><label class=\"checklabel\"><input type=\"checkbox\" name=\"include.$index\" value=\"1\"${if (included) " checked" else ""}> Salva questo link</label><span>${escape(LinkMatcher.domainOf(item.url).orEmpty())}</span></div>")
                append("<p class=\"urlhint\">${escape(item.url)}</p>")
                append(field("title.$index", "Titolo", values["title.$index"] ?: item.title, 500))
                append(categorySelect("category.$index", state.categories, chosenCategory))
                append("<div class=\"editgrid\">${keyField("keys.$index", values["keys.$index"] ?: item.keywords.joinToString("\n"))}${qualityField("quality.$index", values["quality.$index"] ?: item.quality.orEmpty())}</div>")
                // Keep notes short in the bulk form; full notes remain editable from the saved link.
                append(textarea("description.$index", "Descrizione o note", values["description.$index"] ?: item.description.take(1000), 10000, 2))
                append("</section>")
            }
            append("</div><div class=\"savebar\"><button type=\"submit\"${if (state.categories.isEmpty()) " disabled" else ""}>Salva i link selezionati ↗</button><button class=\"quietbutton\" type=\"submit\" formaction=\"/import/cancel\" formnovalidate>Annulla</button></div></form>")
            if (state.categories.isEmpty()) append("<p class=\"error\">Crea prima una categoria condivisa.</p><a class=\"button\" href=\"/categories\">Crea categoria</a>")
        }
        return WebEditResponse(if (errorText == null) 200 else 400, "Organizza i link", frame("Tutti i tuoi link, insieme.", body))
    }
    private fun saveImport(params: Map<String, String>, owner: String, csrf: String): WebEditResponse {
        val token = params["draft"].orEmpty()
        val draft = draft(token, owner)
        return try {
            synchronized(writeLock) {
                val items = synchronized(draft) { draft.items.toList() }
                val global = params["globalCategory"]?.takeIf(String::isNotBlank)?.toLongOrNull()
                if (!params["globalCategory"].isNullOrBlank()) require(global != null) { "Categoria non valida." }
                if (global != null) targetCategory(global)
                val links = items.mapIndexedNotNull { index, item ->
                    if (params["include.$index"] != "1") return@mapIndexedNotNull null
                    val category = global ?: id(params, "category.$index")
                    targetCategory(category)
                    VideoLink(url = item.url, title = cleanTitle(params["title.$index"].orEmpty()), description = string(params["description.$index"], 10000),
                        thumbnail = item.thumbnail, categoryId = category, keys = keys(params["keys.$index"].orEmpty()),
                        quality = string(params["quality.$index"], 30).trim().takeIf(String::isNotEmpty), duration = item.duration)
                }
                require(links.isNotEmpty()) { "Seleziona almeno un link da salvare." }
                protectUrls(links)
                writer.saveLinks(links)
                stop(draft)
                drafts.remove(token)
            }
            WebEditResponse(303, location = "/")
        } catch (failure: IllegalArgumentException) { review(token, owner, csrf, failure.message ?: "Controlla i dati.", params) }
    }
    private fun cancelImport(params: Map<String, String>, owner: String): WebEditResponse {
        val token = params["draft"].orEmpty()
        val draft = draft(token, owner)
        drafts.remove(token)
        stop(draft)
        return WebEditResponse(303, location = "/")
    }
    fun discardOwner(owner: String) {
        drafts.entries.filter { it.value.owner == owner }.forEach { (token, draft) -> drafts.remove(token); stop(draft) }
    }

    private fun editLinkPage(params: Map<String, String>, csrf: String, errorText: String? = null, posted: Map<String, String>? = null): WebEditResponse {
        val linkId = id(params, default = 0)
        val link = if (linkId > 0) visibleLink(linkId) else null
        val values = posted ?: emptyMap()
        val state = sharedState()
        val chosen = values["categoryId"]?.toLongOrNull() ?: link?.categoryId ?: state.categories.firstOrNull()?.id ?: 0
        val body = buildString {
            if (errorText != null) append(error(errorText))
            append("<form method=\"post\" action=\"/link/save\" class=\"editpanel\">${csrf(csrf)}${hidden("id", linkId.toString())}")
            append(field("url", "Indirizzo del video", values["url"] ?: link?.url.orEmpty(), 8192, "url"))
            append(field("title", "Titolo", values["title"] ?: link?.title.orEmpty(), 500))
            append(categorySelect("categoryId", state.categories, chosen))
            append("<div class=\"editgrid\">${keyField("keys", values["keys"] ?: link?.keys?.joinToString("\n").orEmpty())}${qualityField("quality", values["quality"] ?: link?.quality.orEmpty())}</div>")
            append(textarea("description", "Descrizione o note", values["description"] ?: link?.description.orEmpty(), 10000, 4))
            append(field("duration", "Durata · facoltativa", values["duration"] ?: link?.duration.orEmpty(), 50, required = false))
            val favorite = if (posted == null) link?.favorite == true else values["favorite"] == "1"
            append("<label class=\"checklabel\"><input type=\"checkbox\" name=\"favorite\" value=\"1\"${if (favorite) " checked" else ""}> Preferito</label><div class=\"savebar\"><button${if (state.categories.isEmpty()) " disabled" else ""}>Salva modifiche ↗</button><a href=\"/\">Annulla</a></div></form>")
        }
        return WebEditResponse(if (errorText == null) 200 else 400, "Modifica link", frame(if (link == null) "Un nuovo link." else "Ogni link, al suo posto.", body))
    }
    private fun saveLink(params: Map<String, String>, csrf: String): WebEditResponse {
        val linkId = id(params, default = 0)
        val old = if (linkId > 0) visibleLink(linkId) else null
        return try {
            synchronized(writeLock) {
                val url = validateUrl(params["url"].orEmpty())
                val category = id(params, "categoryId")
                targetCategory(category)
                val link = VideoLink(id = old?.id ?: 0, url = url, title = cleanTitle(params["title"].orEmpty()), description = string(params["description"], 10000),
                    thumbnail = old?.thumbnail, categoryId = category, keys = keys(params["keys"].orEmpty()), quality = string(params["quality"], 30).trim().takeIf(String::isNotEmpty),
                    duration = string(params["duration"], 50).trim().takeIf(String::isNotEmpty), favorite = params["favorite"] == "1", createdAt = old?.createdAt ?: System.currentTimeMillis())
                protectUrls(listOf(link))
                writer.saveLinks(listOf(link))
            }
            WebEditResponse(303, location = "/")
        } catch (failure: IllegalArgumentException) { editLinkPage(params, csrf, failure.message, params) }
    }
    private fun deletePage(params: Map<String, String>, csrf: String): WebEditResponse {
        val link = visibleLink(id(params))
        return WebEditResponse(200, "Elimina link", frame("Eliminare questo link?", "<div class=\"editpanel\"><h2>${escape(link.title)}</h2><p>Il link verrà rimosso dalla raccolta del telefono.</p><form method=\"post\" action=\"/link/delete\">${csrf(csrf)}${hidden("id", link.id.toString())}<div class=\"savebar\"><button>Elimina link</button><a href=\"/\">Annulla</a></div></form></div>"))
    }
    private fun deleteLink(params: Map<String, String>): WebEditResponse {
        synchronized(writeLock) { writer.deleteLink(visibleLink(id(params)).id) }
        return WebEditResponse(303, location = "/")
    }

    private fun categoriesPage(params: Map<String, String>, csrf: String, errorText: String? = null, posted: Map<String, String>? = null): WebEditResponse {
        val categoryId = id(params, default = 0)
        val category = if (categoryId > 0) visibleCategory(categoryId) else null
        val values = posted ?: emptyMap()
        val state = sharedState()
        val body = buildString {
            append("<p class=\"intro\">Crea una categoria o modifica nome, colore e regole. Le nuove categorie saranno condivise anche in questa sessione.</p>")
            append("<div class=\"categorylinks\"><a href=\"/categories\" class=\"pill\">+ Nuova categoria</a>")
            state.categories.forEach { append("<a class=\"pill\" href=\"/categories?id=${it.id}\">${escape(it.name)}</a>") }
            append("</div>")
            if (errorText != null) append(error(errorText))
            append("<form method=\"post\" action=\"/category/save\" class=\"editpanel\">${csrf(csrf)}${hidden("id", categoryId.toString())}")
            append(field("name", "Nome categoria", values["name"] ?: category?.name.orEmpty(), 60))
            val icon = values["icon"] ?: category?.icon ?: "folder"
            append("<div class=\"editgrid\"><div><label for=\"icon\">Icona</label><select id=\"icon\" name=\"icon\">")
            linkedMapOf("folder" to "Cartella", "food" to "Cucina", "code" to "Tecnologia", "travel" to "Viaggi", "spark" to "Ispirazione", "music" to "Musica", "sport" to "Sport", "learn" to "Studio").forEach { (value, name) ->
                append("<option value=\"$value\"${if (icon == value) " selected" else ""}>${escape(name)}</option>")
            }
            if (icon !in setOf("folder", "food", "code", "travel", "spark", "music", "sport", "learn")) append("<option value=\"${escape(icon)}\" selected>Icona attuale</option>")
            append("</select></div><div><label for=\"color\">Colore</label><input id=\"color\" name=\"color\" type=\"color\" value=\"${escape(values["color"] ?: "#%06X".format((category?.color ?: 0xFFB9A7FF) and 0xFFFFFF))}\"></div></div>")
            append(textarea("matchKeywords", "Parole per assegnare la categoria", values["matchKeywords"] ?: category?.matchKeywords?.joinToString(", ").orEmpty(), 10000, 3))
            append("<p class=\"hint\">Separa con virgole, punto e virgola o una nuova riga. Basta una parola nell’URL o nei metadati. Es.: black, ebony. I simboli separano le parole.</p><div class=\"savebar\"><button>Salva categoria ↗</button><a href=\"/\">Torna ai link</a></div></form>")
        }
        return WebEditResponse(if (errorText == null) 200 else 400, "Categorie", frame("Le tue categorie.", body))
    }
    private fun saveCategory(params: Map<String, String>, csrf: String): WebEditResponse {
        val categoryId = id(params, default = 0)
        val old = if (categoryId > 0) visibleCategory(categoryId) else null
        return try {
            synchronized(writeLock) {
                val name = string(params["name"], 60).trim().replace(Regex("\\s+"), " ")
                require(name.isNotEmpty() && name.none(Char::isISOControl)) { "Inserisci un nome valido." }
                // Check all names, but return only a generic conflict instead of revealing hidden categories.
                require(snapshot().categories.none { it.id != categoryId && it.name.equals(name, true) }) { "Nome già utilizzato. Scegline uno diverso." }
                val aliases = params["matchKeywords"].orEmpty().split(Regex("[,;\\r\\n]+"))
                    .map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }
                require(aliases.size <= 100 && aliases.all { it.length <= 60 && it.none(Char::isISOControl) }) { "Massimo 100 parole o frasi, ciascuna di massimo 60 caratteri." }
                val colorText = params["color"] ?: "#%06X".format((old?.color ?: 0xFFB9A7FF) and 0xFFFFFF)
                require(Regex("#[0-9a-fA-F]{6}").matches(colorText)) { "Scegli un colore valido." }
                val color = 0xFF000000 or colorText.drop(1).toLong(16)
                val icon = string(params["icon"] ?: old?.icon ?: "folder", 30)
                val savedId = writer.saveCategory(Category(categoryId, name, icon, color, aliases))
                allowed.add(savedId)
            }
            WebEditResponse(303, location = "/categories")
        } catch (failure: IllegalArgumentException) { categoriesPage(params, csrf, failure.message, params) }
    }

    override fun close() {
        drafts.values.forEach(::stop)
        drafts.clear()
        analyses.shutdownNow()
    }

    companion object {
        val routes = setOf("/import", "/import/save", "/import/cancel", "/link/edit", "/link/save", "/link/delete", "/categories", "/category/save")
        private fun escape(value: String) = LibraryHttpServer.escape(value)
        private fun hidden(name: String, value: String) = "<input type=\"hidden\" name=\"${escape(name)}\" value=\"${escape(value)}\">"
        private fun csrf(value: String) = hidden("csrf", value)
        private fun error(value: String) = "<p class=\"error formerror\" role=\"alert\">${escape(value)}</p>"
        private fun field(name: String, label: String, value: String, max: Int, type: String = "text", required: Boolean = true) =
            "<label for=\"${escape(name)}\">${escape(label)}</label><input id=\"${escape(name)}\" name=\"${escape(name)}\" type=\"$type\" value=\"${escape(value)}\" maxlength=\"$max\"${if (required) " required" else ""}>"
        private fun textarea(name: String, label: String, value: String, max: Int, rows: Int) =
            "<label for=\"${escape(name)}\">${escape(label)}</label><textarea id=\"${escape(name)}\" name=\"${escape(name)}\" maxlength=\"$max\" rows=\"$rows\">${escape(value)}</textarea>"
        private fun keyField(name: String, value: String) = "<div>${textarea(name, "Parole chiave · una per riga", value, 6000, 3)}</div>"
        private fun categorySelect(name: String, categories: List<Category>, selected: Long) = buildString {
            append("<label for=\"${escape(name)}\">Categoria</label><select id=\"${escape(name)}\" name=\"${escape(name)}\" required>")
            categories.forEach { append("<option value=\"${it.id}\"${if (it.id == selected) " selected" else ""}>${escape(it.name)}</option>") }
            append("</select>")
        }
        private fun qualityField(name: String, value: String) = buildString {
            append("<div><label for=\"${escape(name)}\">Qualità</label><select id=\"${escape(name)}\" name=\"${escape(name)}\"><option value=\"\"${if (value.isBlank()) " selected" else ""}>Non indicata</option>")
            (listOf("360p", "480p", "720p", "1080p", "1440p", "4K") + listOf(value).filter(String::isNotBlank)).distinctBy { it.lowercase(Locale.ROOT) }.forEach {
                append("<option value=\"${escape(it)}\"${if (it.equals(value, true)) " selected" else ""}>${escape(it)}</option>")
            }
            append("</select></div>")
        }
        fun composer(csrf: String, value: String = "") = """
            <form method="post" action="/import" class="composer">${csrf(csrf)}
            <label for="paste-links">Incolla link o un testo</label><div class="composerrow"><textarea id="paste-links" name="text" rows="2" placeholder="1. https://… un commento  2. example.com/video…" maxlength="262144" required>${escape(value)}</textarea><button type="submit">Organizza ↗</button></div>
            <label class="checklabel"><input type="checkbox" name="skipAnalysis" value="1"> Salva tempo: ricava i dettagli soltanto dagli indirizzi</label><p class="hint">Fino a 100 link per gruppo. I duplicati vengono rimossi.</p></form>
        """.trimIndent()
        private fun frame(title: String, content: String) = "<main class=\"editorpage\"><div class=\"editortop\"><a class=\"brand\" href=\"/\"><span class=\"logo\">↗</span>linko<span class=\"dot\">.</span></a><a href=\"/\">← Torna alla raccolta</a></div><h1>${escape(title)}</h1>$content</main>"
        val css = """
            aside{overflow-y:auto;overscroll-behavior:contain}.quietbutton{background:none;color:var(--muted);font-size:12px;font-weight:400;padding:12px 3px}.quietbutton:hover{background:none;color:var(--ink)}
            textarea{font:inherit;width:100%;min-width:0;resize:vertical;border:1px solid #37333f;background:#17161d;border-radius:12px;padding:14px 16px;color:var(--ink)}textarea:focus-visible{outline:2px solid var(--purple);outline-offset:3px}.editorpage{max-width:960px;margin:0 auto;padding:35px 24px 60px}.editortop{display:flex;align-items:center;justify-content:space-between;gap:20px}.editortop>a:last-child{font-size:12px;color:var(--muted)}.editorpage h1{font-size:38px;line-height:1.2;letter-spacing:-1.2px;margin:36px 0 18px}.editpanel{border:1px solid var(--line);background:var(--panel);border-radius:16px;padding:20px;margin:16px 0}.editpanel>label,.editgrid label{font-size:12px;display:block;margin:15px 0 7px}.editpanel>label:first-child{margin-top:0}.editgrid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.editpanel select{margin:0}.editpanel input[type=color]{height:48px;padding:5px}.hint{font-size:11px;color:var(--muted);line-height:1.6}.hint a{color:var(--purple);text-decoration:underline}.savebar{display:flex;gap:18px;align-items:center;position:sticky;bottom:0;padding:16px 0;background:var(--bg);margin-top:16px}.savebar>a{font-size:12px;color:var(--muted)}.editpanel .savebar{background:var(--panel)}button:disabled{opacity:.45;cursor:default}.importrows{display:grid;gap:2px}.rowhead{display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:11px;color:var(--muted)}.checklabel{display:flex!important;gap:8px;align-items:center;font-size:12px!important}.checklabel input{width:17px;height:17px;accent-color:var(--purple);margin:0;padding:0}.urlhint{font-size:10px;color:var(--muted);overflow-wrap:anywhere;line-height:1.5}.categorylinks{display:flex;flex-wrap:wrap;gap:8px;margin:20px 0}.pill{font-size:12px;padding:8px 11px;border:1px solid var(--line);border-radius:9px;background:var(--panel);color:#d3bfff}.formerror{border:1px solid #77464b;background:#2e2026;padding:13px 15px;border-radius:12px;font-size:13px;color:#ffb4ab}.composer{position:sticky;bottom:12px;background:#1b1822f5;border:1px solid #4c3d60;backdrop-filter:blur(10px);border-radius:17px;padding:16px;margin-top:35px;z-index:2}.composer>label:first-of-type{font-size:12px;font-weight:600;display:block;margin-bottom:9px}.composerrow{display:flex;gap:10px;align-items:stretch}.composerrow textarea{font-size:12px}.composerrow button{font-size:12px;white-space:nowrap}.composer .checklabel{margin-top:10px;color:var(--muted);font-size:11px!important}.composer .hint{margin:7px 0 0}.editactions{display:flex;gap:14px;margin-top:12px;font-size:10px;color:#c9b6eb}.editactions>a:last-child{color:#c399a9}.managecategories{display:inline-flex;margin-bottom:18px;color:var(--purple);font-size:12px}.smartnav{font-size:10px;margin-left:12px;padding:7px 12px}.smartnav small{font-size:9px}@media(max-width:600px){.editorpage{padding:25px 18px}.editorpage h1{font-size:30px}.editgrid{grid-template-columns:1fr;gap:0}.editpanel{padding:15px}.composer{bottom:6px;padding:12px}.composerrow{flex-direction:column}.composerrow button{padding:11px}.editortop .brand{font-size:26px}.editortop>a:last-child{font-size:10px}}
        """.trimIndent()
    }
}
