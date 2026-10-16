package com.reiflix.reiflix_local.data.settings

import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import org.json.JSONException
import org.json.JSONObject

/**
 * Versioned import/export codec for the settings file format formerly owned by
 * core/settings.py. Imports validate every supported key before one native SQLite
 * transaction; unknown keys are reported and ignored, matching schema v1/v2 behavior.
 */
class ReiAnixSettingsFileService(
    private val repository: ReiAnixSqliteSettingsRepository,
) {
    data class ImportResult(
        val imported: Int,
        val values: Map<String, String>,
        val unknownKeys: List<String>,
    )

    suspend fun exportJson(): String {
        val stored = repository.allPreferences()
        val settings = JSONObject()
        EXPORT_KEYS.sorted().forEach { key ->
            val defaultValue = ReiAnixSettingsContract.defaultFor(key)
                ?: throw IllegalStateException("Missing default for exported setting: $key")
            val raw = stored[key] ?: defaultValue
            val normalized = normalizeStoredValue(key, raw, defaultValue)
            settings.put(key, toJsonValue(key, normalized))
        }
        return JSONObject()
            .put("format", EXPORT_FORMAT)
            .put("schema_version", SCHEMA_VERSION)
            .put("settings", settings)
            .toString(2) + "\n"
    }

    suspend fun importJson(raw: String): ImportResult {
        val payload = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("JSON de configurações inválido.", error)
        }
        require(payload.optString("format") == EXPORT_FORMAT) {
            "Formato de configurações incompatível."
        }
        val versionValue = payload.opt("schema_version")
        val version = when (versionValue) {
            is Number -> versionValue.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
            is String -> versionValue.toIntOrNull()
            else -> null
        }
        require(version != null && version in SUPPORTED_SCHEMA_VERSIONS) {
            "Versão de configurações incompatível."
        }
        val settings = payload.optJSONObject("settings")
            ?: throw IllegalArgumentException("Campo settings inválido.")

        val normalized = linkedMapOf<String, String>()
        val unknown = mutableListOf<String>()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in EXPORT_KEYS) {
                unknown += key
                continue
            }
            val value = settings.opt(key)
                ?: throw IllegalArgumentException("Valor ausente para a configuração $key.")
            require(value !== JSONObject.NULL) {
                "Valor nulo para a configuração $key."
            }
            normalized[key] = normalizeImportedValue(key, value)
        }

        if (normalized.isEmpty()) {
            return ImportResult(imported = 0, values = emptyMap(), unknownKeys = unknown)
        }

        // The schema marker remains in the same canonical preferences table. It is
        // intentionally excluded from the runtime notification because it is not a UI setting.
        val valuesToCommit = normalized + ("settings.schema_version" to SCHEMA_VERSION.toString())
        val persisted = repository.setPreferencesAtomically(valuesToCommit)
            .mapValues { (_, record) -> record.value }
        return ImportResult(
            imported = normalized.size,
            values = normalized.keys.associateWith { key ->
                persisted[key] ?: throw IllegalStateException("Imported preference was not confirmed: $key")
            },
            unknownKeys = unknown,
        )
    }

    private fun normalizeStoredValue(key: String, raw: String, default: String): String {
        val compatible = if (key == "player.aspect_ratio") {
            when (raw.trim().lowercase()) {
                "zoom" -> "fill"
                "auto", "original" -> "fit"
                else -> raw
            }
        } else {
            raw
        }
        return runCatching { ReiAnixSettingsContract.normalize(key, compatible) }
            .getOrDefault(default)
    }

    private fun normalizeImportedValue(key: String, value: Any): String {
        val definition = ReiAnixSettingsContract.definitions[key]
            ?: throw IllegalArgumentException("Chave de configuração desconhecida: $key.")
        val raw = when (definition.kind) {
            ReiAnixSettingKind.BOOLEAN -> when (value) {
                is Boolean -> value.toString()
                is String -> value
                is Number -> value.toString()
                else -> throw IllegalArgumentException("Valor booleano inválido para $key.")
            }
            ReiAnixSettingKind.INTEGER -> {
                require(value !is Boolean) { "Inteiro inválido para $key." }
                require(
                    value is Int || value is Long ||
                        (value is String && value.trim().matches(Regex("\\d+")))
                ) { "Inteiro inválido para $key." }
                value.toString()
            }
            ReiAnixSettingKind.DECIMAL -> {
                require(value is Number || value is String) { "Número inválido para $key." }
                value.toString()
            }
            ReiAnixSettingKind.ENUM,
            ReiAnixSettingKind.LANGUAGE -> value.toString()
        }
        return try {
            ReiAnixSettingsContract.normalize(key, raw)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Valor inválido para a configuração $key.", error)
        }
    }

    private fun toJsonValue(key: String, normalized: String): Any =
        when (ReiAnixSettingsContract.definitions[key]?.kind) {
            ReiAnixSettingKind.BOOLEAN -> normalized == "true"
            ReiAnixSettingKind.INTEGER -> normalized.toInt()
            ReiAnixSettingKind.DECIMAL -> normalized.toDouble()
            ReiAnixSettingKind.ENUM, ReiAnixSettingKind.LANGUAGE -> normalized
            null -> normalized
        }

    companion object {
        const val EXTRA_ACTION = "reianix.settings.file_action"
        const val EXTRA_REQUEST_ID = "reianix.settings.request_id"
        private const val EXPORT_FORMAT = "reiflix-settings"
        const val SCHEMA_VERSION = 2
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, SCHEMA_VERSION)
        val EXPORT_KEYS = ReiAnixSettingsContract.definitions.keys.toSet()
    }
}
