package com.reiflix.reiflix_local.data.settings

import com.reiflix.reiflix_local.data.contract.booleanOrDefault
import com.reiflix.reiflix_local.data.contract.intOrDefault
import com.reiflix.reiflix_local.data.contract.intOrNull
import com.reiflix.reiflix_local.data.contract.intRequired
import com.reiflix.reiflix_local.data.contract.longRequired
import com.reiflix.reiflix_local.data.contract.objectOrDefault
import com.reiflix.reiflix_local.data.contract.stringOrDefault
import com.reiflix.reiflix_local.data.contract.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

internal object ReiAnixSettingsSnapshotCodec {
    fun decode(raw: String, previousRevision: Long = 0L): ReiAnixSettingsSnapshot {
        val root = JSONObject(raw)
        val schemaVersion = root.intRequired("schemaVersion")
        require(schemaVersion == 1) {
            "Unsupported Compose settings snapshot schema: $schemaVersion"
        }

        val revision = root.longRequired("revision")
        require(revision >= 0L && revision >= previousRevision) {
            "Stale Compose settings snapshot revision=$revision previous=$previousRevision"
        }

        val status = root.stringOrDefault("status", "ERROR").trim().uppercase().ifEmpty { "ERROR" }
        val error = root.stringOrNull("error")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

        val accountObject = root.objectOrDefault("account")
        val account = ReiAnixSettingsAccount(
            integrationAvailable = accountObject.booleanOrDefault("integrationAvailable", false),
            connected = accountObject.booleanOrDefault("connected", false),
            name = accountObject.stringOrDefault("name", "").trim(),
            email = accountObject.stringOrDefault("email", "").trim(),
            picture = accountObject.stringOrDefault("picture", "").trim(),
            state = accountObject.stringOrDefault("state", "").trim().lowercase(),
        )

        val categoriesObject = root.objectOrDefault("categories")
        val categories = buildMap {
            for (key in categoriesObject.keys()) {
                put(key, categoriesObject.booleanOrDefault(key, false))
            }
        }

        val storageObject = root.objectOrDefault("storage")
        val storage = ReiAnixSettingsStorage(
            known = storageObject.has("mediaReadState") || storageObject.has("lifecycleState"),
            mediaReadState = storageObject.stringOrDefault("mediaReadState", "unknown")
                .trim().lowercase().ifEmpty { "unknown" },
            broadStorageState = storageObject.stringOrDefault("broadStorageState", "unknown")
                .trim().lowercase().ifEmpty { "unknown" },
            safRootCount = storageObject.intOrDefault("safRootCount", 0),
            removableVolumeCount = storageObject.intOrDefault("removableVolumeCount", 0),
            lifecycleState = storageObject.stringOrDefault("lifecycleState", "unknown")
                .trim().lowercase().ifEmpty { "unknown" },
            api = storageObject.intOrNull("api"),
            safSelectionPending = storageObject.booleanOrDefault("safSelectionPending", false),
        )
        require(storage.safRootCount >= 0 && storage.removableVolumeCount >= 0) {
            "Settings storage counts must be non-negative."
        }
        require(storage.api == null || storage.api >= 0) {
            "Settings storage API level must be non-negative."
        }

        val settingsObject = root.objectOrDefault("settings")
        val settings = buildMap {
            for (key in settingsObject.keys()) {
                val value = settingsObject.opt(key)
                val serialized = when (value) {
                    null, JSONObject.NULL -> throw IllegalArgumentException(
                        "Settings snapshot value '$key' cannot be null; omit an absent setting instead.",
                    )
                    is String, is Boolean, is Number, is JSONObject, is JSONArray -> value.toString()
                    else -> throw IllegalArgumentException("Unsupported JSON value for settings key '$key'.")
                }
                put(key, serialized)
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
    val picture: String,
    val state: String,
)
