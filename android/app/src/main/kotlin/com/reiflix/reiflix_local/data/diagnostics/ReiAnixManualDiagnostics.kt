package com.reiflix.reiflix_local.data.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.CancellationSignal
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.PerformanceDiagnostics
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reiflix.reiflix_local.scanner.NativeScanController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs only when an explicit diagnostic action is sent from Settings.
 * Results/history live in SharedPreferences, not in a second database.
 * No scanner, downloader, repair, cache invalidation, or library write is started here.
 */
object ReiAnixManualDiagnostics {
    private const val PREFS = "reianix_manual_diagnostics_v1"
    private const val KEY_REPORT = "current_report"
    private const val KEY_HISTORY = "history"
    private const val HISTORY_LIMIT = 10
    private const val LOG_LIMIT = 300
    private const val ADB_INSTRUCTIONS =
        "Para obter logs mais completos, conecte o aparelho por USB com Depuração USB ativa e execute: " +
            "Execute adb devices e depois adb logcat -v threadtime > reianix-logcat.txt; reproduza o problema e pare com Ctrl+C. " +
            "Depois, use Importar logcat (ADB) e selecione o TXT. O arquivo importado será filtrado e sanitizado; não é necessário limpar os logs existentes."

    private val cancellationRequested = AtomicBoolean(false)

    @Volatile
    private var activeLogcatProcess: java.lang.Process? = null

    private val activeSqliteCancellationSignal = AtomicReference<CancellationSignal?>(null)

    private val generalCategories = listOf(
        "system", "logs", "storage", "scanner", "sqlite", "thumbnails", "bridge", "performance",
    )

    private val categoryLabels = mapOf(
        "system" to "Sistema Android e inicialização",
        "logs" to "Logcat e logs de execução",
        "storage" to "Armazenamento e permissões",
        "scanner" to "Scanner e indexação",
        "sqlite" to "SQLite e integridade da biblioteca",
        "thumbnails" to "Thumbnails, capas e imagens",
        "bridge" to "Ponte e comunicação interna",
        "performance" to "Desempenho e estabilidade",
    )

    fun requestCancellation() {
        cancellationRequested.set(true)
        runCatching { activeLogcatProcess?.destroy() }
        runCatching { activeSqliteCancellationSignal.get()?.cancel() }
    }

    fun run(context: Context, requestedCategory: String): JSONObject {
        val appContext = context.applicationContext
        val normalized = requestedCategory.trim().lowercase(Locale.ROOT)
        require(normalized == "general" || normalized in categoryLabels.keys) {
            "Categoria de diagnóstico desconhecida."
        }
        cancellationRequested.set(false)
        val startedAt = utcTimestamp()
        val startedElapsed = SystemClock.elapsedRealtime()
        val selected = if (normalized == "general") generalCategories else listOf(normalized)
        val executed = JSONArray()
        var failures = 0
        var partials = 0

        selected.forEach { category ->
            if (cancellationRequested.get() || Thread.currentThread().isInterrupted) return@forEach
            val categoryStart = SystemClock.elapsedRealtime()
            val outcome = try {
                val details = collectCategory(appContext, category)
                val status = details.optString(
                    "collection_status",
                    if (details.optBoolean("available", true)) "COMPLETED" else "PARTIAL",
                )
                details.remove("collection_status")
                Triple(status, details, null as String?)
            } catch (error: Exception) {
                val status = if (cancellationRequested.get()) "CANCELLED" else "FAILED"
                Triple(
                    status,
                    JSONObject()
                        .put("available", false)
                        .put("error_type", error.javaClass.simpleName),
                    error.javaClass.simpleName,
                )
            }
            val elapsed = (SystemClock.elapsedRealtime() - categoryStart).coerceAtLeast(0L)
            val entry = JSONObject()
                .put("id", category)
                .put("label", categoryLabels[category] ?: category)
                .put("status", outcome.first)
                .put("collected_at", utcTimestamp())
                .put("duration_ms", elapsed)
                .put("result", outcome.second)
            if (outcome.third != null) entry.put("error_type", outcome.third)
            persistCategory(appContext, category, entry)
            executed.put(category)
            when (outcome.first) {
                "FAILED" -> failures++
                "PARTIAL", "CANCELLED" -> partials++
            }
            ReiAnixDiagnosticTimeline.record(
                name = "MANUAL_DIAGNOSTIC_CATEGORY_FINISHED",
                severity = if (outcome.first == "FAILED") "ERROR" else "INFO",
                source = "ReiAnixManualDiagnostics",
                result = outcome.first,
                context = mapOf("category" to category, "duration_ms" to elapsed),
            )
            if (cancellationRequested.get()) return@forEach
        }

        val cancelled = cancellationRequested.get() || Thread.currentThread().isInterrupted
        val status = when {
            cancelled -> "CANCELLED"
            failures > 0 || partials > 0 -> "PARTIAL"
            executed.length() == 0 -> "CANCELLED"
            else -> "COMPLETED"
        }
        val summary = JSONObject()
            .put("requested_category", normalized)
            .put("status", status)
            .put("started_at", startedAt)
            .put("completed_at", utcTimestamp())
            .put("duration_ms", (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L))
            .put("categories_executed", executed)
            .put("failed_categories", failures)
            .put("partial_categories", partials)
        appendHistory(appContext, summary)
        ReiAnixDiagnosticTimeline.record(
            name = "MANUAL_DIAGNOSTIC_RUN_FINISHED",
            severity = if (status == "PARTIAL") "WARN" else if (status == "CANCELLED") "INFO" else "INFO",
            source = "ReiAnixManualDiagnostics",
            result = status,
            context = mapOf("requested_category" to normalized, "duration_ms" to summary.optLong("duration_ms")),
        )
        return summary
    }

