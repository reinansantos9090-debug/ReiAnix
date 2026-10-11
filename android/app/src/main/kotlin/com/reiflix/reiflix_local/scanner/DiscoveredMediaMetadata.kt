package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.data.media.MediaFilenameParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enriches one already-enumerated physical document without assigning catalogue identity
 * or touching the persisted library. Fallback labels stay useful for discovery, but are
 * not reported as provider metadata.
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
        nameAvailable: Boolean = fileName.isNotBlank(),
        mimeTypeAvailable: Boolean = !mimeType.isNullOrBlank(),
        accessState: String = "accessible",
    ): JSONObject {
        val knownSize = sizeBytes?.takeIf { it >= 0L }
        val knownModifiedAt = modifiedAtMillis?.takeIf { it >= 0L }

        // Keep public field names stable and make nullable scalar fields consistent with
        // availability flags. JSONObject.NULL, not Kotlin null, serializes as JSON null.
        document.put("size", knownSize ?: JSONObject.NULL)
        document.put("modifiedAt", knownModifiedAt ?: JSONObject.NULL)
        if (source == "saf" || source == "mediastore") {
            document.put("mimeType", mimeType?.takeIf { mimeTypeAvailable && it.isNotBlank() } ?: JSONObject.NULL)
        }

        val parseInput = relativePath.trim().takeIf { it.isNotEmpty() } ?: fileName
        val parsed = MediaFilenameParser.parseVideoPath(parseInput)
        val fieldConfidence = JSONObject()
        parsed.fieldConfidence.forEach { (field, confidence) ->
            fieldConfidence.put(field.name.lowercase(), confidence.name.lowercase())
        }
        val usableProviderId = when (source) {
            // Document IDs are opaque provider values; do not interpret their contents.
            "saf" -> providerId?.trim()?.takeIf { it.isNotEmpty() }
            // MediaStore's _ID is a positive numeric row identifier.
            "mediastore" -> providerId?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.toString()
            // Broad filesystem paths are locations, not provider-issued document IDs.
            else -> null
        }

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
                .put("originalFileName", parsed.originalFileName)
                .put("baseName", parsed.baseName)
                .put("technicalTokens", JSONArray(parsed.technicalTokens))
                .put("fieldConfidence", fieldConfidence)
                .put("unknownFields", JSONArray(parsed.unknownFields.map { it.name.lowercase() }))
                .put("evidence", JSONArray(parsed.evidence))
                .put("unresolved", JSONArray(parsed.unresolved))
                .put("ambiguities", JSONArray(parsed.ambiguities))
        )
        document.put(
            "metadataAvailability",
            JSONObject()
                .put("source", source)
                .put("accessState", accessState)
                .put("nameAvailable", nameAvailable && fileName.isNotBlank())
                .put("mimeTypeAvailable", mimeTypeAvailable && !mimeType.isNullOrBlank())
                .put("sizeAvailable", knownSize != null)
                .put("modifiedAtAvailable", knownModifiedAt != null)
                .put("providerIdAvailable", usableProviderId != null)
        )
        return document
    }

    /**
     * Legacy cached strings may have been filled from a fallback label. A stored availability
     * bit is authoritative only when the field itself still contains a usable string.
     */
    internal fun cachedStringAvailable(
        document: JSONObject,
        field: String,
        availabilityField: String,
        legacyFallbackValue: String? = null,
    ): Boolean {
        val value = (document.opt(field) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (legacyFallbackValue != null && value == legacyFallbackValue) return false
        val availability = document.optJSONObject("metadataAvailability")
        return if (availability?.has(availabilityField) == true) {
            availability.optBoolean(availabilityField, false)
        } else {
            true
        }
    }

    /**
     * Reads a numeric value from a reused MediaStore batch. Newer rows carry an explicit
     * availability bit, which is authoritative. For legacy rows without one, zero is
     * ambiguous because older scanners used zero as fallback for missing metadata. This
     * conservative rule may classify a legacy true-zero as unknown, but never promotes an
     * ambiguous placeholder to known metadata. The cached snapshot is not rewritten.
     */
    internal fun cachedLongOrNull(
        document: JSONObject,
        field: String,
        availabilityField: String,
    ): Long? {
        val number = document.opt(field) as? Number ?: return null
        val value = number.toLong()
        if (value < 0L) return null
        val availability = document.optJSONObject("metadataAvailability")
        val known = if (availability?.has(availabilityField) == true) {
            availability.optBoolean(availabilityField, false)
        } else {
            value > 0L
        }
        return value.takeIf { known }
    }
}
