package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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


    @Test
    fun homeRefreshCoordinatorRequiresOrderedSuccessfulStagesAndFencesStaleRequests() {
        val requestId = java.util.UUID.randomUUID().toString()
        assertTrue(HomeRefreshCoordinator.begin(requestId, requestId, "native_compose_scan"))
        assertFalse(HomeRefreshCoordinator.markScanTerminal(requestId))
        assertTrue(HomeRefreshCoordinator.markScanStarted(requestId))
        assertFalse(HomeRefreshCoordinator.markScanStarted(requestId))
        assertTrue(HomeRefreshCoordinator.markScanTerminal(requestId))
        assertTrue(HomeRefreshCoordinator.markCatalogUpdated(requestId))
        assertTrue(HomeRefreshCoordinator.complete(requestId))
        assertEquals(HomeRefreshPhase.SUCCESS, HomeRefreshCoordinator.state.value.phase)
        assertFalse(HomeRefreshCoordinator.markCatalogUpdated(requestId))
    }

    @Test
    fun homeRefreshCoordinatorDoesNotReplaceAnActiveRequestAndAllowsCancellation() {
        val owner = java.util.UUID.randomUUID().toString()
        val rejected = java.util.UUID.randomUUID().toString()
        assertTrue(HomeRefreshCoordinator.begin(owner, owner, "native_compose_scan"))
        assertFalse(HomeRefreshCoordinator.begin(rejected, rejected, "native_compose_scan"))
        assertEquals(owner, HomeRefreshCoordinator.state.value.requestId)
        assertTrue(HomeRefreshCoordinator.cancel(owner, "test_cancellation"))
        assertEquals(HomeRefreshPhase.CANCELLED, HomeRefreshCoordinator.state.value.phase)
        assertFalse(HomeRefreshCoordinator.markScanStarted(owner))
    }


    private enum class LifecycleFixture { RESUME, SHOW }

    private data class LifecycleEventFixture(val state: Any?)

    private data class LifecycleDataEventFixture(val data: Any?)

    @Test
    fun lifecycleNormalizerSupportsEnumsEventObjectsAndDottedStrings() {
        assertEquals("resume", normalizeAppLifecycleState(LifecycleFixture.RESUME))
        assertEquals("show", normalizeAppLifecycleState(LifecycleFixture.SHOW))
        assertEquals("resume", normalizeAppLifecycleState("AppLifecycleState.RESUME"))
        assertEquals("resume", normalizeAppLifecycleState("RESUME"))
        assertEquals("resume", normalizeAppLifecycleState("resume"))
        assertEquals("resume", normalizeAppLifecycleState(LifecycleEventFixture(LifecycleFixture.RESUME)))
        assertEquals("resume", normalizeAppLifecycleState(LifecycleEventFixture("resume")))
        assertEquals("show", normalizeAppLifecycleState(LifecycleDataEventFixture("LifecycleState.SHOW")))
        assertEquals("", normalizeAppLifecycleState(null))
    }


    @Test
    fun initialRouteCannotExposeHomeWhenRecoveryIsRequired() {
        assertEquals(ReiAnixRoutes.RECOVERY, MainEntryPoint.initialRoute(recoveryRequired = true))
        assertEquals(ReiAnixRoutes.HOME, MainEntryPoint.initialRoute(recoveryRequired = false))
    }

    @Test
    fun lifecycleCallbacksNormalizeEventsAndTrackForegroundState() {
        MainEntryPoint.onActivityLifecycleEvent("AppLifecycleState.RESUME")
        val resumed = NativeLifecycleCoordinator.state.value
        assertEquals("resumed", resumed.state)
        assertTrue(resumed.resumed)

        MainEntryPoint.onActivityLifecycleEvent(LifecycleEventFixture("PAUSE"))
        val paused = NativeLifecycleCoordinator.state.value
        assertEquals("paused", paused.state)
        assertFalse(paused.resumed)
        assertTrue(paused.generation > resumed.generation)
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
