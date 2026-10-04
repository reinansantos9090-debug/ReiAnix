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
data class ReiAnixSettingsAccountUiState(
    val integrationAvailable: Boolean = false,
    val connected: Boolean = false,
    val name: String = "",
    val email: String = "",
    val state: String = "disconnected",
)

@Keep
data class ReiAnixSettingsUiState(
    val status: ReiAnixSettingsLoadStatus = ReiAnixSettingsLoadStatus.LOADING,
    val revision: Long = 0L,
    val account: ReiAnixSettingsAccountUiState = ReiAnixSettingsAccountUiState(),
    val categories: List<ReiAnixSettingsCategoryUiModel> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    companion object {
        fun fromSnapshot(snapshot: ReiAnixSettingsSnapshot): ReiAnixSettingsUiState {
            val account = ReiAnixSettingsAccountUiState(
                integrationAvailable = snapshot.account.integrationAvailable,
                connected = snapshot.account.connected,
                name = snapshot.account.name,
                email = snapshot.account.email,
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
                error = snapshot.error,
            )
        }
    }
}
