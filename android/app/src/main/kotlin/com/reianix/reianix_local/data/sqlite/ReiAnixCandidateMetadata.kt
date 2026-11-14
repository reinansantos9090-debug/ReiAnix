package com.reianix.reianix_local.data.sqlite

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Data already returned by a catalog lookup that can also offer an artwork candidate.
 *
 * This is not a verified AniList/Kitsu association. It must never assign a catalog ID,
 * alter artwork URLs or replace non-empty/manual editorial fields.
 */
data class ReiAnixCandidateMetadata(
    val source: String,
    val synopsis: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val durationMinutes: Int? = null,
    val score: Int? = null,
    val format: String? = null,
    val studio: String? = null,
    val genres: List<String> = emptyList(),
) {
    init {
        require(source == "anilist" || source == "kitsu")
    }
}

/**
 * Fill empty catalog fields using the payload already returned by the source of a cover
 * candidate. The candidate may be an imperfect title match, so this path MUST NOT write
 * external IDs, titles/aliases, media identity, or artwork data. Confirmed AniList matches
 * may still replace these provisional editorial values through their existing path.
 */
suspend fun ReiAnixSqliteLibraryRepository.mergeCandidateMetadataGaps(
    animeId: Long,
    metadata: ReiAnixCandidateMetadata,
): Boolean = withContext(Dispatchers.IO) {
    if (animeId <= 0L) return@withContext false
    database.transaction { db ->
        val current = db.rawQuery(
            "SELECT * FROM anime WHERE id=? LIMIT 1",
            arrayOf(animeId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toAnimeRecord() else return@transaction false
        }
        if (current.anilistMatchManual == true ||
            current.metadataSource.equals("manual", ignoreCase = true) ||
            current.metadataStatus.equals("manual", ignoreCase = true)
        ) return@transaction false

        // Malformed manual ownership must fail closed, not accidentally remove a protection.
        val manualFields = runCatching {
            val fields = JSONArray(current.metadataManualFieldsJson ?: "[]")
            buildSet {
                for (index in 0 until fields.length()) {
                    val field = fields.opt(index) as? String
                        ?: error("Invalid manual metadata field")
                    add(field)
                }
            }
        }.getOrNull() ?: return@transaction false

        val values = ContentValues()
        fun fillText(column: String, existing: String?, incoming: String?) {
            val usable = incoming?.trim()?.takeIf(String::isNotEmpty) ?: return
            if (column !in manualFields && existing.isNullOrBlank()) values.put(column, usable)
        }
        fun fillInt(column: String, existing: Int?, incoming: Int?) {
            if (column !in manualFields && existing == null && incoming != null) {
                values.put(column, incoming)
            }
        }

        val synopsis = metadata.synopsis?.trim()?.takeIf { it.length >= 8 }
        fillText("description", current.description, synopsis)
        if ("description" !in manualFields) {
            fillText("description_original", current.descriptionOriginal, synopsis)
        }
        fillInt("year", current.year, metadata.year?.takeIf { it in 1900..2200 })
        fillText("season", current.season, metadata.season)
        fillText("status", current.status, metadata.status)
        fillText("format", current.format, metadata.format)
        fillText("studio", current.studio, metadata.studio)
        fillInt("episodes_count", current.episodesCount, metadata.episodes?.takeIf { it > 0 })
        fillInt("duration", current.duration, metadata.durationMinutes?.takeIf { it > 0 })
        fillInt("score", current.score, metadata.score?.takeIf { it in 0..100 })
        if ("genres" !in manualFields &&
            (current.genresJson.isNullOrBlank() || current.genresJson.orEmpty().trim() == "[]")
        ) {
            val genres = metadata.genres.map(String::trim).filter(String::isNotEmpty).distinct()
            if (genres.isNotEmpty()) values.put("genres", JSONArray(genres).toString())
        }

        // Do not mark a failed/empty catalog lookup as enriched.
        if (values.size() == 0) return@transaction false
        val now = System.currentTimeMillis() / 1_000.0
        if (current.metadataSource.isNullOrBlank() ||
            current.metadataSource.equals("local_index", ignoreCase = true)
        ) values.put("metadata_source", "${metadata.source}_candidate")
        if (current.metadataStatus.isNullOrBlank() ||
            current.metadataStatus.equals("unresolved", ignoreCase = true)
        ) values.put("metadata_status", "supplemental")
        if (current.metadataConfidence.isNullOrBlank()) {
            values.put("metadata_confidence", "unverified_artwork_candidate")
        }
        // Artwork recovery uses metadata_fetched_at for its own cooldown. Never advance
        // that timestamp here or a poster could be deferred by this metadata-only write.
        values.put("metadata_updated_at", now)
        db.update("anime", values, "id=?", arrayOf(animeId.toString())) > 0
    }
}
