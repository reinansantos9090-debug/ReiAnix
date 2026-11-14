package com.reiflix.reiflix_local.data.contract

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Strict scalar conversion rules shared by persistence adapters and transport mappers.
 *
 * A missing/null value remains null. A present but malformed value throws instead of
 * being truncated, saturated, or silently replaced with a plausible domain value.
 */
internal object ReiAnixContractValues {

    fun longOrNull(value: Any?, field: String = "value"): Long? {
        if (isAbsent(value)) return null
        val result = when (value) {
            is Number -> exactDecimal(value)?.let { decimal ->
                runCatching { decimal.longValueExact() }.getOrNull()
            }
            is String -> value.trim().toLongOrNull()
            else -> null
        }
        return result ?: invalid(field, "an integral 64-bit integer", value)
    }

    fun intOrNull(value: Any?, field: String = "value"): Int? {
        val longValue = longOrNull(value, field) ?: return null
        if (longValue < Int.MIN_VALUE.toLong() || longValue > Int.MAX_VALUE.toLong()) {
            invalid(field, "an integer within the 32-bit range", value)
        }
        return longValue.toInt()
    }

    fun doubleOrNull(value: Any?, field: String = "value"): Double? {
        if (isAbsent(value)) return null
        val result = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        if (result == null || !result.isFinite()) {
            invalid(field, "a finite number", value)
        }
        return result
    }

    fun booleanOrNull(value: Any?, field: String = "value"): Boolean? {
        if (isAbsent(value)) return null
        return when (value) {
            is Boolean -> value
            is Number -> when (longOrNull(value, field)) {
                0L -> false
                1L -> true
                else -> invalid(field, "a Boolean or numeric 0/1", value)
            }
            is String -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> invalid(field, "a Boolean or true/false/0/1 string", value)
            }
            else -> invalid(field, "a Boolean or true/false/0/1 value", value)
        }
    }

    private fun exactDecimal(value: Number): BigDecimal? = try {
        when (value) {
            is BigDecimal -> value
            is BigInteger -> BigDecimal(value)
            else -> BigDecimal(value.toString())
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun isAbsent(value: Any?): Boolean = value == null || value === JSONObject.NULL

    private fun invalid(field: String, expected: String, value: Any?): Nothing {
        throw IllegalArgumentException(
            "Invalid contract value for '$field': expected $expected, got ${value?.javaClass?.simpleName ?: "null"}.",
        )
    }
}
