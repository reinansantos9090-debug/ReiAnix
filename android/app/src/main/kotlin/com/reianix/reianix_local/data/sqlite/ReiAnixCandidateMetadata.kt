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

/** Only fully materialized posters qualify for legacy metadata-only recovery. */
data class ReiAnixMetadataRecoveryTarget(
    val animeId: Long,
    val title: String,
    val posterUrl: String?,
    val posterSource: String?,
)

/**
 * Discover already-covered anime that missed their catalog details in an older pass.
 * This query never reads or changes poster bytes, retries or publication state.
 */
suspend fun ReiAnixSqliteLibraryRepository.findCachedPostersWithMetadataGaps(
    limit: Int = 100,
): List<ReiAnixMetadataRecoveryTarget> = withContext(Dispatchers.IO) {
    require(limit in 1..200)
    database.read { db ->
        db.rawQuery(
            """
            SELECT a.id, a.title,
                   (SELECT p.external_url FROM artwork p
                    WHERE p.entity_id=CAST(a.id AS TEXT)
                      AND p.entity_type IN ('anime','movie')
                      AND p.artwork_type='poster'
                      AND p.status='ready' AND TRIM(COALESCE(p.local_path,''))!=''
                    ORDER BY p.priority DESC, p.updated_at DESC, p.id DESC LIMIT 1),
                   (SELECT p.source FROM artwork p
                    WHERE p.entity_id=CAST(a.id AS TEXT)
                      AND p.entity_type IN ('anime','movie')
                      AND p.artwork_type='poster'
                      AND p.status='ready' AND TRIM(COALESCE(p.local_path,''))!=''
                    ORDER BY p.priority DESC, p.updated_at DESC, p.id DESC LIMIT 1)
            FROM anime a
            WHERE (TRIM(COALESCE(a.description,''))='' OR a.year IS NULL
                   OR TRIM(COALESCE(a.status,''))='')
              AND COALESCE(a.metadata_source,'')!='manual'
              AND COALESCE(a.metadata_status,'')!='manual'
              AND COALESCE(a.anilist_match_manual,0)=0
              AND EXISTS (SELECT 1 FROM artwork art
                          WHERE art.entity_id=CAST(a.id AS TEXT)
                            AND art.entity_type IN ('anime','movie')
                            AND art.artwork_type='poster'
                            AND art.status='ready'
                            AND TRIM(COALESCE(art.local_path,''))!='')
            ORDER BY a.id ASC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ReiAnixMetadataRecoveryTarget(
                        animeId = cursor.getLong(0),
                        title = cursor.getString(1),
                        posterUrl = if (cursor.isNull(2)) null else cursor.getString(2),
                        posterSource = if (cursor.isNull(3)) null else cursor.getString(3),
                    ))
                }
            }
        }
    }
}

private fun metadataRecoveryAttemptKey(animeId: Long, source: String): String {
    require(animeId > 0L)
    require(source == "anilist" || source == "kitsu")
    return "__reianix.candidate_metadata_recovery.v1.$source.$animeId"
}

/** Separate cooldown from the source/artwork cursor so already-ready covers are untouched. */
suspend fun ReiAnixSqliteLibraryRepository.candidateMetadataRecoveryAttemptAt(
    animeId: Long,
    source: String = "kitsu",
): Double? = withContext(Dispatchers.IO) {
    database.read { db ->
        db.rawQuery(
            "SELECT value FROM preferences WHERE key=? LIMIT 1",
            arrayOf(metadataRecoveryAttemptKey(animeId, source)),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.toDoubleOrNull() else null
        }
    }
}

suspend fun ReiAnixSqliteLibraryRepository.markCandidateMetadataRecoveryAttempt(
    animeId: Long,
    attemptedAtSeconds: Double,
    source: String = "kitsu",
) = withContext(Dispatchers.IO) {
    require(attemptedAtSeconds.isFinite() && attemptedAtSeconds >= 0.0)
    database.transaction { db ->
        db.execSQL(
            """
            INSERT INTO preferences(key,value,updated_at) VALUES(?,?,?)
            ON CONFLICT(key) DO UPDATE SET
              value=excluded.value, updated_at=excluded.updated_at
            """.trimIndent(),
            arrayOf(metadataRecoveryAttemptKey(animeId, source), attemptedAtSeconds.toString(), attemptedAtSeconds),
        )
    }
}
