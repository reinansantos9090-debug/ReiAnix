package com.reiflix.reiflix_local.ui.model

import androidx.annotation.Keep

@Keep
data class ReiAnixStorageSourceUiModel(
    val reference: String = "",
    val name: String = "",
    val kind: String = "",
    val authorization: String = "",
    val status: String = "",
    val safIdentity: String? = null,
    val safVolumeId: String? = null,
    val safDocumentId: String? = null,
) {
    val stableKey: String
        get() = safIdentity?.takeIf { it.isNotBlank() } ?: reference.ifBlank { name }
}

@Keep
data class ReiAnixStorageUiState(
    val mediaReadState: String = "denied",
    val broadStorageState: String = "unavailable",
    val safRoots: List<String> = emptyList(),
    val safSelectionPending: Boolean = false,
    val safRootIdentities: List<String> = emptyList(),
    val removableVolumes: List<String> = emptyList(),
    val scannerCapabilities: List<String> = emptyList(),
    val reconciliationCapabilities: List<String> = emptyList(),
    val lifecycleState: String = "unknown",
    val api: Int? = null,
    val configuredSources: List<ReiAnixStorageSourceUiModel> = emptyList(),
) {
    val hasAnyConfiguredSource: Boolean
        get() = configuredSources.isNotEmpty()

    fun sourceState(source: ReiAnixStorageSourceUiModel): String =
        when (source.kind.lowercase()) {
            "saf" -> {
                val identity = source.safIdentity?.takeIf { it.isNotBlank() }
                val authorized = when {
                    identity != null -> identity in safRootIdentities
                    else -> source.reference in safRoots
                }
                if (authorized) {
                    "available"
                } else {
                    when (source.status.lowercase()) {
                        "unavailable", "error", "partial" -> source.status.lowercase()
                        else -> "revoked"
                    }
                }
            }
            "mediastore", "media" -> mediaReadState
            "broad-storage", "broad" -> broadStorageState
            else -> source.status.ifBlank { source.authorization.ifBlank { "unknown" } }
        }
}
