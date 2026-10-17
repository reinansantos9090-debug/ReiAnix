package com.reiflix.reiflix_local.data.contract

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared typed JSON boundary helpers for snapshots and command-result payloads.
 *
 * Defaults apply only when an optional field is absent. Explicit null is accepted only
 * through nullable helpers; malformed wire types are rejected rather than coerced.
 */
internal fun JSONObject.stringOrNull(key: String): String? =
    when {
        !has(key) || isNull(key) -> null
        opt(key) is String -> opt(key) as String
        else -> error("Invalid JSON contract field '$key': expected text.")
    }

internal fun JSONObject.stringOrDefault(key: String, default: String): String =
    if (!has(key)) default
    else stringOrNull(key)
        ?: error("Invalid JSON contract field '$key': null is not valid for this string field.")

internal fun JSONObject.longRequired(key: String): Long {
    require(has(key) && !isNull(key)) { "Missing required JSON contract field '$key'." }
    val value = opt(key)
    require(value is Number) { "Invalid JSON contract field '$key': expected a JSON number." }
    return ReiAnixContractValues.longOrNull(value, key)
        ?: error("Invalid required long JSON contract field '$key'.")
}

internal fun JSONObject.longOrDefault(key: String, default: Long): Long {
    if (!has(key)) return default
    require(!isNull(key)) { "Invalid JSON contract field '$key': null is not valid for this integer field." }
    val value = opt(key)
    require(value is Number) { "Invalid JSON contract field '$key': expected a JSON number." }
    return ReiAnixContractValues.longOrNull(value, key)
        ?: error("Invalid long JSON contract field '$key'.")
}

internal fun JSONObject.intRequired(key: String): Int {
    require(has(key) && !isNull(key)) { "Missing required JSON contract field '$key'." }
    val value = opt(key)
    require(value is Number) { "Invalid JSON contract field '$key': expected a JSON number." }
    return ReiAnixContractValues.intOrNull(value, key)
        ?: error("Invalid required integer JSON contract field '$key'.")
}

internal fun JSONObject.intOrDefault(key: String, default: Int): Int {
    if (!has(key)) return default
    require(!isNull(key)) { "Invalid JSON contract field '$key': null is not valid for this integer field." }
    val value = opt(key)
    require(value is Number) { "Invalid JSON contract field '$key': expected a JSON number." }
    return ReiAnixContractValues.intOrNull(value, key)
        ?: error("Invalid integer JSON contract field '$key'.")
}

internal fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    require(value is Number) { "Invalid JSON contract field '$key': expected a JSON number." }
    return ReiAnixContractValues.intOrNull(value, key)
}

internal fun JSONObject.booleanRequired(key: String): Boolean {
    require(has(key) && !isNull(key)) { "Missing required JSON contract field '$key'." }
    return (opt(key) as? Boolean)
        ?: error("Invalid JSON contract field '$key': expected a JSON Boolean.")
}

internal fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    if (!has(key)) return default
    require(!isNull(key)) { "Invalid JSON contract field '$key': null is not valid for this Boolean field." }
    return (opt(key) as? Boolean)
        ?: error("Invalid JSON contract field '$key': expected a JSON Boolean.")
}

internal fun JSONObject.requiredArray(key: String): JSONArray {
    require(has(key) && !isNull(key)) { "Missing required JSON contract array '$key'." }
    return optJSONArray(key)
        ?: error("Invalid JSON contract field '$key': expected an array.")
}

internal fun JSONObject.arrayOrNull(key: String): JSONArray? =
    when {
        !has(key) || isNull(key) -> null
        optJSONArray(key) != null -> optJSONArray(key)
        else -> error("Invalid JSON contract field '$key': expected an array.")
    }

internal fun JSONObject.arrayOrDefault(key: String): JSONArray =
    when {
        !has(key) -> JSONArray()
        isNull(key) -> error("Invalid JSON contract field '$key': null is not valid for this array field.")
        optJSONArray(key) != null -> optJSONArray(key)!!
        else -> error("Invalid JSON contract field '$key': expected an array.")
    }

internal fun JSONObject.objectOrNull(key: String): JSONObject? =
    when {
        !has(key) || isNull(key) -> null
        optJSONObject(key) != null -> optJSONObject(key)
        else -> error("Invalid JSON contract field '$key': expected an object.")
    }

internal fun JSONObject.objectOrDefault(key: String): JSONObject =
    when {
        !has(key) -> JSONObject()
        isNull(key) -> error("Invalid JSON contract field '$key': null is not valid for this object field.")
        optJSONObject(key) != null -> optJSONObject(key)!!
        else -> error("Invalid JSON contract field '$key': expected an object.")
    }

internal fun JSONObject.stringList(key: String): List<String> {
    val array = arrayOrDefault(key)
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val value = array.opt(index) as? String
                ?: error("Invalid JSON contract field '$key[$index]': expected text.")
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) {
                "Invalid JSON contract field '$key[$index]': text must not be blank."
            }
            add(trimmed)
        }
    }.distinct()
}
