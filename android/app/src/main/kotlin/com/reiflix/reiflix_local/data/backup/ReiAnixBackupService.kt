package com.reiflix.reiflix_local.data.backup

import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Native backup/restore boundary for the existing ReiAnix schema-v29 database.
 * Keeps the versioned ZIP/manifest contract compatible with core/backup.py.
 * Restore writes rows to the canonical database in one transaction; it never
 * replaces library.sqlite3 while readers from the still-hybrid runtime exist.
 */
class ReiAnixBackupService(context: Context) {
    private val appContext = context.applicationContext
    private val dataDirectory = File(appContext.filesDir, "data")
    private val databaseFile = File(dataDirectory, DATABASE_NAME)
    private val backupDirectory = File(dataDirectory, "backups")
    private val coversDirectory = File(dataDirectory, "covers")
    private val manualArtworkDirectory = File(coversDirectory, "artwork/manual")

    data class BackupInspection(
        val manifest: JSONObject,
        val counts: JSONObject,
        val invalidOptionalEntries: Set<String>,
        val warnings: List<String>,
    )

    data class RestoreResult(
        val importedRows: Int,
        val missingFiles: Int,
        val manualArtworkReconnected: Int,
        val safetyBackup: File,
        val warnings: List<String>,
        val accountStatePreserved: Boolean = false,
        val reauthenticationRequired: Boolean = true,
    )

    private data class RecoveryAccountSnapshot(
        val rows: List<Pair<String, String>>,
        val readable: Boolean,
    )

    data class IntegrityReport(
        val healthy: Boolean,
        val schemaVersion: Int?,
        val quickCheck: List<String>,
        val foreignKeyViolations: Int,
        val tableCounts: Map<String, Long>,
        val error: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("healthy", healthy)
            .put("schema_version", schemaVersion ?: JSONObject.NULL)
            .put("quick_check", JSONArray(quickCheck))
            .put("foreign_key_violations", foreignKeyViolations)
            .put("table_counts", JSONObject().apply {
                tableCounts.toSortedMap().forEach { (key, value) -> put(key, value) }
            })
            .put("error", error ?: JSONObject.NULL)
    }

    init {
        check(dataDirectory.isDirectory || dataDirectory.mkdirs()) {
            "Não foi possível acessar o diretório de dados local."
        }
        check(backupDirectory.isDirectory || backupDirectory.mkdirs()) {
            "Não foi possível acessar o diretório de backups."
        }
    }

