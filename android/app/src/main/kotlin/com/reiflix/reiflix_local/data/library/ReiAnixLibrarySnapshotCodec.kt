package com.reiflix.reiflix_local.data.library

import com.reiflix.reiflix_local.data.contract.ReiAnixContractValues
import com.reiflix.reiflix_local.ui.mapper.LibraryUiMappers
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixStorageSourceUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixStorageUiState
import org.json.JSONArray
import org.json.JSONObject

internal object ReiAnixLibrarySnapshotCodec {

    fun decode(raw: String, previousRevision: Long = 0L): ReiAnixLibraryUiState {
        val root = JSONObject(raw)
        val schemaVersion = root.intRequired("schemaVersion")
        require(schemaVersion == 1) { "Unsupported Compose library snapshot schema: $schemaVersion" }

        val revision = root.longRequired("revision")
        require(revision >= 0L) { "Compose library snapshot revision must be non-negative." }
        require(previousRevision == 0L || revision > previousRevision) {
            "Stale Compose library snapshot revision=$revision previous=$previousRevision"
        }

        val sourceState = root.stringOrDefault("sourceState", "UNKNOWN").trim().ifEmpty { "UNKNOWN" }
        val sourceAvailable = root.booleanOrDefault("sourceAvailable", sourceState == "AVAILABLE")
        val scanState = root.stringOrDefault("scanState", "IDLE").trim().uppercase().ifEmpty { "IDLE" }
        val lastScanStatus = root.stringOrNull("lastScanStatus")
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() && it != "NULL" }
        val scanInProgress = root.booleanOrDefault(
            "scanInProgress",
            scanState in setOf(
                "QUEUED",
                "RUNNING",
                "CANCELLING",
                "CHECKING",
                "SCANNING",
                "WAITING_FOR_MEDIASTORE",
            ),
        )
        val snapshotStatus = root.stringOrDefault("status", "").trim().uppercase()
        val error = root.stringOrNull("error")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

        val rawAnimes = root.requiredArray("animes")
        val animes = buildList(rawAnimes.length()) {
            for (index in 0 until rawAnimes.length()) {
                val item = rawAnimes.optJSONObject(index)
                    ?: error("Malformed anime at snapshot index=$index")
                @Suppress("UNCHECKED_CAST")
                add(LibraryUiMappers.anime(item.toMap()))
            }
        }

        val storage = decodeStorage(root.objectOrNull("storage"))

        val validAnimeIds = animes.asSequence()
            .map { it.id }
            .toSet()
        val validEpisodeIds = animes.asSequence()
            .flatMap { it.contentEpisodes.asSequence() }
            .map { it.id }
            .toSet()

        val rawContinueWatching = root.arrayOrDefault("continue_watching")
        val continueWatching = buildList(rawContinueWatching.length()) {
            for (index in 0 until rawContinueWatching.length()) {
                val item = rawContinueWatching.optJSONObject(index)
                    ?: error("Malformed continue-watching item at snapshot index=$index")
                @Suppress("UNCHECKED_CAST")
                val model = LibraryUiMappers.continueWatching(item.toMap())
                // Continue Watching belongs to the same canonical snapshot.
                // Never expose an orphan episode/anime pair from a racing write.
                if (model.animeId in validAnimeIds && model.episodeId in validEpisodeIds) {
                    add(model)
                }
            }
        }

        val status = when {
            snapshotStatus == "ERROR" -> ReiAnixLibraryLoadStatus.ERROR
            sourceState == "UNAVAILABLE" -> ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE
            animes.isNotEmpty() -> ReiAnixLibraryLoadStatus.READY
            else -> ReiAnixLibraryLoadStatus.EMPTY
        }

