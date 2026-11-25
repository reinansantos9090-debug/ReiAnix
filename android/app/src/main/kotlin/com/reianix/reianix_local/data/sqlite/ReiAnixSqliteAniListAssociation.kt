package com.reianix.reianix_local.data.sqlite

import android.content.ContentValues
import android.database.Cursor
import com.reianix.reianix_local.data.artwork.ReiAnixPreparedRepairArtwork
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import com.reianix.reianix_local.data.anilist.EXPLICIT_REPAIR_MIN_SCORE
import com.reianix.reianix_local.data.anilist.EXPLICIT_REPAIR_MIN_MARGIN
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
        traceMoeConfirmed: Boolean = false,
        explicitRepair: Boolean = false,
        expectedAssociationId: Long? = null,
        correctManualMusic: Boolean = false,
        preparedArtwork: ReiAnixPreparedRepairArtwork? = null,
        manualSelection: Boolean = false,
        protectManualRefresh: Boolean = false,
        automaticKitsuUpgrade: Boolean = false,
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
            require(!manualSelection || manual)
            require(!automaticKitsuUpgrade || (explicitRepair && !manual && preparedArtwork != null))
            if (automaticKitsuUpgrade && (current.anilistMatchManual == true ||
                    current.metadataSource.equals("manual", true) || current.metadataStatus.equals("manual", true))) {
                return@transaction current
            }
            // A background refresh of the previous manual ID must never undo
            // a newer explicit choice made while the request was in flight.
            if (protectManualRefresh && current.anilistMatchManual == true &&
                current.anilistId != anilistId) return@transaction current
            // Explicit repair may replace an automatic association only if it has not
            // changed since the lookup. Manual associations and fields remain protected.
            val allowManualMusicCorrection = explicitRepair && correctManualMusic &&
                current.format.equals("MUSIC", true) && current.anilistMatchManual == true &&
                current.anilistId == expectedAssociationId && current.anilistId != anilistId &&
                score != null && score >= 0.86 && !patch.format.isNullOrBlank() && !patch.format.equals("MUSIC", true)
            if (explicitRepair && (current.anilistId != expectedAssociationId ||
                    (current.anilistMatchManual == true && (!manual || current.anilistId != anilistId) &&
                        !allowManualMusicCorrection))) {
                return@transaction current
            }
            if (!manual && !allowManualMusicCorrection && (
                    current.anilistMatchManual == true ||
                        current.metadataSource == "manual" ||
                        current.metadataStatus == "manual"
                )
            ) return@transaction current
            if (!manual && !traceMoeConfirmed && !explicitRepair &&
                current.anilistId != null && current.anilistId != anilistId
            ) return@transaction current
            if (!manual && traceMoeConfirmed && (score == null || score < 0.86)) {
                return@transaction current
            }
            val minScore = if (explicitRepair) EXPLICIT_REPAIR_MIN_SCORE else 0.86
            val minMargin = if (explicitRepair) EXPLICIT_REPAIR_MIN_MARGIN else 0.05
            if (!manual && !traceMoeConfirmed &&
                (score == null || score < minScore || margin == null || margin < minMargin)
            ) {
                return@transaction current
            }

            if (preparedArtwork != null) {
                require((explicitRepair || manualSelection) && preparedArtwork.poster.url == patch.coverUrl &&
                    preparedArtwork.banner?.url == patch.bannerUrl) { "Prepared artwork does not match metadata." }
                require(listOfNotNull(preparedArtwork.poster, preparedArtwork.banner).all {
                    File(it.path).isFile && File(it.path).length() == it.bytes && it.bytes > 0
                }) { "Prepared artwork is no longer available." }
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
            // Protect the complete manual artwork pointer pair, even when only
            // one of the two columns was explicitly selected by the user.
            if (automaticKitsuUpgrade && ("cover_url" in protectedFields || "cover_cache" in protectedFields)) {
                values.remove("cover_url")
                values.remove("cover_cache")
            }
            // A changed automatic URL must not inherit an unverified legacy
            // pointer from the previous identity. Preserve manual/local art and
            // keep old files on disk; the normal downloader publishes new art.
            if ((explicitRepair || manualSelection) && "cover_url" !in protectedFields &&
                "cover_cache" !in protectedFields && !patch.coverUrl.isNullOrBlank() &&
                patch.coverUrl != current.coverUrl
            ) {
                val userOwnedArt = connection.rawQuery(
                    "SELECT 1 FROM artwork WHERE entity_type IN ('anime','movie') AND entity_id=? " +
                        "AND artwork_type IN ('poster','cover') AND (COALESCE(manual,0)=1 OR LOWER(COALESCE(source,'')) IN ('manual','local')) LIMIT 1",
                    arrayOf(patch.animeId.toString()),
                ).use { it.moveToFirst() }
                if (!userOwnedArt) {
                    values.putNull("cover_cache")
                    // Give the corrected ready URL priority even when the file
                    // was downloaded earlier and the downloader hits its cache.
                    connection.execSQL(
                        "UPDATE artwork SET priority=CASE WHEN external_url=? THEN 400 ELSE MIN(COALESCE(priority,350),350) END " +
                            "WHERE entity_type IN ('anime','movie') AND entity_id=? AND artwork_type IN ('poster','cover') " +
                            "AND COALESCE(manual,0)=0 AND LOWER(COALESCE(source,'')) NOT IN ('local','manual')",
                        arrayOf(patch.coverUrl, patch.animeId.toString()),
                    )
                    val correctCachedPath = connection.rawQuery(
                        "SELECT local_path FROM artwork WHERE entity_type IN ('anime','movie') AND entity_id=? " +
                            "AND artwork_type IN ('poster','cover') AND external_url=? AND status='ready' " +
                            "AND local_path IS NOT NULL ORDER BY priority DESC,updated_at DESC LIMIT 1",
                        arrayOf(patch.animeId.toString(), patch.coverUrl),
                    ).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (!correctCachedPath.isNullOrBlank()) values.put("cover_cache", correctCachedPath)
                }
            }
            if ((explicitRepair || manualSelection) && "banner_url" !in protectedFields &&
                !patch.bannerUrl.isNullOrBlank() && patch.bannerUrl != current.bannerUrl
            ) {
                connection.execSQL(
                    "UPDATE artwork SET priority=CASE WHEN external_url=? THEN 400 ELSE MIN(COALESCE(priority,350),350) END " +
                        "WHERE entity_type IN ('anime','movie') AND entity_id=? AND artwork_type IN ('banner','backdrop') " +
                        "AND COALESCE(manual,0)=0 AND LOWER(COALESCE(source,'')) NOT IN ('local','manual')",
                    arrayOf(patch.bannerUrl, patch.animeId.toString()),
                )
            }
            if (preparedArtwork != null) {
                val entity = if (current.mediaKind.equals("movie", true)) "movie" else "anime"
                val identityChanged = current.anilistId != anilistId
                val now = System.currentTimeMillis() / 1000.0
                // Publish validated artwork and metadata in one transaction.
                for ((type, image) in listOf("poster" to preparedArtwork.poster, "backdrop" to preparedArtwork.banner)) {
                    val poster = type == "poster"
                    val kinds = if (poster) "('poster','cover')" else "('backdrop','banner')"
                    if ((poster && ("cover_url" in protectedFields || "cover_cache" in protectedFields)) ||
                        (!poster && "banner_url" in protectedFields)) continue
                    val baseWhere = "entity_type IN ('anime','movie') AND entity_id=? AND artwork_type IN $kinds"
                    val owner = connection.rawQuery(
                        "SELECT 1 FROM artwork WHERE $baseWhere AND (COALESCE(manual,0)=1 OR LOWER(COALESCE(source,'')) IN ('manual','local')) LIMIT 1",
                        arrayOf(patch.animeId.toString()),
                    ).use { it.moveToFirst() }
                    if (owner) {
                        if (poster) values.remove("cover_cache")
                        if (automaticKitsuUpgrade) values.remove(if (poster) "cover_url" else "banner_url")
                        continue
                    }
                    if (image == null && (!identityChanged || automaticKitsuUpgrade)) continue
                    connection.delete("artwork", "$baseWhere AND COALESCE(manual,0)=0 AND LOWER(COALESCE(source,'')) NOT IN ('manual','local')",
                        arrayOf(patch.animeId.toString()))
                    if (image == null) {
                        values.putNull("banner_url")
                        continue
                    }
                    val art = ContentValues().apply {
                        put("entity_type", entity); put("entity_id", patch.animeId.toString()); put("artwork_type", type)
                        put("source", "cache"); put("source_ref", image.url); put("external_url", image.url)
                        put("local_path", image.path); put("manual", 0); put("priority", 400); put("status", "ready")
                        put("discovered_at", now); put("updated_at", now); put("last_attempt_at", now)
                        put("failure_count", 0); put("artwork_key", image.key); put("variant", "large")
                        put("byte_size", image.bytes); put("width", image.width); put("height", image.height)
                        put("checksum", image.checksum); put("content_type", image.mime); put("last_access", now)
                    }
                    connection.insertOrThrow("artwork", null, art)
                    if (poster) values.put("cover_cache", image.path)
                }
                if (identityChanged && !automaticKitsuUpgrade) {
                    // Do not retain editorial fields of the rejected identity.
                    val absent = listOf(
                        "romaji" to patch.romaji, "english" to patch.english, "native" to patch.nativeTitle,
                        "aliases" to patch.aliasesJson, "description" to patch.description,
                        "description_original" to patch.descriptionOriginal, "genres" to patch.genresJson,
                        "year" to patch.year, "season" to patch.season, "status" to patch.status,
                        "episodes_count" to patch.episodesCount, "duration" to patch.duration,
                        "score" to patch.score, "format" to patch.format, "studio" to patch.studio,
                    )
                    for ((column, value) in absent) {
                        if ((if (column == "description_original") "description" else column) !in protectedFields && value == null) {
                            values.putNull(column)
                        }
                    }
                }
            }
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
            val metadataIsManual = !allowManualMusicCorrection &&
                (current.metadataSource == "manual" || current.metadataStatus == "manual")
            if (remoteMetadataFieldsApplied && !metadataIsManual) {
                values.put("metadata_source", "anilist")
                values.put("metadata_status", "available")
                values.put("metadata_confidence", "high")
                values.put("metadata_updated_at", System.currentTimeMillis() / 1000.0)
            }
            if (automaticKitsuUpgrade) {
                // A repaired AniList ID must not leave an older verified
                // crosswalk pointing at a different anime. Do not guess new
                // Kitsu/MAL IDs; an inconsistent mapping can be reverified later.
                val key = ReiAnixExternalCatalogIdsCodec.preferenceKey(patch.animeId)
                val stored = connection.rawQuery("SELECT value FROM preferences WHERE key=?", arrayOf(key)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val oldMapping = stored?.let { runCatching { ReiAnixExternalCatalogIdsCodec.decode(it) }.getOrNull() }
                if (oldMapping?.aniListId != null && oldMapping.aniListId != anilistId) {
                    connection.delete("preferences", "key=?", arrayOf(key))
                }
            }
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