    fun importLogcat(context: Context, rawLogcat: String): JSONObject {
        require(rawLogcat.length <= 5 * 1024 * 1024) {
            "O arquivo de log excede o limite de 5 MiB."
        }
        cancellationRequested.set(false)
        val started = SystemClock.elapsedRealtime()
        val sourceLines = rawLogcat.lineSequence().take(20_000).toList()
        val filtered = filterRelevantLogs(sourceLines)
        val payload = JSONObject()
            .put("available", filtered.isNotEmpty())
            .put("source", "user_imported_adb")
            .put("input_lines", sourceLines.size)
            .put("matching_lines", filtered.size)
            .put("messages", JSONArray(filtered))
            .put("adb_instructions", ADB_INSTRUCTIONS)
        val status = if (filtered.isEmpty()) "PARTIAL" else "COMPLETED"
        val entry = JSONObject()
            .put("id", "logs")
            .put("label", categoryLabels["logs"])
            .put("status", status)
            .put("collected_at", utcTimestamp())
            .put("duration_ms", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            .put("result", payload)
        persistCategory(context.applicationContext, "logs", entry)
        val summary = JSONObject()
            .put("requested_category", "imported_adb_logs")
            .put("status", status)
            .put("started_at", utcTimestamp())
            .put("completed_at", utcTimestamp())
            .put("duration_ms", entry.optLong("duration_ms"))
            .put("categories_executed", JSONArray().put("logs"))
            .put("matching_lines", filtered.size)
        appendHistory(context.applicationContext, summary)
        ReiAnixDiagnosticTimeline.record(
            name = "MANUAL_DIAGNOSTIC_LOGCAT_IMPORTED",
            severity = if (filtered.isEmpty()) "WARN" else "INFO",
            source = "ReiAnixManualDiagnostics",
            result = status,
            context = mapOf("matching_lines" to filtered.size),
        )
        return summary
    }

    /** Export reads the latest manually collected results; it never starts a check. */
    fun exportJson(context: Context): ByteArray {
        val report = currentReport(context.applicationContext)
            .put("exported_at", utcTimestamp())
            .put("export_mode", "explicit_user_action")
            .put("history", history(context.applicationContext))
        return (report.toString(2) + "\n").toByteArray(Charsets.UTF_8)
    }

    fun clear(context: Context) {
        requestCancellation()
        val cleared = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        check(cleared) { "Não foi possível limpar os dados locais de diagnóstico." }
        ReiAnixDiagnosticTimeline.clear()
    }

    fun resultSummary(context: Context): String {
        val categories = currentReport(context.applicationContext).optJSONObject("categories") ?: JSONObject()
        if (categories.length() == 0) return "Nenhuma categoria coletada. Executar esta tela não inicia verificações."
        val lines = mutableListOf<String>()
        val order = generalCategories + categories.keys().asSequence().filter { it !in generalCategories }.toList()
        order.distinct().forEach { key ->
            val entry = categories.optJSONObject(key) ?: return@forEach
            val result = entry.optJSONObject("result")
            val details = when (key) {
                "system" -> result?.let {
                    "Android ${it.optString("android_release", "?")} (API ${it.optInt("android_api", 0)}); " +
                        "dependências nativas: ${if (it.optBoolean("native_dependencies_available")) "disponíveis" else "indisponíveis"}"
                }
                "logs" -> result?.let {
                    val source = it.optString("source", it.optString("collection_scope", "Logcat"))
                    source + "; " + it.optInt("matching_lines") + " linhas relevantes" +
                        if (!it.optBoolean("available")) "; acesso limitado ou sem registros" else ""
                }
                "storage" -> result?.let {
                    "SAF: ${it.optInt("persisted_saf_roots_accessible")}/${it.optInt("persisted_saf_roots_checked")} pastas acessíveis verificadas; " +
                        "${it.optInt("persisted_saf_grants")} autorizações persistentes; " +
                        "vídeos: ${if (it.optBoolean("read_media_video_granted")) "permitido" else "sem permissão"}; " +
                        "nova varredura: não iniciada"
                }
                "scanner" -> result?.let {
                    "operações rastreadas: " + it.optInt("tracked_operations") +
                        "; em execução: " + it.optInt("active_operations") +
                        "; nova varredura: não iniciada"
                }
                "sqlite" -> result?.let {
                    if (!it.optBoolean("available")) "Banco/consulta indisponível: " + it.optString("error_type", "sem detalhes")
                    else "schema " + it.optInt("schema_version") +
                        "; quick_check=" + it.optJSONArray("quick_check").let { check ->
                            if (check == null || check.length() == 0) "sem resultado" else check.optString(0)
                        } +
                        "; violações FK=${it.optInt("foreign_key_violations")}" +
                        "; grupos duplicados=${if (it.isNull("duplicate_media_identity_groups")) "não verificável" else it.optLong("duplicate_media_identity_groups").toString()}"
                }
                "thumbnails" -> result?.let {
                    "eventos: " + it.optInt("timeline_event_count") +
                        "; falhas registradas: " + it.optInt("failure_events") +
                        "; cache: " + it.optInt("cache_file_count") + " arquivos / " + it.optLong("cache_bytes") + " bytes"
                }
                "bridge" -> result?.let { "eventos da ponte/mailbox: " + it.optInt("timeline_event_count") }
                "performance" -> result?.let {
                    val events = it.optJSONArray("recent_events")
                    "eventos de desempenho correlacionados: " + (events?.length() ?: 0) +
                        "; teste de estresse: não executado"
                }
                else -> null
            }
            val detail = when {
                result?.has("error_type") == true -> " • " + result.optString("error_type")
                result?.has("matching_lines") == true -> " • " + result.optInt("matching_lines") + " linhas relevantes"
                result?.has("available") == true && !result.optBoolean("available") -> " • dados indisponíveis"
                else -> ""
            }
            lines += (categoryLabels[key] ?: key) + ": " + entry.optString("status", "UNKNOWN") +
                " • " + entry.optString("collected_at", "timestamp unavailable") +
                " (" + entry.optLong("duration_ms") + " ms)" + detail +
                (details?.let { "\n    " + it } ?: "")
            if (key == "logs" && result?.optJSONArray("messages")?.length()?.let { it > 0 } == true) {
                val messages = result.optJSONArray("messages")!!
                val sample = (maxOf(0, messages.length() - 3) until messages.length())
                    .map { messages.optString(it) }
                lines += sample.map { "    " + it }.joinToString("\n")
            }
        }
        return lines.joinToString("\n")
    }

    fun historySummary(context: Context): String {
        val recent = history(context.applicationContext)
        if (recent.length() == 0) return "Histórico vazio: ainda não foi executada nenhuma verificação manual."
        return (0 until minOf(recent.length(), 5)).mapNotNull { index ->
            val item = recent.optJSONObject(index) ?: return@mapNotNull null
            item.optString("completed_at") + " • " + item.optString("requested_category") +
                " • " + item.optString("status") +
                " • categorias: " + (item.optJSONArray("categories_executed")?.length() ?: 0)
        }.joinToString("\n")
    }

    private fun collectCategory(context: Context, category: String): JSONObject {
        check(!cancellationRequested.get()) { "CANCELLED" }
        return when (category) {
            "system" -> collectSystem(context)
            "logs" -> collectLogcat()
            "storage" -> collectStorage(context)
            "scanner" -> collectScanner()
            "sqlite" -> collectSqlite(context)
            "thumbnails" -> collectThumbnails(context)
            "bridge" -> collectBridge()
            "performance" -> collectPerformance()
            else -> throw IllegalArgumentException("Categoria desconhecida.")
        }
    }

    private fun collectSystem(context: Context): JSONObject {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val dependencies = ReiAnixAppDependencies.existingOrNull(context.applicationContext)
        val events = ReiAnixDiagnosticTimeline.snapshotJson(100)
        val lifecycleNames = mutableListOf<String>()
        for (index in 0 until events.length()) {
            val name = events.optJSONObject(index)?.optString("name").orEmpty()
            if (name.contains("BOOT", true) || name.contains("INITIAL", true) ||
                name.contains("ACTIVITY", true) || name.contains("LIFECYCLE", true) ||
                name.contains("EXCEPTION", true) || name.contains("FAILED", true)) {
                lifecycleNames.add(name)
            }
        }
        val runtime = Runtime.getRuntime()
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: 0L
        }
        return JSONObject()
            .put("available", true)
            .put("android_release", Build.VERSION.RELEASE.orEmpty())
            .put("android_api", Build.VERSION.SDK_INT)
            .put("app_id", context.packageName)
            .put("app_version", packageInfo?.versionName ?: BuildConfig.VERSION_NAME)
            .put("app_version_code", versionCode)
            .put("process_id", Process.myPid())
            .put("native_dependencies_available", dependencies != null)
            .put("native_recovery_required", dependencies?.bootstrap?.state?.value?.recoveryRequired ?: JSONObject.NULL)
            .put("process_uptime_ms", SystemClock.elapsedRealtime())
            .put("process_cpu_time_ms", Process.getElapsedCpuTime())
            .put("memory", JSONObject()
                .put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
                .put("java_max_bytes", runtime.maxMemory())
                .put("native_pss_kb", memory.totalPss))
            .put("requested_permissions", JSONObject()
                .put("read_external_storage", hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE))
                .put("read_media_video", hasPermission(context, "android.permission.READ_MEDIA_VIDEO"))
                .put("manage_external_storage", Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()))
            .put("recent_lifecycle_or_error_events", JSONArray(lifecycleNames.take(40)))
            .put("lifecycle_event_source", "existing_process_timeline_only")
    }

    private fun collectStorage(context: Context): JSONObject {
        val grants = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        val readable = grants.count { it.isReadPermission }
        val writable = grants.count { it.isWritePermission }
        val files = context.filesDir
        val cache = context.cacheDir
        val mediaRead = hasPermission(context, "android.permission.READ_MEDIA_VIDEO")
        val safRootAccess = JSONArray()
        var accessibleSafRoots = 0
        var checkedSafRoots = 0
        // Query only the selected document/tree root; never enumerate children or start a library scan.
        grants.take(100).forEachIndexed { index, grant ->
            if (cancellationRequested.get()) {
                throw java.util.concurrent.CancellationException("Storage diagnostic cancelled by user.")
            }
            checkedSafRoots++
            val rootAccess = try {
                val uri = grant.uri
                val documentUri = if (DocumentsContract.isTreeUri(uri)) {
                    DocumentsContract.buildDocumentUriUsingTree(
                        uri,
                        DocumentsContract.getTreeDocumentId(uri),
                    )
                } else {
                    uri
                }
                context.contentResolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { cursor -> cursor.moveToFirst() } ?: false
            } catch (error: Exception) {
                safRootAccess.put(
                    JSONObject()
                        .put("index", index + 1)
                        .put("accessible", false)
                        .put("read_permission", grant.isReadPermission)
                        .put("write_permission", grant.isWritePermission)
                        .put("error_type", error.javaClass.simpleName),
                )
                return@forEachIndexed
            }
            if (rootAccess) accessibleSafRoots++
            safRootAccess.put(
                JSONObject()
                    .put("index", index + 1)
                    .put("accessible", rootAccess)
                    .put("read_permission", grant.isReadPermission)
                    .put("write_permission", grant.isWritePermission)
                    .put("error_type", if (rootAccess) JSONObject.NULL else "ROOT_QUERY_RETURNED_NO_DOCUMENT"),
            )
        }
        val mediaVisualSelectedRead = Build.VERSION.SDK_INT >= 34 &&
            hasPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        return JSONObject()
            .put("available", true)
            .put("persisted_saf_grants", grants.size)
            .put("persisted_saf_roots_checked", checkedSafRoots)
            .put("persisted_saf_roots_accessible", accessibleSafRoots)
            .put("persisted_saf_roots", safRootAccess)
            .put("persisted_saf_roots_check_limit", 100)
            .put("read_media_visual_user_selected_granted", mediaVisualSelectedRead)
            .put("persisted_saf_read_grants", readable)
            .put("persisted_saf_write_grants", writable)
            .put("read_media_video_granted", mediaRead)
            .put("read_external_storage_granted", hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE))
            .put("broad_storage_access", Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            .put("app_files_dir", JSONObject()
                .put("exists", files.exists())
                .put("readable", files.canRead())
                .put("writable", files.canWrite()))
            .put("app_cache_dir", JSONObject()
                .put("exists", cache.exists())
                .put("readable", cache.canRead())
                .put("writable", cache.canWrite()))
            .put("library_rescan_started", false)
            .put("paths_exported", false)
    }

    private fun collectScanner(): JSONObject {
        val snapshots = NativeScanController.progress.value.values.toList()
        val operations = JSONArray()
        snapshots.sortedByDescending { it.startedAtMillis }.take(25).forEach { item ->
            operations.put(
                JSONObject()
                    .put("state", item.state.name)
                    .put("source", item.source)
                    .put("started_at_ms", item.startedAtMillis)
                    .put("files_found", item.filesFound)
                    .put("files_processed", item.filesProcessed)
                    .put("failures", item.failures)
                    .put("terminal", item.state.isTerminal),
            )
        }
        val active = snapshots.count { !it.state.isTerminal }
        return JSONObject()
            .put("available", true)
            .put("active_operations", active)
            .put("tracked_operations", snapshots.size)
            .put("operations", operations)
            .put("new_scan_started", false)
            .put("persisted_catalog_diff", "not_run; no new library scan was requested")
    }

    private fun collectSqlite(context: Context): JSONObject {
        val file = ReiAnixSqliteDatabase.canonicalFile(context.applicationContext)
        if (!file.isFile) {
            return JSONObject().put("available", false).put("status_detail", "canonical_database_missing")
                .put("database_exists", false)
        }
        val cancellationSignal = CancellationSignal()
        activeSqliteCancellationSignal.set(cancellationSignal)
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val quickCheck = JSONArray()
                db.rawQuery("PRAGMA quick_check", null, cancellationSignal).use { cursor ->
                    while (cursor.moveToNext() && quickCheck.length() < 20) quickCheck.put(cursor.getString(0))
                }
                var foreignKeyViolations = 0
                db.rawQuery("PRAGMA foreign_key_check", null, cancellationSignal).use { cursor ->
                    while (cursor.moveToNext()) foreignKeyViolations++
                }
                val tables = mutableSetOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null, cancellationSignal).use { cursor ->
                    while (cursor.moveToNext()) tables += cursor.getString(0)
                }
                val counts = JSONObject()
                listOf("anime", "episodes", "artwork", "folders", "genres", "scan_runs").forEach { table ->
                    if (table in tables) counts.put(table, safeCount(db, "SELECT COUNT(*) FROM \"$table\""))
                }
                val duplicateMediaIdentity = if ("episodes" in tables &&
                    columnExists(db, "episodes", "media_identity")) {
                    safeCount(db, """
                        SELECT COUNT(*) FROM (
                            SELECT media_identity FROM episodes
                            WHERE media_identity IS NOT NULL AND TRIM(media_identity)!=''
                            GROUP BY media_identity HAVING COUNT(*)>1
                        )
                    """.trimIndent())
                } else null
                val integrityOk = quickCheck.length() > 0 && quickCheck.optString(0) == "ok"
                JSONObject()
                    .put("available", true)
                    .put("database_exists", true)
                    .put("database_bytes", file.length())
                    .put("schema_version", pragmaInt(db, "user_version"))
                    .put("quick_check", quickCheck)
                    .put("integrity_ok", integrityOk)
                    .put("foreign_key_violations", foreignKeyViolations)
                    .put("table_counts", counts)
                    .put("duplicate_media_identity_groups", duplicateMediaIdentity ?: JSONObject.NULL)
                    .put("opened_read_only", true)
                    .put("mutations_performed", false)
            }
        } catch (error: Exception) {
            if (cancellationRequested.get()) {
                throw java.util.concurrent.CancellationException("SQLite diagnostic cancelled by user.")
            }
            ReiAnixDiagnosticTimeline.record(
                name = "MANUAL_DIAGNOSTIC_SQLITE_FAILED",
                severity = "ERROR",
                source = "ReiAnixManualDiagnostics",
                result = "FAILED",
                error = error,
            )
            JSONObject().put("available", false).put("error_type", error.javaClass.simpleName)
        } finally {
            activeSqliteCancellationSignal.compareAndSet(cancellationSignal, null)
        }
    }

    private fun collectThumbnails(context: Context): JSONObject {
        val events = ReiAnixDiagnosticTimeline.snapshotJson(300)
        val selected = JSONArray()
        var requested = 0
        var completed = 0
        var failed = 0
        var cacheHits = 0
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val name = event.optString("name")
            if (!name.contains("THUMBNAIL", true) &&
                !name.contains("ARTWORK", true) &&
                !name.contains("IMAGE", true) &&
                !name.contains("COIL", true)) continue
            selected.put(event)
            if (name.contains("REQUEST", true) || name.contains("STARTED", true)) requested++
            if (name.contains("READY", true) || name.contains("FINISHED", true) || name.contains("CACHE_HIT", true)) completed++
            if (name.contains("FAILED", true) || name.contains("ERROR", true)) failed++
            if (name.contains("CACHE_HIT", true)) cacheHits++
        }
        val cacheDirectory = File(context.cacheDir, "reiflix/thumbnails")
        return JSONObject()
            .put("available", true)
            .put("timeline_event_count", selected.length())
            .put("requested_or_started_events", requested)
            .put("completed_or_cache_hit_events", completed)
            .put("failure_events", failed)
            .put("cache_hit_events", cacheHits)
            .put("cache_directory_exists", cacheDirectory.isDirectory)
            .put("cache_directory_readable", cacheDirectory.canRead())
            .put("cache_file_count", if (cacheDirectory.isDirectory) boundedFileCount(cacheDirectory) else 0)
            .put("cache_bytes", if (cacheDirectory.isDirectory) boundedDirectoryBytes(cacheDirectory) else 0L)
            .put("recent_events", selected)
            .put("full_paths_exported", false)
            .put("new_images_loaded_or_downloaded", false)
            .put("cache_cleared", false)
    }

    private fun collectBridge(): JSONObject {
        val events = ReiAnixDiagnosticTimeline.snapshotJson(300)
        val selected = JSONArray()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val name = event.optString("name")
            if (listOf("BRIDGE", "MAILBOX", "REQUEST", "HANDOFF", "DISPATCH", "PENDING", "DROPPED")
                    .any { name.contains(it, true) }) {
                selected.put(event)
            }
        }
        return JSONObject()
            .put("available", true)
            .put("timeline_event_count", selected.length())
            .put("recent_events", JSONArray().also { target ->
                val start = maxOf(0, selected.length() - 60)
                for (index in start until selected.length()) target.put(selected.optJSONObject(index))
            })
            .put("pending_mailbox_files_inspected", false)
            .put("new_messages_sent", false)
    }

    private fun collectPerformance(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("performance_snapshot", PerformanceDiagnostics.snapshotJson())
            .put("recent_events", filteredTimelineEvents(
                setOf("PERFORMANCE", "SLOW", "ANR", "MEMORY", "FRAME", "JANK"),
                80,
            ))
            .put("stress_test_started", false)

    private fun collectLogcat(): JSONObject {
        val process: java.lang.Process
        return try {
            process = ProcessBuilder(
                "logcat", "-d", "-t", "1000", "--pid", Process.myPid().toString(), "-v", "threadtime",
            ).redirectErrorStream(true).start()
            activeLogcatProcess = process
            val outputLines = Collections.synchronizedList(mutableListOf<String>())
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (outputLines.size < 2500) outputLines.add(line) }
                    }
                }
            }, "ReiAnix-Diagnostic-Logcat").apply {
                isDaemon = true
                start()
            }
            val exited = process.waitFor(5, TimeUnit.SECONDS)
            if (!exited || cancellationRequested.get()) {
                process.destroyForcibly()
                reader.join(300)
                return logcatUnavailable(if (cancellationRequested.get()) "cancelled_by_user" else "logcat_timeout")
            }
            reader.join(1000)
            val rawLines = outputLines.toList()
            if (process.exitValue() != 0) {
                return logcatUnavailable("logcat_exit_code_" + process.exitValue())
            }
            if (rawLines.isEmpty()) return logcatUnavailable("logcat_no_output_or_access_restricted")
            val permissionDenied = rawLines.any {
                it.contains("permission denied", ignoreCase = true) ||
                    it.contains("access denied", ignoreCase = true) ||
                    it.contains("not allowed", ignoreCase = true)
            }
            if (permissionDenied) return logcatUnavailable("logcat_access_denied")
            val filtered = filterRelevantLogs(rawLines)
            JSONObject()
                .put("available", true)
                .put("collection_scope", "process_uid_only")
                .put("process_id", Process.myPid())
                .put("raw_lines_read", outputLines.size)
                .put("matching_lines", filtered.size)
                .put("messages", JSONArray(filtered))
                .put("adb_instructions", ADB_INSTRUCTIONS)
                .put("system_wide_logcat_available", false)
                .put("collection_note", "Android may restrict cross-process/system log access; this is not a claim that full Logcat was collected.")
        } catch (error: Exception) {
            logcatUnavailable(
                if (error is IOException) "logcat_command_unavailable_or_denied"
                else error.javaClass.simpleName,
            )
        } finally {
            activeLogcatProcess = null
        }
    }

    private fun logcatUnavailable(reason: String): JSONObject =
        JSONObject()
            .put("available", false)
            .put("collection_status", if (cancellationRequested.get()) "CANCELLED" else "PARTIAL")
            .put("reason", reason)
            .put("messages", JSONArray())
            .put("adb_instructions", ADB_INSTRUCTIONS)
            .put("limitation", "O processo do aplicativo não conseguiu confirmar acesso ao Logcat necessário. Nenhuma permissão privilegiada será solicitada.")

    private fun filterRelevantLogs(lines: List<String>): List<String> {
        val relevant = Regex(
            "(?i)(FATAL EXCEPTION|AndroidRuntime|SecurityException|SQLiteException|database is locked|" +
                "FileNotFoundException|EACCES|Permission denied|BitmapFactory|ImageDecoder|Coil|" +
                "THUMBNAIL|ARTWORK|Application Not Responding|ANR in |OutOfMemoryError|GC overhead|" +
                "ReiAnix.*(FAILED|ERROR)|NATIVE_.*(FAILED|ERROR)|Exception|Caused by:)",
        )
        val result = mutableListOf<String>()
        var continuation = 0
        lines.takeLast(2500).forEach { line ->
            if (relevant.containsMatchIn(line)) continuation = 5
            if (continuation > 0 && result.size < LOG_LIMIT) {
                result += sanitizeLogLine(line)
                continuation--
            }
        }
        return result.takeLast(LOG_LIMIT)
    }

    private fun sanitizeLogLine(raw: String): String {
        var line = raw.take(1000)
        line = line.replace(Regex("(?i)content://[^\\s]+"), "<content-uri>")
        line = line.replace(Regex("(?i)https?://[^\\s]+"), "<url>")
        line = line.replace(
            Regex("(?i)(/data/user/\\d+/[^\\s,;]+|/data/data/[^\\s,;]+|/storage/emulated/\\d+/[^\\s,;]+|/sdcard/[^\\s,;]+)"),
            "<path>",
        )
        line = line.replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "<email>")
        line = Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?|(?:token|password|cookie|secret|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+")
            .replace(line) { match -> match.groupValues[1] + "<redacted>" }
        return line
    }

    private fun filteredTimelineEvents(tokens: Set<String>, limit: Int): JSONArray {
        val source = ReiAnixDiagnosticTimeline.snapshotJson(300)
        val output = JSONArray()
        for (index in 0 until source.length()) {
            val event = source.optJSONObject(index) ?: continue
            val name = event.optString("name")
            if (tokens.any { name.contains(it, true) }) output.put(event)
            if (output.length() >= limit) break
        }
        return output
    }

    private fun persistCategory(context: Context, category: String, entry: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = currentReport(context)
        val categories = report.optJSONObject("categories") ?: JSONObject()
        categories.put(category, entry)
        report.put("categories", categories)
            .put("updated_at", utcTimestamp())
        check(prefs.edit().putString(KEY_REPORT, report.toString()).commit()) {
            "Não foi possível persistir os resultados de diagnóstico."
        }
    }

    private fun currentReport(context: Context): JSONObject {
        val existing = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REPORT, null)
        if (!existing.isNullOrBlank()) {
            runCatching { JSONObject(existing) }.getOrNull()?.let { return it }
        }
        return JSONObject()
            .put("format", "reianix-manual-diagnostic-v1")
            .put("created_at", utcTimestamp())
            .put("app", JSONObject()
                .put("name", "ReiAnix")
                .put("version", BuildConfig.VERSION_NAME)
                .put("build_type", BuildConfig.BUILD_TYPE))
            .put("categories", JSONObject())
            .put("privacy", JSONObject()
                .put("secrets_exported", false)
                .put("paths_redacted", true)
                .put("raw_file_paths_exported", false)
                .put("authentication_values_exported", false)
                .put("remote_telemetry_sent", false))
    }

    private fun appendHistory(context: Context, summary: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]")) }.getOrDefault(JSONArray())
        val result = JSONArray().put(summary)
        for (index in 0 until minOf(previous.length(), HISTORY_LIMIT - 1)) {
            previous.optJSONObject(index)?.let { result.put(it) }
        }
        check(prefs.edit().putString(KEY_HISTORY, result.toString()).commit()) {
            "Não foi possível persistir o histórico de diagnóstico."
        }
    }

    private fun history(context: Context): JSONArray =
        runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, "[]"))
        }.getOrDefault(JSONArray())

    private fun hasPermission(context: Context, permission: String): Boolean =
        if (Build.VERSION.SDK_INT < 23) false
        else runCatching { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun safeCount(db: SQLiteDatabase, sql: String): Long =
        runCatching {
            db.rawQuery(sql, null, activeSqliteCancellationSignal.get())
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }.getOrDefault(-1L)

    private fun pragmaInt(db: SQLiteDatabase, name: String): Int =
        db.rawQuery("PRAGMA $name", null, activeSqliteCancellationSignal.get())
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        var found = false
        db.rawQuery("PRAGMA table_info(\"$table\")", null, activeSqliteCancellationSignal.get()).use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
        }
        return found
    }

    private fun boundedFileCount(root: File): Int =
        runCatching { root.walkTopDown().take(5000).count { it.isFile } }.getOrDefault(0)

    private fun boundedDirectoryBytes(root: File): Long =
        runCatching { root.walkTopDown().take(5000).filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    private fun utcTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}