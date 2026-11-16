package com.reianix.reianix_local.data.collections

import android.content.Context
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Personal, offline-only groups of canonical anime IDs. Stored in the existing
 * SQLite preferences table; no new schema, files, scanner or cover fetcher.
 *
 * One process-scoped instance is owned by the Library ViewModel. Each edit reads
 * the latest saved snapshot under the mutex and writes it transactionally via
 * ReiAnixSqliteSettingsRepository, never silently discarding corrupt data.
 */
data class ReiAnixPersonalCollection(
    val id: String,
    val name: String,
    val animeIds: List<Long> = emptyList(),
    val coverAnimeId: Long? = null,
) {
    val effectiveCoverId: Long? get() = coverAnimeId?.takeIf(animeIds::contains) ?: animeIds.firstOrNull()
}

class ReiAnixPersonalCollectionsRepository(
    private val settings: ReiAnixSqliteSettingsRepository,
) {
    constructor(context: Context) : this(ReiAnixSqliteSettingsRepository(context.applicationContext))

    private val lock = Mutex()

    suspend fun load(): List<ReiAnixPersonalCollection> = lock.withLock { read() }

    suspend fun create(name: String): ReiAnixPersonalCollection {
        val validName = validateName(name)
        val newCollection = ReiAnixPersonalCollection(UUID.randomUUID().toString(), validName)
        edit { existing ->
            require(existing.size < MAX_COLLECTIONS) { "Limite de coleções atingido." }
            ensureUniqueName(existing, validName)
            existing + newCollection
        }
        return newCollection
    }

    suspend fun rename(id: String, name: String): List<ReiAnixPersonalCollection> {
        val validName = validateName(name)
        return edit { existing ->
            ensureUniqueName(existing, validName, exceptId = id)
            update(existing, id) { it.copy(name = validName) }
        }
    }

    suspend fun delete(id: String): List<ReiAnixPersonalCollection> = edit { existing ->
        require(existing.any { it.id == id }) { "Coleção não encontrada." }
        existing.filterNot { it.id == id }
    }

    suspend fun toggleAnime(id: String, animeId: Long): List<ReiAnixPersonalCollection> {
        require(animeId > 0L) { "ID de anime inválido." }
        return edit { existing ->
            update(existing, id) { collection ->
                if (animeId in collection.animeIds) {
                    collection.copy(
                        animeIds = collection.animeIds.filterNot { it == animeId },
                        coverAnimeId = collection.coverAnimeId?.takeUnless { it == animeId },
                    )
                } else {
                    require(collection.animeIds.size < MAX_ITEMS) { "Limite de animes atingido." }
                    collection.copy(animeIds = collection.animeIds + animeId)
                }
            }
        }
    }

    suspend fun moveAnime(id: String, animeId: Long, direction: Int): List<ReiAnixPersonalCollection> {
        require(direction == -1 || direction == 1)
        return edit { existing ->
            update(existing, id) { collection ->
                val index = collection.animeIds.indexOf(animeId)
                if (index < 0 || index + direction !in collection.animeIds.indices) collection
                else {
                    val items = collection.animeIds.toMutableList()
                    java.util.Collections.swap(items, index, index + direction)
                    collection.copy(animeIds = items)
                }
            }
        }
    }

    suspend fun setCover(id: String, animeId: Long?): List<ReiAnixPersonalCollection> = edit { existing ->
        update(existing, id) { collection ->
            require(animeId == null || animeId in collection.animeIds) {
                "A capa deve pertencer a um anime da coleção."
            }
            collection.copy(coverAnimeId = animeId)
        }
    }

    private suspend fun edit(
        change: (List<ReiAnixPersonalCollection>) -> List<ReiAnixPersonalCollection>,
    ): List<ReiAnixPersonalCollection> = lock.withLock {
        val current = read()
        val updated = change(current)
        if (current != updated) {
            settings.setPreference(STORAGE_KEY, encode(updated))
        }
        updated
    }

    private suspend fun read(): List<ReiAnixPersonalCollection> {
        val serialized = settings.getPreference(STORAGE_KEY)?.value ?: return emptyList()
        val root = JSONObject(serialized)
        require(root.getInt("version") == 1) { "Versão de coleções não suportada." }
        val items = root.getJSONArray("collections")
        require(items.length() <= MAX_COLLECTIONS) { "Arquivo de coleções muito grande." }
        val seenIds = HashSet<String>()
        val result = ArrayList<ReiAnixPersonalCollection>(items.length())
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            val id = obj.getString("id")
            require(id.isNotBlank() && seenIds.add(id)) { "ID de coleção inválido ou duplicado." }
            val name = validateName(obj.getString("name"))
            val members = obj.getJSONArray("animeIds")
            require(members.length() <= MAX_ITEMS) { "Lista de animes muito grande." }
            val animeIds = buildList {
                repeat(members.length()) { index ->
                    val animeId = members.getLong(index)
                    require(animeId > 0L && animeId !in this) { "Anime inválido ou duplicado." }
                    add(animeId)
                }
            }
            val coverId = if (obj.isNull("coverAnimeId")) null else obj.getLong("coverAnimeId")
            result += ReiAnixPersonalCollection(
                id = id,
                name = name,
                animeIds = animeIds,
                coverAnimeId = coverId?.takeIf(animeIds::contains),
            )
        }
        return result
    }

    private fun encode(items: List<ReiAnixPersonalCollection>): String {
        val array = JSONArray()
        for (collection in items) {
            val members = JSONArray()
            collection.animeIds.forEach(members::put)
            array.put(
                JSONObject()
                    .put("id", collection.id)
                    .put("name", collection.name)
                    .put("animeIds", members)
                    .put("coverAnimeId", collection.coverAnimeId ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("version", 1).put("collections", array).toString()
    }

    private fun validateName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= 64) {
            "O nome deve ter entre 1 e 64 caracteres."
        }
        return name
    }

    private fun ensureUniqueName(
        items: List<ReiAnixPersonalCollection>,
        name: String,
        exceptId: String? = null,
    ) {
        require(items.none { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            "Já existe uma coleção com esse nome."
        }
    }

    private fun update(
        items: List<ReiAnixPersonalCollection>,
        id: String,
        edit: (ReiAnixPersonalCollection) -> ReiAnixPersonalCollection,
    ): List<ReiAnixPersonalCollection> {
        require(items.any { it.id == id }) { "Coleção não encontrada." }
        return items.map { if (it.id == id) edit(it) else it }
    }

    companion object {
        private const val STORAGE_KEY = "library.personal_collections_v1"
        private const val MAX_COLLECTIONS = 120
        private const val MAX_ITEMS = 2000
    }
}
