package it.linko.app.data

import org.junit.Assert.*
import org.junit.Test

class BulkUrlExtractionTest {
    @Test fun `numbered pasted list mixes full and bare video links`() {
        val text = """
            Questi sono i video da raccogliere:
            1. https://video.example.com/asian-food
            2) youtube.com/watch?v=abc123
            3 - www.vimeo.com/123456
            4. https://example.org/art-video.
        """.trimIndent()
        assertEquals(listOf("https://video.example.com/asian-food", "https://youtube.com/watch?v=abc123",
            "https://www.vimeo.com/123456", "https://example.org/art-video"), MetadataExtractor.extractUrls(text))
    }

    @Test fun `mixed prose deduplicates normalized URLs while retaining first occurrence order`() {
        val text = "Guarda HTTPS://EXAMPLE.COM/first e poi example.org/second; ancora https://example.com/first e example.com/third."
        assertEquals(listOf("https://example.com/first", "https://example.org/second", "https://example.com/third"), MetadataExtractor.extractUrls(text))
    }

    @Test fun `emails unsafe scheme embeddings and invalid first URL are ignored`() {
        val text = """
            Scrivi a editor@example.com o nome@video.example.org.
            javascript:https://evil.example/watch data:text/plain,https://bad.example/watch ftp://files.example/video
            https://user:password@private.example/video
            Video valido: example.com/watch
        """.trimIndent()
        assertEquals(listOf("https://example.com/watch"), MetadataExtractor.extractUrls(text))
    }

    @Test fun `commas escaped values and terminal signature punctuation remain byte identical`() {
        val first = "https://example.com/watch?list=a,b,c&sig=AbC%2F12!"
        val second = "https://example.com/watch?list=a,b,c&sig=abc%2F12!"
        assertEquals(listOf(first, second), MetadataExtractor.extractUrls("1. $first\n2. $second\n3. $first"))
    }

    @Test fun `parenthesized links retain parentheses that belong to the path`() {
        assertEquals(listOf("https://example.com/film_(2024)", "https://example.org/video"),
            MetadataExtractor.extractUrls("(https://example.com/film_(2024)), [https://example.org/video]"))
    }

    @Test fun `bare host and full URL share canonical identity without changing signed variants`() {
        assertEquals(listOf("https://example.com/", "https://example.com/watch?sig=one", "https://example.com/watch?sig=two"),
            MetadataExtractor.extractUrls("example.com https://EXAMPLE.COM/ example.com/watch?sig=one example.com/watch?sig=two"))
    }

    @Test fun `oversized input is rejected and excessive list returns overflow sentinel`() {
        try {
            MetadataExtractor.extractUrls("x".repeat(256 * 1024 + 1))
            fail("Expected oversized text to be rejected")
        } catch (_: IllegalArgumentException) { }
        val many = (1..250).joinToString("\n") { "https://example.com/video-$it" }
        assertEquals(201, MetadataExtractor.extractUrls(many).size)
    }

    @Test fun `adjacent comma separated full URLs outside query are split`() {
        assertEquals(listOf("https://example.com/first", "https://example.org/second"),
            MetadataExtractor.extractUrls("https://example.com/first,https://example.org/second"))
    }
}