        return ReiAnixLibraryUiState(
            status = status,
            storage = storage,
            revision = revision,
            animes = animes,
            continueWatching = continueWatching,
            sourceAvailable = sourceAvailable,
            sourceState = sourceState,
            scanInProgress = scanInProgress,
            scanState = scanState,
            lastScanStatus = lastScanStatus,
            error = error,
        )
    }

    private fun decodeStorage(raw: JSONObject?): ReiAnixStorageUiState {
        if (raw == null) return ReiAnixStorageUiState()
        val capabilities = raw.objectOrNull("capabilities") ?: JSONObject()
        val configured = raw.arrayOrDefault("configuredSources")
        val sources = buildList(configured.length()) {
            for (index in 0 until configured.length()) {
                val item = configured.optJSONObject(index)
                    ?: error("Malformed configured storage source at snapshot index=$index")
                add(
                    ReiAnixStorageSourceUiModel(
                        reference = item.stringOrDefault("reference", "").trim(),
                        name = item.stringOrDefault("name", "").trim(),
                        kind = item.stringOrDefault("kind", "").trim(),
                        authorization = item.stringOrDefault("authorization", "").trim(),
                        status = item.stringOrDefault("status", "").trim(),
                        safIdentity = item.stringOrNull("saf_identity")?.trim()?.takeIf { it.isNotEmpty() },
                        safVolumeId = item.stringOrNull("saf_volume_id")?.trim()?.takeIf { it.isNotEmpty() },
                        safDocumentId = item.stringOrNull("saf_document_id")?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }.sortedBy { it.stableKey }

        return ReiAnixStorageUiState(
            mediaReadState = capabilities.stringOrDefault("mediaReadState", "denied").trim().lowercase(),
            broadStorageState = capabilities.stringOrDefault("broadStorageState", "unavailable").trim().lowercase(),
            onboardingState = raw.stringOrDefault("onboardingState", "checking").trim().lowercase(),
            onboardingMessage = raw.stringOrNull("onboardingMessage")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
            onboardingDismissed = raw.booleanOrDefault("onboardingDismissed", false),
            onboardingError = raw.stringOrNull("onboardingError")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
            safRoots = capabilities.stringList("safRoots"),
            safSelectionPending = raw.booleanOrDefault("safSelectionPending", false),
            safRootIdentities = capabilities.stringList("safRootIdentities"),
            removableVolumes = capabilities.stringList("removableVolumes"),
            scannerCapabilities = capabilities.stringList("scannerCapabilities").sorted(),
            reconciliationCapabilities = capabilities.stringList("reconciliationCapabilities").sorted(),
            lifecycleState = capabilities.stringOrDefault("lifecycleState", "unknown").trim().lowercase(),
            api = capabilities.intOrNull("api"),
            configuredSources = sources,
        )
    }

    fun decodeCommandResult(raw: String): CommandResult {
        val root = JSONObject(raw)
        require(root.intRequired("schemaVersion") == 1) {
            "Unsupported Compose command-result schema"
        }
        val payload = root.objectOrNull("payload")
        val libraryPage = payload
            ?.takeIf { it.stringOrNull("kind")?.trim() == "library_page" }
            ?.let { decodeLibraryPage(it) }
        return CommandResult(
            requestId = root.stringOrNull("requestId")?.trim()?.takeIf { it.isNotEmpty() },
            action = root.stringOrNull("action")?.trim()?.takeIf { it.isNotEmpty() },
            status = root.stringOrDefault("status", "UNKNOWN").trim().uppercase().ifEmpty { "UNKNOWN" },
            error = root.stringOrNull("error")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
            message = root.stringOrNull("message")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
            libraryPage = libraryPage,
        )
    }

    private fun decodeLibraryPage(payload: JSONObject): LibraryPageResult {
        val rawItems = payload.requiredArray("items")
        val items = buildList(rawItems.length()) {
            for (index in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(index)
                    ?: error("Malformed library page item at index=$index")
                @Suppress("UNCHECKED_CAST")
                add(LibraryUiMappers.anime(item.toMap()))
            }
        }
        val generation = payload.longRequired("generation")
        val page = payload.intRequired("page")
        val pageSize = payload.intRequired("page_size")
        val total = payload.intRequired("total")
        require(generation >= 0L) { "Library page generation must be non-negative." }
        require(page >= 0 && pageSize >= 0 && total >= 0) {
            "Library page pagination values must be non-negative."
        }
        return LibraryPageResult(
            generation = generation,
            page = page,
            pageSize = pageSize,
            total = total,
            hasMore = payload.booleanRequired("has_more"),
            items = items,
        )
    }

    data class CommandResult(
        val requestId: String?,
        val action: String?,
        val status: String,
        val error: String?,
        val message: String? = null,
        val libraryPage: LibraryPageResult? = null,
    )

    data class LibraryPageResult(
        val generation: Long,
        val page: Int,
        val pageSize: Int,
        val total: Int,
        val hasMore: Boolean,
        val items: List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>,
    )
}

private fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> jsonValueToKotlin(opt(key)) }

private fun JSONArray.toListValue(): List<Any?> =
    buildList(length()) {
        for (index in 0 until length()) {
            add(jsonValueToKotlin(opt(index)))
        }
    }

private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> value.toListValue()
    else -> value
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = arrayOrDefault(key)
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val rawValue = array.opt(index)
            val value = rawValue as? String
                ?: error("Invalid snapshot field '$key[$index]': expected text.")
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "Invalid snapshot field '$key[$index]': text must not be blank." }
            add(trimmed)
        }
    }.distinct()
}

