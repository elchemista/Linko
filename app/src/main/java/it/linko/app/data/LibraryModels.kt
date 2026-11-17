package it.linko.app.data

data class Category(
    val id: Long,
    val name: String,
    val icon: String = "folder",
    val color: Long = 0xFFB9A7FF,
    val matchKeywords: List<String> = emptyList()
)
data class VideoLink(
    val id: Long = 0,
    val url: String,
    val title: String,
    val description: String = "",
    val thumbnail: String? = null,
    val categoryId: Long = 1,
    val keys: List<String> = emptyList(),
    val quality: String? = null,
    val duration: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
data class LibraryState(
    val categories: List<Category> = emptyList(),
    val keys: List<String> = emptyList(),
    val links: List<VideoLink> = emptyList(),
    val smartThreshold: Int = 3
)
data class LinkMetadata(
    val url: String,
    val title: String,
    val description: String = "",
    val thumbnail: String? = null,
    val keywords: List<String> = emptyList(),
    val suggestedCategory: String? = null,
    val quality: String? = null,
    val duration: String? = null,
    val source: String = "Indirizzo del link",
    val warning: String? = null
)
