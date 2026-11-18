package com.reianix.reianix_local.player.cast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Android video intent supported by Web Video Caster and used by CloudStream.
 * The shared URL is a temporary read-only LAN stream served by ReiAnix itself,
 * not a content:// URI or a copy of the local video.
 *
 * https://www.webvideocaster.com/integrations
 */
internal object WebVideoCasterHandoff {
    const val PACKAGE = "com.instantbits.cast.webvideo"

    fun isInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun createVideoIntent(publishedUrl: String, title: String): Intent {
        val uri = Uri.parse(publishedUrl)
        // Publish is local-only: never accidentally hand an unrelated remote
        // browsing or cloud URL to this external application.
        require(uri.scheme == "http" && uri.host != null && uri.port > 0 &&
            uri.path?.startsWith("/media/") == true) {
            "Endereço local de transmissão inválido"
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage(PACKAGE)
            setDataAndType(uri, "video/*")
            putExtra("title", title.trim().ifEmpty { "Vídeo ReiAnix" }.take(180))
            putExtra("secure_uri", true)
            // The local HTTP endpoint authenticates with its unguessable path.
            // No cookies, private headers, or local filesystem paths are shared.
            putExtra("android.media.intent.extra.HTTP_HEADERS", Bundle())
        }
    }

    fun storeIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PACKAGE"),
    )

    fun storeWebIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"),
    )
}
