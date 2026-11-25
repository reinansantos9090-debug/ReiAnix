package com.reianix.reianix_local.scanner

import android.webkit.MimeTypeMap
import android.media.MediaMetadataRetriever
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale

/** Discovery does not depend on the filename parser or on decoder support. */
internal object LocalVideoRecognition {
    private val extensions = setOf(
        "3g2","3gp","3gp2","3gpp","asf","avi","divx","flv","f4v","qt","m4v",
        "mtv","mkv","mp4","mpeg","mpe","mpg","mov","ogm","ogv","ogx","vob","wtv",
        "webm","ts","m2ts","wmv","mts","m2t","m1v","m2v","mpv","rm","rmvb",
        "mxf","nsv","nut","bik","bk2","dvr-ms","mod","tod","hevc","h265","h264",
        "av1","ivf","y4m","mjpeg","mjpg","mp4v","amv",
    )

    fun accepts(name: String?, mimeType: String?): Boolean {
        val mime = mimeType?.trim().orEmpty()
        if (mime.startsWith("video/", ignoreCase = true)) return true
        if (mime.equals("application/vnd.rn-realmedia", true) ||
            mime.equals("application/vnd.rn-realmedia-vbr", true) ||
            mime.equals("application/mxf", true)) return true
        val extension = name?.substringAfterLast('.', "")?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (extension in extensions) return true
        return platformMime(extension)?.startsWith("video/", ignoreCase = true) == true
    }

    private fun platformMime(extension: String): String? = runCatching {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }.getOrNull()

    /** Unknown formats/names can be probed after authorization, without decoding frames. */
    fun needsProbe(name: String?, mimeType: String?): Boolean {
        val mime = mimeType?.trim().orEmpty()
        if (mime.isNotEmpty() && !mime.equals("application/octet-stream", true) &&
            !mime.equals("application/unknown", true) && !mime.equals("*/*")) return false
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (extension in setOf("jpg","jpeg","png","gif","webp","srt","ass","ssa","vtt","txt","nfo",
                "json","xml","zip","rar","7z","pdf","mp3","flac","wav","aac","m4a","opus")) return false
        val known = platformMime(extension)
        return known == null || known.equals("application/octet-stream", true)
    }

    private inline fun probe(setSource: (MediaMetadataRetriever) -> Unit): Boolean {
        val retriever = runCatching { MediaMetadataRetriever() }.getOrNull() ?: return false
        return try {
            setSource(retriever)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                .equals("yes", ignoreCase = true)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun probe(context: Context, uri: Uri): Boolean = probe { it.setDataSource(context, uri) }
    fun probe(file: File): Boolean = probe { it.setDataSource(file.absolutePath) }
}
