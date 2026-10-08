package com.reiflix.reiflix_local.player
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
    )

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
    ): Metadata {
        val normalized = normalizeMediaId(mediaId)
        if (normalized.isBlank()) return Metadata.empty()
        val current = get(normalized)
        val updated = current.copy(
            opening = segmentOrNull(openingStartMs, openingEndMs),
            ending = segmentOrNull(endingStartMs, endingEndMs),
        )
        write(updateRoot(readRoot(), updated))
        return updated
    }

    @Synchronized
    fun addNote(mediaId: String, timestampMs: Long, text: String, nowMs: Long = System.currentTimeMillis()): Metadata {
        val normalized = normalizeMediaId(mediaId)
        val cleanText = text.trim().take(MAX_NOTE_LENGTH)
        if (normalized.isBlank() || timestampMs < 0L || cleanText.isBlank()) return get(normalized)
        val current = get(normalized)
        val note = Note(
            id = UUID.randomUUID().toString(),
            timestampMs = timestampMs,
            text = cleanText,
            createdAtMs = nowMs,
        )
        val updated = current.copy(notes = (current.notes + note).takeLast(MAX_NOTES_PER_MEDIA))
        write(updateRoot(readRoot(), updated))
        return updated
    }

    @Synchronized
    fun deleteNote(mediaId: String, noteId: String): Metadata {
        val normalized = normalizeMediaId(mediaId)
        if (normalized.isBlank()) return Metadata.empty()
        val current = get(normalized)
        val updated = current.copy(notes = current.notes.filterNot { it.id == noteId })
        write(updateRoot(readRoot(), updated))
        return updated
    }

    fun hasMarkers(metadata: Metadata): Boolean =
        metadata.opening != null || metadata.ending != null

    companion object {
        private const val TAG = "[REIFLIX][PLAYER_METADATA]"
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

        private fun segmentOrNull(startMs: Long?, endMs: Long?): Segment? =
            if (startMs != null && endMs != null && startMs >= 0L && endMs > startMs) {
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
                        add(
                            Note(
                                id = id,
                                timestampMs = note.optLong("timestampMs", 0L).coerceAtLeast(0L),
                                text = text,
                                createdAtMs = note.optLong("createdAtMs", 0L).coerceAtLeast(0L),
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
                                    .put("createdAtMs", note.createdAtMs),
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

    private fun write(root: JSONObject) {
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "PLAYER_METADATA_DIRECTORY_CREATE_FAILED")
            return
        }
        val temp = File(parent, file.name + ".tmp")
        runCatching {
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    throw IllegalStateException("Unable to replace local player metadata file")
                }
            }
        }.onFailure {
            temp.delete()
            Log.e(TAG, "PLAYER_METADATA_WRITE_FAILED", it)
        }
    }
}
