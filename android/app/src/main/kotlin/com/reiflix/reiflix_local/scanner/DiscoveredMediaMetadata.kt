package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.data.media.MediaFilenameParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adds non-destructive parsing and metadata-availability facts to an already
 * discovered physical document. It never assigns catalogue IDs or mutates the
 * persisted library; URI/source identity remain owned by the originating scanner.
 */
internal object DiscoveredMediaMetadata {
    fun attach(
        document: JSONObject,
        fileName: String,
        relativePath: String,
        source: String,
        mimeType: String?,
        sizeBytes: Long?,
        modifiedAtMillis: Long?,
        providerId: String?,
    ): JSONObject {
        val parseInput = relativePath.trim().takeIf { it.isNotEmpty() } ?: fileName
        val parsed = MediaFilenameParser.parseVideoPath(parseInput)
        document.put(
            "parsedMedia",
            JSONObject()
                .put("animeTitle", parsed.animeTitle)
                .put("season", parsed.season ?: JSONObject.NULL)
                .put("episodeNumber", parsed.episodeNumber ?: JSONObject.NULL)
                .put("absoluteNumber", parsed.absoluteNumber ?: JSONObject.NULL)
                .put("kind", parsed.kind.name.lowercase())
                .put("identificationSource", parsed.identificationSource)
                .put("confidence", parsed.confidence.name.lowercase())
                .put("displayTitle", parsed.displayTitle)
                .put("extension", parsed.extension)
                .put("supportedParserExtension", parsed.isSupportedVideoExtension)
                .put("evidence", JSONArray(parsed.evidence))
                .put("unresolved", JSONArray(parsed.unresolved))
                .put("ambiguities", JSONArray(parsed.ambiguities))
        )
        document.put(
            "metadataAvailability",
            JSONObject()
                .put("source", source)
                .put("accessState", "accessible")
                .put("nameAvailable", fileName.isNotBlank())
                .put("mimeTypeAvailable", !mimeType.isNullOrBlank())
                .put("sizeAvailable", sizeBytes != null)
                .put("modifiedAtAvailable", modifiedAtMillis != null)
                .put("providerIdAvailable", !providerId.isNullOrBlank())
        )
        return document
    }
}
