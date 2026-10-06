package com.reiflix.reiflix_local.ui.model

import androidx.annotation.Keep
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsSnapshot
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsCategoryUiModel

@Keep
enum class ReiAnixSettingsLoadStatus {
    LOADING,
    READY,
    ERROR,
}

@Keep
data class ReiAnixSettingsStorageUiState(
    val known: Boolean = false,
    val mediaReadState: String = "unknown",
    val broadStorageState: String = "unknown",
    val safRootCount: Int = 0,
    val removableVolumeCount: Int = 0,
    val lifecycleState: String = "unknown",
    val api: Int? = null,
    val safSelectionPending: Boolean = false,
)

data class ReiAnixSettingsAccountUiState(
    val integrationAvailable: Boolean = false,
    val connected: Boolean = false,
    val name: String = "",
    val email: String = "",
    val picture: String = "",
    val state: String = "disconnected",
)

@Keep
enum class ReiAnixSettingsOperationState {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCESS,
    ERROR,
    CANCELLED,
}

@Keep
data class ReiAnixSettingsOperationUiState(
    val requestId: String,
    val action: String,
    val key: String? = null,
    val state: ReiAnixSettingsOperationState = ReiAnixSettingsOperationState.QUEUED,
    val message: String? = null,
    val error: String? = null,
    val timestampMs: Long = 0L,
)

@Keep
data class ReiAnixSettingsUiState(
    val status: ReiAnixSettingsLoadStatus = ReiAnixSettingsLoadStatus.LOADING,
    val revision: Long = 0L,
    val account: ReiAnixSettingsAccountUiState = ReiAnixSettingsAccountUiState(),
    val categories: List<ReiAnixSettingsCategoryUiModel> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val storage: ReiAnixSettingsStorageUiState = ReiAnixSettingsStorageUiState(),
    val operations: Map<String, ReiAnixSettingsOperationUiState> = emptyMap(),
    val error: String? = null,
) {
    companion object {
        internal fun fromSnapshot(snapshot: ReiAnixSettingsSnapshot): ReiAnixSettingsUiState {
            val account = ReiAnixSettingsAccountUiState(
                integrationAvailable = snapshot.account.integrationAvailable,
                connected = snapshot.account.connected,
                name = snapshot.account.name,
                email = snapshot.account.email,
                picture = snapshot.account.picture,
                state = snapshot.account.state,
            )
            return ReiAnixSettingsUiState(
                status = when (snapshot.status) {
                    "READY" -> ReiAnixSettingsLoadStatus.READY
                    else -> ReiAnixSettingsLoadStatus.ERROR
                },
                revision = snapshot.revision,
                account = account,
                categories = ReiAnixSettingsCategoryUiModel.defaultCategories().filter {
                    snapshot.categories[it.label] == true
                },
                settings = snapshot.settings,
                storage = ReiAnixSettingsStorageUiState(
                    known = snapshot.storage.known,
                    mediaReadState = snapshot.storage.mediaReadState,
                    broadStorageState = snapshot.storage.broadStorageState,
                    safRootCount = snapshot.storage.safRootCount,
                    removableVolumeCount = snapshot.storage.removableVolumeCount,
                    lifecycleState = snapshot.storage.lifecycleState,
                    api = snapshot.storage.api,
                    safSelectionPending = snapshot.storage.safSelectionPending,
                ),
                error = snapshot.error,
            )
        }
    }
}