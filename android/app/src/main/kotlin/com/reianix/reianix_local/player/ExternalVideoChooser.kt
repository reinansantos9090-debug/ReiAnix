package com.reianix.reianix_local.player

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.reianix.reianix_local.ExternalVideoActivity
import java.io.File
import java.util.Locale

/**
 * Opens the system's video-app chooser without choosing a particular TV protocol
 * or requiring Web Video Caster, Castify, VLC, etc. to be installed.
 *
 * SAF/MediaStore content URIs retain a temporary read grant; legacy file URIs
 * are converted to a FileProvider content URI so Android never exposes file://.
 */
internal object ExternalVideoChooser {
    private fun createVideoViewIntent(context: Context, localUri: Uri): Intent {
        val sharedUri = when (localUri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> {
                require(localUri.authority != "${context.packageName}.videoexports") { "URI interna não pode ser compartilhada novamente." }
                localUri
            }
            "file" -> {
                val path = requireNotNull(localUri.path?.takeIf { it.isNotBlank() }) {
                    "Caminho do arquivo não disponível"
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.videoexports",
                    requireShareableVideoFile(context, File(path)),
                )
            }
            else -> throw IllegalArgumentException("Somente vídeos locais podem ser abertos externamente")
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(sharedUri, "video/*")
            clipData = ClipData.newRawUri("Vídeo ReiAnix", sharedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Official Web Video Caster Android ACTION_VIEW integration for local files.
     *  URI grants and FileProvider wrapping are shared with the existing chooser.
     *  No SDK or proprietary Web Video Caster code is embedded in ReiAnix.
     */
    fun createWebVideoCasterIntent(context: Context, localUri: Uri, title: String): Intent =
        createVideoViewIntent(context, localUri).apply {
            setPackage("com.instantbits.cast.webvideo")
            // Official Web Video Caster intent integration documents this key.
            putExtra("title", title)
            putExtra(Intent.EXTRA_TITLE, title)
        }

    fun createIntent(context: Context, localUri: Uri): Intent {
        val viewVideo = createVideoViewIntent(context, localUri)
        val sharedUri = requireNotNull(viewVideo.data)
        return Intent.createChooser(viewVideo, "Abrir com...").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("Vídeo ReiAnix", sharedUri)
            // ReiAnix itself is also an ACTION_VIEW handler. Exclude it to
            // prevent users from accidentally reopening this same episode.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context, ExternalVideoActivity::class.java)),
                )
            }
        }
    }
}
