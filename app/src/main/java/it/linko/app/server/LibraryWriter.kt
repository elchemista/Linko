package it.linko.app.server

import it.linko.app.data.Category
import it.linko.app.data.VideoLink

/** The Android repository adapter is supplied only when PC editing is enabled. */
interface LibraryWriter {
    fun saveLinks(links: List<VideoLink>): Int
    fun deleteLink(id: Long)
    fun saveCategory(category: Category): Long
}
