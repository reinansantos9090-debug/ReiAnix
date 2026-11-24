package com.reianix.reianix_local.player

import android.content.Context
import java.io.File
import java.util.Locale

/** Canonicalize before granting read access; exclude application data and non-video files. */
internal fun requireShareableVideoFile(context: Context, file: File): File {
    val canonical = file.canonicalFile
    fun within(root: File): Boolean =
        canonical.path.startsWith(root.canonicalFile.path + File.separator)
    val export = File(context.cacheDir, "video_exports")
    val shared = canonical.path.startsWith("/storage/") ||
        within(android.os.Environment.getExternalStorageDirectory())
    val protectedDirectory = canonical.path.split('/').windowed(2).any {
        it[0].equals("Android", true) && (it[1].equals("data", true) || it[1].equals("obb", true))
    }
    require(within(export) || (shared && !protectedDirectory && !within(context.filesDir) && !within(context.cacheDir))) {
        "Somente vídeos do armazenamento compartilhado ou da pasta de exportação são permitidos."
    }
    require(canonical.isFile && canonical.canRead() && canonical.extension.lowercase(Locale.ROOT) in
        setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "mts", "3gp", "3g2", "flv", "wmv", "mpg", "mpeg", "vob", "ogv", "rm", "rmvb", "asf", "f4v", "divx")) {
        "Arquivo de vídeo inválido ou indisponível."
    }
    return canonical
}
