package it.linko.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The one local library. All writes and snapshots are serialized, including HTTP server reads. */
class LibraryRepository(context: Context) {
    private val helper = Database(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences("linko_preferences", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readState())
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()

    @Synchronized fun snapshot(): LibraryState = mutableState.value

    @Synchronized fun setSmartCollectionThreshold(value: Int) {
        val threshold = SmartCollections.requireThreshold(value)
        if (snapshot().smartThreshold == threshold) return
        preferences.edit().putInt(SMART_THRESHOLD_KEY, threshold).apply()
        publish()
    }

    @Synchronized fun saveLink(link: VideoLink): Long {
        val db = helper.writableDatabase
        val validated = validateLink(link)
        // Sharing an existing link should keep its original date and starred status.
        val existing = if (validated.id == 0L) snapshot().links.firstOrNull { it.url == validated.url } else null
        val normalized = existing?.let { validated.copy(id = it.id, favorite = it.favorite || validated.favorite, createdAt = it.createdAt) } ?: validated
        db.beginTransaction()
        val id: Long
        try {
            id = putLink(db, normalized)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish()
        return id
    }

    /** Save an entire reviewed paste atomically, publishing exactly once after commit. */
    @Synchronized fun saveLinks(links: List<VideoLink>): Int {
        require(links.size <= 100) { "Puoi salvare al massimo 100 link alla volta" }
        val validated = links.map(::validateLink).distinctBy { it.url }
        if (validated.isEmpty()) return 0
        val current = snapshot()
        val categoryIds = current.categories.map { it.id }.toSet()
        val existingByUrl = current.links.associateBy { it.url }
        val existingById = current.links.associateBy { it.id }
        val normalized = validated.map { link ->
            require(link.categoryId in categoryIds) { "Categoria non trovata" }
            val duplicate = existingByUrl[link.url]
            if (link.id > 0) {
                require(link.id in existingById) { "Link non trovato" }
                require(duplicate == null || duplicate.id == link.id) { "Questo link è già salvato" }
                link
            } else duplicate?.let { link.copy(id = it.id, favorite = it.favorite || link.favorite, createdAt = it.createdAt) } ?: link
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            normalized.forEach { putLink(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish()
        return normalized.size
    }

    @Synchronized fun deleteLink(id: Long) {
        helper.writableDatabase.delete("links", "id=?", arrayOf(id.toString()))
        publish()
    }

    @Synchronized fun toggleFavorite(id: Long) {
        helper.writableDatabase.execSQL("UPDATE links SET favorite=1-favorite WHERE id=?", arrayOf(id))
        publish()
    }

    @Synchronized fun saveCategory(name: String, icon: String = "folder", color: Long = 0xFFB9A7FF, id: Long = 0, matchKeywords: List<String> = emptyList()): Long {
        val clean = cleanName(name, 60)
        val aliases = CategoryRules.normalizeKeywords(matchKeywords)
        val db = helper.writableDatabase
        val existing = categoryId(db, clean)
        require(existing == null || existing == id || id == 0L) { "Esiste già una categoria con questo nome" }
        if (id == 0L && existing != null) return existing
        val values = ContentValues().apply {
            put("name", clean)
            put("icon", icon.take(30))
            put("color", color)
            put("url_keywords", JSONArray(aliases).toString())
        }
        val result = if (id > 0) {
            require(db.update("categories", values, "id=?", arrayOf(id.toString())) > 0) { "Categoria non trovata" }
            id
        } else db.insertOrThrow("categories", null, values)
        publish()
        return result
    }

    /** Explicit backfill: only category IDs change; every other link field is preserved. */
    @Synchronized fun applyCategoryRules(): Int {
        val current = snapshot()
        val changes = current.links.mapNotNull { link ->
            val metadataText = (listOf(link.title, link.description) + link.keys).joinToString(" ")
            CategoryRules.suggest(link.url, current.categories, metadataText)?.takeIf { it.id != link.categoryId }?.let { link.id to it.id }
        }
        if (changes.isEmpty()) return 0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            changes.forEach { (linkId, categoryId) ->
                db.update("links", ContentValues().apply { put("category_id", categoryId) }, "id=?", arrayOf(linkId.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish()
        return changes.size
    }

    @Synchronized fun deleteCategory(id: Long) {
        require(id != 1L) { "La categoria Da organizzare non può essere eliminata" }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE links SET category_id=1 WHERE category_id=?", arrayOf(id))
            db.delete("categories", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish()
    }

    @Synchronized fun addKey(name: String) {
        putKey(helper.writableDatabase, cleanKey(name))
        publish()
    }

    @Synchronized fun deleteKey(name: String) {
        val key = cleanKey(name)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("keywords", "name=? COLLATE NOCASE", arrayOf(key))
            readLinks(db).filter { link -> link.keys.any { it.equals(key, true) } }.forEach { link ->
                db.update("links", ContentValues().apply {
                    put("keywords", JSONArray(link.keys.filterNot { it.equals(key, true) }).toString())
                }, "id=?", arrayOf(link.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish()
    }

    @Synchronized fun exportJson(): String {
        val library = snapshot()
        return JSONObject().apply {
            put("version", 1)
            put("app", "Linko")
            put("exportedAt", System.currentTimeMillis())
            put("smartThreshold", library.smartThreshold)
            put("categories", JSONArray().apply { library.categories.forEach { category ->
                put(JSONObject().apply {
                    put("id", category.id); put("name", category.name)
                    put("icon", category.icon); put("color", category.color)
                    put("matchKeywords", JSONArray(category.matchKeywords))
                })
            } })
            put("keys", JSONArray(library.keys))
            put("links", JSONArray().apply { library.links.forEach { link ->
                put(JSONObject().apply {
                    put("url", link.url); put("title", link.title); put("description", link.description)
                    put("thumbnail", link.thumbnail ?: JSONObject.NULL); put("categoryId", link.categoryId)
                    put("keys", JSONArray(link.keys)); put("quality", link.quality ?: JSONObject.NULL)
                    put("duration", link.duration ?: JSONObject.NULL); put("favorite", link.favorite)
                    put("createdAt", link.createdAt)
                })
            } })
        }.toString(2)
    }

    /** Merge a backup atomically. Invalid data leaves the original database untouched. */
    @Synchronized fun importJson(json: String): Int {
        require(json.length <= 20 * 1024 * 1024) { "Il backup supera 20 MB" }
        val root = JSONObject(json)
        require(root.optInt("version") == 1) { "Formato backup non supportato" }
        val categories = root.getJSONArray("categories")
        val keys = root.getJSONArray("keys")
        val links = root.getJSONArray("links")
        val importedThreshold = if (root.has("smartThreshold")) SmartCollections.requireThreshold(root.getInt("smartThreshold")) else null
        require(categories.length() <= 1000 && keys.length() <= 10000 && links.length() <= 50000) { "Backup troppo grande" }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val categoryMap = mutableMapOf<Long, Long>()
            for (i in 0 until categories.length()) {
                val entry = categories.getJSONObject(i)
                val oldId = entry.getLong("id")
                require(oldId > 0 && oldId !in categoryMap) { "Categorie duplicate nel backup" }
                val name = cleanName(entry.getString("name"), 60)
                val hasAliases = entry.has("matchKeywords")
                val incomingAliases = if (hasAliases) {
                    val array = entry.getJSONArray("matchKeywords")
                    require(array.length() <= 100) { "Massimo 100 parole o frasi per categoria" }
                    CategoryRules.normalizeKeywords((0 until array.length()).map { array.getString(it) })
                } else emptyList()
                val existingId = categoryId(db, name)
                if (existingId != null) {
                    // A version-1 backup without the optional field must leave local rules intact.
                    if (hasAliases) {
                        val existingAliases = db.rawQuery("SELECT url_keywords FROM categories WHERE id=?", arrayOf(existingId.toString())).use { cursor ->
                            if (cursor.moveToFirst()) JSONArray(cursor.getString(0)).stringList() else emptyList()
                        }
                        val merged = (existingAliases + incomingAliases).distinct()
                        val aliases = CategoryRules.normalizeKeywords(merged)
                        db.update("categories", ContentValues().apply { put("url_keywords", JSONArray(aliases).toString()) }, "id=?", arrayOf(existingId.toString()))
                    }
                    categoryMap[oldId] = existingId
                } else {
                    categoryMap[oldId] = db.insertOrThrow("categories", null, ContentValues().apply {
                        put("name", name); put("icon", entry.optString("icon", "folder").take(30))
                        put("color", entry.optLong("color", 0xFFB9A7FF)); put("url_keywords", JSONArray(incomingAliases).toString())
                    })
                }
            }
            for (i in 0 until keys.length()) putKey(db, cleanKey(keys.getString(i)))
            for (i in 0 until links.length()) {
                val entry = links.getJSONObject(i)
                val linkKeys = entry.optJSONArray("keys") ?: JSONArray()
                require(linkKeys.length() <= 100) { "Troppe parole chiave per un link" }
                val category = categoryMap[entry.optLong("categoryId", 1)] ?: 1L
                val link = validateLink(VideoLink(
                    url = entry.getString("url"), title = entry.getString("title"),
                    description = entry.optString("description"), thumbnail = entry.nullableString("thumbnail"),
                    categoryId = category, keys = (0 until linkKeys.length()).map { cleanKey(linkKeys.getString(it)) },
                    quality = entry.nullableString("quality"), duration = entry.nullableString("duration"),
                    favorite = entry.optBoolean("favorite"), createdAt = entry.optLong("createdAt", System.currentTimeMillis()).coerceAtLeast(0)
                ))
                putLink(db, link)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // Apply preferences only after the database transaction has committed successfully.
        // A legacy backup without this optional setting leaves the current preference intact.
        importedThreshold?.let { preferences.edit().putInt(SMART_THRESHOLD_KEY, it).apply() }
        publish()
        return links.length()
    }

    private fun validateLink(link: VideoLink): VideoLink {
        require(link.url.length <= MAX_WEB_URL_LENGTH) { "Indirizzo troppo lungo" }
        val url = MetadataExtractor.extractUrl(link.url) ?: throw IllegalArgumentException("Inserisci un indirizzo HTTP o HTTPS valido")
        require(link.title.trim().isNotEmpty()) { "Inserisci un titolo" }
        require(link.keys.size <= 100) { "Massimo 100 parole chiave" }
        return link.copy(url = url, title = link.title.trim().take(500), description = link.description.take(10000),
            thumbnail = link.thumbnail?.let { normalizeWebUrl(it.trim()) ?: MetadataExtractor.extractUrl(it) },
            keys = link.keys.map { cleanKey(it) }.distinct(), quality = link.quality?.take(30), duration = link.duration?.take(50))
    }

    private fun putLink(db: SQLiteDatabase, link: VideoLink): Long {
        val categoryExists = db.rawQuery("SELECT id FROM categories WHERE id=?", arrayOf(link.categoryId.toString())).use { it.moveToFirst() }
        require(categoryExists) { "Categoria non trovata" }
        val duplicateId = db.rawQuery("SELECT id FROM links WHERE url=?", arrayOf(link.url)).use { if (it.moveToFirst()) it.getLong(0) else null }
        val targetId = if (link.id > 0) link.id else duplicateId
        require(duplicateId == null || targetId == duplicateId) { "Questo link è già salvato" }
        val values = ContentValues().apply {
            put("url", link.url); put("title", link.title); put("description", link.description)
            put("thumbnail", link.thumbnail); put("category_id", link.categoryId)
            put("keywords", JSONArray(link.keys).toString()); put("quality", link.quality)
            put("duration", link.duration); put("favorite", if (link.favorite) 1 else 0)
            put("created_at", link.createdAt)
        }
        val id = if (targetId != null) {
            require(db.update("links", values, "id=?", arrayOf(targetId.toString())) > 0) { "Link non trovato" }
            targetId
        } else db.insertOrThrow("links", null, values)
        link.keys.forEach { putKey(db, it) }
        return id
    }

    private fun putKey(db: SQLiteDatabase, name: String) {
        db.insertWithOnConflict("keywords", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun categoryId(db: SQLiteDatabase, name: String): Long? = db.rawQuery(
        "SELECT id FROM categories WHERE name=? COLLATE NOCASE", arrayOf(name)
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
    private fun publish() { mutableState.value = readState() }
    private fun readState(): LibraryState {
        val db = helper.readableDatabase
        val categories = db.rawQuery("SELECT id,name,icon,color,url_keywords FROM categories ORDER BY id=1 DESC,name COLLATE NOCASE", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Category(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), JSONArray(cursor.getString(4)).stringList())) }
        }
        val keys = db.rawQuery("SELECT name FROM keywords ORDER BY name COLLATE NOCASE", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return LibraryState(categories, keys, readLinks(db), preferences.getInt(SMART_THRESHOLD_KEY, SmartCollections.DEFAULT_THRESHOLD)
            .coerceIn(SmartCollections.MIN_THRESHOLD, SmartCollections.MAX_THRESHOLD))
    }
    private fun readLinks(db: SQLiteDatabase): List<VideoLink> = db.rawQuery("SELECT * FROM links ORDER BY created_at DESC,id DESC", null).use { c ->
        buildList { while (c.moveToNext()) {
            val words = JSONArray(c.string("keywords"))
            add(VideoLink(c.long("id"), c.string("url"), c.string("title"), c.string("description"),
                c.nullString("thumbnail"), c.long("category_id"), (0 until words.length()).map { words.getString(it) },
                c.nullString("quality"), c.nullString("duration"), c.long("favorite") == 1L, c.long("created_at")))
        } }
    }
    private fun cleanName(name: String, max: Int): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty() && clean.length <= max && clean.none { it.isISOControl() }) { "Nome non valido (massimo $max caratteri)" }
        return clean
    }
    private fun cleanKey(name: String) = cleanName(name.trim().removePrefix("#"), 50).lowercase(Locale.ROOT)

    private class Database(context: Context) : SQLiteOpenHelper(context, "linko.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL COLLATE NOCASE UNIQUE,icon TEXT NOT NULL,color INTEGER NOT NULL,url_keywords TEXT NOT NULL DEFAULT '[]')")
            db.execSQL("CREATE TABLE keywords(name TEXT PRIMARY KEY COLLATE NOCASE)")
            db.execSQL("CREATE TABLE links(id INTEGER PRIMARY KEY AUTOINCREMENT,url TEXT NOT NULL UNIQUE,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',thumbnail TEXT,category_id INTEGER NOT NULL REFERENCES categories(id),keywords TEXT NOT NULL DEFAULT '[]',quality TEXT,duration TEXT,favorite INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX links_category ON links(category_id)")
            listOf(
                Category(1, "Da organizzare", "inbox", 0xFFB9A7FF), Category(2, "Cucina", "cooking", 0xFFFFBD8C),
                Category(3, "Tecnologia", "tech", 0xFF9FBFFF), Category(4, "Viaggi", "travel", 0xFF9DDBC7),
                Category(5, "Ispirazione", "sparkles", 0xFFE6A7D3)
            ).forEach { category -> db.insertOrThrow("categories", null, ContentValues().apply {
                put("id", category.id); put("name", category.name); put("icon", category.icon); put("color", category.color)
            }) }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE categories ADD COLUMN url_keywords TEXT NOT NULL DEFAULT '[]'")
        }
    }

    private companion object {
        const val SMART_THRESHOLD_KEY = "smart_collection_threshold"
    }
}

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.nullString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
private fun JSONArray.stringList(): List<String> = (0 until length()).map { getString(it) }
