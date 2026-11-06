package com.reiflix.reiflix_local.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStorageOnboardingStateTest {

    @Test
    fun checkingDoesNotShowPromptBeforeStorageValidationCompletes() {
        assertFalse(NativeStorageOnboardingState().isPromptVisible)
        assertFalse(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_CHECKING,
            ).isPromptVisible,
        )
    }

    @Test
    fun missingFolderShowsPromptAfterValidation() {
        assertTrue(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
            ).isPromptVisible,
        )
    }

    @Test
    fun folderPickerAndValidationErrorsRemainVisible() {
        assertTrue(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_FOLDER_PICKER_OPEN,
            ).isPromptVisible,
        )
        assertTrue(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_ERROR,
            ).isPromptVisible,
        )
    }

    @Test
    fun readyOrDismissedPromptRemainsHidden() {
        assertFalse(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_READY,
            ).isPromptVisible,
        )
        assertFalse(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
                dismissed = true,
            ).isPromptVisible,
        )
    }
}
