package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.MessageDigest

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
                    buildSet<String> {
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
            if (!manual && (score == null || score < 0.86 || margin == null || margin < 0.05)) {
                return@transaction current
            }

            val values = ContentValues()
            patch.title?.takeIf(String::isNotBlank)?.let { if ("title" !in protectedFields) values.put("title", it.trim()) }
            patch.romaji?.takeIf(String::isNotBlank)?.let { if ("romaji" !in protectedFields) values.put("romaji", it) }
            patch.english?.takeIf(String::isNotBlank)?.let { if ("english" !in protectedFields) values.put("english", it) }
            patch.nativeTitle?.takeIf(String::isNotBlank)?.let { if ("native" !in protectedFields) values.put("native", it) }
            patch.aliasesJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }?.let { if ("aliases" !in protectedFields) values.put("aliases", it) }
            patch.description?.takeIf(String::isNotBlank)?.let { if ("description" !in protectedFields) values.put("description", it) }
            patch.descriptionOriginal?.takeIf(String::isNotBlank)?.let {
                if ("description" !in protectedFields) values.put("description_original", it)
            }
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
            val remoteMetadataFieldsApplied = values.size() > 0
            patch.metadataFetchedAt?.let { values.put("metadata_fetched_at", it) }

            values.put("anilist_id", anilistId)
            values.put("anilist_match_manual", if (manual) 1 else 0)
            values.put("anilist_match_status", if (manual) "manual" else "matched")
            if (score != null) values.put("anilist_match_score", score)
            if (margin != null) values.put("anilist_match_margin", margin)

            // A manually curated metadata record owns its source/status/confidence
            // contract even when the user explicitly changes the AniList link.
            // Do not make a metadata refresh look successful when every catalog
            // field was protected by metadata_manual_fields.
            val metadataIsManual = current.metadataSource == "manual" || current.metadataStatus == "manual"
            if (remoteMetadataFieldsApplied && !metadataIsManual) {
                values.put("metadata_source", "anilist")
                values.put("metadata_status", "available")
                values.put("metadata_confidence", "high")
                values.put("metadata_updated_at", System.currentTimeMillis() / 1000.0)
            }
            connection.update("anime", values, "id = ?", arrayOf(patch.animeId.toString()))
            patch.bannerArtworkUrl?.takeIf(String::isNotBlank)?.let { rawUrl ->
                persistAniListBannerCandidate(
                    database = connection,
                    animeId = patch.animeId,
                    entityType = if (current.mediaKind.equals("movie", true)) "movie" else "anime",
                    anilistId = anilistId,
                    sourceRef = patch.bannerArtworkSourceRef ?: "anilist:$anilistId:bannerImage",
                    rawUrl = rawUrl,
                    variant = patch.bannerArtworkVariant,
                    priority = patch.bannerArtworkPriority,
                )
            }
            connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(patch.animeId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toAnimeRecord()
                else throw NoSuchElementException("Anime disappeared while applying AniList metadata.")
            }
        }
    }


/**
 * Upsert AniList's semantic bannerImage into the independent canonical Banner type.
 * It intentionally does not touch anime.banner_url, which remains a legacy Backdrop field.
 */
