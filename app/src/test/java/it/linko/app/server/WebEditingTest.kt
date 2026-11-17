package it.linko.app.server

import it.linko.app.data.Category
import it.linko.app.data.LibraryState
import it.linko.app.data.VideoLink
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder

/** End-to-end requests against the real socket server with a deterministic in-memory writer. */
class WebEditingTest {
    @Test fun `read only session cannot call any write or edit route`() = withServer(editable = false) { server, store ->
        val session = login(server)
        val gallery = request(server, cookie = session.cookie)
        assertFalse(gallery.body.contains("action=\"/import\""))
        for (path in listOf("/import", "/import/save", "/link/save", "/link/delete", "/category/save")) {
            assertEquals(path, 403, post(server, path, session, "id" to "1", "text" to "example.com/new-video").code)
        }
        for (path in listOf("/categories", "/link/edit?id=1", "/link/delete?id=1")) {
            assertEquals(path, 403, request(server, path, cookie = session.cookie).code)
        }
        assertEquals(0, store.writeCalls)
    }

    @Test fun `write toggle without a writer cannot mutate library`() = withServer(editable = true, provideWriter = false) { server, store ->
        val session = login(server)
        assertEquals(403, post(server, "/link/delete", session, "id" to "1").code)
        assertEquals(0, store.writeCalls)
    }

    @Test fun `mutations require authentication and the current session csrf`() = withServer { server, store ->
        val anonymous = request(server, "/link/delete", "POST", listOf("id" to "1"))
        assertTrue(anonymous.code in setOf(200, 401, 403, 303))
        assertEquals(0, store.writeCalls)
        val session = login(server)
        assertEquals(403, request(server, "/link/delete", "POST", listOf("id" to "1"), session.cookie).code)
        assertEquals(403, request(server, "/link/delete", "POST", listOf("id" to "1", "csrf" to "wrong"), session.cookie).code)
        val foreign = login(server)
        assertEquals(403, request(server, "/link/delete", "POST", listOf("id" to "1", "csrf" to foreign.csrf), session.cookie).code)
        assertEquals(0, store.writeCalls)
    }

    @Test fun `cross origin write is rejected even with a valid csrf token`() = withServer { server, store ->
        val session = login(server)
        val fields = listOf("csrf" to session.csrf, "id" to "1")
        assertEquals(403, request(server, "/link/delete", "POST", fields, session.cookie, "Origin: http://attacker.test\r\n").code)
        assertEquals(403, request(server, "/link/delete", "POST", fields, session.cookie, "Origin: null\r\n").code)
        assertEquals(0, store.writeCalls)
    }

    @Test fun `visible link editing persists values with browser style headers`() = withServer { server, store ->
        val session = login(server)
        val editor = request(server, "/link/edit?id=1", cookie = session.cookie)
        assertEquals(200, editor.code)
        assertTrue(editor.body.contains("VISIBILE"))
        assertFalse(editor.body.contains("CATEGORIA_PRIVATA"))
        val saved = post(server, "/link/save", session,
            "id" to "1", "url" to "https://example.com/edited-food", "title" to "Nuovo <titolo>",
            "description" to "Una descrizione & note", "categoryId" to "1", "keys" to "Asian\nfood", "quality" to "720p", "duration" to "2:00", "favorite" to "1")
        assertEquals(303, saved.code)
        val link = store.snapshot().links.single { it.id == 1L }
        assertEquals("Nuovo <titolo>", link.title)
        assertEquals(listOf("Asian", "food"), link.keys)
        assertEquals("720p", link.quality)
        assertTrue(link.favorite)
        assertEquals(123L, link.createdAt)
        val gallery = request(server, cookie = session.cookie)
        assertTrue(gallery.body.contains("Nuovo &lt;titolo&gt;"))
        assertFalse(gallery.body.contains("Nuovo <titolo>"))
        assertTrue(gallery.headers.contains("Referrer-Policy: same-origin"))
    }

