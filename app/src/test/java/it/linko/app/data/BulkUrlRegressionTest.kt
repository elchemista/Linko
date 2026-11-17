package it.linko.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression cases found while independently reviewing prose and signed-URL imports. */
class BulkUrlRegressionTest {
    @Test fun `sentence punctuation outside a query wrapper is excluded`() {
        assertEquals(
            listOf("https://example.com/watch?v=abc"),
            MetadataExtractor.extractUrls("Il video è qui: (https://example.com/watch?v=abc).")
        )
        assertEquals(
            "https://example.com/watch?v=abc",
            MetadataExtractor.extractUrl("Il video è qui: (https://example.com/watch?v=abc).")
        )
    }

    @Test fun `unwrapped signed query keeps its terminal parenthesis and punctuation`() {
        val signed = "https://example.com/watch?sig=A%2FB%3D)&expires=123!"
        val terminalParenthesis = "https://example.com/watch?sig=ABC)"
        assertEquals(listOf(signed, terminalParenthesis), MetadataExtractor.extractUrls("$signed\n$terminalParenthesis"))
        assertEquals(terminalParenthesis, MetadataExtractor.extractUrl(terminalParenthesis))
    }

    @Test fun `nested prose wrappers preserve punctuation belonging to the query`() {
        assertEquals(
            listOf("https://example.com/watch?sig=ABC!"),
            MetadataExtractor.extractUrls("Guarda [(https://example.com/watch?sig=ABC!)].")
        )
    }

    @Test fun `numbered prose imports bare links without email or credential fragments`() {
        assertEquals(
            listOf("https://example.com/asian-food", "https://youtube.com/watch?v=abc"),
            MetadataExtractor.extractUrls("""
                1. Guarda example.com/asian-food
                2) https://user:secret@private.example/watch
                3. youtube.com/watch?v=abc
                Scrivi a me@example.org per gli altri video.
            """.trimIndent())
        )
    }

    @Test fun `duplicates normalize host case without merging different signatures`() {
        assertEquals(
            listOf("https://example.com/watch?sig=ABC%2F&x=1", "https://example.com/watch?sig=abc%2F&x=1"),
            MetadataExtractor.extractUrls("""
                HTTPS://EXAMPLE.COM/watch?sig=ABC%2F&x=1
                https://example.com/watch?sig=ABC%2F&x=1
                https://example.com/watch?sig=abc%2F&x=1
            """.trimIndent())
        )
    }

    @Test fun `UTF8 byte limit rejects oversized text instead of truncating it`() {
        assertThrows(IllegalArgumentException::class.java) {
            MetadataExtractor.extractUrls("è".repeat(131073))
        }
    }
}
