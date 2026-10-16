package com.reiflix.reiflix_local.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.PerformanceDiagnostics
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
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
        val recentEvents = ReiAnixDiagnosticTimeline.snapshotJson(100)
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
            .put("database", integrity.toJson())
            .put("library", library)
            .put("artwork", artwork)
            .put("timeline", JSONObject()
                .put("retention_limit", ReiAnixDiagnosticTimeline.RETENTION_LIMIT)
                .put("returned_events", recentEvents.length())
                .put("storage", "process_memory")
                .put("recent_events", recentEvents))
            .put("performance", PerformanceDiagnostics.snapshotJson())
            .put("privacy", JSONObject()
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
                    .put("favorites", count(db, "SELECT COUNT(*) FROM anime WHERE favorite=1"))
                    .put("pinned", count(db, "SELECT COUNT(*) FROM anime WHERE is_pinned=1"))
                    .put("watched", count(db, "SELECT COUNT(*) FROM episodes WHERE watched=1"))
                    .put("in_progress", count(db, "SELECT COUNT(*) FROM episodes WHERE progress>0 AND watched=0"))
                    .put("missing", count(db, "SELECT COUNT(*) FROM episodes WHERE missing=1"))
                    .put("history", count(db, "SELECT COUNT(*) FROM episodes WHERE last_played_at IS NOT NULL"))
                    .put("folders", count(db, "SELECT COUNT(*) FROM folders"))
                    .put("genres", count(db, "SELECT COUNT(*) FROM genres"))
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
