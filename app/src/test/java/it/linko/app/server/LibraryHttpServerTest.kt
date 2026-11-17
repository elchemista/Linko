package it.linko.app.server

import it.linko.app.data.Category
import it.linko.app.data.LibraryState
import it.linko.app.data.VideoLink
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder

class LibraryHttpServerTest {
    private val state = LibraryState(
        categories = listOf(Category(1, "Cucina"), Category(2, "Privato")),
        links = listOf(
            VideoLink(id = 1, url = "https://example.org/food", title = "Asian food <script>alert(1)</script>", categoryId = 1, keys = listOf("asian", "<script>")),
            VideoLink(id = 2, url = "https://example.org/private", title = "VIDEO_PRIVATO", categoryId = 2),
            VideoLink(id = 3, url = "javascript:alert(1)", title = "UNSAFE_LINK", categoryId = 1)
        )
    )

    @Test fun `library remains private until password login and filters categories`() = withServer { server ->
        val locked = request(server)
        assertEquals(200, locked.code)
        assertTrue(locked.body.contains("Password della raccolta"))
        assertFalse(locked.body.contains("Asian food"))
        val login = request(server, "/login", "POST", "password=correct-password")
        assertEquals(303, login.code)
        assertTrue(login.headers.contains("HttpOnly; SameSite=Strict"))
        assertFalse(login.headers.contains("correct-password"))
        val page = request(server, cookie = login.cookie)
        assertEquals(200, page.code)
        assertTrue(page.body.contains("Asian food &lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(page.body.contains("<script>"))
        assertFalse(page.body.contains("VIDEO_PRIVATO"))
        assertFalse(page.body.contains("Privato"))
        assertFalse(page.body.contains("UNSAFE_LINK"))
        assertTrue(page.headers.contains("Content-Security-Policy:"))
        assertTrue(page.headers.contains("Cache-Control: no-store"))
    }

    @Test fun `empty category selection exposes no links`() = withServer(categories = emptySet()) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, cookie = cookie)
        assertFalse(page.body.contains("Asian food"))
        assertFalse(page.body.contains("VIDEO_PRIVATO"))
        assertTrue(page.body.contains("0 link"))
    }

