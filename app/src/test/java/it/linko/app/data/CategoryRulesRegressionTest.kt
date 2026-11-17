package it.linko.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Independent edge cases for URL rules, including boundaries and stable conflict resolution. */
class CategoryRulesRegressionTest {
    @Test fun `alias matches whole URL tokens without matching seafood`() {
        val category = Category(10, "Cucina", matchKeywords = listOf("food"))
        assertNull(CategoryRules.suggest("https://videos.example/seafood", listOf(category)))
        assertEquals(category, CategoryRules.suggest("https://videos.example/asian-food-fast", listOf(category)))
    }

    @Test fun `phrase matches URL separators decoded spaces and plus in query`() {
        val category = Category(10, "Cucina asiatica", matchKeywords = listOf("asian food"))
        listOf(
            "https://videos.example/asian-food",
            "https://videos.example/Asian%20Food",
            "https://videos.example/watch?title=Asian+Food"
        ).forEach { url -> assertEquals(url, category, CategoryRules.suggest(url, listOf(category))) }
        assertNull(CategoryRules.suggest("https://videos.example/food-asian", listOf(category)))
    }

    @Test fun `Unicode normalization is consistent for fullwidth aliases and encoded accents`() {
        val category = Category(10, "Pausa", matchKeywords = listOf("Ｃａｆｆè"))
        assertEquals(category, CategoryRules.suggest("https://videos.example/caff%C3%A8", listOf(category)))
        val japanese = Category(20, "Giappone", matchKeywords = listOf("日本"))
        assertEquals(japanese, CategoryRules.suggest("https://videos.example/%E6%97%A5%E6%9C%AC-food", listOf(japanese)))
    }

    @Test fun `more matching aliases take precedence over a lower category ID`() {
        val generic = Category(2, "Generico", matchKeywords = listOf("Asian"))
        val specific = Category(30, "Cucina", matchKeywords = listOf("asian", "food"))
        assertEquals(specific, CategoryRules.suggest("https://videos.example/asian-food", listOf(generic, specific)))
        assertEquals(specific, CategoryRules.suggest("https://videos.example/asian-food", listOf(specific, generic)))
    }

    @Test fun `equal scores resolve by category ID independently of list order`() {
        val newer = Category(80, "A", matchKeywords = listOf("food"))
        val older = Category(7, "Z", matchKeywords = listOf("food"))
        assertEquals(older, CategoryRules.suggest("https://videos.example/food", listOf(newer, older)))
        assertEquals(older, CategoryRules.suggest("https://videos.example/food", listOf(older, newer)))
    }

    @Test fun `duplicate normalized aliases do not inflate a category score`() {
        val duplicates = Category(30, "Duplicati", matchKeywords = listOf("Food", " food ", "Ｆｏｏｄ"))
        val unique = Category(7, "Unico", matchKeywords = listOf("food"))
        assertEquals(unique, CategoryRules.suggest("https://videos.example/food", listOf(duplicates, unique)))
    }

    @Test fun `alias punctuation is normalized rather than executed as a regular expression`() {
        val wildcard = Category(10, "Wildcard", matchKeywords = listOf(".*"))
        val alternation = Category(20, "Alternativa", matchKeywords = listOf("food|asian"))
        assertNull(CategoryRules.suggest("https://videos.example/food", listOf(wildcard, alternation)))
        assertEquals(alternation, CategoryRules.suggest("https://videos.example/food-asian", listOf(wildcard, alternation)))
    }

    @Test fun `metadata aliases categorize opaque URLs without substring false positives`() {
        val category = Category(10, "Cucina", matchKeywords = listOf("food"))
        val opaque = "https://videos.example/watch?v=123456"
        assertEquals(category, CategoryRules.suggest(opaque, listOf(category), "Una ricetta · Asian FOOD"))
        assertNull(CategoryRules.suggest(opaque, listOf(category), "Un video di seafood"))
    }

    @Test fun `a phrase must match within URL or metadata instead of crossing their boundary`() {
        val category = Category(10, "Cucina asiatica", matchKeywords = listOf("asian food"))
        assertNull(CategoryRules.suggest("https://videos.example/asian", listOf(category), "food"))
        assertEquals(category, CategoryRules.suggest("https://videos.example/opaque", listOf(category), "asian-food"))
    }

    @Test fun `symbol separators and malformed percent text preserve literal words`() {
        val category = Category(10, "Regola", matchKeywords = listOf("black female"))
        listOf("-", "_", "&", "*", "%").forEach { separator ->
            val url = "https://videos.example/black${separator}female"
            assertEquals(separator, category, CategoryRules.suggest(url, listOf(category)))
        }
        assertEquals(category, CategoryRules.suggest("https://videos.example/black%20female%oops", listOf(category)))
        assertEquals(category, CategoryRules.suggest("https://videos.example/black%20female", listOf(category)))
    }

    @Test fun `different aliases may match URL and metadata for the same category`() {
        val generic = Category(2, "Generico", matchKeywords = listOf("asian"))
        val precise = Category(30, "Cucina", matchKeywords = listOf("asian", "food"))
        assertEquals(precise, CategoryRules.suggest("https://videos.example/asian", listOf(generic, precise), "food"))
    }
}
