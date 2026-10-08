package com.reiflix.reiflix_local

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackAndSettingsReturnInstrumentedTest {
    private lateinit var target: android.content.Context
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        target = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        launchMainActivity()
        waitForForegroundPackage(target.packageName)
        assertMainActivityAlive()
    }

    @After
    fun tearDown() {
        runCatching { device.pressHome() }
    }

    @Test
    fun allFilesSettingsBackReturnsToMainActivity() {
        val wasLaunched = invokeNoArg("openBroadStorageSettings")
        assertTrue("All-files Settings launch method must accept the request while Activity is resumed", wasLaunched)
        waitForForegroundPackage("com.android.settings")
        pressBackAcrossApplicationBoundary()
        waitForForegroundPackage(target.packageName)
        assertMainActivityAlive()
    }

    @Test
    fun appInfoSettingsBackReturnsToMainActivity() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + target.packageName))
        val launched = invokeExternalSettings("app_details", intent)
        assertTrue("App Info Settings launch must be accepted", launched)
        waitForForegroundPackage("com.android.settings")
        pressBackAcrossApplicationBoundary()
        waitForForegroundPackage(target.packageName)
        assertMainActivityAlive()
    }

    @Test
    fun safPickerBackReturnsToMainActivityAndReleasesPendingState() {
        val launched = invokeNoArg("openTreePicker")
        assertTrue("SAF picker launch method must accept the request while Activity is resumed", launched)
        waitForForegroundPackage(
            "com.android.documentsui",
            "com.google.android.documentsui",
        )
        pressBackAcrossApplicationBoundary()
        waitForForegroundPackage(target.packageName)
        val activity = runOnMainBoundedValue { currentResumedMainActivity() }
        assertFalse("MainActivity must not be finishing after DocumentsUI Back", activity.isFinishing)
        assertFalse("MainActivity must remain alive after DocumentsUI Back", activity.isDestroyed)
        val field = MainActivity::class.java.getDeclaredField("safPickerPending")
        field.isAccessible = true
        assertFalse("SAF pending state must be cleared after cancel/back", field.getBoolean(activity))
    }

    @Test
    fun mainActivitySelectTreeDeepLink_opensDocumentsUi_andBackClearsPending() {
        val requestId = "instrumented-main-activity-select-tree"
        val commandUri = android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", "select_tree")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .build()

        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(target, MainActivity::class.java)
                .setData(commandUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        waitForForegroundPackage(
            "com.android.documentsui",
            "com.google.android.documentsui",
        )
        pressBackAcrossApplicationBoundary()
        waitForForegroundPackage(target.packageName)

        val activity = runOnMainBoundedValue { currentResumedMainActivity() }
        assertFalse("MainActivity must not be finishing after DocumentsUI Back", activity.isFinishing)
        assertFalse("MainActivity must remain alive after DocumentsUI Back", activity.isDestroyed)
        val pending = MainActivity::class.java.getDeclaredField("safPickerPending").apply { isAccessible = true }
        assertFalse("MainActivity IPC deep-link must release SAF pending state after cancel", pending.getBoolean(activity))
    }

    @Test
    fun mainActivityRequestMediaAccessDeepLink_isReceivedByNativeRequestState() {
        val requestId = "instrumented-main-activity-media-access"
        val commandUri = nativeCommandUri("request_media_access", requestId)
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(target, MainActivity::class.java)
                .setData(commandUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        val state = awaitNativeRequestState(requestId)
        assertTrue(
            "MainActivity must create a request snapshot before dispatching media permission access, state=$state",
            state in setOf(
                NativeRequestState.OperationState.RECEIVED,
                NativeRequestState.OperationState.QUEUED,
                NativeRequestState.OperationState.RUNNING,
                NativeRequestState.OperationState.COMPLETED,
                NativeRequestState.OperationState.CANCELLED,
                NativeRequestState.OperationState.FAILED,
                NativeRequestState.OperationState.TIMEOUT,
            ),
        )
        if (device.currentPackageName != target.packageName) {
            device.pressBack()
            waitForForegroundPackage(target.packageName)
        }
    }

    @Test
    fun mainActivityBroadStorageDeepLink_isReceivedAndDispatched() {
        val requestId = "instrumented-main-activity-broad-storage"
        val commandUri = nativeCommandUri("open_broad_storage_settings", requestId)
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(target, MainActivity::class.java)
                .setData(commandUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        val state = awaitNativeRequestState(requestId)
        assertTrue(
            "MainActivity must create a request snapshot for broad-storage Settings, state=$state",
            state in setOf(
                NativeRequestState.OperationState.RECEIVED,
                NativeRequestState.OperationState.QUEUED,
                NativeRequestState.OperationState.RUNNING,
                NativeRequestState.OperationState.COMPLETED,
                NativeRequestState.OperationState.FAILED,
            ),
        )
        if (device.currentPackageName != target.packageName) {
            waitForForegroundPackage("com.android.settings")
            device.pressBack()
            waitForForegroundPackage(target.packageName)
        }
    }

    @Test
    fun appSystemBackFromChildActivityReturnsToReiAnix() {
        val intent = Intent(target, NativePlayerActivity::class.java)
            .putExtra("requestId", "instrumented-system-back")
            .putExtra("uri", "content://invalid/reiflix-system-back")
            .putExtra("title", "Invalid fixture")
            .putExtra("autoplay", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        waitForForegroundPackage(target.packageName)
        assertTrue(
            "UiDevice.pressBack() must dispatch the supported system Back action",
            device.pressBack(),
        )
        waitForForegroundPackage(target.packageName)
        assertMainActivityAlive()
    }

    private fun nativeCommandUri(action: String, requestId: String): android.net.Uri =
        android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", action)
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .build()

    private fun awaitNativeRequestState(
        requestId: String,
        timeoutMs: Long = 5_000L,
    ): NativeRequestState.OperationState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var state: NativeRequestState.OperationState? = null
        while (SystemClock.uptimeMillis() < deadline) {
            state = runOnMainBoundedValue {
                val activity = currentResumedMainActivity()
                val field = MainActivity::class.java.getDeclaredField("nativeRequestState")
                    .apply { isAccessible = true }
                val requestState = field.get(activity) as NativeRequestState
                requestState.operationState(requestId)
            }
            if (state != null) return state!!
            SystemClock.sleep(100L)
        }
        assertTrue("Native request $requestId was never accepted by MainActivity", false)
        throw AssertionError("unreachable")
    }

    private fun launchMainActivity() {
        val intent = Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
    }

    private fun invokeNoArg(name: String): Boolean {
        return runOnMainBoundedValue {
            val activity = currentResumedMainActivity()
            val method = MainActivity::class.java.getDeclaredMethod(name)
            method.isAccessible = true
            (method.invoke(activity) as? Boolean) ?: true
        }
    }

    private fun invokeExternalSettings(kind: String, intent: Intent): Boolean {
        return runOnMainBoundedValue {
            val activity = currentResumedMainActivity()
            val method = MainActivity::class.java.getDeclaredMethod(
                "launchExternalSettings",
                String::class.java,
                String::class.java,
                java.util.List::class.java,
            )
            method.isAccessible = true
            method.invoke(
                activity,
                kind,
                null,
                listOf("instrumented" to intent),
            ) as Boolean
        }
    }

    private fun currentResumedMainActivity(): MainActivity {
        var activity: MainActivity? = null
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is MainActivity }
            ?.let { activity = it as MainActivity }
        return checkNotNull(activity) { "MainActivity must be RESUMED when this helper is used" }
    }

    private fun assertMainActivityAlive() {
        val state = runOnMainBoundedValue {
            val activity = currentResumedMainActivity()
            activity.isFinishing to activity.isDestroyed
        }
        assertFalse("MainActivity must not be finishing", state.first)
        assertFalse("MainActivity must remain alive", state.second)
    }

    private fun runOnMainBounded(timeoutMs: Long = 2_000L, action: () -> Unit) {
        val completed = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }
        assertTrue(
            "Android main thread did not respond within ${timeoutMs}ms; possible UI-thread hang",
            completed.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        failure.get()?.let { throw AssertionError("Main-thread action failed", it) }
    }

    private fun <T> runOnMainBoundedValue(timeoutMs: Long = 2_000L, action: () -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference<T?>(null)
        runOnMainBounded(timeoutMs) {
            result.set(action())
        }
        return checkNotNull(result.get()) { "Main-thread action returned null unexpectedly" }
    }

    private fun pressBackAcrossApplicationBoundary() {
        // UiDevice.pressBack() may return false on newer emulator images even
        // when the back key event is dispatched and the foreground activity
        // changes. The assertions below verify the actual foreground result.
        device.pressBack()
        SystemClock.sleep(250L)
    }

    private fun waitForForegroundPackage(vararg packages: String, timeoutMs: Long = 15_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val expected = packages.toSet()
        while (SystemClock.uptimeMillis() < deadline) {
            if (expected.contains(device.currentPackageName)) return
            SystemClock.sleep(100L)
        }
        assertTrue(
            "Expected foreground Android package, got: " + (device.currentPackageName ?: "<none>") +
                "; expected one of: " + packages.joinToString(),
            false,
        )
    }
}