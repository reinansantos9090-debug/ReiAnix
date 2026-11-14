package com.reiflix.reiflix_local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainEntryPointTest {

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