private fun JSONObject.stringOrNull(key: String): String? =
    when {
        !has(key) || isNull(key) -> null
        opt(key) is String -> opt(key) as String
        else -> error("Invalid snapshot field '$key': expected text.")
    }

private fun JSONObject.stringOrDefault(key: String, default: String): String =
    stringOrNull(key) ?: default

private fun JSONObject.longRequired(key: String): Long {
    require(has(key) && !isNull(key)) { "Missing required snapshot field '$key'." }
    val value = opt(key)
    require(value is Number) { "Invalid snapshot field '$key': expected a JSON number." }
    return ReiAnixContractValues.longOrNull(value, key)
        ?: error("Invalid required long field '$key'.")
}

private fun JSONObject.intRequired(key: String): Int {
    require(has(key) && !isNull(key)) { "Missing required snapshot field '$key'." }
    val value = opt(key)
    require(value is Number) { "Invalid snapshot field '$key': expected a JSON number." }
    return ReiAnixContractValues.intOrNull(value, key)
        ?: error("Invalid required integer field '$key'.")
}

private fun JSONObject.booleanRequired(key: String): Boolean {
    require(has(key) && !isNull(key)) { "Missing required snapshot field '$key'." }
    return (opt(key) as? Boolean)
        ?: error("Invalid required Boolean field '$key'.")
}

private fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    if (!has(key) || isNull(key)) return default
    return (opt(key) as? Boolean)
        ?: error("Invalid snapshot field '$key': expected a JSON Boolean.")
}

private fun JSONObject.requiredArray(key: String): JSONArray {
    require(has(key) && !isNull(key)) { "Missing required snapshot array '$key'." }
    return optJSONArray(key)
        ?: error("Invalid snapshot field '$key': expected an array.")
}

private fun JSONObject.arrayOrDefault(key: String): JSONArray =
    when {
        !has(key) || isNull(key) -> JSONArray()
        optJSONArray(key) != null -> optJSONArray(key)!!
        else -> error("Invalid snapshot field '$key': expected an array.")
    }

private fun JSONObject.objectOrNull(key: String): JSONObject? =
    when {
        !has(key) || isNull(key) -> null
        optJSONObject(key) != null -> optJSONObject(key)
        else -> error("Invalid snapshot field '$key': expected an object.")
    }

private fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    require(value is Number) { "Invalid snapshot field '$key': expected a JSON number." }
    return ReiAnixContractValues.intOrNull(value, key)
}
