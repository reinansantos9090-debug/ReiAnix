package com.reianix.reianix_local.data.anilist

import com.reianix.reianix_local.viewmodel.ReiAnixAniListUiState
import com.reianix.reianix_local.viewmodel.consumeRepairNotice
import org.junit.Assert.*
import org.junit.Test

class ReiAnixRepairNoticeTest {
    @Test
    fun successNoticeIsConsumedOnceAndDoesNotReplayWhenDetailsReopens() {
        val completed = ReiAnixAniListUiState(
            repairAnimeId = 42L,
            repairMessage = "Identificação e detalhes atualizados.",
            repairRevision = 7L,
        )
        val (consumed, first) = completed.consumeRepairNotice(42L, 7L)
        assertEquals("Identificação e detalhes atualizados.", first)
        assertNull(consumed.repairMessage)
        val (_, second) = consumed.consumeRepairNotice(42L, 7L)
        assertNull(second)
    }

    @Test
    fun otherAnimeAndPendingRepairsCannotConsumeNotice() {
        val pending = ReiAnixAniListUiState(
            repairAnimeId = 42L,
            repairMessage = "Pronto.",
            repairRunning = true,
            repairRevision = 2L,
        )
        assertNull(pending.consumeRepairNotice(42L, 2L).second)
        assertNull(pending.copy(repairRunning = false).consumeRepairNotice(9L, 2L).second)
        assertNull(pending.copy(repairRunning = false).consumeRepairNotice(42L, 1L).second)
    }
}
