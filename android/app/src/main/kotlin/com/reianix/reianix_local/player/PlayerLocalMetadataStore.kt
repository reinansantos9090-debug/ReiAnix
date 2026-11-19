package com.reianix.reianix_local.player
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Local-only player annotations. This is intentionally separate from playback
 * progress: it stores optional manual opening/ending markers and timestamped
 * notes for one episode/media identity.
 */
class PlayerLocalMetadataStore(context: Context) {
    data class Segment(val startMs: Long, val endMs: Long) {
        init {
            require(startMs >= 0L && endMs > startMs)
        }
    }

    data class Note(
        val id: String,
        val timestampMs: Long,
        val text: String,
        val createdAtMs: Long,
        val favorite: Boolean = false,
        val clipEndMs: Long? = null,
        val animeTitle: String = "",
        val episodeLabel: String = "",
    )

    data class FavoriteScene(val mediaId: String, val note: Note)

    data class Metadata(
        val mediaId: String,
        val opening: Segment? = null,
        val ending: Segment? = null,
        val notes: List<Note> = emptyList(),
    ) {
        companion object {
            fun empty(mediaId: String = "") = Metadata(mediaId = mediaId)
        }
    }

    private val file = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun get(mediaId: String): Metadata {
        val normalized = normalizeMediaId(mediaId)
        if (normalized.isBlank()) return Metadata.empty()
        return runCatching {
            decode(readRoot(), normalized)
        }.onFailure {
            Log.w(TAG, "PLAYER_METADATA_READ_FAILED mediaKey=${keyFor(normalized)}", it)
        }.getOrElse {
            Metadata.empty(normalized)
        }
    }

    @Synchronized
    fun setSegments(
        mediaId: String,
        openingStartMs: Long?,
        openingEndMs: Long?,
        endingStartMs: Long?,
        endingEndMs: Long?,
        durationMs: Long? = null,
    ): Metadata {
        val normalized = normalizeMediaId(mediaId)
        if (normalized.isBlank()) return Metadata.empty()

        // Validate the complete request before reading/modifying the persisted record.
        // A null/null pair means an explicit clear; a partial or invalid pair rejects
        // the whole operation so a malformed marker can never erase a valid one.
        val validDuration = durationMs?.takeIf { it > 0L }
        require(isValidSegment(openingStartMs, openingEndMs, validDuration)) {
            "Invalid opening marker interval"
        }
        require(isValidSegment(endingStartMs, endingEndMs, validDuration)) {
            "Invalid ending marker interval"
        }

        val current = get(normalized)
        val updated = current.copy(
            opening = segmentOrNull(openingStartMs, openingEndMs, validDuration),
            ending = segmentOrNull(endingStartMs, endingEndMs, validDuration),
        )
        check(write(updateRoot(readRoot(), updated))) {
            "Unable to persist local player markers"
        }
        return updated
    }

    @Synchronized
    fun addNote(mediaId: String, timestampMs: Long, text: String, nowMs: Long = System.currentTimeMillis(), favorite: Boolean = false): Metadata {
        val normalized = normalizeMediaId(mediaId)
        val cleanText = text.trim().take(MAX_NOTE_LENGTH)
        if (normalized.isBlank() || timestampMs < 0L || cleanText.isBlank()) return get(normalized)
        val current = get(normalized)
        val note = Note(
            id = UUID.randomUUID().toString(),
            timestampMs = timestampMs,
            text = cleanText,
            createdAtMs = nowMs,
            favorite = favorite,
        )
        val updated = current.copy(notes = (current.notes + note).takeLast(MAX_NOTES_PER_MEDIA))
        val written = write(updateRoot(readRoot(), updated))
        if (favorite) check(written) { "Unable to persist favorite scene" }
        return updated
    }

