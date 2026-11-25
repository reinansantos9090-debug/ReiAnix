package com.reianix.reianix_local.scanner

import android.webkit.MimeTypeMap
import java.util.Locale

/** Discovery does not depend on the filename parser or on decoder support. */
internal object LocalVideoRecognition {
    private val extensions = setOf(
        "3g2","3gp","3gp2","3gpp","asf","avi","divx","flv","f4v","qt","m4v",
        "mtv","mkv","mp4","mpeg","mpe","mpg","mov","ogm","ogv","ogx","vob","wtv",
        "webm","ts","m2ts","wmv","mts","m2t","m1v","m2v","mpv","rm","rmvb",
        "mxf","nsv","nut","bik","bk2","dvr-ms","mod","tod","hevc","h265","h264",
        "av1","ivf","y4m","mjpeg","mjpg","mp4v","m4p","amv","dat",
    ) - "dat" // A generic data extension alone is not video evidence.

    fun accepts(name: String?, mimeType: String?): Boolean {
        val mime = mimeType?.trim().orEmpty()
        if (mime.startsWith("video/", ignoreCase = true)) return true
        if (mime.equals("application/vnd.rn-realmedia", true) ||
            mime.equals("application/vnd.rn-realmedia-vbr", true) ||
            mime.equals("application/mxf", true)) return true
        val extension = name?.substringAfterLast('.', "")?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (extension in extensions) return true
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?.startsWith("video/", ignoreCase = true) == true
    }
}