    @Test fun `hidden link ids and hidden target categories cannot be edited`() = withServer { server, store ->
        val session = login(server)
        for (path in listOf("/link/edit?id=2", "/link/delete?id=2", "/categories?id=2")) {
            val result = request(server, path, cookie = session.cookie)
            assertEquals(path, 404, result.code)
            assertFalse(result.body.contains("LINK_PRIVATO"))
            assertFalse(result.body.contains("CATEGORIA_PRIVATA"))
        }
        assertEquals(404, post(server, "/link/delete", session, "id" to "2").code)
        assertEquals(404, post(server, "/link/save", session, "id" to "2", "url" to "https://private.example/secret", "title" to "Changed", "categoryId" to "1").code)
        val moved = post(server, "/link/save", session, "id" to "1", "url" to "https://example.com/visible", "title" to "Moved", "categoryId" to "2")
        assertTrue(moved.code in setOf(400, 403, 404))
        assertEquals(0, store.writeCalls)
        assertEquals(2, store.snapshot().links.size)
    }

    @Test fun `hidden url collision cannot move or overwrite a private video`() = withServer { server, store ->
        val session = login(server)
        val result = post(server, "/link/save", session, "id" to "0", "url" to "https://private.example/secret", "title" to "Override private", "categoryId" to "1")
        assertEquals(403, result.code)
        assertEquals(403, post(server, "/link/save", session, "id" to "1", "url" to "https://private.example/secret", "title" to "Colliding edit", "categoryId" to "1").code)
        assertEquals(0, store.writeCalls)
        assertEquals("LINK_PRIVATO", store.snapshot().links.single { it.id == 2L }.title)
        assertFalse(request(server, cookie = session.cookie).body.contains("LINK_PRIVATO"))
    }

    @Test fun `category rules can be edited and a new category enters only this share scope`() = withServer { server, store ->
        val session = login(server)
        val edited = post(server, "/category/save", session, "id" to "1", "name" to "Raccolta rinominata", "icon" to "food", "color" to "#BEAAFF", "matchKeywords" to "black, ebony")
        assertEquals(303, edited.code)
        val existing = store.snapshot().categories.single { it.id == 1L }
        assertEquals("Raccolta rinominata", existing.name)
        assertEquals(listOf("black", "ebony"), existing.matchKeywords)
        val created = post(server, "/category/save", session, "id" to "0", "name" to "Nuova categoria", "icon" to "folder", "color" to "#A4D5BF", "matchKeywords" to "travel\nmountain")
        assertEquals(303, created.code)
        val category = store.snapshot().categories.single { it.name == "Nuova categoria" }
        val gallery = request(server, cookie = session.cookie)
        assertTrue(gallery.body.contains("Nuova categoria"))
        assertFalse(gallery.body.contains("CATEGORIA_PRIVATA"))
        assertEquals(303, post(server, "/link/save", session, "id" to "0", "url" to "https://example.com/new-category-video", "title" to "NEW_CATEGORY_VIDEO", "categoryId" to category.id.toString()).code)
        assertEquals(category.id, store.snapshot().links.single { it.title == "NEW_CATEGORY_VIDEO" }.categoryId)
    }

    @Test fun `category creation cannot reveal an existing hidden category`() = withServer { server, store ->
        val session = login(server)
        assertEquals(400, post(server, "/category/save", session, "id" to "0", "name" to "CATEGORIA_PRIVATA", "icon" to "folder", "color" to "#BEAAFF").code)
        assertEquals(400, post(server, "/category/save", session, "id" to "1", "name" to "CATEGORIA_PRIVATA", "icon" to "folder", "color" to "#BEAAFF").code)
        assertEquals(404, post(server, "/category/save", session, "id" to "2", "name" to "Renamed private", "icon" to "folder", "color" to "#BEAAFF").code)
        assertEquals(0, store.writeCalls)
        assertFalse(request(server, cookie = session.cookie).body.contains("CATEGORIA_PRIVATA"))
        assertEquals("CATEGORIA_PRIVATA", store.snapshot().categories.single { it.id == 2L }.name)
    }