    fun createBackupFile(destination: File? = null): File = try {
        withCanonicalFileMaintenance { createBackupFileLocked(destination) }.also { file ->
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_BACKUP_CREATED",
                source = "ReiAnixBackupService",
                result = "COMPLETED",
                context = mapOf("archive_bytes" to file.length()),
            )
        }
    } catch (error: Exception) {
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_BACKUP_CREATE_FAILED",
            severity = "ERROR",
            source = "ReiAnixBackupService",
            result = "FAILED",
            error = error,
        )
        throw error
    }

    private fun createBackupFileLocked(destination: File?): File {
        requireCanonicalDatabase()
        val base = destination ?: File(backupDirectory, "reiflix-backup-" + timestamp() + ".zip")
        val target = uniqueFile(if (base.extension.isBlank()) File(base.path + ".zip") else base)
        val snapshot = File(dataDirectory, ".reiflix-backup-" + UUID.randomUUID() + ".sqlite3")
        val temporaryZip = File(target.parentFile ?: backupDirectory, "." + target.name + "." + UUID.randomUUID() + ".tmp")
        try {
            createSanitizedSnapshot(snapshot)
            val artworkRows = portableManualArtwork(snapshot)
            val archiveEntries = JSONObject()
            ZipOutputStream(FileOutputStream(temporaryZip)).use { zip ->
                writeFileEntry(zip, snapshot, "library.sqlite3")
                archiveEntries.put("library.sqlite3", entryDigest(snapshot))
                for (index in 0 until artworkRows.length()) {
                    val artwork = artworkRows.optJSONObject(index) ?: continue
                    if (!artwork.optBoolean("portable")) continue
                    val source = File(artwork.getString("original_path"))
                    val member = artwork.getString("member")
                    writeFileEntry(zip, source, member)
                    archiveEntries.put(member, entryDigest(source))
                }
                val manifest = buildManifest(snapshot, artworkRows, archiveEntries)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(prettyJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val inspection = inspectBackup(temporaryZip)
            check(inspection.manifest.optString("format") == FORMAT) {
                "O backup criado não passou na validação do formato."
            }
            syncFile(temporaryZip)
            check(temporaryZip.renameTo(target)) {
                "Não foi possível publicar atomicamente o arquivo de backup."
            }
            syncDirectory(target.parentFile ?: backupDirectory)
            return target
        } finally {
            snapshot.delete()
            temporaryZip.delete()
        }
    }

    fun inspectBackup(file: File): BackupInspection {
        require(file.isFile) { "Arquivo de backup não encontrado." }
        require(file.length() in 1..MAX_ARCHIVE_BYTES) { "Arquivo de backup vazio ou maior que o limite suportado." }
        try {
            ZipFile(file).use { zip ->
                val entries = linkedMapOf<String, ZipEntry>()
                val enumeration = zip.entries()
                var count = 0
                var declaredTotal = 0L
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    count++
                    require(count <= MAX_MEMBER_COUNT) { "Backup contém entradas demais." }
                    val name = safeMemberName(entry.name)
                    require(!entry.isDirectory) { "Entradas de diretório não são permitidas no backup." }
                    require(entries.put(name, entry) == null) { "Backup contém entradas duplicadas." }
                    require(name == "manifest.json" || name == "library.sqlite3" || name.startsWith(MANUAL_ARTWORK_PREFIX)) {
                        "O backup contém uma entrada não permitida."
                    }
                    require(entry.size <= MAX_MEMBER_BYTES) { "Uma entrada do backup excede o tamanho permitido." }
                    declaredTotal += entry.size.coerceAtLeast(0)
                    require(declaredTotal <= MAX_ARCHIVE_BYTES) { "O backup excede o tamanho total permitido." }
                }
                require(entries.keys.containsAll(REQUIRED_MEMBERS)) {
                    "Backup sem manifest.json ou library.sqlite3."
                }
                val manifestRaw = zip.getInputStream(entries.getValue("manifest.json")).use {
                    readBounded(it, MAX_MANIFEST_BYTES)
                }
                val manifest = try {
                    JSONObject(String(manifestRaw, Charsets.UTF_8))
                } catch (error: Exception) {
                    throw BackupValidationException("manifest.json inválido.", error)
                }
                validateManifestHeader(manifest)
                val invalidOptional = validateIntegrity(zip, entries, manifest)
                val snapshot = File(dataDirectory, ".reiflix-inspect-" + UUID.randomUUID() + ".sqlite3")
                try {
                    zip.getInputStream(entries.getValue("library.sqlite3")).use { input ->
                        FileOutputStream(snapshot).use { output -> copyBounded(input, output, MAX_MEMBER_BYTES) }
                    }
                    validateSnapshot(snapshot, sanitizeUnknownPreferences = false)
                    val counts = databaseCounts(snapshot)
                    return BackupInspection(
                        manifest = manifest,
                        counts = counts,
                        invalidOptionalEntries = invalidOptional,
                        warnings = invalidOptional.sorted().map { "Arte manual opcional ignorada por checksum inválido: " + it },
                    )
                } finally {
                    snapshot.delete()
                }
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException(
                error.message?.takeIf { it.isNotBlank() } ?: "Container ZIP inválido ou truncado.",
                error,
            )
        }
    }

    fun restoreBackup(file: File): RestoreResult = try {
        withCanonicalFileMaintenance { restoreBackupLocked(file) }.also { result ->
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_BACKUP_RESTORE_COMPLETED",
                source = "ReiAnixBackupService",
                result = "COMPLETED",
                context = mapOf(
                    "imported_rows" to result.importedRows,
                    "manual_artwork_reconnected" to result.manualArtworkReconnected,
                ),
            )
        }
    } catch (error: Exception) {
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_BACKUP_RESTORE_FAILED",
            severity = "ERROR",
            source = "ReiAnixBackupService",
            result = "FAILED",
            error = error,
        )
        throw error
    }

    private fun restoreBackupLocked(file: File): RestoreResult {
        val preview = inspectBackup(file)
        val safety = createBackupFile(File(backupDirectory, "pre-restore-" + timestamp() + ".zip"))
        val staging = File(backupDirectory, ".reiflix-restore-" + UUID.randomUUID())
        check(staging.mkdirs()) { "Não foi possível preparar a restauração." }
        val createdAssets = mutableListOf<File>()
        var canonicalCommitCompleted = false
        try {
            val snapshot = File(staging, DATABASE_NAME)
            val mappings = mutableListOf<Pair<String, String>>()
            ZipFile(file).use { zip ->
                zip.getInputStream(zip.getEntry("library.sqlite3")
                    ?: throw BackupValidationException("Backup sem library.sqlite3.")).use { input ->
                    FileOutputStream(snapshot).use { output -> copyBounded(input, output, MAX_MEMBER_BYTES) }
                }
                validateSnapshot(snapshot, sanitizeUnknownPreferences = true)
                val artwork = preview.manifest.optJSONArray("artwork") ?: JSONArray()
                for (index in 0 until artwork.length()) {
                    val item = artwork.optJSONObject(index) ?: continue
                    if (!item.optBoolean("portable")) continue
                    val member = safeMemberName(item.optString("member"))
                    if (member in preview.invalidOptionalEntries) continue
                    val entry = zip.getEntry(member) ?: continue
                    val stagedAsset = File(staging, "asset-" + index + safeSuffix(member))
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(stagedAsset).use { output -> copyBounded(input, output, MAX_MEMBER_BYTES) }
                    }
                    val expectedHash = item.optString("sha256").lowercase(Locale.ROOT)
                    require(expectedHash.matches(Regex("[0-9a-f]{64}")) && sha256(stagedAsset) == expectedHash) {
                        "Checksum inválido para arte manual."
                    }
                    require(stagedAsset.length() == item.optLong("size", stagedAsset.length())) {
                        "Tamanho inválido para arte manual."
                    }
                    val target = File(manualArtworkDirectory, expectedHash + safeSuffix(member))
                    if (target.exists()) {
                        require(sha256(target) == expectedHash) { "Já existe um arquivo de arte manual conflitante." }
                    } else {
                        check(manualArtworkDirectory.isDirectory || manualArtworkDirectory.mkdirs()) {
                            "Não foi possível preparar o diretório de arte manual."
                        }
                        val temporaryAsset = File(manualArtworkDirectory, "." + target.name + "." + UUID.randomUUID() + ".tmp")
                        try {
                            stagedAsset.copyTo(temporaryAsset, overwrite = true)
                            syncFile(temporaryAsset)
                            check(temporaryAsset.renameTo(target)) { "Não foi possível publicar a arte manual restaurada." }
                            createdAssets += target
                        } finally {
                            temporaryAsset.delete()
                        }
                    }
                    mappings += item.optString("original_path") to target.absolutePath
                }
            }
            restoreIntoCanonicalDatabase(snapshot, mappings)
            canonicalCommitCompleted = true
            // The restore transaction itself ran integrity_check, foreign_key_check,
            // schema-v29 validation and domain invariants before committing. These
            // post-commit summaries are best-effort so a transient diagnostic read
            // cannot report failure after data has already been committed or delete
            // artwork now referenced by the canonical database.
            val report = runCatching { integrityReport() }.getOrNull()
            val counts = report?.takeIf { it.healthy }?.tableCounts
                ?.let { tableCounts ->
                    JSONObject().apply { tableCounts.forEach { (key, value) -> put(key, value) } }
                }
                ?: runCatching { databaseCounts(databaseFile) }.getOrNull()
                ?: preview.counts
            val missingFiles = runCatching { countMissingEpisodes() }.getOrNull()
            val postCommitWarning = if (report?.healthy == true) {
                emptyList()
            } else {
                listOf("A restauração foi validada antes do commit; a leitura de integridade pós-commit não ficou disponível.")
            }
            return RestoreResult(
                importedRows = (
                    (counts.optLong("anime", 0L)) + counts.optLong("episodes", 0L)
                ).toInt(),
                missingFiles = missingFiles ?: -1,
                manualArtworkReconnected = mappings.size,
                safetyBackup = safety,
                warnings = listOf(
                    "Credenciais da sessão Google não são incluídas; o perfil local existente permanece na tabela account.",
                    "Os arquivos de vídeo não são incluídos no backup.",
                    "Capas remotas e thumbnails gerados podem ser reconstruídos.",
                ) + preview.warnings + postCommitWarning,
                accountStatePreserved = true,
                reauthenticationRequired = false,
            )
        } catch (error: Exception) {
            if (!canonicalCommitCompleted) createdAssets.forEach { asset -> runCatching { asset.delete() } }
            if (error is BackupValidationException) throw error
            throw BackupRestoreException(
                error.message?.takeIf { it.isNotBlank() } ?: "A restauração foi revertida; o catálogo anterior foi preservado.",
                error,
                safety,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Creates an explicit, non-destructive raw safety snapshot from the current SQLite
     * file, sidecars and interrupted-promotion remnants. This is a diagnostic/recovery
     * copy, not a ZIP v1 archive and not something restoreBackup can import directly.
     */
    fun createRecoverySafetySnapshotForUser(): File = withCanonicalFileMaintenance {
        val hasDatabase = databaseFile.isFile
        val hasSidecar = listOf("-wal", "-shm", "-journal").any {
            File(databaseFile.absolutePath + it).isFile
        }
        val hasInterruptedPromotion = dataDirectory.listFiles().orEmpty().any {
            it.isFile && (
                it.name.startsWith("." + DATABASE_NAME + ".previous-") ||
                    it.name.startsWith("." + DATABASE_NAME + ".candidate-")
                )
        }
        require(hasDatabase || hasSidecar || hasInterruptedPromotion) {
            "Não há arquivo SQLite ou remanescente recuperável para criar um snapshot."
        }
        createRecoverySafetySnapshot()
    }

    /**
     * Recovery-only restore path for an unreadable or incompatible canonical database.
     * The incoming archive is fully inspected before touching the active path. The
     * previous database and SQLite sidecars are preserved byte-for-byte before an
     * adjacent staged file is promoted into place.
     */
    fun restoreRecoveryBackup(file: File): RestoreResult = try {
        withCanonicalFileMaintenance { restoreRecoveryBackupLocked(file) }.also { result ->
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_RECOVERY_RESTORE_COMPLETED",
                source = "ReiAnixBackupService",
                result = "COMPLETED",
                context = mapOf(
                    "imported_rows" to result.importedRows,
                    "manual_artwork_reconnected" to result.manualArtworkReconnected,
                ),
            )
        }
    } catch (error: Exception) {
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_RECOVERY_RESTORE_FAILED",
            severity = "ERROR",
            source = "ReiAnixBackupService",
            result = "FAILED",
            error = error,
        )
        throw error
    }

    private fun restoreRecoveryBackupLocked(file: File): RestoreResult {
        val preview = inspectBackup(file)
        // The backup format intentionally excludes account/authentication. Preserve
        // the existing non-secret profile key/value rows only when SQLite can still
        // read that table; otherwise surface the legacy reauthentication outcome.
        val accountSnapshot = readRecoverableAccountSnapshot()
        val recoverySnapshot = createRecoverySafetySnapshot()
        val token = UUID.randomUUID().toString()
        val staging = File(dataDirectory, ".recovery-restore-" + token)
        val candidate = File(dataDirectory, "." + DATABASE_NAME + ".candidate-" + token)
        val displaced = File(dataDirectory, "." + DATABASE_NAME + ".previous-" + token)
        val sidecarPairs = mutableListOf<Pair<File, File>>()
        val createdAssets = mutableListOf<File>()
        val artworkMappings = mutableListOf<Pair<String, String>>()
        var previousDatabaseMoved = false
        var replacementPromoted = false

        try {
            check(staging.isDirectory || staging.mkdirs()) {
                "Não foi possível preparar a restauração de recuperação."
            }
            val stagedDatabase = File(staging, DATABASE_NAME)
            ZipFile(file).use { zip ->
                val databaseEntry = zip.getEntry(DATABASE_NAME)
                    ?: throw BackupValidationException("Backup sem library.sqlite3.")
                zip.getInputStream(databaseEntry).use { input ->
                    FileOutputStream(stagedDatabase).use { output ->
                        copyBounded(input, output, MAX_MEMBER_BYTES)
                        output.fd.sync()
                    }
                }
                validateSnapshot(stagedDatabase, sanitizeUnknownPreferences = true)

                val artwork = preview.manifest.optJSONArray("artwork") ?: JSONArray()
                for (index in 0 until artwork.length()) {
                    val item = artwork.optJSONObject(index) ?: continue
                    if (!item.optBoolean("portable")) continue
                    val member = safeMemberName(item.optString("member"))
                    if (member in preview.invalidOptionalEntries) continue
                    val entry = zip.getEntry(member) ?: continue
                    val stagedAsset = File(staging, "asset-" + index + safeSuffix(member))
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(stagedAsset).use { output ->
                            copyBounded(input, output, MAX_MEMBER_BYTES)
                            output.fd.sync()
                        }
                    }
                    val expectedHash = item.optString("sha256").lowercase(Locale.ROOT)
                    require(expectedHash.matches(Regex("[0-9a-f]{64}")) && sha256(stagedAsset) == expectedHash) {
                        "Checksum inválido para arte manual."
                    }
                    require(stagedAsset.length() == item.optLong("size", stagedAsset.length())) {
                        "Tamanho inválido para arte manual."
                    }
                    val target = File(manualArtworkDirectory, expectedHash + safeSuffix(member))
                    if (target.exists()) {
                        require(sha256(target) == expectedHash) {
                            "Já existe um arquivo de arte manual conflitante."
                        }
                    } else {
                        check(manualArtworkDirectory.isDirectory || manualArtworkDirectory.mkdirs()) {
                            "Não foi possível preparar o diretório de arte manual."
                        }
                        val temporaryAsset = File(
                            manualArtworkDirectory,
                            "." + target.name + "." + UUID.randomUUID() + ".tmp",
                        )
                        try {
                            stagedAsset.copyTo(temporaryAsset, overwrite = false)
                            syncFile(temporaryAsset)
                            check(temporaryAsset.renameTo(target)) {
                                "Não foi possível publicar a arte manual restaurada."
                            }
                            createdAssets += target
                        } finally {
                            temporaryAsset.delete()
                        }
                    }
                    artworkMappings += item.optString("original_path") to target.absolutePath
                }
            }

            restoreRecoverableAccountSnapshot(stagedDatabase, accountSnapshot)
            reconnectArtworkReferences(stagedDatabase, artworkMappings)
            validateSnapshot(stagedDatabase, sanitizeUnknownPreferences = false)
            FileInputStream(stagedDatabase).use { input ->
                FileOutputStream(candidate).use { output ->
                    copyBounded(input, output, MAX_MEMBER_BYTES)
                    output.fd.sync()
                }
            }
            validateSnapshot(candidate, sanitizeUnknownPreferences = false)

            // A live WAL/journal must never be allowed to attach itself to the new
            // database. Preserve and detach every sidecar before promoting the candidate.
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val activeSidecar = File(databaseFile.absolutePath + suffix)
                if (activeSidecar.isFile) {
                    val preservedSidecar = File(recoverySnapshot, "active-" + activeSidecar.name)
                    check(activeSidecar.renameTo(preservedSidecar)) {
                        "Não foi possível isolar o arquivo SQLite auxiliar " + suffix + "."
                    }
                    sidecarPairs += activeSidecar to preservedSidecar
                }
            }

            if (databaseFile.exists()) {
                check(databaseFile.renameTo(displaced)) {
                    "Não foi possível preservar o banco ativo antes da recuperação."
                }
                previousDatabaseMoved = true
            }
            check(candidate.renameTo(databaseFile)) {
                "Não foi possível promover atomicamente o banco validado."
            }
            replacementPromoted = true

            validateSnapshot(databaseFile, sanitizeUnknownPreferences = false)
            requireCanonicalDatabase()
            val sharedDatabase = ReiAnixAppDependencies.existingOrNull(appContext)?.sqliteDatabase
            val schemaSnapshot = sharedDatabase?.inspectSchema()
            require(schemaSnapshot == null || schemaSnapshot.supportsCurrentContract) {
                "O banco restaurado não corresponde ao contrato SQLite canônico do aplicativo."
            }
            val postRestoreIntegrity = integrityReportLocked()
            require(postRestoreIntegrity.healthy) {
                "O banco restaurado não passou na verificação de integridade posterior à promoção."
            }
            val counts = databaseCounts(databaseFile)
            val missingFiles = countMissingEpisodes()

            // Keep a second copy of the displaced live file inside the recovery bundle;
            // the manifest also contains byte-verified copies of the pre-restore file set.
            if (previousDatabaseMoved && displaced.isFile) {
                val preservedLiveDatabase = File(recoverySnapshot, "active-" + DATABASE_NAME)
                check(displaced.renameTo(preservedLiveDatabase)) {
                    "O banco foi restaurado, mas não foi possível arquivar o arquivo anterior."
                }
                previousDatabaseMoved = false
            }

            return RestoreResult(
                importedRows = (counts.optLong("anime", 0L) + counts.optLong("episodes", 0L)).toInt(),
                missingFiles = missingFiles,
                manualArtworkReconnected = artworkMappings.size,
                safetyBackup = recoverySnapshot,
                warnings = listOf(
                    "A restauração de recuperação preservou uma cópia bruta do SQLite anterior e de seus arquivos auxiliares.",
                    if (accountSnapshot.readable) {
                        if (accountSnapshot.rows.isEmpty()) {
                            "A tabela local de conta pôde ser lida; não havia perfil local para copiar."
                        } else {
                            "O perfil local da conta foi preservado; credenciais e tokens de sessão não são exportados."
                        }
                    } else {
                        "A tabela local de conta não pôde ser lida antes da recuperação; talvez seja necessário entrar novamente."
                    },
                    "Arquivos de vídeo não são incluídos no backup.",
                    "Permissões SAF e acesso ao armazenamento dependem de concessão Android válida.",
                    "Capas remotas e thumbnails gerados podem ser reconstruídos.",
                ) + preview.warnings,
                accountStatePreserved = accountSnapshot.readable,
                reauthenticationRequired = !accountSnapshot.readable,
            )
        } catch (error: Exception) {
            if (replacementPromoted && databaseFile.exists()) {
                val failedReplacement = File(recoverySnapshot, "failed-restored-" + DATABASE_NAME)
                if (!databaseFile.renameTo(failedReplacement)) {
                    // A copy fallback preserves the failed candidate before removing it
                    // from the canonical path so the previous DB can be restored safely.
                    try {
                        FileInputStream(databaseFile).use { input ->
                            FileOutputStream(failedReplacement).use { output ->
                                input.copyTo(output, COPY_BUFFER_BYTES)
                                output.fd.sync()
                            }
                        }
                        check(databaseFile.length() == failedReplacement.length() &&
                            sha256(databaseFile) == sha256(failedReplacement)) {
                            "Não foi possível preservar a base substituta que falhou."
                        }
                        check(databaseFile.delete()) {
                            "Não foi possível retirar do caminho canônico a base que falhou."
                        }
                    } catch (preservationError: Exception) {
                        android.util.Log.e("ReiAnixBackup", "Could not preserve failed recovery database", preservationError)
                        throw BackupRestoreException(
                            "A recuperação falhou e o arquivo substituto não pôde ser isolado. Os arquivos de recuperação foram mantidos em: " +
                                recoverySnapshot.absolutePath,
                            preservationError,
                            recoverySnapshot,
                        )
                    }
                }
            }
            if (previousDatabaseMoved && displaced.exists() && !databaseFile.exists()) {
                if (!displaced.renameTo(databaseFile)) {
                    throw BackupRestoreException(
                        "A recuperação falhou e não foi possível devolver o banco anterior ao caminho canônico. Snapshot preservado em: " +
                            recoverySnapshot.absolutePath,
                        error,
                        recoverySnapshot,
                    )
                }
                previousDatabaseMoved = false
            }
            sidecarPairs.asReversed().forEach { (original, preserved) ->
                if (preserved.exists()) {
                    if (original.exists()) {
                        val failedSidecar = File(recoverySnapshot, "failed-" + original.name + "-" + UUID.randomUUID())
                        original.renameTo(failedSidecar)
                    }
                    if (!preserved.renameTo(original)) {
                        android.util.Log.e("ReiAnixBackup", "Could not restore SQLite sidecar " + original.name, error)
                    }
                }
            }
            createdAssets.forEach { asset -> runCatching { asset.delete() } }
            if (error is BackupValidationException || error is BackupRestoreException) throw error
            throw BackupRestoreException(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "A recuperação foi interrompida; o estado anterior foi preservado quando possível.",
                error,
                recoverySnapshot,
            )
        } finally {
            candidate.delete()
            staging.deleteRecursively()
        }
    }

    private fun readRecoverableAccountSnapshot(): RecoveryAccountSnapshot {
        if (!databaseFile.isFile) return RecoveryAccountSnapshot(emptyList(), readable = false)
        return try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tableExists = countRows(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='account'",
                ) == 1L
                if (!tableExists) {
                    RecoveryAccountSnapshot(emptyList(), readable = false)
                } else {
                    val rows = db.rawQuery("SELECT key,value FROM account ORDER BY key", null).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(cursor.getString(0) to cursor.getString(1))
                            }
                        }
                    }
                    RecoveryAccountSnapshot(rows, readable = true)
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("ReiAnixBackup", "Could not read the current account profile before recovery", error)
            RecoveryAccountSnapshot(emptyList(), readable = false)
        }
    }

    private fun restoreRecoverableAccountSnapshot(
        target: File,
        accountSnapshot: RecoveryAccountSnapshot,
    ) {
        if (!accountSnapshot.readable || accountSnapshot.rows.isEmpty()) return
        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransactionNonExclusive()
            try {
                db.delete("account", null, null)
                accountSnapshot.rows.forEach { (key, value) ->
                    val values = ContentValues().apply {
                        put("key", key)
                        put("value", value)
                    }
                    val rowId = db.insertWithOnConflict(
                        "account",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    check(rowId != -1L) {
                        "Não foi possível preservar o perfil local da conta durante a recuperação."
                    }
                }
                validateDatabaseConnection(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun createRecoverySafetySnapshot(): File {
        val token = UUID.randomUUID().toString()
        val directory = File(backupDirectory, "recovery-snapshot-" + timestamp() + "-" + token.take(8))
        check(directory.isDirectory || directory.mkdirs()) {
            "Não foi possível criar o diretório de snapshot de recuperação."
        }
        val interruptedFiles = dataDirectory.listFiles().orEmpty()
            .filter {
                it.isFile && (
                    it.name.startsWith("." + DATABASE_NAME + ".previous-") ||
                        it.name.startsWith("." + DATABASE_NAME + ".candidate-")
                    )
            }
            .sortedBy { it.name }
        val files = listOf(
            databaseFile,
            File(databaseFile.absolutePath + "-wal"),
            File(databaseFile.absolutePath + "-shm"),
            File(databaseFile.absolutePath + "-journal"),
        ) + interruptedFiles
        val manifestEntries = JSONArray()
        try {
            files.forEach { source ->
                if (!source.isFile) return@forEach
                val destination = File(directory, source.name)
                FileInputStream(source).use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output, COPY_BUFFER_BYTES)
                        output.fd.sync()
                    }
                }
                check(source.length() == destination.length() && sha256(source) == sha256(destination)) {
                    "O snapshot de recuperação não corresponde ao arquivo de origem " + source.name + "."
                }
                manifestEntries.put(
                    JSONObject()
                        .put("name", source.name)
                        .put("size", destination.length())
                        .put("sha256", sha256(destination)),
                )
            }
            val metadata = JSONObject()
                .put("format", "reianix-recovery-snapshot-v1")
                .put("created_at", utcTimestamp())
                .put("database_present", databaseFile.isFile)
                .put("files", manifestEntries)
                .put("note", "Cópia bruta de segurança; não é um backup restaurável do formato rei-flix-backup-v1.")
            FileOutputStream(File(directory, "recovery-snapshot.json")).use { output ->
                output.write(prettyJson(metadata).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            return directory
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun reconnectArtworkReferences(snapshot: File, mappings: List<Pair<String, String>>) {
        if (mappings.isEmpty()) return
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransactionNonExclusive()
            try {
                mappings.forEach { (oldPath, newPath) ->
                    if (oldPath.isBlank() || newPath.isBlank()) return@forEach
                    db.execSQL(
                        "UPDATE artwork SET local_path=?,source_ref=? WHERE local_path=? AND manual=1",
                        arrayOf(newPath, newPath, oldPath),
                    )
                    db.execSQL("UPDATE anime SET cover_cache=? WHERE cover_cache=?", arrayOf(newPath, oldPath))
                }
                validateDatabaseConnection(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun <T> withCanonicalFileMaintenance(block: () -> T): T {
        val sharedDatabase = ReiAnixAppDependencies.existingOrNull(appContext)?.sqliteDatabase
        return if (sharedDatabase != null) {
            sharedDatabase.withExclusiveFileMaintenance { block() }
        } else {
            synchronized(MAINTENANCE_LOCK) { block() }
        }
    }

    fun integrityReport(): IntegrityReport =
        withCanonicalFileMaintenance { integrityReportLocked() }

    private fun integrityReportLocked(): IntegrityReport {
        return try {
            requireCanonicalDatabase()
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val quick = queryStrings(db, "PRAGMA integrity_check")
                val schema = queryLongOrNull(db, "SELECT MAX(version) FROM schema_migrations")?.toInt()
                val fkCount = resultRowCount(db, "PRAGMA foreign_key_check")
                val counts = linkedMapOf<String, Long>()
                REPORT_TABLES.forEach { table -> counts[table] = countRows(db, "SELECT COUNT(*) FROM \"" + table + "\"") }
                val ok = quick == listOf("ok") && fkCount == 0 && schema == SCHEMA_VERSION
                IntegrityReport(
                    healthy = ok,
                    schemaVersion = schema,
                    quickCheck = quick,
                    foreignKeyViolations = fkCount,
                    tableCounts = counts,
                    error = if (ok) null else "Integridade SQLite, schema ou foreign keys inválidos.",
                )
            }
        } catch (error: Exception) {
            IntegrityReport(
                healthy = false,
                schemaVersion = null,
                quickCheck = emptyList(),
                foreignKeyViolations = -1,
                tableCounts = emptyMap(),
                error = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName,
            )
        }
    }

    fun reconcileRestoredFileAvailability(): Int =
        withCanonicalFileMaintenance { reconcileRestoredFileAvailabilityLocked() }

    private fun reconcileRestoredFileAvailabilityLocked(): Int {
        requireCanonicalDatabase()
        val updates = mutableListOf<Pair<Long, Pair<Int, String>>>()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,path,availability_state FROM episodes", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val path = cursor.getString(1)?.trim().orEmpty()
                    val state = cursor.getString(2).orEmpty()
                    if (state == "scope_removed") continue
                    if (path.isBlank()) {
                        updates += id to (1 to "missing")
                    } else if (
                        path.startsWith("content://", true) ||
                        path.startsWith("file://", true) ||
                        path.startsWith("http://", true) ||
                        path.startsWith("https://", true)
                    ) {
                        continue
                    } else {
                        val exists = File(path).isFile
                        updates += id to ((if (exists) 0 else 1) to (if (exists) "available" else "missing"))
                    }
                }
            }
        }
        if (updates.isEmpty()) return 0
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransactionNonExclusive()
            try {
                updates.forEach { (id, result) ->
                    val values = ContentValues().apply {
                        put("missing", result.first)
                        put("availability_state", result.second)
                    }
                    db.update("episodes", values, "id=? AND availability_state!='scope_removed'", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return updates.size
    }

    private fun createSanitizedSnapshot(snapshot: File) {
        requireCanonicalDatabase()
        snapshot.delete()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val escapedPath = snapshot.absolutePath.replace("'", "''")
            // VACUUM INTO creates a consistent standalone SQLite snapshot, including committed data.
            // Unsupported SQLite versions fail explicitly; there is no racy copy-file fallback.
            db.execSQL("VACUUM INTO '" + escapedPath + "'")
        }
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransactionNonExclusive()
            try {
                db.delete("account", null, null)
                db.execSQL("UPDATE folders SET account_id=NULL")
                db.delete("scan_runs", null, null)
                val allowed = exportedPreferenceKeys().toList().sorted()
                val placeholders = allowed.joinToString(",") { "?" }
                db.delete("preferences", "key NOT IN (" + placeholders + ")", allowed.toTypedArray())
                validateDatabaseConnection(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        validateSnapshot(snapshot, sanitizeUnknownPreferences = false)
    }

    private fun validateSnapshot(file: File, sanitizeUnknownPreferences: Boolean) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransactionNonExclusive()
            try {
                require(queryStrings(db, "PRAGMA integrity_check") == listOf("ok")) {
                    "Snapshot SQLite falhou no integrity_check."
                }
                require(resultRowCount(db, "PRAGMA foreign_key_check") == 0) {
                    "Snapshot SQLite contém referências inválidas."
                }
                val schema = queryLongOrNull(db, "SELECT MAX(version) FROM schema_migrations")?.toInt()
                require(schema == SCHEMA_VERSION) { "Schema do backup incompatível com o schema v29." }
                if (sanitizeUnknownPreferences) {
                    val allowed = exportedPreferenceKeys().toList().sorted()
                    val placeholders = allowed.joinToString(",") { "?" }
                    db.delete("preferences", "key NOT IN (" + placeholders + ")", allowed.toTypedArray())
                }
                validateSettingsAndConsumption(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun validateDatabaseConnection(db: SQLiteDatabase) {
        require(queryStrings(db, "PRAGMA integrity_check") == listOf("ok")) {
            "Snapshot SQLite inválido após sanitização."
        }
        require(resultRowCount(db, "PRAGMA foreign_key_check") == 0) {
            "Snapshot SQLite possui referências inválidas."
        }
        validateSettingsAndConsumption(db)
    }

    private fun validateSettingsAndConsumption(db: SQLiteDatabase) {
        db.rawQuery("SELECT key,value FROM preferences", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                val value = cursor.getString(1)
                if (key !in exportedPreferenceKeys()) continue
                if (key == "settings.schema_version") {
                    val settingsSchemaVersion = value.toIntOrNull()
                    require(settingsSchemaVersion == SETTINGS_SCHEMA_VERSION) {
                        "Versão persistida do SettingsStore incompatível com o aplicativo."
                    }
                    continue
                }
                runCatching { ReiAnixSettingsContract.normalize(key, value) }.getOrElse {
                    throw BackupValidationException("Valor inválido no SettingsStore: " + key + ".", it)
                }
            }
        }
        require(countRows(
            db,
            "SELECT COUNT(*) FROM episodes WHERE progress<0 OR duration<0 OR (duration>0 AND progress>duration+0.001)",
        ) == 0L) { "Backup contém progresso/duração inconsistentes." }
        require(countRows(db, "SELECT COUNT(*) FROM episodes WHERE watched NOT IN (0,1)") == 0L) {
            "Backup contém estados de consumo inválidos."
        }
        require(resultRowCount(
            db,
            "SELECT media_identity FROM episodes WHERE media_identity IS NOT NULL AND TRIM(media_identity)!='' " +
                "GROUP BY media_identity HAVING COUNT(*)>1",
        ) == 0) { "Backup contém identidades de mídia duplicadas." }
    }

    private fun restoreIntoCanonicalDatabase(snapshot: File, artworkMappings: List<Pair<String, String>>) {
        requireCanonicalDatabase()
        validateSnapshot(snapshot, sanitizeUnknownPreferences = true)
        val deleteOrder = listOf(
            "episode_observations", "anime_genres", "genre_aliases", "episodes", "artwork",
            "associations", "pending_matches", "scan_runs", "folders", "anime", "genres",
            "preferences", "schema_migrations",
        )
        val insertOrder = listOf(
            "schema_migrations", "preferences", "folders", "anime", "genres", "genre_aliases",
            "episodes", "artwork", "associations", "pending_matches", "anime_genres",
            "episode_observations", "scan_runs",
        )
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("ATTACH DATABASE ? AS restore_db", arrayOf(snapshot.absolutePath))
            try {
                db.beginTransactionNonExclusive()
                try {
                    deleteOrder.forEach { table -> db.execSQL("DELETE FROM main.\"" + table + "\"") }
                    insertOrder.forEach { table ->
                        val mainColumns = tableColumns(db, "main", table)
                        val sourceColumns = tableColumns(db, "restore_db", table)
                        require(mainColumns.isNotEmpty() && mainColumns == sourceColumns) {
                            "Schema incompatível na tabela " + table + "."
                        }
                        val columns = mainColumns.joinToString(",") { "\"" + it + "\"" }
                        db.execSQL(
                            "INSERT INTO main.\"" + table + "\" (" + columns + ") " +
                                "SELECT " + columns + " FROM restore_db.\"" + table + "\"",
                        )
                    }
                    artworkMappings.forEach { (oldPath, newPath) ->
                        if (oldPath.isBlank() || newPath.isBlank()) return@forEach
                        db.execSQL(
                            "UPDATE artwork SET local_path=?,source_ref=? WHERE local_path=? AND manual=1",
                            arrayOf(newPath, newPath, oldPath),
                        )
                        db.execSQL("UPDATE anime SET cover_cache=? WHERE cover_cache=?", arrayOf(newPath, oldPath))
                    }
                    reconcileFileAvailability(db)
                    validateDatabaseConnection(db)
                    require(resultRowCount(
                        db,
                        "SELECT media_identity FROM episodes WHERE media_identity IS NOT NULL AND TRIM(media_identity)!='' " +
                            "GROUP BY media_identity HAVING COUNT(*)>1",
                    ) == 0) { "A restauração produziria identidades de mídia duplicadas." }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                // Do not let a secondary DETACH error mask a completed canonical transaction.
                runCatching { db.execSQL("DETACH DATABASE restore_db") }
            }
        }
    }

    private fun reconcileFileAvailability(db: SQLiteDatabase) {
        val states = mutableListOf<Triple<Long, Int, String>>()
        db.rawQuery("SELECT id,path,availability_state FROM episodes", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val path = cursor.getString(1)?.trim().orEmpty()
                val state = cursor.getString(2).orEmpty()
                if (state == "scope_removed") continue
                if (path.isBlank()) {
                    states += Triple(id, 1, "missing")
                } else if (
                    path.startsWith("content://", true) ||
                    path.startsWith("file://", true) ||
                    path.startsWith("http://", true) ||
                    path.startsWith("https://", true)
                ) {
                    continue
                } else {
                    val exists = File(path).isFile
                    states += Triple(id, if (exists) 0 else 1, if (exists) "available" else "missing")
                }
            }
        }
        states.forEach { (id, missing, state) ->
            db.execSQL(
                "UPDATE episodes SET missing=?,availability_state=? WHERE id=? AND availability_state!='scope_removed'",
                arrayOf(missing, state, id),
            )
        }
    }

    private fun portableManualArtwork(snapshot: File): JSONArray {
        val root = coversDirectory.canonicalFile
        val rows = JSONArray()
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT id,entity_type,entity_id,artwork_type,local_path,source FROM artwork " +
                    "WHERE manual=1 AND local_path IS NOT NULL",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(4)?.trim().orEmpty()
                    var portable = false
                    var member: String? = null
                    var digest: String? = null
                    var size: Long? = null
                    if (path.isNotBlank()) {
                        runCatching {
                            val candidate = File(path).canonicalFile
                            require(candidate.toPath().startsWith(root.toPath()))
                            require(candidate.isFile && candidate.length() <= MAX_MEMBER_BYTES)
                            size = candidate.length()
                            digest = sha256(candidate)
                            member = MANUAL_ARTWORK_PREFIX + digest + safeSuffix(candidate.name)
                            portable = true
                        }
                    }
                    rows.put(JSONObject()
                        .put("artwork_id", cursor.getLong(0))
                        .put("entity_type", cursor.getString(1))
                        .put("entity_id", cursor.getString(2))
                        .put("artwork_type", cursor.getString(3))
                        .put("original_path", path)
                        .put("member", member ?: JSONObject.NULL)
                        .put("sha256", digest ?: JSONObject.NULL)
                        .put("size", size ?: JSONObject.NULL)
                        .put("portable", portable)
                        .put("source", cursor.getString(5) ?: ""))
                }
            }
        }
        return rows
    }

    private fun buildManifest(snapshot: File, artwork: JSONArray, entries: JSONObject): JSONObject {
        val schema = SCHEMA_VERSION
        val portableArtwork = JSONArray()
        for (index in 0 until artwork.length()) {
            val item = artwork.optJSONObject(index) ?: continue
            if (item.optBoolean("portable")) portableArtwork.put(item)
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("format_version", FORMAT_VERSION)
            .put("app", APP_NAME)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("schema_version", schema)
            .put("created_at", utcTimestamp())
            .put("compatibility", JSONObject()
                .put("min_schema", schema)
                .put("max_schema", schema)
                .put("migrations", JSONArray().put("backup-format-v1")))
            .put("contents", JSONObject()
                .put("logical_database", true)
                .put("video_files", false)
                .put("authentication", false)
                .put("temporary_logs", false)
                .put("generated_artwork_cache", false)
                .put("manual_artwork", portableArtwork))
            .put("counts", databaseCounts(snapshot))
            .put("artwork", artwork)
        val payloadHash = sha256(canonicalJson(entries).toByteArray(Charsets.UTF_8))
        val coreHash = sha256(canonicalJson(manifest).toByteArray(Charsets.UTF_8))
        val optional = JSONArray()
        entries.keys().asSequence().filter { it.startsWith(MANUAL_ARTWORK_PREFIX) }.sorted().forEach { optional.put(it) }
        manifest.put("integrity", JSONObject()
            .put("algorithm", "SHA-256")
            .put("entries", entries)
            .put("optional_entries", optional)
            .put("payload_sha256", payloadHash)
            .put("manifest_core_sha256", coreHash))
        return manifest
    }

    private fun validateManifestHeader(manifest: JSONObject) {
        require(manifest.optString("app") == APP_NAME) { "Este arquivo não é um backup do ReiAnix." }
        require(manifest.optString("format") == FORMAT && manifest.optInt("format_version", -1) == FORMAT_VERSION) {
            "Formato de backup incompatível."
        }
        require(manifest.optInt("schema_version", -1) == SCHEMA_VERSION) { "Schema do backup incompatível com o schema v29." }
        val compatibility = manifest.optJSONObject("compatibility")
            ?: throw BackupValidationException("Bloco de compatibilidade inválido.")
        listOf("min_schema", "max_schema").forEach { key ->
            if (compatibility.has(key)) require(compatibility.optInt(key, -1) >= 0) { "Faixa de schema inválida." }
        }
        val integrity = manifest.optJSONObject("integrity")
            ?: throw BackupValidationException("Integridade SHA-256 ausente.")
        require(integrity.optString("algorithm") == "SHA-256") { "Algoritmo de integridade incompatível." }
        require(integrity.optJSONObject("entries")?.has("library.sqlite3") == true) {
            "Manifesto de integridade incompleto."
        }
    }

    private fun validateIntegrity(
        zip: ZipFile,
        actualEntries: Map<String, ZipEntry>,
        manifest: JSONObject,
    ): Set<String> {
        val integrity = manifest.getJSONObject("integrity")
        val stored = integrity.getJSONObject("entries")
        val optional = integrity.optJSONArray("optional_entries") ?: JSONArray()
        val optionalNames = (0 until optional.length()).map { safeMemberName(optional.getString(it)) }.toSet()
        val actual = JSONObject()
        val invalidOptional = linkedSetOf<String>()
        actualEntries.filterKeys { it != "manifest.json" }.toSortedMap().forEach { (name, entry) ->
            val measured = zip.getInputStream(entry).use { digestStream(it, MAX_MEMBER_BYTES) }
            actual.put(name, JSONObject().put("size", measured.second).put("sha256", measured.first))
            val expected = stored.optJSONObject(name)
            if (expected == null ||
                expected.optLong("size", -1L) != measured.second ||
                expected.optString("sha256").lowercase(Locale.ROOT) != measured.first
            ) {
                if (name in optionalNames) invalidOptional += name
                else throw BackupValidationException("Checksum/integridade inválida para " + name + ".")
            }
        }
        val storedNames = stored.keys().asSequence().toSet()
        val actualNames = actual.keys().asSequence().toSet()
        val missing = storedNames - actualNames
        require((missing - optionalNames).isEmpty()) { "Manifesto de integridade não corresponde ao container." }
        val expectedPayload = integrity.optString("payload_sha256")
        val actualPayload = sha256(canonicalJson(actual).toByteArray(Charsets.UTF_8))
        val optionalMissingOrInvalid = missing.intersect(optionalNames) + invalidOptional
        if (optionalMissingOrInvalid.isEmpty()) {
            require(expectedPayload == actualPayload) { "Checksum do payload inválido." }
        }
        val core = JSONObject(canonicalJson(manifest))
        core.remove("integrity")
        val expectedCore = integrity.optString("manifest_core_sha256")
        val actualCore = sha256(canonicalJson(core).toByteArray(Charsets.UTF_8))
        require(expectedCore == actualCore) { "Checksum do manifest inválido." }
        return invalidOptional + missing.intersect(optionalNames)
    }

    private fun databaseCounts(file: File): JSONObject {
        val counts = JSONObject()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            counts.put("folders", countRows(db, "SELECT COUNT(*) FROM folders"))
            counts.put("anime", countRows(db, "SELECT COUNT(*) FROM anime"))
            counts.put("episodes", countRows(db, "SELECT COUNT(*) FROM episodes"))
            counts.put("favorites", countRows(db, "SELECT COUNT(*) FROM anime WHERE favorite=1"))
            counts.put("pinned", countRows(db, "SELECT COUNT(*) FROM anime WHERE is_pinned=1"))
            counts.put("notes", countRows(db, "SELECT COUNT(*) FROM anime WHERE personal_note IS NOT NULL AND TRIM(personal_note)!=''"))
            counts.put("history", countRows(db, "SELECT COUNT(*) FROM episodes WHERE last_played_at IS NOT NULL"))
            counts.put("progress", countRows(db, "SELECT COUNT(*) FROM episodes WHERE progress>0 OR watched=1"))
            counts.put("missing_files", countRows(db, "SELECT COUNT(*) FROM episodes WHERE missing=1"))
            counts.put("genres", countRows(db, "SELECT COUNT(*) FROM genres"))
            counts.put("artwork_references", countRows(db, "SELECT COUNT(*) FROM artwork"))
            counts.put("anilist_matches", countRows(db, "SELECT COUNT(*) FROM anime WHERE anilist_id IS NOT NULL"))
            var tags = 0L
            db.rawQuery("SELECT user_tags FROM anime", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val parsed = runCatching { JSONArray(cursor.getString(0) ?: "[]") }.getOrNull()
                    if (parsed != null && parsed.length() > 0) tags++
                }
            }
            counts.put("tags", tags)
        }
        return counts
    }

    private fun requireCanonicalDatabase() {
        require(databaseFile.isFile) {
            "O banco SQLite canônico não existe; a operação foi interrompida sem criar outro banco."
        }
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(queryStrings(db, "PRAGMA quick_check") == listOf("ok")) { "O banco SQLite canônico falhou no quick_check." }
            require(queryLongOrNull(db, "SELECT MAX(version) FROM schema_migrations")?.toInt() == SCHEMA_VERSION) {
                "O banco SQLite canônico não corresponde ao schema v29."
            }
            require(resultRowCount(db, "PRAGMA foreign_key_check") == 0) {
                "O banco SQLite canônico contém referências inválidas."
            }
        }
    }

    private fun exportedPreferenceKeys(): Set<String> =
        ReiAnixSettingsContract.definitions.keys + "settings.schema_version"

    private fun tableColumns(db: SQLiteDatabase, schema: String, table: String): List<String> {
        require(schema == "main" || schema == "restore_db")
        require(table in RESTORE_TABLES)
        return db.rawQuery("PRAGMA " + schema + ".table_info(\"" + table + "\")", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
    }

    private fun countMissingEpisodes(): Int =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            countRows(db, "SELECT COUNT(*) FROM episodes WHERE missing=1").toInt()
        }

    private fun queryStrings(db: SQLiteDatabase, sql: String): List<String> =
        db.rawQuery(sql, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0).orEmpty()) }
        }

    private fun queryLongOrNull(db: SQLiteDatabase, sql: String): Long? =
        db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun countRows(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun resultRowCount(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { cursor -> var count = 0; while (cursor.moveToNext()) count++; count }

    private fun writeFileEntry(zip: ZipOutputStream, file: File, member: String) {
        zip.putNextEntry(ZipEntry(member))
        FileInputStream(file).use { input -> input.copyTo(zip, bufferSize = COPY_BUFFER_BYTES) }
        zip.closeEntry()
    }

    private fun entryDigest(file: File): JSONObject =
        JSONObject().put("size", file.length()).put("sha256", sha256(file))

    private fun digestStream(input: InputStream, maxBytes: Long): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw BackupValidationException("Uma entrada do backup excede o tamanho permitido.")
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex() to total
    }

    private fun sha256(file: File): String = FileInputStream(file).use { digestStream(it, Long.MAX_VALUE).first }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(value.get(it))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun prettyJson(value: JSONObject): String = value.toString(2) + "\n"

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyBounded(input, output, maxBytes)
        return output.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "O conteúdo excede o tamanho máximo permitido." }
            output.write(buffer, 0, read)
        }
    }

    private fun safeMemberName(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith("/") &&
            !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
            normalized.split('/').none { it == ".." || it == "." } &&
            '\u0000' !in normalized) {
            "O backup contém um caminho inválido."
        }
        return normalized
    }

    private fun safeSuffix(name: String): String {
        val suffix = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (suffix.matches(Regex("[a-z0-9]{1,8}"))) ".$suffix" else ".bin"
    }

    private fun uniqueFile(base: File): File {
        if (!base.exists()) return base
        var index = 1
        while (true) {
            val candidate = File(base.parentFile, base.nameWithoutExtension + "-" + index + "." + base.extension)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    private fun utcTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun syncDirectory(directory: File) {
        runCatching {
            val descriptor = android.system.Os.open(
                directory.absolutePath,
                android.system.OsConstants.O_RDONLY,
                0,
            )
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    class BackupValidationException(message: String, cause: Throwable? = null) : IOException(message, cause)
    class BackupRestoreException(message: String, cause: Throwable, val safetyBackup: File) : IOException(message, cause)

    companion object {
        private val MAINTENANCE_LOCK = Any()
        const val DATABASE_NAME = "library.sqlite3"
        const val SCHEMA_VERSION = 29
        const val SETTINGS_SCHEMA_VERSION = 2
        const val FORMAT = "rei-flix-backup-v1"
        const val FORMAT_VERSION = 1
        const val APP_NAME = "ReiAnix"
        const val MANUAL_ARTWORK_PREFIX = "artwork/manual/"
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val MAX_MEMBER_BYTES = 64L * 1024 * 1024
        const val MAX_MEMBER_COUNT = 2048
        const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
        val REQUIRED_MEMBERS = setOf("manifest.json", "library.sqlite3")
        val RESTORE_TABLES = listOf(
            "episode_observations", "anime_genres", "genre_aliases", "episodes", "artwork",
            "associations", "pending_matches", "scan_runs", "folders", "anime", "genres",
            "preferences", "schema_migrations",
        )
        val REPORT_TABLES = listOf(
            "folders", "anime", "episodes", "episode_observations", "artwork", "associations",
            "pending_matches", "preferences", "genres", "genre_aliases", "anime_genres", "schema_migrations",
            "scan_runs",
        )
    }
}
