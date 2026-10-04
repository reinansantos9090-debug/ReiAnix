package com.reiflix.reiflix_local.player

import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import java.io.File
import java.util.Locale

/**
 * Resolves local subtitle sidecars for an already-authorized video.
 *
 * This is intentionally a playback helper, not a second video scanner or catalog.
 * It never downloads subtitles and never crosses an authorization boundary.
 */
object LocalSubtitleResolver {
    data class SubtitleTrack(
        val uri: Uri,
        val mimeType: String,
        val language: String?,
        val displayName: String,
        val isDefault: Boolean,
    )

    private val supportedExtensions = setOf("srt", "ssa", "ass", "vtt", "sup")

    fun mimeForExtension(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
        "srt" -> "application/x-subrip"
        "ssa", "ass" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        "sup" -> "application/pgs"
        else -> null
    }

    fun resolve(context: Context, videoUri: Uri): List<SubtitleTrack> {
        val videoName = displayName(context, videoUri) ?: videoUri.lastPathSegment.orEmpty()
        val base = videoName.substringBeforeLast('.', videoName)
        if (base.isBlank()) return emptyList()

        val candidates = when (videoUri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> resolveFile(videoUri, base)
            "content" -> when {
                videoUri.authority == MediaStore.AUTHORITY -> resolveMediaStore(context, videoUri, base)
                else -> resolveSaf(context, videoUri, base)
            }
            else -> emptyList()
        }

        return candidates
            .distinctBy { it.uri.toString() }
            .sortedWith(compareByDescending<SubtitleTrack> { it.isDefault }.thenBy { it.displayName.lowercase(Locale.ROOT) })
            .take(128)
    }

    private fun resolveFile(videoUri: Uri, base: String): List<SubtitleTrack> {
        val videoFile = runCatching { File(videoUri.path ?: "").canonicalFile }.getOrNull() ?: return emptyList()
        val parent = videoFile.parentFile ?: return emptyList()
        val files = runCatching { parent.listFiles() }.getOrNull() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && !it.name.startsWith(".") }
            .mapNotNull { file ->
                val extension = file.extension.lowercase(Locale.ROOT)
                val mimeType = mimeForExtension(extension) ?: return@mapNotNull null
                val stem = file.name.substringBeforeLast('.', file.name)
                if (!stem.startsWith(base, ignoreCase = true)) return@mapNotNull null
                SubtitleTrack(
                    uri = Uri.fromFile(file.canonicalFile),
                    mimeType = mimeType,
                    language = languageSuffix(base, stem),
                    displayName = file.name,
                    isDefault = stem.equals(base, ignoreCase = true) && extension == "srt",
                )
            }
            .toList()
    }

    private fun resolveSaf(context: Context, videoUri: Uri, base: String): List<SubtitleTrack> {
        if (videoUri.scheme != "content" || !DocumentsContract.isDocumentUri(context, videoUri)) return emptyList()
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull() ?: return emptyList()

        // Generic DocumentsProvider IDs do not expose a reliable parent relationship.
        // For the Android external-storage provider the document id is volume:path,
        // so its parent is deterministic and can be queried without converting to a file path.
        if (videoUri.authority != "com.android.externalstorage.documents") return emptyList()
        val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentId.isBlank()) return emptyList()

        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { p ->
            if (!p.isReadPermission || p.uri.authority != videoUri.authority) return@firstOrNull false
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(p.uri) }.getOrNull() ?: return@firstOrNull false
            documentId == treeId || documentId.startsWith("$treeId/")
        } ?: return emptyList()

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(permission.uri, parentId)
        }.getOrNull() ?: return emptyList()

        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) return@use emptyList<SubtitleTrack>()

                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val mimeType = mimeForExtension(extension) ?: continue
                        if (!name.substringBeforeLast('.', name).startsWith(base, ignoreCase = true)) continue
                        val childUri = runCatching {
                            DocumentsContract.buildDocumentUriUsingTree(permission.uri, id)
                        }.getOrNull() ?: continue
                        add(
                            SubtitleTrack(
                                uri = childUri,
                                mimeType = mimeType,
                                language = languageSuffix(base, name.substringBeforeLast('.', name)),
                                displayName = name,
                                isDefault = name.substringBeforeLast('.', name).equals(base, ignoreCase = true) && extension == "srt",
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun resolveMediaStore(context: Context, videoUri: Uri, base: String): List<SubtitleTrack> {
        if (!MediaStoreScanner.hasReadPermission(context)) return emptyList()
        val resolver = context.contentResolver
        val metadata = runCatching {
            resolver.query(
                videoUri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                val volume = if (android.os.Build.VERSION.SDK_INT >= 29) cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)) else MediaStore.VOLUME_EXTERNAL
                Triple(name, relativePath, volume)
            }
        }.getOrNull() ?: return emptyList()

        val (displayName, relativePath, volumeName) = metadata
        if (!displayName.substringBeforeLast('.', displayName).equals(base, ignoreCase = true)) {
            return emptyList()
        }

        val collection = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(volumeName)
            else MediaStore.Files.getContentUri("external")
        }.getOrNull() ?: return emptyList()

        return runCatching {
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(relativePath),
                MediaStore.MediaColumns.DISPLAY_NAME + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) return@use emptyList<SubtitleTrack>()
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: continue
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val mimeType = mimeForExtension(extension) ?: continue
                        val stem = name.substringBeforeLast('.', name)
                        if (!stem.startsWith(base, ignoreCase = true)) continue
                        val id = cursor.getLong(idColumn)
                        add(
                            SubtitleTrack(
                                uri = Uri.withAppendedPath(collection, id.toString()),
                                mimeType = mimeType,
                                language = languageSuffix(base, stem),
                                displayName = name,
                                isDefault = stem.equals(base, ignoreCase = true) && extension == "srt",
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun displayName(context: Context, uri: Uri): String? = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> runCatching { File(uri.path ?: "").name }.getOrNull()
        "content" -> runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val mediaIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val documentIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                when {
                    mediaIndex >= 0 -> cursor.getString(mediaIndex)
                    documentIndex >= 0 -> cursor.getString(documentIndex)
                    else -> null
                }
            }
        }.getOrNull()
        else -> null
    }

    private fun languageSuffix(base: String, stem: String): String? {
        if (stem.equals(base, ignoreCase = true)) return null
        val suffix = stem.removePrefix(base).trimStart('.', ' ', '-', '_')
        return suffix.takeIf { it.isNotBlank() && it.length <= 16 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
    }
}
