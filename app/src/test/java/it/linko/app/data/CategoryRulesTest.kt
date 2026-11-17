package it.linko.app.data

import org.junit.Assert.*
import org.junit.Test

class CategoryRulesTest {
    private val black = Category(20, "Black", matchKeywords = listOf("black", "ebony"))

    @Test fun `any configured alias can identify a category case insensitively`() {
        assertEquals(black, CategoryRules.suggest("https://example.com/BLACK-video", listOf(black)))
        assertEquals(black, CategoryRules.suggest("https://example.com/watch?tag=ebony", listOf(black)))
        assertEquals(black, CategoryRules.suggest("https://example.com/%62lack-video", listOf(black)))
    }

    @Test fun `all URL separators split words and invalid percent is handled safely`() {
        listOf("-", "_", "&", "*", "%20", "%", "%ZZ").forEach { separator ->
            assertEquals("Separator $separator should delimit the alias", black,
                CategoryRules.suggest("https://example.com/black${separator}food", listOf(black)))
        }
        assertEquals(black, CategoryRules.suggest("https://example.com/%62lack%ZZ", listOf(black)))
        assertEquals(black, CategoryRules.suggest("https://example.com/%62lack%oops", listOf(black)))
    }

    @Test fun `substring inside a longer word never matches`() {
        assertNull(CategoryRules.suggest("https://example.com/blackberry-phone", listOf(black)))
        assertNull(CategoryRules.suggest("https://example.com/ebonywood", listOf(black)))
    }

    @Test fun `multiword aliases match slug query and escaped whitespace separators`() {
        val category = Category(9, "Portrait", matchKeywords = listOf("black hair"))
        listOf("black-hair", "black_hair", "black%20hair", "black+hair", "black&hair").forEach { slug ->
            assertEquals(category, CategoryRules.suggest("https://example.com/$slug", listOf(category)))
        }
        assertNull(CategoryRules.suggest("https://example.com/blackberry-hair", listOf(category)))
    }

    @Test fun `metadata can categorize an opaque URL and uses whole word boundaries`() {
        val opaqueUrl = "https://example.com/watch?v=1234"
        assertEquals(black, CategoryRules.suggest(opaqueUrl, listOf(black), "A film tagged Ebony and food"))
        assertNull(CategoryRules.suggest(opaqueUrl, listOf(black), "A blackberry recipe"))
    }

    @Test fun `more distinct aliases wins and ties choose the lowest category ID`() {
        val other = Category(2, "Other", matchKeywords = listOf("black"))
        assertEquals(black, CategoryRules.suggest("https://example.com/black-ebony", listOf(other, black)))
        assertEquals(other, CategoryRules.suggest("https://example.com/black-video", listOf(black, other)))
        assertEquals(other, CategoryRules.suggest("https://example.com/black-video", listOf(other, black)))
    }

    @Test fun `no configured aliases or matching rule yields no suggestion`() {
        assertNull(CategoryRules.suggest("https://example.com/black-video", listOf(Category(1, "Inbox"))))
        assertNull(CategoryRules.suggest("https://example.com/landscape", listOf(black)))
    }

    @Test fun `stored aliases normalize separators case and duplicates and enforce size limits`() {
        assertEquals(listOf("black hair", "ebony"), CategoryRules.normalizeKeywords(listOf(" BLACK-hair ", "black_hair", "Ebony")))
        try {
            CategoryRules.normalizeKeywords(listOf("a".repeat(61)))
            fail("Expected long alias to be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            CategoryRules.normalizeKeywords((1..101).map { "word$it" })
            fail("Expected too many aliases to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
