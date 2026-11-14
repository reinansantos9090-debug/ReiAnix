package com.reiflix.reiflix_local.data.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReiAnixContractValuesTest {

    @Test
    fun missingNullZeroFalseAndEmptyRemainDistinct() {
        assertNull(ReiAnixContractValues.longOrNull(null, "id"))
        assertEquals(0L, ReiAnixContractValues.longOrNull(0, "count"))
        assertEquals(0, ReiAnixContractValues.intOrNull("0", "count"))
        assertEquals(0.0, ReiAnixContractValues.doubleOrNull("0.0", "position")!!, 0.0)
        assertEquals(false, ReiAnixContractValues.booleanOrNull(false, "watched"))
        assertNull(ReiAnixContractValues.booleanOrNull(null, "watched"))
    }

    @Test
    fun integerConversionsRejectFractionsAndOverflowInsteadOfTruncating() {
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.longOrNull(17.75, "episode_id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.intOrNull(2_147_483_648L, "year")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.longOrNull("9223372036854775808", "anime_id")
        }
    }

    @Test
    fun numericConversionsRejectNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.doubleOrNull(Double.NaN, "progress")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.doubleOrNull(Double.POSITIVE_INFINITY, "duration")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.doubleOrNull("NaN", "timestamp")
        }
    }

    @Test
    fun booleansAcceptOnlyBooleanOrCanonicalZeroOneValues() {
        assertEquals(true, ReiAnixContractValues.booleanOrNull(1L, "watched"))
        assertEquals(false, ReiAnixContractValues.booleanOrNull(0.0, "watched"))
        assertEquals(true, ReiAnixContractValues.booleanOrNull("TRUE", "favorite"))
        assertEquals(false, ReiAnixContractValues.booleanOrNull("0", "favorite"))

        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.booleanOrNull(2, "favorite")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.booleanOrNull(0.5, "watched")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixContractValues.booleanOrNull("yes", "favorite")
        }
    }
}