    @Test fun `prose bulk preview deduplicates and commits reviewed per link categories atomically`() = withServer { server, store ->
        val session = login(server)
        val text = "1. example.com/asian-food-480p commento\n2. https://example.org/mountain-travel-720p altra frase\n3. https://example.com/asian-food-480p"
        val preview = post(server, "/import", session, "text" to text, "skipAnalysis" to "1")
        assertEquals(200, preview.code)
        assertEquals(0, store.writeCalls)
        val draft = hidden(preview.body, "draft")
        assertTrue(preview.body.contains("Asian food 480p"))
        assertTrue(preview.body.contains("Mountain travel 720p"))
        val saved = post(server, "/import/save", session,
            "draft" to draft, "include.0" to "1", "title.0" to "Primo video", "category.0" to "1", "keys.0" to "asian\nfood", "quality.0" to "480p",
            "include.1" to "1", "title.1" to "Secondo video", "category.1" to "3", "keys.1" to "mountain\ntravel", "quality.1" to "720p")
        assertEquals(303, saved.code)
        assertEquals(1, store.linkSaveCalls)
        val links = store.snapshot().links
        assertEquals(4, links.size)
        assertEquals(1L, links.single { it.title == "Primo video" }.categoryId)
        assertEquals(3L, links.single { it.title == "Secondo video" }.categoryId)
        assertEquals(2, store.lastSavedBatch.size)
    }

    @Test fun `one global category applies to the whole reviewed batch`() = withServer { server, store ->
        val session = login(server)
        val preview = post(server, "/import", session, "text" to "example.com/one-video example.org/second-video", "skipAnalysis" to "1")
        val draft = hidden(preview.body, "draft")
        assertEquals(303, post(server, "/import/save", session,
            "draft" to draft, "globalCategory" to "3", "include.0" to "1", "include.1" to "1", "title.0" to "Global first", "title.1" to "Global second").code)
        assertEquals(1, store.linkSaveCalls)
        assertTrue(store.lastSavedBatch.all { it.categoryId == 3L })
    }

    @Test fun `a malformed bulk row cannot partially commit other rows`() = withServer { server, store ->
        val session = login(server)
        val preview = post(server, "/import", session, "text" to "example.com/first-video example.org/second-video", "skipAnalysis" to "1")
        val draft = hidden(preview.body, "draft")
        val failed = post(server, "/import/save", session,
            "draft" to draft, "include.0" to "1", "title.0" to "Valid row", "category.0" to "1",
            "include.1" to "1", "title.1" to "Invalid target", "category.1" to "2")
        assertTrue(failed.code in setOf(400, 403, 404))
        assertEquals(0, store.writeCalls)
        assertEquals(2, store.snapshot().links.size)
    }

    @Test fun `bulk draft belongs to the authenticated session that created it`() = withServer { server, store ->
        val creator = login(server)
        val preview = post(server, "/import", creator, "text" to "example.com/new-video", "skipAnalysis" to "1")
        val draft = hidden(preview.body, "draft")
        val other = login(server)
        assertEquals(404, request(server, "/import?draft=" + encode(draft), cookie = other.cookie).code)
        assertEquals(404, post(server, "/import/save", other, "draft" to draft, "include.0" to "1", "category.0" to "1", "title.0" to "Stolen draft").code)
        assertEquals(0, store.writeCalls)
    }

    @Test fun `bulk preview rejects empty and over limit lists instead of saving a prefix`() = withServer { server, store ->
        val session = login(server)
        val empty = post(server, "/import", session, "text" to "Solo testo senza indirizzi", "skipAnalysis" to "1")
        assertEquals(400, empty.code)
        val tooMany = (1..101).joinToString("\n") { "https://example.com/video-$it" }
        val overflow = post(server, "/import", session, "text" to tooMany, "skipAnalysis" to "1")
        assertEquals(400, overflow.code)
        assertTrue(overflow.body.contains("100"))
        assertEquals(0, store.writeCalls)
        assertEquals(2, store.snapshot().links.size)
    }

