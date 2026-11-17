package it.linko.app.data

import org.junit.Assert.*
import org.junit.Test

class LinkMatcherTest {
    private val link = VideoLink(
        id = 5, url = "https://www.Example.com/asian-food-eat-fast?play=1", title = "Street food in Asia",
        description = "Una ricetta da provare", categoryId = 2, keys = listOf("asian", "food", "fast"),
        quality = "480p", favorite = true
    )

    @Test fun `all constraints and all keywords must match together`() {
        val filters = LinkFilters(query = "ricetta", categoryId = 2, keys = setOf("asian", "food"),
            qualities = setOf("480p", "720p"), domains = setOf("example.com", "another.org"), favoritesOnly = true)
        assertTrue(LinkMatcher.matches(link, filters, "Cucina"))
        assertTrue(LinkMatcher.matches(link.copy(quality = "720p"), filters, "Cucina"))
        assertTrue(LinkMatcher.matches(link.copy(url = "https://another.org/video", quality = "720p"), filters, "Cucina"))
        assertFalse(LinkMatcher.matches(link.copy(keys = listOf("asian")), filters, "Cucina"))
        assertFalse(LinkMatcher.matches(link.copy(keys = listOf("food")), filters, "Cucina"))
        assertFalse(LinkMatcher.matches(link, filters.copy(keys = setOf("asian", "food", "travel"))))
        assertFalse(LinkMatcher.matches(link.copy(quality = "1080p"), filters, "Cucina"))
        assertFalse(LinkMatcher.matches(link, filters.copy(categoryId = 3)))
        assertFalse(LinkMatcher.matches(link, filters.copy(domains = setOf("another.com", "third.org"))))
        assertFalse(LinkMatcher.matches(link.copy(favorite = false), filters))
        assertFalse(LinkMatcher.matches(link, filters.copy(query = "missing")))
    }

    @Test fun `keywords normalize hashes whitespace and case without substring matching`() {
        assertTrue(LinkMatcher.matches(link, LinkFilters(keys = setOf(" #ASIAN ", " FOOD "))))
        assertTrue(LinkMatcher.matches(link.copy(keys = listOf(" #Asian ", "FOOD")), LinkFilters(keys = setOf("asian", "food"))))
        assertFalse(LinkMatcher.matches(link.copy(keys = listOf("asian-food")), LinkFilters(keys = setOf("asian", "food"))))
        assertTrue(LinkMatcher.matches(link, LinkFilters(keys = setOf("", " # "))))
    }

    @Test fun `quality is exact and missing resolution never matches a selected quality`() {
        assertTrue(LinkMatcher.matches(link, LinkFilters(qualities = setOf(" 480P ", "720p"))))
        assertFalse(LinkMatcher.matches(link.copy(quality = null), LinkFilters(qualities = setOf("480p", "720p"))))
        assertFalse(LinkMatcher.matches(link.copy(quality = "HD"), LinkFilters(qualities = setOf("480p", "720p"))))
        assertTrue(LinkMatcher.matches(link.copy(quality = null), LinkFilters(qualities = setOf(" "))))
    }

    @Test fun `source host normalizes www case port and terminal dot`() {
        assertEquals("example.com", LinkMatcher.domainOf(link.url))
        assertEquals("example.com", LinkMatcher.domainOf("http://WWW.Example.COM.:8080/path"))
        assertTrue(LinkMatcher.matches(link, LinkFilters(domains = setOf(" WWW.EXAMPLE.COM. "))))
        assertFalse(LinkMatcher.matches(link.copy(url = "https://sub.example.com/video"), LinkFilters(domains = setOf("example.com"))))
        assertNull(LinkMatcher.domainOf("not a link"))
        assertNull(LinkMatcher.domainOf("javascript:alert(1)"))
    }

    @Test fun `dot prefix matches a suffix at the domain boundary`() {
        assertTrue(LinkMatcher.matches(link, LinkFilters(domains = setOf(".com"))))
        assertTrue(LinkMatcher.matches(link.copy(url = "https://sub.example.com/watch"), LinkFilters(domains = setOf(".example.com"))))
        assertTrue(LinkMatcher.matches(link.copy(url = "https://another.org/watch"), LinkFilters(domains = setOf(".com", ".org"))))
        assertFalse(LinkMatcher.matches(link.copy(url = "https://example.company/watch"), LinkFilters(domains = setOf(".com", ".org"))))
        assertFalse(LinkMatcher.matches(link.copy(url = "https://example.com.evil.test/watch"), LinkFilters(domains = setOf(".com", ".org"))))
        assertFalse(LinkMatcher.matches(link, LinkFilters(domains = setOf(".it"))))
    }

    @Test fun `query searches title URL description category and keywords case insensitively`() {
        listOf("STREET", "play=1", "RICETTA", "cucina", "FAST").forEach { query ->
            assertTrue("Expected global query to find $query", LinkMatcher.matches(link, LinkFilters(query = query), "Cucina"))
        }
        assertTrue(LinkMatcher.matches(link, LinkFilters()))
        assertFalse(LinkMatcher.matches(link, LinkFilters(query = "720p")))
    }
}
