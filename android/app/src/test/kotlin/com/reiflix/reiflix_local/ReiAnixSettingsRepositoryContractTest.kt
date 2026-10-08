package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsRepository
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsAccountUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsStorageUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixSettingsRepositoryContractTest {
    @Test
    fun consecutiveErrorsPreserveLastKnownSettingsAndAccountUntilReadySnapshotArrives() {
        val known = ReiAnixSettingsUiState(
            status = ReiAnixSettingsLoadStatus.READY,
            revision = 10L,
            account = ReiAnixSettingsAccountUiState(
                integrationAvailable = true,
                connected = true,
                name = "Reinan",
                email = "reinan@email",
                picture = "https://example.com/picture.jpg",
                state = "connected",
            ),
            categories = listOf(),
            settings = mapOf("player.autoplay" to "true"),
            storage = ReiAnixSettingsStorageUiState(
                known = true,
                mediaReadState = "granted",
                broadStorageState = "available",
                safRootCount = 2,
                removableVolumeCount = 1,
                lifecycleState = "ready",
                api = 36,
                safSelectionPending = false,
            ),
            error = null,
        )

        val firstError = ReiAnixSettingsUiState(
            status = ReiAnixSettingsLoadStatus.ERROR,
            revision = 11L,
            account = ReiAnixSettingsAccountUiState(),
            categories = emptyList(),
            settings = emptyMap(),
            storage = ReiAnixSettingsStorageUiState(),
            error = "IPC_ERROR_1",
        )

        val afterFirstError = ReiAnixSettingsRepository.mergeSnapshotState(firstError, known)

        assertEquals(ReiAnixSettingsLoadStatus.ERROR, afterFirstError.status)
        assertEquals("IPC_ERROR_1", afterFirstError.error)
        assertEquals(11L, afterFirstError.revision)
        assertEquals(known.account, afterFirstError.account)
        assertEquals(known.settings, afterFirstError.settings)
        assertEquals(known.storage, afterFirstError.storage)

        val secondError = ReiAnixSettingsUiState(
            status = ReiAnixSettingsLoadStatus.ERROR,
            revision = 12L,
            account = ReiAnixSettingsAccountUiState(),
            categories = emptyList(),
            settings = emptyMap(),
            storage = ReiAnixSettingsStorageUiState(),
            error = "IPC_ERROR_2",
        )

        val afterSecondError = ReiAnixSettingsRepository.mergeSnapshotState(secondError, afterFirstError)

        assertEquals(ReiAnixSettingsLoadStatus.ERROR, afterSecondError.status)
        assertEquals("IPC_ERROR_2", afterSecondError.error)
        assertEquals(12L, afterSecondError.revision)
        assertEquals(known.account, afterSecondError.account)
        assertEquals(known.account.name, afterSecondError.account.name)
        assertEquals(known.account.email, afterSecondError.account.email)
        assertEquals(known.account.picture, afterSecondError.account.picture)
        assertEquals(known.settings, afterSecondError.settings)
        assertEquals(known.storage, afterSecondError.storage)

        val readySnapshot = ReiAnixSettingsUiState(
            status = ReiAnixSettingsLoadStatus.READY,
            revision = 13L,
            account = ReiAnixSettingsAccountUiState(
                integrationAvailable = true,
                connected = true,
                name = "Novo Nome",
                email = "novo@email",
                picture = "https://example.com/new-picture.jpg",
                state = "connected",
            ),
            settings = mapOf("player.autoplay" to "false"),
            error = null,
        )

        val afterReady = ReiAnixSettingsRepository.mergeSnapshotState(readySnapshot, afterSecondError)

        assertEquals(readySnapshot, afterReady)
        assertEquals(ReiAnixSettingsLoadStatus.READY, afterReady.status)
        assertEquals("Novo Nome", afterReady.account.name)
        assertEquals("novo@email", afterReady.account.email)
        assertEquals("https://example.com/new-picture.jpg", afterReady.account.picture)
        assertEquals("false", afterReady.settings["player.autoplay"])
    }
}
