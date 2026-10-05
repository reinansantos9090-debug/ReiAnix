package com.reiflix.reiflix_local.data.settings

import org.json.JSONObject

internal object ReiAnixSettingsSnapshotCodec {
    fun decode(raw: String, previousRevision: Long = 0L): ReiAnixSettingsSnapshot {
        val root = JSONObject(raw)
        val schemaVersion = root.optInt("schemaVersion", 0)
        require(schemaVersion == 1) {
            "Unsupported Compose settings snapshot schema: $schemaVersion"
        }

        val revision = root.optLong("revision", 0L)
        require(revision >= previousRevision) {
            "Stale Compose settings snapshot revision=$revision previous=$previousRevision"
        }

        val status = root.optString("status").trim().uppercase().ifEmpty { "ERROR" }
        val error = root.optString("error").trim()
            .takeIf { it.isNotEmpty() && it != "null" }

        val accountObject = root.optJSONObject("account")
        val account = ReiAnixSettingsAccount(
            integrationAvailable = accountObject?.optBoolean("integrationAvailable", false) ?: false,
            connected = accountObject?.optBoolean("connected", false) ?: false,
            name = accountObject?.optString("name").orEmpty().trim(),
            email = accountObject?.optString("email").orEmpty().trim(),
            state = accountObject?.optString("state").orEmpty().trim().lowercase(),
        )

        val categoriesObject = root.optJSONObject("categories") ?: JSONObject()
        val categories = buildMap {
            for (key in categoriesObject.keys()) {
                put(key, categoriesObject.optBoolean(key, false))
            }
        }

        val storageObject = root.optJSONObject("storage")
        val storage = ReiAnixSettingsStorage(
            known = storageObject?.has("mediaReadState") == true || storageObject?.has("lifecycleState") == true,
            mediaReadState = storageObject?.optString("mediaReadState").orEmpty().trim().lowercase().ifEmpty { "unknown" },
            broadStorageState = storageObject?.optString("broadStorageState").orEmpty().trim().lowercase().ifEmpty { "unknown" },
            safRootCount = storageObject?.optInt("safRootCount", 0) ?: 0,
            removableVolumeCount = storageObject?.optInt("removableVolumeCount", 0) ?: 0,
            lifecycleState = storageObject?.optString("lifecycleState").orEmpty().trim().lowercase().ifEmpty { "unknown" },
            api = if (storageObject?.has("api") == true && !storageObject.isNull("api")) {
                storageObject.optInt("api")
            } else {
                null
            },
            safSelectionPending = storageObject?.optBoolean("safSelectionPending", false) ?: false,
        )

        val settingsObject = root.optJSONObject("settings") ?: JSONObject()
        val settings = buildMap {
            for (key in settingsObject.keys()) {
                val value = settingsObject.opt(key)
                put(
                    key,
                    when (value) {
                        JSONObject.NULL, null -> ""
                        is Boolean -> value.toString()
                        else -> value.toString()
                    },
                )
            }
        }

        return ReiAnixSettingsSnapshot(
            revision = revision,
            status = status,
            account = account,
            categories = categories,
            settings = settings,
            storage = storage,
            error = error,
        )
    }
}

internal data class ReiAnixSettingsSnapshot(
    val revision: Long,
    val status: String,
    val account: ReiAnixSettingsAccount,
    val categories: Map<String, Boolean>,
    val settings: Map<String, String>,
    val storage: ReiAnixSettingsStorage,
    val error: String?,
)

internal data class ReiAnixSettingsStorage(
    val known: Boolean,
    val mediaReadState: String,
    val broadStorageState: String,
    val safRootCount: Int,
    val removableVolumeCount: Int,
    val lifecycleState: String,
    val api: Int?,
    val safSelectionPending: Boolean,
)

internal data class ReiAnixSettingsAccount(
    val integrationAvailable: Boolean,
    val connected: Boolean,
    val name: String,
    val email: String,
    val state: String,
)