    @Test fun `bulk save cannot overwrite a url in an unshared category`() = withServer { server, store ->
        val session = login(server)
        val preview = post(server, "/import", session, "text" to "https://private.example/secret", "skipAnalysis" to "1")
        // Rejecting at preview is also valid and avoids disclosing the hidden record.
        if (preview.code == 200) {
            assertEquals(403, post(server, "/import/save", session,
                "draft" to hidden(preview.body, "draft"), "include.0" to "1", "title.0" to "Private overwrite", "category.0" to "1").code)
        } else assertEquals(403, preview.code)
        assertEquals(0, store.writeCalls)
        assertEquals(2L, store.snapshot().links.single { it.url == "https://private.example/secret" }.categoryId)
    }

    @Test fun `deleting a visible link requires its explicit post action`() = withServer { server, store ->
        val session = login(server)
        assertEquals(200, request(server, "/link/delete?id=1", cookie = session.cookie).code)
        assertEquals(0, store.writeCalls)
        assertEquals(303, post(server, "/link/delete", session, "id" to "1").code)
        assertFalse(store.snapshot().links.any { it.id == 1L })
        assertTrue(store.snapshot().links.any { it.id == 2L })
        assertEquals(1, store.writeCalls)
    }

    @Test fun `smart subcollections link to shared repeated keys and honor the saved threshold`() = withServer { server, store ->
        store.seedLinks(listOf(
            VideoLink(id = 10, url = "https://example.com/food-two", title = "SECOND_SHARED", categoryId = 1, keys = listOf("food")),
            VideoLink(id = 11, url = "https://example.com/food-three", title = "THIRD_SHARED", categoryId = 1, keys = listOf("food")),
            VideoLink(id = 12, url = "https://private.example/hidden-two", title = "PRIVATE_TWO", categoryId = 2, keys = listOf("private-key")),
            VideoLink(id = 13, url = "https://private.example/hidden-three", title = "PRIVATE_THREE", categoryId = 2, keys = listOf("private-key"))
        ))
        val session = login(server)
        fun smartFoodLink(html: String): Boolean = Jsoup.parse(html).select("a[href]").any { anchor ->
            val href = anchor.attr("href")
            href.contains("category=1") && href.contains("key=food")
        }
        val gallery = request(server, cookie = session.cookie)
        assertTrue(smartFoodLink(gallery.body))
        assertFalse(gallery.body.contains("private-key"))
        assertFalse(gallery.body.contains("PRIVATE_TWO"))
        val filtered = request(server, "/?key=previous-group&quality=720p", cookie = session.cookie)
        val sibling = Jsoup.parse(filtered.body).select("a.smartnav").first { it.attr("href").contains("key=food") }.attr("href")
        assertFalse(sibling.contains("previous-group"))
        assertTrue(sibling.contains("quality=720p"))
        store.setThreshold(4)
        assertFalse(smartFoodLink(request(server, cookie = session.cookie).body))
        assertEquals(0, store.writeCalls)
    }

