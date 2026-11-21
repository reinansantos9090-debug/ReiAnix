package com.reianix.reianix_local.data.personal

import android.content.Context
import android.util.Log
import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Character data belongs to the SAME fetched metadata payload as the poster
 * and synopsis. Publish the parsed cast before metadata is announced to Compose.
 * Cache persistence is asynchronous and never blocks metadata or cover work.
 *
 * Uses the existing app cache directory, without changing SQLite schema v29.
 */
class ReiAnixMetadataCharactersStore(context: Context) {
    data class Entry(
        val source: String,
        val sourceId: String,
        val characters: List<ReiAnixAnimeCharacter>,
    )

    private val folder = File(context.applicationContext.cacheDir, "characters/metadata")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Long>(Channel.UNLIMITED)
    private val lock = Any()
    private val mutableEntries = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    val entries = mutableEntries.asStateFlow()

    init {
        scope.launch {
            for (animeId in pending) {
                try {
                    val entry = mutableEntries.value[animeId] ?: continue
                    persist(animeId, entry)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("ReiAnixCharacters", "Character metadata cache write deferred: " +
                        error.javaClass.simpleName)
                }
            }
        }
    }

    fun publish(
        animeId: Long,
        source: String,
        sourceId: String,
        characters: List<ReiAnixAnimeCharacter>?,
    ) {
        if (animeId <= 0L || sourceId.isBlank() || characters == null) return
        if (source != "anilist" && source != "kitsu") return
        val accepted = synchronized(lock) {
            val previous = mutableEntries.value[animeId]
            // The primary AniList association has precedence over a supplemental
            // Kitsu enrichment; neither provider can replace usable data with no cast.
            if (previous?.source == "anilist" && source == "kitsu" &&
                previous.characters.isNotEmpty()
            ) return@synchronized false
            if (characters.isEmpty() && previous?.characters?.isNotEmpty() == true &&
                previous.sourceId == sourceId
            ) return@synchronized false
            val next = Entry(source, sourceId, characters.distinctBy { it.id }.take(18))
            if (previous == next) return@synchronized false
            mutableEntries.value = mutableEntries.value + (animeId to next)
            true
        }
        if (accepted) pending.trySend(animeId)
    }

    suspend fun restore(animeId: Long) {
        if (animeId <= 0L || mutableEntries.value.containsKey(animeId)) return
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(folder, animeId.toString() + ".json")
                if (!file.isFile) return@runCatching null
                val root = JSONObject(file.readText())
                if (root.optInt("version") != 1) return@runCatching null
                val source = root.optString("source")
                val sourceId = root.optString("sourceId")
                if ((source != "anilist" && source != "kitsu") || sourceId.isBlank()) {
                    return@runCatching null
                }
                val array = root.optJSONArray("characters") ?: return@runCatching null
                val values = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optInt("id").takeIf { it > 0 } ?: continue
                        val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
                        val image = item.optString("image").takeIf { it.startsWith("https://") }
                        add(ReiAnixAnimeCharacter(id, name, item.optString("role"), image))
                    }
                }
                Entry(source, sourceId, values)
            }.getOrNull()
        } ?: return
        synchronized(lock) {
            if (!mutableEntries.value.containsKey(animeId)) {
                mutableEntries.value = mutableEntries.value + (animeId to stored)
            }
        }
    }

    private fun persist(animeId: Long, entry: Entry) {
        folder.mkdirs()
        val array = JSONArray()
        entry.characters.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("role", item.role).put("image", item.imageUrl ?: ""))
        }
        val payload = JSONObject().put("version", 1)
            .put("source", entry.source).put("sourceId", entry.sourceId)
            .put("characters", array).toString()
        val destination = File(folder, animeId.toString() + ".json")
        val temporary = File(folder, animeId.toString() + ".tmp")
        temporary.writeText(payload)
        if (!temporary.renameTo(destination)) {
            destination.writeText(payload)
            temporary.delete()
        }
    }
}
