package com.reiflix.reiflix_local.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.PerformanceDiagnostics
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.NativeScanController
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Privacy-first native diagnostic export for Compose Settings.
 *
 * The export contains counts and health states, never anime titles, account
 * values, raw local paths, SAF document IDs, URLs, or authentication tokens.
 */
class ReiAnixDiagnosticsService(context: Context) {
    private val appContext = context.applicationContext
    private val dataDirectory = File(appContext.filesDir, "data")
    private val databaseFile = File(dataDirectory, "library.sqlite3")
    private val coversDirectory = File(dataDirectory, "covers")

    fun exportJson(): ByteArray = try {
        createReport().also { bytes ->
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_REPORT_EXPORTED",
                source = "ReiAnixDiagnosticsService",
                result = "COMPLETED",
                context = mapOf("report_bytes" to bytes.size),
            )
        }
    } catch (error: Exception) {
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_DIAGNOSTIC_REPORT_EXPORT_FAILED",
            severity = "ERROR",
            source = "ReiAnixDiagnosticsService",
            result = "FAILED",
            error = error,
        )
        throw error
    }

    private fun createReport(): ByteArray {
        val backupService = ReiAnixBackupService(appContext)
        val integrity = backupService.integrityReport()
        val library = librarySummary()
        val artwork = artworkSummary()
        val files = fileSummary()
        val orphans = orphanSummary()
        val duplicates = duplicateSummary()
        val consumption = consumptionSummary()
        val aniList = aniListSummary()
        val recentEvents = ReiAnixDiagnosticTimeline.snapshotJson(100)
        val player = playerSummary(recentEvents)
        val scan = scanSummary()
        val overallHealthy = integrity.healthy &&
            library.optBoolean("available") &&
            artwork.optBoolean("available") &&
            files.optBoolean("available") &&
            orphans.optBoolean("available") &&
            duplicates.optBoolean("available") &&
            consumption.optBoolean("available") &&
            aniList.optBoolean("available") &&
            orphanCount(orphans) == 0L &&
            duplicates.optLong("media_identity", -1L) == 0L &&
            consumption.optLong("invalid_progress_rows", -1L) == 0L
        val report = JSONObject()
            .put("format", "reianix-diagnostic-v1")
            .put("generated_at", utcTimestamp())
            .put("app", JSONObject()
                .put("name", "ReiAnix")
                .put("version", BuildConfig.VERSION_NAME)
                .put("build_type", BuildConfig.BUILD_TYPE))
            .put("platform", JSONObject()
                .put("android_api", Build.VERSION.SDK_INT)
                .put("android_release", Build.VERSION.RELEASE.take(32))
                .put("supported_schema", ReiAnixBackupService.SCHEMA_VERSION))
            .put("storage", JSONObject()
                .put("database_exists", databaseFile.isFile)
                .put("database_bytes", databaseFile.takeIf { it.isFile }?.length() ?: 0L)
                .put("persisted_document_grants", appContext.contentResolver.persistedUriPermissions.size)
                .put("mediastore_access", runCatching { MediaStoreScanner.accessLevel(appContext) }.getOrDefault("unknown"))
                .put("covers_cache_files", fileCount(coversDirectory))
                .put("covers_cache_bytes", directoryBytes(coversDirectory)))
            .put("overall", if (overallHealthy) "OK" else "ATTENTION_REQUIRED")
            .put("database", integrity.toJson())
            .put("library", library)
            .put("files", files)
            .put("duplicates", duplicates)
            .put("orphans", orphans)
            .put("consumption", consumption)
            .put("artwork", artwork)
            .put("anilist", aniList)
            .put("player", player)
            .put("scan", scan)
            .put("timeline", JSONObject()
                .put("retention_limit", ReiAnixDiagnosticTimeline.RETENTION_LIMIT)
                .put("returned_events", recentEvents.length())
                .put("storage", "process_memory")
                .put("recent_events", recentEvents))
            .put("performance", PerformanceDiagnostics.snapshotJson())
            .put("privacy", JSONObject()
                .put("secrets_exported", false)
                .put("paths_redacted", true)
                .put("authentication_state_exported", false)
                .put("device_identifiers_exported", false)
                .put("includes_authentication", false)
                .put("includes_video_paths", false)
                .put("includes_document_uris", false)
                .put("includes_anime_titles", false)
                .put("includes_tokens", false)
                .put("includes_raw_file_paths_in_events", false)
                .put("includes_remote_telemetry", false))
        return (report.toString(2) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun librarySummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                JSONObject()
                    .put("available", true)
                    .put("anime", count(db, "SELECT COUNT(*) FROM anime"))
                    .put("episodes", count(db, "SELECT COUNT(*) FROM episodes"))
                    .put("available_files", count(db, "SELECT COUNT(*) FROM episodes WHERE missing=0"))
                    .put("missing", count(db, "SELECT COUNT(*) FROM episodes WHERE missing=1"))
                    .put("favorites", count(db, "SELECT COUNT(*) FROM anime WHERE favorite=1"))
                    .put("pinned", count(db, "SELECT COUNT(*) FROM anime WHERE is_pinned=1"))
                    .put("notes", count(db, "SELECT COUNT(*) FROM anime WHERE NULLIF(TRIM(personal_note),'') IS NOT NULL"))
                    .put("anilist_matches", count(db, "SELECT COUNT(*) FROM anime WHERE anilist_id IS NOT NULL"))
                    .put("watched", count(db, "SELECT COUNT(*) FROM episodes WHERE watched=1"))
                    .put("in_progress", count(db, "SELECT COUNT(*) FROM episodes WHERE progress>0 AND watched=0"))
                    .put("history", count(db, "SELECT COUNT(*) FROM episodes WHERE last_played_at IS NOT NULL"))
                    .put("folders", count(db, "SELECT COUNT(*) FROM folders"))
                    .put("genres", count(db, "SELECT COUNT(*) FROM genres"))
                    .put("unique_tags", uniqueTagCount(db))
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_LIBRARY_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun artworkSummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val missing = db.rawQuery(
                    "SELECT local_path FROM artwork WHERE local_path IS NOT NULL AND TRIM(local_path)!=''",
                    null,
                ).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(0).orEmpty()
                        if (!File(path).isFile) count++
                    }
                    count
                }
                JSONObject()
                    .put("available", true)
                    .put("references", count(db, "SELECT COUNT(*) FROM artwork"))
                    .put("manual", count(db, "SELECT COUNT(*) FROM artwork WHERE manual=1"))
                    .put("cached", count(db, "SELECT COUNT(*) FROM artwork WHERE source='cache'"))
                    .put("pending", count(db, "SELECT COUNT(*) FROM artwork WHERE status IN ('queued','downloading')"))
                    .put("missing_local_references", missing)
                    .put("cache_files", fileCount(coversDirectory))
                    .put("cache_bytes", directoryBytes(coversDirectory))
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_ARTWORK_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun fileSummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                var existing = 0L
                var missing = 0L
                var unverified = 0L
                db.rawQuery("SELECT path FROM episodes", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(0)?.trim().orEmpty()
                        when {
                            path.isBlank() -> missing++
                            path.startsWith("content://", true) ||
                                path.startsWith("file://", true) ||
                                path.startsWith("http://", true) ||
                                path.startsWith("https://", true) -> unverified++
                            File(path).isFile -> existing++
                            else -> missing++
                        }
                    }
                }
                JSONObject()
                    .put("available", true)
                    .put("existing", existing)
                    .put("missing", missing)
                    .put("unverified_native_or_uri", unverified)
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_FILE_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun orphanSummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        val queries = linkedMapOf(
            "episodes_without_anime" to """
                SELECT COUNT(*) FROM episodes e
                LEFT JOIN anime a ON a.id=e.anime_id
                WHERE a.id IS NULL
            """.trimIndent(),
            "observations_without_episode" to """
                SELECT COUNT(*) FROM episode_observations o
                LEFT JOIN episodes e ON e.id=o.episode_id
                WHERE e.id IS NULL
            """.trimIndent(),
            "anime_genres_without_anime" to """
                SELECT COUNT(*) FROM anime_genres ag
                LEFT JOIN anime a ON a.id=ag.anime_id
                WHERE a.id IS NULL
            """.trimIndent(),
            "anime_genres_without_genre" to """
                SELECT COUNT(*) FROM anime_genres ag
                LEFT JOIN genres g ON g.id=ag.genre_id
                WHERE g.id IS NULL
            """.trimIndent(),
            "genre_aliases_without_genre" to """
                SELECT COUNT(*) FROM genre_aliases ga
                LEFT JOIN genres g ON g.id=ga.genre_id
                WHERE g.id IS NULL
            """.trimIndent(),
            "artwork_unknown_entity" to """
                SELECT COUNT(*) FROM artwork
                WHERE entity_type NOT IN ('anime','movie','episode','season','special')
            """.trimIndent(),
            "artwork_missing_anime" to """
                SELECT COUNT(*) FROM artwork aw
                WHERE aw.entity_type IN ('anime','movie')
                  AND NOT EXISTS (
                      SELECT 1 FROM anime a WHERE a.id=CAST(aw.entity_id AS INTEGER)
                  )
            """.trimIndent(),
            "artwork_missing_episode" to """
                SELECT COUNT(*) FROM artwork aw
                WHERE aw.entity_type='episode'
                  AND NOT EXISTS (
                      SELECT 1 FROM episodes e WHERE e.id=CAST(aw.entity_id AS INTEGER)
                  )
            """.trimIndent(),
        )
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                JSONObject().put("available", true).apply {
                    queries.forEach { (key, sql) -> put(key, count(db, sql)) }
                }
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_ORPHAN_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun duplicateSummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                JSONObject()
                    .put("available", true)
                    .put(
                        "media_identity",
                        count(
                            db,
                            """
                                SELECT COUNT(*) FROM (
                                    SELECT media_identity
                                    FROM episodes
                                    WHERE media_identity IS NOT NULL AND TRIM(media_identity)!=''
                                    GROUP BY media_identity
                                    HAVING COUNT(*)>1
                                )
                            """.trimIndent(),
                        ),
                    )
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_DUPLICATE_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun consumptionSummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                JSONObject()
                    .put("available", true)
                    .put(
                        "invalid_progress_rows",
                        count(
                            db,
                            """
                                SELECT COUNT(*) FROM episodes
                                WHERE progress < 0 OR duration < 0
                                   OR (duration > 0 AND progress > duration + 0.001)
                            """.trimIndent(),
                        ),
                    )
                    .put("completed_rows", count(db, "SELECT COUNT(*) FROM episodes WHERE watched=1"))
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_CONSUMPTION_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun aniListSummary(): JSONObject {
        if (!databaseFile.isFile) return JSONObject().put("available", false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                JSONObject()
                    .put("available", true)
                    .put(
                        "matched",
                        count(db, """
                            SELECT COUNT(*) FROM anime
                            WHERE anilist_id IS NOT NULL AND anilist_match_status='matched'
                        """.trimIndent()),
                    )
                    .put(
                        "unmatched",
                        count(db, """
                            SELECT COUNT(*) FROM anime
                            WHERE anilist_id IS NULL OR anilist_match_status='unmatched'
                        """.trimIndent()),
                    )
                    .put("ambiguous", count(db, "SELECT COUNT(*) FROM anime WHERE anilist_match_status='ambiguous'"))
                    .put(
                        "failed",
                        count(db, """
                            SELECT COUNT(*) FROM anime
                            WHERE anilist_match_status IN ('error','network_error','rate_limited')
                        """.trimIndent()),
                    )
                    .put("cached_metadata", count(db, "SELECT COUNT(*) FROM anime WHERE metadata_updated_at IS NOT NULL"))
            }
        } catch (error: Exception) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_DIAGNOSTIC_ANILIST_SUMMARY_FAILED",
                severity = "ERROR",
                source = "ReiAnixDiagnosticsService",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error", safeError(error))
        }
    }

    private fun playerSummary(events: org.json.JSONArray): JSONObject {
        val errors = mutableListOf<JSONObject>()
        for (index in events.length() - 1 downTo 0) {
            val event = events.optJSONObject(index) ?: continue
            val name = event.optString("name")
            val error = event.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (!name.contains("PLAYER", ignoreCase = true)) continue
            errors += JSONObject()
                .put("name", name)
                .put("error", error)
                .put("timestamp_ms", event.optLong("timestamp_ms"))
        }
        val latest = errors.lastOrNull()
        return JSONObject()
            .put("last_error", latest ?: JSONObject.NULL)
            .put("recent_errors", org.json.JSONArray().apply {
                errors.takeLast(5).forEach { put(it) }
            })
    }

    private fun scanSummary(): JSONObject {
        val snapshots = NativeScanController.progress.value.values.toList()
        val active = snapshots.count { !it.state.isTerminal }
        val latest = snapshots.maxByOrNull { it.startedAtMillis }
        return JSONObject()
            .put("active_operations", active)
            .put("latest_state", latest?.state?.name ?: JSONObject.NULL)
            .put("latest_source", latest?.source ?: JSONObject.NULL)
            .put("latest_discovered_files", latest?.filesFound ?: 0L)
            .put("latest_processed_files", latest?.filesProcessed ?: 0L)
            .put("latest_failures", latest?.failures ?: 0L)
    }

    private fun orphanCount(orphans: JSONObject): Long {
        val keys = listOf(
            "episodes_without_anime",
            "observations_without_episode",
            "anime_genres_without_anime",
            "anime_genres_without_genre",
            "genre_aliases_without_genre",
            "artwork_unknown_entity",
            "artwork_missing_anime",
            "artwork_missing_episode",
        )
        return keys.sumOf { orphans.optLong(it, 0L) }
    }

    private fun uniqueTagCount(db: SQLiteDatabase): Long {
        val unique = linkedSetOf<String>()
        db.rawQuery("SELECT user_tags FROM anime", null).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0).orEmpty()
                val tags = try {
                    org.json.JSONArray(raw.ifBlank { "[]" })
                } catch (_: Exception) {
                    continue
                }
                for (index in 0 until tags.length()) {
                    val value = tags.opt(index)?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    if (value.isNotBlank()) unique += value
                }
            }
        }
        return unique.size.toLong()
    }

    private fun count(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun fileCount(root: File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().count { it.isFile }.toLong()
    }

    private fun directoryBytes(root: File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { file ->
            runCatching { file.length() }.getOrDefault(0L)
        }
    }

    private fun safeError(error: Exception): String =
        error::class.java.simpleName.take(80)

    private fun utcTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
