package com.reiflix.reiflix_local.data.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTitleIdentityTest {
    @Test fun explicitSeasonsKeepTheirCanonicalFranchiseBase() {
        val series=SeriesTitleIdentity.form("Youjo Senki")!!
        val sequel=SeriesTitleIdentity.form("Youjo Senki II")!!
        val numbered=SeriesTitleIdentity.form("Youjo Senki 2")!!
        val dubbed=SeriesTitleIdentity.form("Saga of Tanya the Evil Season 2")!!
        val third=SeriesTitleIdentity.form("Youjo Senki Temporada 3")!!
        val alternative=SeriesTitleIdentity.form("Youjo Senki S02")!!

        assertEquals(series.key,sequel.key)
        assertEquals(series.key,numbered.key)
        assertEquals(series.key,third.key)
        assertEquals(series.key,alternative.key)
        assertEquals(1,series.season)
        assertFalse(series.tagged)
        assertEquals(2,sequel.season)
        assertTrue(sequel.tagged)
        assertEquals(2,numbered.season)
        assertEquals(2,dubbed.season)
        assertEquals(3,third.season)
    }

    @Test fun numbersInsideTitlesAreNotRemovedWithoutTheirExactSequelContext() {
        val numbered=SeriesTitleIdentity.form("86 Eighty-Six")!!
        val ordinary=SeriesTitleIdentity.form("Date A Live III")!!
        val different=SeriesTitleIdentity.form("Youjo Shenki 2")!!
        val proper=SeriesTitleIdentity.form("Youjo Senki II")!!
        assertEquals("86 eighty six",numbered.key)
        assertEquals(3,ordinary.season)
        assertFalse(different.key==proper.key)
        assertNull(SeriesTitleIdentity.form("Episódio 12"))
    }
}
