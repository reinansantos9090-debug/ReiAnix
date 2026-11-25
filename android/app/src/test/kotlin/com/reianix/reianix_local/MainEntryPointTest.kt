package com.reianix.reianix_local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainEntryPointTest {

    @Test
    fun watchedEpisodeResumesPartialRewatchEvenPastCompletionThreshold() {
        for (position in listOf(35.25, 115.0)) {
            val start = resolveNativePlaybackStartPosition(position, 120.0, true, true)
            assertEquals(position, start.positionSeconds, 0.0)
            assertEquals((position * 1_000).toLong(), start.positionMsOverride)
            assertFalse(start.progressInvalid)
        }
        assertEquals(35_000L,
            resolveNativePlaybackStartPosition(35.0, null, true, true).positionMsOverride)
    }

    @Test
    fun completedEpisodeStartsAtZeroButOrdinaryProgressStillResumes() {
        assertEquals(0L,
            resolveNativePlaybackStartPosition(120.0, 120.0, true, true).positionMsOverride)
        assertEquals(35_000L,
            resolveNativePlaybackStartPosition(35.0, 120.0, false, true).positionMsOverride)
        assertNull(resolveNativePlaybackStartPosition(0.0, 120.0, false, true).positionMsOverride)
        assertEquals(0L,
            resolveNativePlaybackStartPosition(35.0, 120.0, true, false).positionMsOverride)
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 121.0)) {
            val start = resolveNativePlaybackStartPosition(invalid, 120.0, true, true)
            assertTrue(start.progressInvalid)
            assertEquals(0L, start.positionMsOverride)
        }
    }

    @Test
    fun nativeOperationsStayBlockedUntilActivityDecisionIsComplete() {
        assertFalse(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = false,
                bootstrapState = readyState(),
            )
        )
    }

    @Test
    fun healthyCanonicalDatabaseReleasesNativeOperationsAfterBothGatesComplete() {
        assertTrue(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(),
            )
        )
    }

    @Test
    fun freshInstallWithoutDatabaseCanReleaseNativeOperationsAfterValidation() {
        assertTrue(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(
                    persistenceStatus = ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
                ),
            )
        )
    }

    @Test
    fun nonFatalWarningsDoNotBlockAnOtherwiseReadyBootstrap() {
        assertTrue(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(
                    phase = ReiAnixBootstrapPhase.DEGRADED,
                    warnings = listOf("non-fatal startup warning"),
                ),
            )
        )
    }

    @Test
    fun incompleteBootstrapStageKeepsNativeOperationsBlocked() {
        assertFalse(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(stage = ReiAnixBootstrapStage.NATIVE_SERVICES_READY),
            )
        )
    }

    @Test
    fun explicitRecoveryRequirementAlwaysBlocksNativeOperations() {
        assertFalse(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(recoveryRequired = true),
            )
        )
    }

    @Test
    fun recoveryRequiredPersistenceStatusBlocksNativeOperations() {
        assertFalse(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(
                    persistenceStatus = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                ),
            )
        )
    }

    @Test
    fun uncheckedPersistenceNeverReleasesNativeOperations() {
        assertFalse(
            MainEntryPoint.allowsNativeOperations(
                activityDecisionComplete = true,
                bootstrapState = readyState(
                    persistenceStatus = ReiAnixBootstrapPersistenceStatus.NOT_CHECKED,
                ),
            )
        )
    }

    private fun readyState(
        phase: ReiAnixBootstrapPhase = ReiAnixBootstrapPhase.READY,
        stage: ReiAnixBootstrapStage = ReiAnixBootstrapStage.READY,
        persistenceStatus: ReiAnixBootstrapPersistenceStatus =
            ReiAnixBootstrapPersistenceStatus.HEALTHY,
        recoveryRequired: Boolean = false,
        warnings: List<String> = emptyList(),
    ) = ReiAnixBootstrapState(
        phase = phase,
        stage = stage,
        persistenceStatus = persistenceStatus,
        recoveryRequired = recoveryRequired,
        warnings = warnings,
    )
}
