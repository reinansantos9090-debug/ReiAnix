package com.reianix.reianix_local.downloads

import java.io.InputStream

/**
 * Identify a downloaded container from its bytes, never from a CDN's .bin
 * extension or its application/octet-stream response header.
 *
 * This is deliberately conservative: an unrecognized binary is not an MP4.
 * Container detection does not imply that its codecs can be remuxed.
 */
internal object DownloadContainerDetector {
    data class Type(val extension: String, val mime: String)

    val convertibleExtensions = setOf("ts", "m2ts", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp")
    val knownExtensions = convertibleExtensions + setOf("mp4", "m4v")

    fun sniff(stream: InputStream): Type? {
        val header = ByteArray(4096)
        var size = 0
        while (size < header.size) {
            val n = stream.read(header, size, header.size - size)
            if (n <= 0) break
            size += n
        }
        return sniff(header.copyOf(size))
    }

    fun sniff(bytes: ByteArray): Type? {
        if (bytes.size < 12) return null
        fun text(from: Int, length: Int): String =
            String(bytes, from, length, Charsets.US_ASCII)
        if (text(4, 4) == "ftyp") {
            val brand = text(8, 4).lowercase()
            return when {
                brand.startsWith("3g") -> Type("3gp", "video/3gpp")
                brand == "qt  " -> Type("mov", "video/quicktime")
                else -> Type("mp4", "video/mp4")
            }
        }
        if (bytes.size >= 16 && text(4, 4) == "moov") return Type("mov", "video/quicktime")
        if (text(0, 4) == "RIFF" && text(8, 4) == "AVI ") return Type("avi", "video/x-msvideo")
        if (text(0, 3) == "FLV") return Type("flv", "video/x-flv")
        if (bytes.size >= 16 && listOf(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
                0xA6, 0xD9, 0, 0xAA, 0, 0x62, 0xCE, 0x6C)
                .indices.all { (bytes[it].toInt() and 0xff) ==
                    listOf(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
                        0xA6, 0xD9, 0, 0xAA, 0, 0x62, 0xCE, 0x6C)[it] })
            return Type("wmv", "video/x-ms-wmv")
        if ((bytes[0].toInt() and 0xff) == 0x1a &&
            (bytes[1].toInt() and 0xff) == 0x45 &&
            (bytes[2].toInt() and 0xff) == 0xdf &&
            (bytes[3].toInt() and 0xff) == 0xa3) {
            val headerText = text(0, bytes.size).lowercase()
            return if ("webm" in headerText.take(512)) Type("webm", "video/webm")
                else Type("mkv", "video/x-matroska")
        }
        if (bytes.size > 376 &&
            (bytes[0].toInt() and 0xff) == 0x47 &&
            (bytes[188].toInt() and 0xff) == 0x47 &&
            (bytes[376].toInt() and 0xff) == 0x47)
            return Type("ts", "video/mp2t")
        if (bytes.size > 388 &&
            (bytes[4].toInt() and 0xff) == 0x47 &&
            (bytes[196].toInt() and 0xff) == 0x47 &&
            (bytes[388].toInt() and 0xff) == 0x47)
            return Type("m2ts", "video/mp2t")
        return null
    }

    fun outputName(source: String, extension: String): String {
        val stem = source.substringBeforeLast('.', source).trimEnd('.')
        return (stem.ifBlank { "episodio" }) + "." + extension
    }
}
