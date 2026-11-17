package it.linko.app.data

import org.junit.Assert.*
import org.junit.Test

class MetadataExtractorTest {
    @Test fun `Chrome shared text extracts only the web address`() {
        assertEquals("https://example.com/asian-food-eat-fast", MetadataExtractor.extractUrl("Guarda questo video: https://example.com/asian-food-eat-fast."))
    }

    @Test fun `unsafe schemes and URL credentials are rejected`() {
        assertNull(MetadataExtractor.extractUrl("javascript:alert(1)"))
        assertNull(MetadataExtractor.extractUrl("file:///etc/passwd"))
        assertNull(MetadataExtractor.extractUrl("https://user:password@example.com/video"))
        assertNull(MetadataExtractor.extractUrl("https://example.com:70000/video"))
    }

    @Test fun `URL normalization preserves signed parameters and timestamps`() {
        assertEquals("https://example.com:443/watch?v=a%2Fb&token=ABC#t=20", MetadataExtractor.extractUrl("HTTPS://EXAMPLE.COM:443/watch?v=a%2Fb&token=ABC#t=20"))
        assertEquals("https://example.com/watch?token=ABC!", MetadataExtractor.extractUrl("https://example.com/watch?token=ABC!"))
        assertEquals("https://example.com/", MetadataExtractor.extractUrl("example.com"))
        assertEquals("https://example.com/film_(2024)", MetadataExtractor.extractUrl("(https://example.com/film_(2024))"))
    }

    @Test fun `slug creates editable food suggestions without inventing video quality`() {
        val result = MetadataExtractor.fromUrl("https://example.com/asian-food-eat-fast")
        assertEquals("Asian food eat fast", result.title)
        assertEquals(listOf("asian", "food", "eat", "fast"), result.keywords)
        assertEquals("Cucina", result.suggestedCategory)
        assertNull(result.quality)
    }

    @Test fun `explicit quality marker is extracted and opaque video ID is ignored`() {
        assertEquals("1080p", MetadataExtractor.fromUrl("https://example.com/asian-food-1080p.mp4").quality)
        val result = MetadataExtractor.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(result.keywords.isEmpty())
        assertEquals("Video su youtube.com", result.title)
        assertNull(result.quality)
    }

    @Test fun `Open Graph uses property and name attributes and resolves image URL`() {
        val fallback = MetadataExtractor.fromUrl("https://example.com/my-video")
        val result = MetadataExtractor.parseHtml(fallback, """
            <html><head>
            <title>Generic title</title>
            <meta property="og:title" content="Una ricetta &amp; un consiglio">
            <meta name="description" content="Ricetta veloce">
            <meta name="twitter:image" content="/images/preview.jpg">
            <meta name="keywords" content="pasta, Veloce, pasta">
            <meta property="og:video:height" content="1080">
            </head></html>
        """.trimIndent())
        assertEquals("Una ricetta & un consiglio", result.title)
        assertEquals("https://example.com/images/preview.jpg", result.thumbnail)
        assertEquals("1080p", result.quality)
        assertEquals("Cucina", result.suggestedCategory)
        assertEquals(1, result.keywords.count { it == "pasta" })
        assertEquals("Metadati della pagina", result.source)
    }

    @Test fun `metadata prefers the first nonblank value from the highest priority source`() {
        val fallback = MetadataExtractor.fromUrl("https://example.com/my-video")
        val result = MetadataExtractor.parseHtml(fallback, """
            <meta property="og:title" content=" ">
            <meta name="twitter:title" content="Twitter title">
            <meta name="OG:TITLE" content="Open Graph title">
            <meta property="og:title" content="Another Open Graph title">
        """.trimIndent())
        assertEquals("Open Graph title", result.title)
    }

    @Test fun `a description or explicit resolution is sufficient to count as page metadata`() {
        val fallback = MetadataExtractor.fromUrl("https://example.com/my-video")
        val description = MetadataExtractor.parseHtml(fallback, "<meta name='description' content='A short film'>")
        assertEquals("A short film", description.description)
        assertNull(description.warning)

        val resolution = MetadataExtractor.parseHtml(fallback, "<meta property='og:video:height' content='720'>")
        assertEquals("720p", resolution.quality)
        assertNull(resolution.warning)
    }

    @Test fun `thumbnail resolution preserves punctuation in a valid relative URL`() {
        val fallback = MetadataExtractor.fromUrl("https://example.com/my-video")
        val result = MetadataExtractor.parseHtml(fallback, "<meta property='og:image' content='/images/preview!'>")
        assertEquals("https://example.com/images/preview!", result.thumbnail)
    }

    @Test fun `VideoObject inside graph exposes duration thumbnail genre and keys`() {
        val result = MetadataExtractor.parseHtml(MetadataExtractor.fromUrl("https://example.com/video"), """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
                {"@type":"WebPage","name":"Page"},
                {"@type":["VideoObject"],"name":"A mountain trip","description":"A short film",
                 "thumbnailUrl":["https://example.com/preview.jpg"],"duration":"PT1H2M3S",
                 "keywords":["mountain","hiking"],"genre":"Viaggi","videoQuality":"4K"}
            ]}
            </script>
        """.trimIndent())
        assertEquals("A mountain trip", result.title)
        assertEquals("1:02:03", result.duration)
        assertEquals("4K", result.quality)
        assertEquals("Viaggi", result.suggestedCategory)
        assertEquals(listOf("mountain", "hiking"), result.keywords)
    }

    @Test fun `empty or broken structured data retains URL fallback`() {
        val fallback = MetadataExtractor.fromUrl("https://example.com/asian-food")
        val result = MetadataExtractor.parseHtml(fallback, "<script type='application/ld+json'>not json</script>")
        assertEquals(fallback.title, result.title)
        assertEquals(fallback.keywords, result.keywords)
        assertNotNull(result.warning)
        assertNull(result.thumbnail)
    }
}