    /** All saved scenes, including legacy timestamp-only favorites. */
    @Synchronized
    fun favoriteScenes(): List<FavoriteScene> {
        val root = readRoot()
        val items = root.optJSONObject("items") ?: return emptyList()
        return items.keys().asSequence()
            .mapNotNull { key -> items.optJSONObject(key)?.optString("mediaId")?.takeIf(String::isNotBlank) }
            .flatMap { mediaId -> decode(root, mediaId).notes.asSequence()
                .filter { it.favorite }.map { FavoriteScene(mediaId, it) } }
            .sortedByDescending { it.note.createdAtMs }
            .toList()
    }

    /** Persist only the interval and identity, never a duplicate video file. */
    @Synchronized
    fun saveFavoriteClip(
        mediaId: String,
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        title: String,
        animeTitle: String,
        episodeLabel: String,
        existingId: String? = null,
    ): Metadata {
        val normalized = normalizeMediaId(mediaId)
        require(normalized.isNotBlank() && durationMs > 0L &&
            isValidSegment(startMs, endMs, durationMs)) { "Invalid favorite scene interval" }
        val current = get(normalized)
        val existing = existingId?.let { id -> current.notes.firstOrNull { it.id == id && it.favorite }
            ?: error("Favorite scene no longer exists") }
        val note = Note(
            id = existing?.id ?: UUID.randomUUID().toString(),
            timestampMs = startMs,
            text = title.trim().take(MAX_NOTE_LENGTH).ifBlank { "Cena favorita" },
            createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
            favorite = true,
            clipEndMs = endMs,
            animeTitle = animeTitle.trim().take(MAX_NOTE_LENGTH),
            episodeLabel = episodeLabel.trim().take(MAX_NOTE_LENGTH),
        )
        // Never silently delete a saved clip to make room for another one.
        require(existing != null || current.notes.size < MAX_NOTES_PER_MEDIA) {
            "Limite de cenas e notas deste episódio atingido"
        }
        val notes = current.notes.filterNot { it.id == note.id } + note
        val updated = current.copy(notes = notes.sortedBy { it.timestampMs })
        check(write(updateRoot(readRoot(), updated))) { "Unable to persist favorite clip" }
        return updated
    }

    @Synchronized
    fun deleteNote(mediaId: String, noteId: String): Metadata {
        val normalized = normalizeMediaId(mediaId)
        if (normalized.isBlank()) return Metadata.empty()
        val current = get(normalized)
        val updated = current.copy(notes = current.notes.filterNot { it.id == noteId })
        check(write(updateRoot(readRoot(), updated))) { "Unable to delete local player note" }
        return updated
    }

    fun hasMarkers(metadata: Metadata): Boolean =
        metadata.opening != null || metadata.ending != null

