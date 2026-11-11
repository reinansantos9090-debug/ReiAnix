package com.reiflix.reiflix_local.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.reiflix.reiflix_local.data.media.MediaFilenameParser
import com.reiflix.reiflix_local.data.sqlite.NativeDiscoveredMedia
import com.reiflix.reiflix_local.data.sqlite.NativeLibraryScanSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Imports videos visible to ReiAnix inside the built-in Downloads/ReiAnix directory.
 * The result is permission-dependent, never proof that an existing file disappeared.
 * No broad-storage permission or synthetic SAF tree grant is used.
 */
internal object DefaultReiAnixFolderScanner {
    const val SOURCE_KEY = "default_downloads:Download/ReiAnix"
    private const val FOLDER = "Download/ReiAnix"
    private const val PREFS = "reianix_default_folder_scan"
    private const val LAST_REVISION = "last_successful_media_revision_v1"
    private const val TAG = "ReiAnixDefaultFolder"

    data class Discovery(
        val snapshot: NativeLibraryScanSnapshot,
        val readable: Boolean,
        val cancelled: Boolean,
        val errors: List<String>,
    )

    internal fun isInFolder(directory: String): Boolean {
        val normalized = directory.replace('\\', '/').trim('/').lowercase(Locale.ROOT)
        val root = FOLDER.lowercase(Locale.ROOT)
        return normalized == root || normalized.startsWith("$root/")
    }

    private fun isVideo(fileName: String, mimeType: String?): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return mimeType?.startsWith("video/", ignoreCase = true) == true ||
            MediaFilenameParser.supportsExtension(".$extension")
    }

    /** MediaStore's generation changes when indexed media changes (Android 11+). */
    fun revision(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
            MediaStore.getVersion(context, volume) + ":" +
                MediaStore.getGeneration(context, volume) + ":" +
                MediaStoreScanner.accessLevel(context)
        }.getOrNull()
    }

    fun isUpToDate(context: Context, revision: String?): Boolean =
        revision != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_REVISION, null) == revision

    fun markIndexed(context: Context, revision: String?) {
        if (revision != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(LAST_REVISION, revision).apply()
        }
    }

    suspend fun discover(
        context: Context,
        operationId: String,
        isCancelled: () -> Boolean,
    ): Discovery = withContext(Dispatchers.IO) {
        val items = ArrayList<NativeDiscoveredMedia>()
        val seenPaths = HashSet<String>()
        val errors = ArrayList<String>()
        var successfulQueries = 0
        var cancelled = false

        if (Build.VERSION.SDK_INT < 29) {
            return@withContext Discovery(
                snapshot(operationId, emptyList(), false, false),
                readable = false, cancelled = false,
                errors = listOf("Android version does not support scoped Downloads queries"),
            )
        }

        val resolver = context.contentResolver
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collections = listOf(
            MediaStore.Video.Media.getContentUri(volume),
            // Also sees ReiAnix-owned downloads without a broad video grant.
            MediaStore.Downloads.getContentUri(volume),
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING,
        )

        for (collection in collections) {
            if (isCancelled()) { cancelled = true; break }
            try {
                val cursor = resolver.query(
                    collection, projection,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    arrayOf("$FOLDER/%"), null,
                )
                if (cursor == null) {
                    errors.add("Provider returned no cursor: $collection")
                    continue
                }
                cursor.use {
                    successfulQueries++
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val mimeColumn = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeColumn = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val modifiedColumn = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val pendingColumn = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    while (it.moveToNext()) {
                        if (isCancelled()) { cancelled = true; break }
                        if (pendingColumn >= 0 && !it.isNull(pendingColumn) &&
                            it.getInt(pendingColumn) != 0) continue
                        val directory = it.getString(pathColumn).orEmpty()
                        if (!isInFolder(directory)) continue
                        val fileName = it.getString(nameColumn)?.trim().orEmpty()
                        if (fileName.isBlank()) continue
                        val mimeType = if (mimeColumn >= 0 && !it.isNull(mimeColumn))
                            it.getString(mimeColumn) else null
                        if (!isVideo(fileName, mimeType)) continue

                        val relativePath = directory.trimEnd('/') + "/" + fileName
                        val id = it.getLong(idColumn)
                        if (id <= 0L || !seenPaths.add(relativePath.lowercase(Locale.ROOT))) continue
                        val scopedPath = relativePath.substring(FOLDER.length).trimStart('/')
                        val parsed = MediaFilenameParser.parseVideoPath(
                            scopedPath, allowStandaloneMovie = '/' !in scopedPath,
                        )
                        val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn))
                            it.getLong(sizeColumn).takeIf { value -> value > 0 } else null
                        val modifiedAt = if (modifiedColumn >= 0 && !it.isNull(modifiedColumn))
                            it.getLong(modifiedColumn).takeIf { value -> value > 0 }?.toDouble()
                            else null
                        items.add(
                            NativeDiscoveredMedia(
                                location = ContentUris.withAppendedId(collection, id).toString(),
                                fileName = fileName,
                                scopeLocation = relativePath,
                                scopeVerified = true, // Provider-supplied path verified above.
                                animeTitle = parsed.animeTitle.takeIf { title -> title.isNotBlank() },
                                season = parsed.season,
                                episodeNumber = parsed.episodeNumber,
                                absoluteNumber = parsed.absoluteNumber?.toDouble(),
                                episodeType = parsed.kind.name.lowercase(Locale.ROOT),
                                episodeTitle = parsed.displayTitle.takeIf { title -> title.isNotBlank() },
                                identificationSource = parsed.identificationSource,
                                identificationConfidence = parsed.confidence.name.lowercase(Locale.ROOT),
                                mimeType = mimeType,
                                sizeBytes = size,
                                modifiedAtEpochSeconds = modifiedAt,
                                sourceFolder = FOLDER,
                                relativePath = relativePath,
                                volumeId = volume,
                                mediaIdentity = "mediastore:$volume:$id",
                                ambiguous = parsed.ambiguities.isNotEmpty(),
                            )
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Default folder query failed for $collection", exception)
                errors.add(exception.javaClass.simpleName + ": " + collection)
            }
        }
        Discovery(
            snapshot = snapshot(operationId, items, successfulQueries > 0, cancelled),
            readable = successfulQueries > 0,
            cancelled = cancelled,
            errors = errors,
        )
    }

    private fun snapshot(
        operationId: String,
        items: List<NativeDiscoveredMedia>,
        readable: Boolean,
        cancelled: Boolean,
    ): NativeLibraryScanSnapshot = NativeLibraryScanSnapshot(
        operationId = operationId,
        source = "mediastore",
        scopeRoot = FOLDER,
        scopeReference = FOLDER,
        expectedLocations = setOf(FOLDER),
        // MediaStore can omit non-owned/unauthorized files. NEVER reconcile missing.
        processedLocations = emptySet(),
        status = if (cancelled) "CANCELLED" else if (readable) "COMPLETED" else "FAILED",
        complete = false,
        cancelled = cancelled,
        authorizationValid = readable,
        items = items,
    )
}