    @Test fun `unknown category cannot access hidden links`() = withServer { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, "/?category=2", cookie = cookie)
        assertFalse(page.body.contains("VIDEO_PRIVATO"))
        assertFalse(page.body.contains("Asian food"))
    }

    @Test fun `failed logins are rate limited`() = withServer { server ->
        repeat(6) {
            assertEquals(401, request(server, "/login", "POST", "password=wrong-password").code)
        }
        val result = request(server, "/login", "POST", "password=correct-password")
        assertEquals(429, result.code)
        assertTrue(result.headers.contains("Retry-After: 300"))
    }

    @Test fun `logout requires csrf token and invalidates session`() = withServer { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, cookie = cookie)
        val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(page.body)!!.groupValues[1]
        assertEquals(403, request(server, "/logout", "POST", "csrf=wrong", cookie).code)
        assertEquals(303, request(server, "/logout", "POST", "csrf=$csrf", cookie).code)
        assertFalse(request(server, cookie = cookie).body.contains("Asian food"))
    }

    @Test fun `expired sessions must authenticate again`() = withServer(lifetime = 0) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        assertFalse(request(server, cookie = cookie).body.contains("Asian food"))
    }

    @Test fun `cross origin requests and rebound hosts are rejected`() = withServer { server ->
        assertEquals(403, request(server, "/login", "POST", "password=correct-password", extra = "Origin: https://attacker.test\r\n").code)
        assertEquals(400, request(server, host = "attacker.test:${server.actualPort}").code)
        assertEquals(413, request(server, "/login", "POST", "x".repeat(4097)).code)
    }

    @Test fun `browser form policy preserves same origin login and logout`() = withServer { server ->
        val page = request(server)
        assertTrue(page.headers.contains("Referrer-Policy: same-origin"))
        assertFalse(page.headers.contains("Referrer-Policy: no-referrer"))
        val browserHeaders = "Origin: http://127.0.0.1:${server.actualPort}\r\n" +
            "Referer: http://127.0.0.1:${server.actualPort}/\r\nSec-Fetch-Site: same-origin\r\n"
        val login = request(server, "/login", "POST", "password=correct-password", extra = browserHeaders)
        assertEquals(303, login.code)
        assertTrue(login.headers.contains("Referrer-Policy: same-origin"))
        val library = request(server, cookie = login.cookie)
        assertTrue(library.body.contains("Asian food"))
        val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(library.body)!!.groupValues[1]
        val logout = request(server, "/logout", "POST", "csrf=$csrf", login.cookie, extra = browserHeaders)
        assertEquals(303, logout.code)
        assertFalse(request(server, cookie = login.cookie).body.contains("Asian food"))
    }

    @Test fun `opaque and foreign browser origins remain rejected`() = withServer { server ->
        for (origin in listOf("null", "http://attacker.test")) {
            assertEquals(403, request(server, "/login", "POST", "password=correct-password", extra = "Origin: $origin\r\n").code)
        }
        assertEquals(403, request(server, "/login", "POST", "password=correct-password", extra =
            "Origin: http://127.0.0.1:${server.actualPort}\r\nSec-Fetch-Site: cross-site\r\n").code)
    }

    @Test fun `password comparison is exact including spaces case and encoded characters`() {
        val password = " Caffè & tè + 123 "
        LibraryHttpServer(password, setOf(1), { state }, port = 0, bindAddress = InetAddress.getByName("127.0.0.1")).use { server ->
            server.start()
            for (different in listOf(password.trim(), password.lowercase(), "password-sbagliata")) {
                assertEquals(401, request(server, "/login", "POST", "password=" + URLEncoder.encode(different, "UTF-8")).code)
            }
            val login = request(server, "/login", "POST", "password=" + URLEncoder.encode(password, "UTF-8"))
            assertEquals(303, login.code)
            assertFalse(login.headers.contains(password))
            assertTrue(request(server, cookie = login.cookie).body.contains("Asian food"))
        }
    }

    @Test fun `search is escaped and matches selected links`() = withServer { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, "/?q=" + URLEncoder.encode("\" onfocus=\"alert(1)", "UTF-8"), cookie = cookie)
        assertFalse(page.body.contains("value=\"\" onfocus="))
        assertTrue(page.body.contains("&quot; onfocus=&quot;alert(1)"))
        assertFalse(page.body.contains("Asian food"))
        assertTrue(request(server, "/?q=asian", cookie = cookie).body.contains("Asian food"))
    }

    @Test fun `sidebar counts apply filters across shared categories`() = withServer(categories = setOf(1, 3), library = filterState()) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, "/?category=1&quality=480p&favorites=1", cookie = cookie)

        assertTrue(page.body.contains("Tutti i link</span><small>5</small>"))
        assertTrue(page.body.contains("Cucina</span><small>4</small>"))
        assertTrue(page.body.contains("Ispirazione</span><small>1</small>"))
        assertFalse(page.body.contains("WRONG_CATEGORY"))
        assertFalse(page.body.contains("HIDDEN_MATCH"))
    }

    @Test fun `unsafe url schemes and credentials are rejected`() {
        assertNull(LibraryHttpServer.safeUrl("javascript:alert(1)"))
        assertNull(LibraryHttpServer.safeUrl("https://user:password@example.org/video"))
        assertNull(LibraryHttpServer.safeUrl("file:///sdcard/file"))
        assertEquals("https://example.org/video", LibraryHttpServer.safeUrl("https://example.org/video"))
    }

    @Test fun `all keys combine with any selected qualities and domains plus category and favorites`() = withServer(categories = setOf(1, 3), library = filterState()) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, "/?category=1&key=Asian&key=food&quality=480p&quality=720p&domain=food.example.com&domain=vimeo.com&favorites=1", cookie = cookie)
        assertEquals(200, page.code)
        assertTrue(page.body.contains("MATCH_480"))
        assertTrue(page.body.contains("MATCH_720"))
        listOf("WRONG_KEY", "WRONG_QUALITY", "WRONG_DOMAIN", "WRONG_CATEGORY", "NOT_FAVORITE", "COMMA_KEY", "HIDDEN_MATCH").forEach { assertFalse(it, page.body.contains(it)) }
        assertTrue(page.body.contains("2 link trovati"))
        assertTrue(page.body.contains("name=\"quality\" value=\"480p\" checked"))
        assertTrue(page.body.contains("name=\"quality\" value=\"720p\" checked"))
        assertTrue(page.body.contains("name=\"domain\" value=\"food.example.com\" checked"))
        assertTrue(page.body.contains("name=\"domain\" value=\"vimeo.com\" checked"))
        val categoryLink = Regex("href=\"([^\"]*category=3[^\"]*)\"").find(page.body)!!.groupValues[1]
        listOf("key=Asian", "key=food", "quality=480p", "quality=720p", "domain=food.example.com", "domain=vimeo.com", "favorites=1").forEach { assertTrue(it, categoryLink.contains(it)) }
    }

    @Test fun `domain extension filter matches only its suffix`() = withServer(library = filterState()) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, "/?key=Asian&key=food&quality=480p&domain=.com", cookie = cookie)
        assertTrue(page.body.contains("MATCH_480"))
        assertTrue(page.body.contains("NOT_FAVORITE"))
        assertFalse(page.body.contains("MATCH_720"))
        assertFalse(page.body.contains("WRONG_DOMAIN"))
    }

    @Test fun `filter choices contain only metadata from shared categories`() = withServer(library = filterState()) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, cookie = cookie)
        assertFalse(page.body.contains("private-secret.test"))
        assertFalse(page.body.contains("private-key-secret"))
        assertFalse(page.body.contains("4320p"))
        assertTrue(page.body.contains("name=\"key\" value=\"Asian\""))
        assertTrue(page.body.contains("name=\"quality\" value=\"480p\""))
        assertTrue(page.body.contains("name=\"domain\" value=\".com\""))
        assertTrue(page.body.contains("Applica filtri"))
        assertTrue(page.body.contains(">Azzera</a>"))
    }

    @Test fun `repeated key parameters preserve a key containing commas`() = withServer(library = filterState()) { server ->
        val cookie = request(server, "/login", "POST", "password=correct-password").cookie
        val page = request(server, "/?key=asian%2Cfood", cookie = cookie)
        assertTrue(page.body.contains("COMMA_KEY"))
        assertFalse(page.body.contains("MATCH_480"))
        assertFalse(page.body.contains("MATCH_720"))
    }

    private fun filterState(): LibraryState {
        fun video(id: Long, title: String, url: String = "https://food.example.com/$id", keys: List<String> = listOf("Asian", "food"), quality: String = "480p", category: Long = 1, favorite: Boolean = true) = VideoLink(id = id, title = title, url = url, keys = keys, quality = quality, categoryId = category, favorite = favorite)
        return LibraryState(
            categories = listOf(Category(1, "Cucina"), Category(2, "Privato"), Category(3, "Ispirazione")),
            links = listOf(
                video(1, "MATCH_480"),
                video(2, "MATCH_720", url = "https://vimeo.com/2", quality = "720p"),
                video(3, "WRONG_KEY", keys = listOf("Asian")),
                video(4, "WRONG_QUALITY", quality = "1080p"),
                video(5, "WRONG_DOMAIN", url = "https://example.org/5"),
                video(6, "WRONG_CATEGORY", category = 3),
                video(7, "NOT_FAVORITE", favorite = false),
                video(8, "COMMA_KEY", keys = listOf("asian,food")),
                video(9, "HIDDEN_MATCH", url = "https://private-secret.test/9", keys = listOf("private-key-secret"), quality = "4320p", category = 2)
            )
        )
    }

    private fun withServer(categories: Set<Long> = setOf(1), lifetime: Long = 60000, library: LibraryState = state, block: (LibraryHttpServer) -> Unit) {
        LibraryHttpServer("correct-password", categories, { library }, port = 0, bindAddress = InetAddress.getByName("127.0.0.1"), sessionLifetimeMillis = lifetime).use { server ->
            server.start()
            block(server)
        }
    }

    private data class Response(val code: Int, val headers: String, val body: String) {
        val cookie: String get() = Regex("Set-Cookie: ([^;]+)").find(headers)!!.groupValues[1]
    }

    private fun request(server: LibraryHttpServer, path: String = "/", method: String = "GET", body: String = "", cookie: String? = null, extra: String = "", host: String = "127.0.0.1:${server.actualPort}"): Response {
        Socket("127.0.0.1", server.actualPort).use { socket ->
            socket.soTimeout = 10000
            val data = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\n")
                cookie?.let { append("Cookie: $it\r\n") }
                if (method == "POST") append("Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${data.size}\r\n")
                append(extra)
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(data); flush() }
            val output = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val split = output.indexOf("\r\n\r\n")
            return Response(output.substringBefore("\r\n").split(' ')[1].toInt(), output.substring(0, split), output.substring(split + 4))
        }
    }
}
