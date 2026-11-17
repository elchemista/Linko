package it.linko.app.data

import org.junit.Assert.*
import org.junit.Test

class SmartCollectionsTest {
    private val categories = listOf(Category(1, "Cucina"), Category(2, "Viaggi"))
    private fun video(id: Long, category: Long = 1, keys: List<String> = listOf("food"), url: String = "https://example.com/video-$id") =
        VideoLink(id = id, url = url, title = "Video $id", categoryId = category, keys = keys)
    private fun library(links: List<VideoLink>, threshold: Int = 3) = LibraryState(categories = categories, links = links, smartThreshold = threshold)

    @Test fun `default collection appears at three distinct videos and follows saved changes`() {
        assertEquals(3, LibraryState().smartThreshold)
        val links = (1L..3L).map { video(it) }
        assertEquals(listOf(SmartCollection(1, "food", 3)), SmartCollections.build(library(links)))
        assertTrue(SmartCollections.build(library(links.take(2))).isEmpty())
        assertEquals(listOf(1L, 1L, 1L), links.map { it.categoryId })
    }

    @Test fun `minimum and configured thresholds change groups without changing videos`() {
        val links = (1L..4L).map { video(it) }
        assertEquals(4, SmartCollections.build(library(links, 2)).single().count)
        assertEquals(4, SmartCollections.build(library(links, 4)).single().count)
        assertTrue(SmartCollections.build(library(links, 5)).isEmpty())
    }

    @Test fun `the same key counts once per video despite case hashes unicode and duplicates`() {
        val links = listOf(
            video(1, keys = listOf("#FOOD", "food", " Ｆｏｏｄ ", "# #")),
            video(2, keys = listOf("food")), video(3, keys = listOf("#Food"))
        )
        assertEquals(listOf(SmartCollection(1, "food", 3)), SmartCollections.build(library(links)))
    }

    @Test fun `groups belong to their own parent and ignore categories outside the snapshot`() {
        val links = listOf(video(1), video(2), video(3), video(4, 2), video(5, 2), video(6, 99))
        assertEquals(listOf(SmartCollection(1, "food", 3)), SmartCollections.build(library(links)))
        val scoped = library(links).copy(categories = listOf(categories[1]))
        assertTrue(SmartCollections.build(scoped).isEmpty())
    }

    @Test fun `duplicated URL or saved ID cannot inflate a collection`() {
        val links = listOf(
            video(1), video(2, url = "HTTPS://EXAMPLE.COM/video-1"),
            video(2, url = "https://other.example/video-2"), video(3)
        )
        assertTrue(SmartCollections.build(library(links)).isEmpty())
        assertEquals(listOf(SmartCollection(1, "food", 2)), SmartCollections.build(library(links, 2)))
    }

    @Test fun `Unicode and its URL-encoded equivalent count as one video`() {
        val links = listOf(video(1, url = "https://example.com/café"), video(2, url = "https://example.com/caf%C3%A9"))
        assertTrue(SmartCollections.build(library(links, 2)).isEmpty())
    }

    @Test fun `unsigned new drafts with different URLs count as different videos`() {
        val links = (1..3).map { video(0, url = "https://example.com/$it") }
        assertEquals(listOf(SmartCollection(1, "food", 3)), SmartCollections.build(library(links)))
    }

    @Test fun `results sort by popularity then key then category`() {
        val links = listOf(
            video(1, keys = listOf("asian", "food")), video(2, keys = listOf("asian", "food")),
            video(3, keys = listOf("asian", "food")), video(4, keys = listOf("food")),
            video(5, category = 2, keys = listOf("asian")), video(6, category = 2, keys = listOf("asian")),
            video(7, category = 2, keys = listOf("asian"))
        )
        assertEquals(listOf(SmartCollection(1, "food", 4), SmartCollection(1, "asian", 3), SmartCollection(2, "asian", 3)), SmartCollections.build(library(links)))
    }

    @Test fun `blank symbol only and invalid URL records never create groups`() {
        val links = (1L..3L).map { video(it, keys = listOf("", "#", " --- ", " ")) } +
            (4L..6L).map { video(it, url = "javascript:alert($it)") }
        assertTrue(SmartCollections.build(library(links)).isEmpty())
    }

    @Test fun `smart keys use the same unicode and whitespace rules as the link filter`() {
        val links = (1L..3L).map { video(it, keys = listOf(" #Ｓｔｒｅｅｔ   Ｆｏｏｄ ")) }
        val collection = SmartCollections.build(library(links)).single()
        assertEquals("street food", collection.key)
        assertTrue(links.all { LinkMatcher.matches(it, LinkFilters(keys = setOf(collection.key))) })
    }

    @Test fun `configuration rejects values outside the supported range`() {
        assertEquals(2, SmartCollections.requireThreshold(2))
        assertEquals(20, SmartCollections.requireThreshold(20))
        for (invalid in listOf(-1, 0, 1, 21, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SmartCollections.requireThreshold(invalid) }
        }
    }
}