    private class Store : LibraryWriter {
        private var state = LibraryState(
            categories = listOf(Category(1, "Visibile"), Category(2, "CATEGORIA_PRIVATA"), Category(3, "Altra raccolta")),
            links = listOf(
                VideoLink(id = 1, url = "https://example.com/visible", title = "VISIBILE", categoryId = 1, keys = listOf("food"), createdAt = 123),
                VideoLink(id = 2, url = "https://private.example/secret", title = "LINK_PRIVATO", categoryId = 2, keys = listOf("private-key"), createdAt = 456)
            )
        )
        var writeCalls = 0
        var linkSaveCalls = 0
        var lastSavedBatch = emptyList<VideoLink>()
        @Synchronized fun snapshot(): LibraryState = state
        @Synchronized fun seedLinks(links: List<VideoLink>) { state = state.copy(links = state.links + links) }
        @Synchronized fun setThreshold(value: Int) { state = state.copy(smartThreshold = value) }
        @Synchronized override fun saveLinks(links: List<VideoLink>): Int {
            require(links.all { it.title.isNotBlank() && state.categories.any { c -> c.id == it.categoryId } })
            val updated = state.links.toMutableList()
            var nextId = (updated.maxOfOrNull { it.id } ?: 0) + 1
            links.forEach { link ->
                val existing = if (link.id > 0) updated.indexOfFirst { it.id == link.id } else updated.indexOfFirst { it.url == link.url }
                if (existing >= 0) updated[existing] = link.copy(id = updated[existing].id)
                else updated.add(link.copy(id = nextId++))
            }
            state = state.copy(links = updated)
            writeCalls++
            linkSaveCalls++
            lastSavedBatch = links
            return links.size
        }
        @Synchronized override fun deleteLink(id: Long) {
            state = state.copy(links = state.links.filterNot { it.id == id })
            writeCalls++
        }
        @Synchronized override fun saveCategory(category: Category): Long {
            state.categories.firstOrNull { category.id == 0L && it.name.equals(category.name, true) }?.let { return it.id }
            val id = category.id.takeIf { it > 0 } ?: ((state.categories.maxOfOrNull { it.id } ?: 0) + 1)
            state = state.copy(categories = state.categories.filterNot { it.id == id } + category.copy(id = id))
            writeCalls++
            return id
        }
    }

    private fun withServer(editable: Boolean = true, provideWriter: Boolean = true, block: (LibraryHttpServer, Store) -> Unit) {
        val store = Store()
        LibraryHttpServer("correct-password", setOf(1, 3), store::snapshot,
            port = 0, bindAddress = InetAddress.getByName("127.0.0.1"), allowEditing = editable,
            writer = store.takeIf { provideWriter }).use { server ->
            server.start()
            block(server, store)
        }
    }

    private data class Session(val cookie: String, val csrf: String)
    private data class Response(val code: Int, val headers: String, val body: String)
    private fun login(server: LibraryHttpServer): Session {
        val result = request(server, "/login", "POST", listOf("password" to "correct-password"))
        assertEquals(303, result.code)
        val cookie = Regex("Set-Cookie: ([^;]+)").find(result.headers)!!.groupValues[1]
        val page = request(server, cookie = cookie)
        assertEquals(200, page.code)
        return Session(cookie, hidden(page.body, "csrf"))
    }
    private fun hidden(html: String, name: String): String = Jsoup.parse(html).selectFirst("input[name=$name]")?.attr("value")
        ?: error("Missing form field $name in $html")
    private fun post(server: LibraryHttpServer, path: String, session: Session, vararg fields: Pair<String, String>): Response =
        request(server, path, "POST", listOf("csrf" to session.csrf) + fields, session.cookie,
            "Origin: http://127.0.0.1:${server.actualPort}\r\nSec-Fetch-Site: same-origin\r\n")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun request(server: LibraryHttpServer, path: String = "/", method: String = "GET", fields: List<Pair<String, String>> = emptyList(), cookie: String? = null, extra: String = ""): Response {
        Socket("127.0.0.1", server.actualPort).use { socket ->
            socket.soTimeout = 15000
            val body = fields.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.actualPort}\r\n")
                cookie?.let { append("Cookie: $it\r\n") }
                if (method == "POST") append("Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.size}\r\n")
                append(extra)
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            val output = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val split = output.indexOf("\r\n\r\n")
            require(split > 0) { "Incomplete HTTP response: $output" }
            return Response(output.substringBefore("\r\n").split(' ')[1].toInt(), output.substring(0, split), output.substring(split + 4))
        }
    }
}