    companion object {
        private const val TAG = "[REIANIX][PLAYER_METADATA]"
        private const val FILE_NAME = "player_metadata.json"
        private const val VERSION = 1
        private const val MAX_NOTES_PER_MEDIA = 100
        private const val MAX_NOTE_LENGTH = 500

        private fun normalizeMediaId(mediaId: String): String = mediaId.trim()

        private fun keyFor(mediaId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(
                mediaId.toByteArray(StandardCharsets.UTF_8),
            )
            return digest.joinToString("") { "%02x".format(it) }
        }

        internal fun isValidSegment(startMs: Long?, endMs: Long?, durationMs: Long? = null): Boolean {
            if (startMs == null && endMs == null) return true
            if (startMs == null || endMs == null || startMs < 0L || endMs <= startMs) return false
            val duration = durationMs?.takeIf { it > 0L }
            return duration == null || endMs <= duration
        }

        private fun segmentOrNull(startMs: Long?, endMs: Long?, durationMs: Long? = null): Segment? =
            if (isValidSegment(startMs, endMs, durationMs) && startMs != null && endMs != null) {
                Segment(startMs, endMs)
            } else {
                null
            }

        private fun decode(root: JSONObject, mediaId: String): Metadata {
            val item = root.optJSONObject("items")?.optJSONObject(keyFor(mediaId)) ?: return Metadata.empty(mediaId)
            val opening = segmentOrNull(
                item.optLongOrNull("openingStartMs"),
                item.optLongOrNull("openingEndMs"),
            )
            val ending = segmentOrNull(
                item.optLongOrNull("endingStartMs"),
                item.optLongOrNull("endingEndMs"),
            )
            val notesJson = item.optJSONArray("notes")
            val notes = buildList {
                if (notesJson != null) {
                    for (index in 0 until notesJson.length()) {
                        val note = notesJson.optJSONObject(index) ?: continue
                        val id = note.optString("id").trim()
                        val text = note.optString("text").trim().take(MAX_NOTE_LENGTH)
                        if (id.isBlank() || text.isBlank()) continue
                        val timestamp = note.optLong("timestampMs", 0L).coerceAtLeast(0L)
                        val favorite = note.optBoolean("favorite", false)
                        add(
                            Note(
                                id = id,
                                timestampMs = timestamp,
                                text = text,
                                createdAtMs = note.optLong("createdAtMs", 0L).coerceAtLeast(0L),
                                favorite = favorite,
                                clipEndMs = note.optLongOrNull("clipEndMs")?.takeIf { favorite && it > timestamp },
                                animeTitle = note.optString("animeTitle", "").take(MAX_NOTE_LENGTH),
                                episodeLabel = note.optString("episodeLabel", "").take(MAX_NOTE_LENGTH),
                            ),
                        )
                    }
                }
            }
            return Metadata(
                mediaId = mediaId,
                opening = opening,
                ending = ending,
                notes = notes.sortedBy { it.timestampMs },
            )
        }

        private fun updateRoot(root: JSONObject, metadata: Metadata): JSONObject {
            val items = root.optJSONObject("items") ?: JSONObject().also { root.put("items", it) }
            val item = JSONObject()
                .put("mediaId", metadata.mediaId)
                .put("openingStartMs", metadata.opening?.startMs ?: JSONObject.NULL)
                .put("openingEndMs", metadata.opening?.endMs ?: JSONObject.NULL)
                .put("endingStartMs", metadata.ending?.startMs ?: JSONObject.NULL)
                .put("endingEndMs", metadata.ending?.endMs ?: JSONObject.NULL)
                .put(
                    "notes",
                    JSONArray().also { array ->
                        metadata.notes.takeLast(MAX_NOTES_PER_MEDIA).forEach { note ->
                            array.put(
                                JSONObject()
                                    .put("id", note.id)
                                    .put("timestampMs", note.timestampMs)
                                    .put("text", note.text)
                                    .put("createdAtMs", note.createdAtMs)
                                    .put("favorite", note.favorite)
                                    .put("clipEndMs", note.clipEndMs ?: JSONObject.NULL)
                                    .put("animeTitle", note.animeTitle)
                                    .put("episodeLabel", note.episodeLabel),
                            )
                        }
                    },
                )
            items.put(keyFor(metadata.mediaId), item)
            return root.put("version", VERSION)
        }

        private fun newRoot(): JSONObject = JSONObject()
            .put("version", VERSION)
            .put("items", JSONObject())

        private fun JSONObject.optLongOrNull(name: String): Long? =
            if (!has(name) || isNull(name)) null else optLong(name).takeIf { it >= 0L }

        private fun readFile(file: File): JSONObject {
            if (!file.exists()) return newRoot()
            return runCatching {
                JSONObject(file.readText(Charsets.UTF_8))
            }.getOrElse {
                Log.w(TAG, "PLAYER_METADATA_RESET_CORRUPT_FILE", it)
                newRoot()
            }
        }
    }

    private fun readRoot(): JSONObject = readFile(file)

    private fun write(root: JSONObject): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "PLAYER_METADATA_DIRECTORY_CREATE_FAILED")
            return false
        }
        val temp = File(parent, file.name + ".tmp")
        return runCatching {
            temp.writeText(root.toString(), Charsets.UTF_8)
            // Keep the old file intact if replacement fails; never delete it first.
            if (!temp.renameTo(file)) {
                throw IllegalStateException("Unable to replace local player metadata file")
            }
            true
        }.onFailure {
            temp.delete()
            Log.e(TAG, "PLAYER_METADATA_WRITE_FAILED", it)
        }.getOrDefault(false)
    }
}
