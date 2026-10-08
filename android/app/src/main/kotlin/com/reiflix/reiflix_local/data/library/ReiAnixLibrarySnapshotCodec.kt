package com.reiflix.reiflix_local.data.library

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
        val schemaVersion = root.optInt("schemaVersion", 0)
        require(schemaVersion == 1) { "Unsupported Compose library snapshot schema: $schemaVersion" }

        val revision = root.optLong("revision", 0L)
        require(previousRevision == 0L || revision > previousRevision) {
            "Stale Compose library snapshot revision=$revision previous=$previousRevision"
        }

        val sourceState = root.optString("sourceState").trim().ifEmpty { "UNKNOWN" }
        val sourceAvailable = root.optBoolean("sourceAvailable", sourceState == "AVAILABLE")
        val scanState = root.optString("scanState").trim().uppercase().ifEmpty { "IDLE" }
        val scanInProgress = root.optBoolean(
            "scanInProgress",
            scanState in setOf("CHECKING", "SCANNING", "WAITING_FOR_MEDIASTORE"),
        )
        val snapshotStatus = root.optString("status").trim().uppercase()
        val error = root.optString("error").trim().takeIf { it.isNotEmpty() && it != "null" }

        val rawAnimes = root.optJSONArray("animes") ?: JSONArray()
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

        val rawContinueWatching = root.optJSONArray("continue_watching") ?: JSONArray()
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
            error = error,
        )
    }

    private fun decodeStorage(raw: JSONObject?): ReiAnixStorageUiState {
        if (raw == null) return ReiAnixStorageUiState()
        val capabilities = raw.optJSONObject("capabilities") ?: JSONObject()
        val configured = raw.optJSONArray("configuredSources") ?: JSONArray()
        val sources = buildList(configured.length()) {
            for (index in 0 until configured.length()) {
                val item = configured.optJSONObject(index) ?: continue
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
            onboardingError = raw.optString("onboardingError").trim()
                .takeIf { it.isNotEmpty() && it != "null" },
            safRoots = capabilities.stringList("safRoots"),
            safSelectionPending = raw.optBoolean("safSelectionPending", false),
            safRootIdentities = capabilities.stringList("safRootIdentities"),
            removableVolumes = capabilities.stringList("removableVolumes"),
            scannerCapabilities = capabilities.stringList("scannerCapabilities").sorted(),
            reconciliationCapabilities = capabilities.stringList("reconciliationCapabilities").sorted(),
            lifecycleState = capabilities.optString("lifecycleState", "unknown").trim().lowercase(),
            api = capabilities.optIntOrNull("api"),
            configuredSources = sources,
        )
    }

    fun decodeCommandResult(raw: String): CommandResult {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", 0) == 1) {
            "Unsupported Compose command-result schema"
        }
        val payload = root.optJSONObject("payload")
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
        val rawItems = payload.optJSONArray("items") ?: JSONArray()
        val items = buildList(rawItems.length()) {
            for (index in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(index)
                    ?: error("Malformed library page item at index=$index")
                @Suppress("UNCHECKED_CAST")
                add(LibraryUiMappers.anime(item.toMap()))
            }
        }
        return LibraryPageResult(
            generation = payload.optLong("generation", 0L),
            page = payload.optInt("page", 0),
            pageSize = payload.optInt("page_size", 0),
            total = payload.optInt("total", 0),
            hasMore = payload.optBoolean("has_more", false),
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

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
