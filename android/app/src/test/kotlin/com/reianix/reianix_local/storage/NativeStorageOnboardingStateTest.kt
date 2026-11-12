package com.reianix.reianix_local.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStorageOnboardingStateTest {

    @Test
    fun checkingDoesNotShowPromptWhileSafAccessIsBeingValidated() {
        assertFalse(NativeStorageOnboardingState().isPromptVisible)
        assertFalse(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_CHECKING,
            ).isPromptVisible,
        )
    }

    @Test
    fun missingFolderShowsChoiceUntilTheUserDismissesIt() {
        assertTrue(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
            ).isPromptVisible,
        )
        assertFalse(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
                dismissed = true,
            ).isPromptVisible,
        )
    }

    @Test
    fun explicitPickerAndRecoveryErrorRemainActionable() {
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
    fun readyStateNeverShowsOnboarding() {
        assertFalse(
            NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_READY,
            ).isPromptVisible,
        )
    }
}
