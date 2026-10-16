package com.reiflix.reiflix_local.storage

/**
 * Native, UI-facing decision for the first library source.
 *
 * This value is derived from the canonical SQLite folder records and Android's
 * current persisted SAF grants. It is not a persisted replacement for either:
 * SQLite remains the source of configured folders, and ContentResolver remains
 * authoritative for grants.
 */
data class NativeStorageOnboardingState(
    val phase: String = PHASE_CHECKING,
    val message: String? = null,
    val error: String? = null,
    val dismissed: Boolean = false,
) {
    val isPromptVisible: Boolean
        get() = !dismissed && phase in PROMPT_PHASES

    companion object {
        const val PHASE_CHECKING = "checking"
        const val PHASE_READY = "ready"
        const val PHASE_NEEDS_FOLDER = "needs_folder"
        const val PHASE_FOLDER_PICKER_OPEN = "folder_picker_open"
        const val PHASE_ERROR = "error"

        private val PROMPT_PHASES = setOf(
            PHASE_CHECKING,
            PHASE_NEEDS_FOLDER,
            PHASE_FOLDER_PICKER_OPEN,
            PHASE_ERROR,
        )
    }
}
