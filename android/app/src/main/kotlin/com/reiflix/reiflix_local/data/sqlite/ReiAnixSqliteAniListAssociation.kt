package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AniList-specific association persistence, kept separate from the generic
 * catalog patch contract while sharing the canonical library repository/database.
 * Association and remote metadata changes remain atomic on the existing row.
 */
suspend fun ReiAnixSqliteLibraryRepository.updateAniListMetadataAndAssociation(
        patch: ReiAnixAnimeMetadataPatch,
        anilistId: Long,
        manual: Boolean,
        score: Double?,
        margin: Double?,
    ): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
        require(anilistId > 0L) { "AniList ID must be positive." }
        require(score == null || (score.isFinite() && score in 0.0..1.0))
        require(margin == null || (margin.isFinite() && margin in 0.0..1.0))
        database.transaction { connection ->
            val currentAndManualFields = connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(patch.animeId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) throw NoSuchElementException("Anime ID does not exist.")
                val record = cursor.toAnimeRecord()
                val raw = cursor.optionalString("metadata_manual_fields") ?: "[]"
                val manualFields = try {
                    val parsed = JSONArray(raw)
                    buildSet {
                        for (index in 0 until parsed.length()) {
                            val field = parsed.get(index)
                            if (field !is String) throw IllegalStateException("Manual metadata ownership is invalid.")
                            add(field as String)
                        }
                    }
                } catch (error: JSONException) {
                    throw IllegalStateException("Manual metadata ownership is unreadable; refusing remote update.", error)
                }
                record to manualFields
            }
            val current = currentAndManualFields.first
            val protectedFields = currentAndManualFields.second
            if (!manual && current.anilistMatchManual == true) return@transaction current
            if (!manual && current.anilistId != null && current.anilistId != anilistId) return@transaction current
            if (!manual && (score == null || score < 0.88 || margin == null || margin < 0.12)) {
                return@transaction current
            }

            val values = ContentValues()
            patch.title?.takeIf(String::isNotBlank)?.let { if ("title" !in protectedFields) values.put("title", it.trim()) }
            patch.romaji?.takeIf(String::isNotBlank)?.let { if ("romaji" !in protectedFields) values.put("romaji", it) }
            patch.english?.takeIf(String::isNotBlank)?.let { if ("english" !in protectedFields) values.put("english", it) }
            patch.nativeTitle?.takeIf(String::isNotBlank)?.let { if ("native" !in protectedFields) values.put("native", it) }
            patch.aliasesJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }?.let { if ("aliases" !in protectedFields) values.put("aliases", it) }
            patch.description?.takeIf(String::isNotBlank)?.let { if ("description" !in protectedFields) values.put("description", it) }
            patch.coverUrl?.takeIf(String::isNotBlank)?.let { if ("cover_url" !in protectedFields) values.put("cover_url", it) }
            patch.coverCache?.takeIf(String::isNotBlank)?.let { if ("cover_cache" !in protectedFields) values.put("cover_cache", it) }
            patch.bannerUrl?.takeIf(String::isNotBlank)?.let { if ("banner_url" !in protectedFields) values.put("banner_url", it) }
            patch.genresJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }?.let { if ("genres" !in protectedFields) values.put("genres", it) }
            patch.year?.let { if ("year" !in protectedFields) values.put("year", it) }
            patch.season?.takeIf(String::isNotBlank)?.let { if ("season" !in protectedFields) values.put("season", it) }
            patch.status?.takeIf(String::isNotBlank)?.let { if ("status" !in protectedFields) values.put("status", it) }
            patch.episodesCount?.let { if ("episodes_count" !in protectedFields) values.put("episodes_count", it) }
            patch.duration?.let { if ("duration" !in protectedFields) values.put("duration", it) }
            patch.score?.let { if ("score" !in protectedFields) values.put("score", it) }
            patch.format?.takeIf(String::isNotBlank)?.let { if ("format" !in protectedFields) values.put("format", it) }
            patch.studio?.takeIf(String::isNotBlank)?.let { if ("studio" !in protectedFields) values.put("studio", it) }
            patch.metadataFetchedAt?.let { values.put("metadata_fetched_at", it) }

            values.put("anilist_id", anilistId)
            values.put("anilist_match_manual", if (manual) 1 else 0)
            values.put("anilist_match_status", if (manual) "manual" else "matched")
            if (score != null) values.put("anilist_match_score", score)
            if (margin != null) values.put("anilist_match_margin", margin)
            values.put("metadata_source", "anilist")
            values.put("metadata_status", "available")
            values.put("metadata_confidence", "high")
            values.put("metadata_updated_at", System.currentTimeMillis() / 1000.0)
            connection.update("anime", values, "id = ?", arrayOf(patch.animeId.toString()))
            connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(patch.animeId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toAnimeRecord()
                else throw NoSuchElementException("Anime disappeared while applying AniList metadata.")
            }
        }
    }