private fun persistAniListBannerCandidate(
    database: android.database.sqlite.SQLiteDatabase,
    animeId: Long,
    entityType: String,
    anilistId: Long,
    sourceRef: String,
    rawUrl: String,
    variant: String,
    priority: Int,
) {
    val url = rawUrl.trim()
    val parsed = runCatching { URL(url) }.getOrNull() ?: return
    if (!parsed.protocol.equals("https", true) || parsed.host.isBlank() || parsed.userInfo != null) return
    if (sourceRef.isBlank() || variant.isBlank() || priority < 0) return

    val manualBannerExists = database.rawQuery(
        "SELECT 1 FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type='banner' " +
            "AND (manual=1 OR LOWER(COALESCE(source,'')) IN ('manual','local')) LIMIT 1",
        arrayOf(entityType, animeId.toString()),
    ).use { it.moveToFirst() }
    if (manualBannerExists) return

    val keyInput = "anilist|$anilistId|banner|$sourceRef|$variant|$url"
    val key = MessageDigest.getInstance("SHA-256")
        .digest(keyInput.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val existing = database.rawQuery(
        "SELECT id,external_url FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type='banner' " +
            "AND (source_ref=? OR artwork_key=? OR external_url=?) ORDER BY updated_at DESC,id DESC LIMIT 1",
        arrayOf(entityType, animeId.toString(), sourceRef, key, url),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getLong(0) to cursor.getString(1)
    }
    // An equivalent URL is already represented. Do not reset ready rows or duplicate the image.
    if (existing?.second == url) return

    val now = System.currentTimeMillis() / 1_000.0
    val values = ContentValues().apply {
        put("entity_type", entityType)
        put("entity_id", animeId.toString())
        put("artwork_type", "banner")
        put("source", "anilist")
        put("source_ref", sourceRef)
        put("external_url", url)
        put("manual", 0)
        put("priority", priority)
        put("status", "not_requested")
        put("updated_at", now)
        put("artwork_key", key)
        put("variant", variant)
        if (existing == null) {
            put("discovered_at", now)
            put("last_attempt_at", now)
            put("failure_count", 0)
        } else {
            // URL changed for the same provider/type; the old local file is no longer current.
            putNull("local_path")
            putNull("checksum")
            put("byte_size", 0L)
        }
    }
    if (existing == null) {
        database.insertOrThrow("artwork", null, values)
    } else {
        database.update("artwork", values, "id=?", arrayOf(existing.first.toString()))
    }
}

/**
 * Persists the outcome of an automatic lookup without changing a canonical
 * association or any editorial/local metadata. A later library hydration can
 * therefore distinguish "not tried" from "not found/ambiguous/network failed".
 */
suspend fun ReiAnixSqliteLibraryRepository.recordAniListAutoMatchOutcome(
    animeId: Long,
    status: String,
    score: Double? = null,
    margin: Double? = null,
): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Anime ID must be positive." }
    require(status in setOf("not_found", "ambiguous", "network_error", "rate_limited", "invalid_response")) {
        "Unsupported automatic AniList match outcome."
    }
    require(score == null || (score.isFinite() && score in 0.0..1.0))
    require(margin == null || (margin.isFinite() && margin in 0.0..1.0))

    database.transaction { connection ->
        val current = connection.rawQuery(
            "SELECT * FROM anime WHERE id = ? LIMIT 1",
            arrayOf(animeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Anime ID does not exist.")
            cursor.toAnimeRecord()
        }
        if (current.anilistId != null ||
            current.anilistMatchManual == true ||
            current.metadataSource == "manual" ||
            current.metadataStatus == "manual"
        ) {
            return@transaction current
        }

        val values = ContentValues().apply {
            put("anilist_match_status", status)
            if (score == null) putNull("anilist_match_score") else put("anilist_match_score", score)
            if (margin == null) putNull("anilist_match_margin") else put("anilist_match_margin", margin)
            if (status == "ambiguous") {
                put("metadata_status", "ambiguous")
                if (current.metadataConfidence.isNullOrBlank() || current.metadataConfidence == "low") {
                    put("metadata_confidence", "medium")
                }
            }
        }
        connection.update(
            "anime",
            values,
            "id = ? AND anilist_id IS NULL AND COALESCE(anilist_match_manual, 0) = 0",
            arrayOf(animeId.toString()),
        )
        connection.rawQuery(
            "SELECT * FROM anime WHERE id = ? LIMIT 1",
            arrayOf(animeId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toAnimeRecord()
            else throw NoSuchElementException("Anime disappeared while recording AniList match outcome.")
        }
    }
}
