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
        val schemaVersion = ReiAnixContractValues.intOrNull(root.opt("schemaVersion"), "schemaVersion") ?: 0
        require(schemaVersion == 1) { "Unsupported Compose library snapshot schema: $schemaVersion" }

        val revision = root.longOrDefault("revision", 0L)
        require(revision >= 0L) { "Compose library snapshot revision must be non-negative." }
        require(previousRevision == 0L || revision > previousRevision) {
            "Stale Compose library snapshot revision=$revision previous=$previousRevision"
        }

        val sourceState = root.optString("sourceState").trim().ifEmpty { "UNKNOWN" }
        val sourceAvailable = root.booleanOrDefault("sourceAvailable", sourceState == "AVAILABLE")
        val scanState = root.optString("scanState").trim().uppercase().ifEmpty { "IDLE" }
        val lastScanStatus = root.optString("lastScanStatus")
            .trim()
            .uppercase()
            .takeIf { it.isNotEmpty() && it != "NULL" }
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
        val snapshotStatus = root.optString("status").trim().uppercase()
        val error = root.optString("error").trim().takeIf { it.isNotEmpty() && it != "null" }

        val rawAnimes = root.arrayOrDefault("animes")
        val animes = buildList(rawAnimes.length()) {
            for (index in 0 until rawAnimes.length()) {
                val item = rawAnimes.optJSONObject(index)
                    ?: error("Malformed anime at snapshot index=$index")
                @Suppress("UNCHECKED_CAST")
                add(LibraryUiMappers.anime(item.toMap()))
            }
        }

        val storage = decodeStorage(root.optJSONObject("storage"))

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
                        reference = item.optString("reference").trim(),
                        name = item.optString("name").trim(),
                        kind = item.optString("kind").trim(),
                        authorization = item.optString("authorization").trim(),
                        status = item.optString("status").trim(),
                        safIdentity = item.optString("saf_identity").trim().takeIf { it.isNotEmpty() },
                        safVolumeId = item.optString("saf_volume_id").trim().takeIf { it.isNotEmpty() },
                        safDocumentId = item.optString("saf_document_id").trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }.sortedBy { it.stableKey }

        return ReiAnixStorageUiState(
            mediaReadState = capabilities.optString("mediaReadState", "denied").trim().lowercase(),
            broadStorageState = capabilities.optString("broadStorageState", "unavailable").trim().lowercase(),
            onboardingState = raw.optString("onboardingState", "checking").trim().lowercase(),
            onboardingMessage = raw.optString("onboardingMessage").trim()
                .takeIf { it.isNotEmpty() && it != "null" },
            onboardingDismissed = raw.booleanOrDefault("onboardingDismissed", false),
            onboardingError = raw.optString("onboardingError").trim()
                .takeIf { it.isNotEmpty() && it != "null" },
            safRoots = capabilities.stringList("safRoots"),
            safSelectionPending = raw.booleanOrDefault("safSelectionPending", false),
            safRootIdentities = capabilities.stringList("safRootIdentities"),
            removableVolumes = capabilities.stringList("removableVolumes"),
            scannerCapabilities = capabilities.stringList("scannerCapabilities").sorted(),
            reconciliationCapabilities = capabilities.stringList("reconciliationCapabilities").sorted(),
            lifecycleState = capabilities.optString("lifecycleState", "unknown").trim().lowercase(),
            api = capabilities.intOrNull("api"),
            configuredSources = sources,
        )
    }

    fun decodeCommandResult(raw: String): CommandResult {
        val root = JSONObject(raw)
        require(ReiAnixContractValues.intOrNull(root.opt("schemaVersion"), "schemaVersion") == 1) {
            "Unsupported Compose command-result schema"
        }
        val payload = root.objectOrNull("payload")
        val libraryPage = payload
            ?.takeIf { it.optString("kind").trim() == "library_page" }
            ?.let { decodeLibraryPage(it) }
        return CommandResult(
            requestId = root.optString("requestId").trim().takeIf { it.isNotEmpty() },
            action = root.optString("action").trim().takeIf { it.isNotEmpty() },
            status = root.optString("status").trim().uppercase().ifEmpty { "UNKNOWN" },
            error = root.optString("error").trim().takeIf { it.isNotEmpty() && it != "null" },
            message = root.optString("message").trim().takeIf { it.isNotEmpty() && it != "null" },
            libraryPage = libraryPage,
        )
    }

    private fun decodeLibraryPage(payload: JSONObject): LibraryPageResult {
        val rawItems = payload.arrayOrDefault("items")
        val items = buildList(rawItems.length()) {
            for (index in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(index)
                    ?: error("Malformed library page item at index=$index")
                @Suppress("UNCHECKED_CAST")
                add(LibraryUiMappers.anime(item.toMap()))
            }
        }
        val generation = payload.longOrDefault("generation", 0L)
        val page = payload.intOrDefault("page", 0)
        val pageSize = payload.intOrDefault("page_size", 0)
        val total = payload.intOrDefault("total", 0)
        require(generation >= 0L) { "Library page generation must be non-negative." }
        require(page >= 0 && pageSize >= 0 && total >= 0) {
            "Library page pagination values must be non-negative."
        }
        return LibraryPageResult(
            generation = generation,
            page = page,
            pageSize = pageSize,
            total = total,
            hasMore = payload.booleanOrDefault("has_more", false),
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
    val array = optJSONArray(key) ?: return emptyList()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val value = array.opt(index)?.toString()?.trim().orEmpty()
            if (value.isNotEmpty() && value != "null") add(value)
        }
    }.distinct()
}

private fun JSONObject.longOrDefault(key: String, default: Long): Long =
    if (!has(key) || isNull(key)) default
    else ReiAnixContractValues.longOrNull(opt(key), key)
        ?: error("Invalid non-null long field '$key'.")

private fun JSONObject.intOrDefault(key: String, default: Int): Int =
    if (!has(key) || isNull(key)) default
    else ReiAnixContractValues.intOrNull(opt(key), key)
        ?: error("Invalid non-null integer field '$key'.")

private fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean =
    if (!has(key) || isNull(key)) default
    else ReiAnixContractValues.booleanOrNull(opt(key), key)
        ?: error("Invalid non-null Boolean field '$key'.")

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

private fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null
    else ReiAnixContractValues.intOrNull(opt(key), key)
