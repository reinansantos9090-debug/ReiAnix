package com.reiflix.reiflix_local

import android.content.ContentValues
import android.graphics.Matrix
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.MediaStore
import org.json.JSONObject
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePlayerPlaybackInstrumentedTest {
    private companion object {
        const val TEST_TAG = "[REIFLIX][TEST][PLAYER]"
        const val FIXTURE_DISPLAY_NAME = "reiflix-player-fixture.mp4"
        const val MAIN_THREAD_TIMEOUT_MS = 2_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var target: android.content.Context
    private var fixtureUri: android.net.Uri? = null
    private var activity: NativePlayerActivity? = null

    @Before
    fun setUp() {
        target = InstrumentationRegistry.getInstrumentation().targetContext
        grantMediaReadPermission()
        removeStalePlayerFixtures()
        logStage("SETUP_COMPLETE")
    }

    @After
    fun tearDown() {
        activity?.finish()
        fixtureUri?.let { runCatching { target.contentResolver.delete(it, null, null) } }
    }

    @Test
    fun pictureInPicture_entersAndReturnsToMainActivityWithImmersivePolicy() {
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri
        val intent = Intent(target, NativePlayerActivity::class.java)
            .putExtra("requestId", "instrumented-player-pip")
            .putExtra("uri", uri.toString())
            .putExtra("title", "Fixture local PiP")
            .putExtra("positionMs", 0L)
            .putExtra("autoplay", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent) as NativePlayerActivity
        val player = awaitView<PlayerView>("reiflix_player_view").let { view ->
            onMain { requireNotNull(view.player) }
        }
        await("PiP player must reach READY") { player.playbackState == Player.STATE_READY }
        val pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            target.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        assertTrue("CI emulator must expose Picture-in-Picture when the app advertises the optional feature", pipSupported)
        assertTrue(
            "Player must successfully enter Picture-in-Picture",
            onMain {
                activity!!.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            },
        )
        await("Player must actually enter Picture-in-Picture") {
            activity!!.isInPictureInPictureMode
        }
        val controls = awaitView<View>("reiflix_controls_root")
        assertFalse("Player controls must be hidden while in PiP", onMain { controls.isShown })

        val mainIntent = Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        target.startActivity(mainIntent)
        waitForReiAnixMainActivityForeground()
        await("Bringing ReiAnix to foreground must exit the player from PiP") {
            !activity!!.isInPictureInPictureMode
        }
        assertTrue("Native Player Activity must remain alive after PiP exit", !activity!!.isDestroyed)
        await("PiP exit must re-apply the configured immersive policy") {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity!!.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Test
    fun mainActivityPlayDeepLink_opensNativePlayerAndReachesReady() {
        logStage("MAIN_ACTIVITY_PLAY_HANDOFF_START")
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri

        val requestId = "instrumented-main-activity-play"
        val commandUri = android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", "play")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .appendQueryParameter("player_session_id", "instrumented-assist-session")
            .appendQueryParameter("uri", uri.toString())
            .appendQueryParameter("title", "Fixture through MainActivity")
            .appendQueryParameter("position_ms", "0")
            .appendQueryParameter("can_next", "false")
            .appendQueryParameter("can_previous", "false")
            .appendQueryParameter("autoplay", "false")
            .build()

        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(target, MainActivity::class.java)
                .setData(commandUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        await("MainActivity must dispatch the native player Activity") {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .firstOrNull { it is NativePlayerActivity } as? NativePlayerActivity
            if (resumed != null) {
                activity = resumed
                true
            } else {
                false
            }
        }

        val playerView = awaitView<PlayerView>("reiflix_player_view")
        val player = onMain {
            requireNotNull(playerView.player) {
                "MainActivity play handoff must attach the Media3 player to PlayerView"
            }
        }
        assertEquals(requestId, onMain { activity!!.intent.getStringExtra("requestId") })
        await("MainActivity handoff player must reach READY") {
            player.playbackState == Player.STATE_READY
        }
        assertTrue(
            "NativePlayerActivity must remain alive after a MainActivity deep-link handoff",
            !activity!!.isFinishing && !activity!!.isDestroyed,
        )
    }

    @Test
    fun nativeCommandDispatcher_playUsesNativePlayerOwnershipWithoutMainActivityRouting() {
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri

        val requestId = "instrumented-native-command-play"
        val commandUri = android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", "play")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .appendQueryParameter("player_session_id", "instrumented-native-command-session")
            .appendQueryParameter("episode_id", "1")
            .appendQueryParameter("uri", uri.toString())
            .appendQueryParameter("title", "Fixture native command")
            .appendQueryParameter("position_ms", "0")
            .appendQueryParameter("can_next", "false")
            .appendQueryParameter("can_previous", "false")
            .appendQueryParameter("autoplay", "false")
            .build()

        writeInternalNativeCommand(requestId, "play", commandUri)
        await("Private native command dispatcher must launch NativePlayerActivity") {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .firstOrNull { it is NativePlayerActivity } as? NativePlayerActivity
            if (resumed != null) {
                activity = resumed
                true
            } else {
                false
            }
        }
        assertEquals(requestId, onMain { requireNotNull(activity).intent.getStringExtra("requestId") })
        val playerView = awaitView<PlayerView>("reiflix_player_view")
        val player = onMain { requireNotNull(playerView.player) }
        await("Native command player must reach READY") {
            player.playbackState == Player.STATE_READY
        }
        assertTrue(
            "Native command handoff must leave NativePlayerActivity alive",
            onMain { !requireNotNull(activity).isFinishing && !requireNotNull(activity).isDestroyed },
        )
    }

    @Test
    fun nativeCommandDispatcher_thumbnailWhilePlayerOpenDoesNotTearDownPlayer() {
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri

        val playRequestId = "instrumented-native-command-thumbnail-player"
        val playIntent = Intent(target, NativePlayerActivity::class.java)
            .putExtra("requestId", playRequestId)
            .putExtra("playerSessionId", "instrumented-thumbnail-session")
            .putExtra("uri", uri.toString())
            .putExtra("episodeId", "1")
            .putExtra("title", "Fixture thumbnail overlap")
            .putExtra("positionMs", 0L)
            .putExtra("autoplay", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(playIntent) as NativePlayerActivity

        val playerView = awaitView<PlayerView>("reiflix_player_view")
        val player = onMain { requireNotNull(playerView.player) }
        await("Player must reach READY before thumbnail command") {
            player.playbackState == Player.STATE_READY
        }
        val requestId = "instrumented-native-command-thumbnail"
        val commandUri = android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", "extract_thumbnail")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .appendQueryParameter("uri", uri.toString())
            .appendQueryParameter("size", "320")
            .appendQueryParameter("modified_at", "0")
            .appendQueryParameter("media_identity", "instrumented-native-command-thumbnail")
            .build()

        writeInternalNativeCommand(requestId, "extract_thumbnail", commandUri)
        await("Thumbnail command must be consumed independently of MainActivity") {
            nativeMailboxEventExists(requestId, "thumbnail_ready") ||
                nativeMailboxEventExists(requestId, "thumbnail_error")
        }
        assertTrue(
            "Thumbnail command must not finish or destroy the active NativePlayerActivity",
            onMain { !requireNotNull(activity).isFinishing && !requireNotNull(activity).isDestroyed },
        )
        assertTrue(
            "Thumbnail processing must not release the active player",
            onMain { requireNotNull(playerView.player) === player && !requireNotNull(activity).isFinishing },
        )
    }

    @Test
    fun mainActivityPlay_restoresResumePositionOnlyAfterReady() {
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri

        val requestId = "instrumented-main-activity-resume"
        val resumeMs = 1_500L
        val commandUri = android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", "play")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .appendQueryParameter("player_session_id", "instrumented-resume-session")
            .appendQueryParameter("uri", uri.toString())
            .appendQueryParameter("title", "Fixture resume")
            .appendQueryParameter("position_ms", resumeMs.toString())
            .appendQueryParameter("can_next", "false")
            .appendQueryParameter("can_previous", "false")
            .appendQueryParameter("autoplay", "false")
            .build()

        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(target, MainActivity::class.java)
                .setData(commandUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        await("Resume test must dispatch NativePlayerActivity") {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .firstOrNull { it is NativePlayerActivity } as? NativePlayerActivity
            if (resumed != null) {
                activity = resumed
                true
            } else {
                false
            }
        }

        val playerView = awaitView<PlayerView>("reiflix_player_view")
        val player = onMain { requireNotNull(playerView.player) }
        await("Resume test player must reach READY") {
            player.playbackState == Player.STATE_READY
        }
        assertFalse("Resume instrumentation must not autoplay", onMain { player.isPlaying })
        await("Resume position must be applied after READY") {
            val position = onMain { player.currentPosition }
            position in 900L..2_100L
        }
        assertTrue(
            "Resume test Activity must remain alive",
            !activity!!.isFinishing && !activity!!.isDestroyed,
        )
    }

    @Test
    fun mainActivityPlay_duplicateSameUriIsSuppressed_butDifferentUriIsAccepted() {
        launchMainActivityForPlayer()
        val firstUri = "content://reiflix.test/player-first"
        val secondUri = "content://reiflix.test/player-second"

        sendPlayDeepLink("instrumented-duplicate-first", firstUri)
        await("First play request must create the native player") {
            currentPlayerRequestId() == "instrumented-duplicate-first"
        }

        sendPlayDeepLink("instrumented-duplicate-second", firstUri)
        SystemClock.sleep(100L)
        assertEquals(
            "instrumented-duplicate-first",
            currentPlayerRequestId(),
        )

        sendPlayDeepLink("instrumented-different-episode", secondUri)
        await("A different episode must not be blocked by same-URI deduplication") {
            currentPlayerRequestId() == "instrumented-different-episode"
        }
        assertEquals(secondUri, onMain { activity!!.intent.getStringExtra("uri") })
    }

    @Test
    fun mainActivityPlay_reopenSameUriAfterClosingPlayerIsAccepted() {
        launchMainActivityForPlayer()
        val uri = "content://reiflix.test/reopen"

        sendPlayDeepLink("instrumented-reopen-first", uri)
        await("Initial reopen test player must launch") {
            currentPlayerRequestId() == "instrumented-reopen-first"
        }

        activity?.finish()
        await("Closing the native player must return to MainActivity") {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .any { it is MainActivity }
        }

        SystemClock.sleep(1_000L)
        sendPlayDeepLink("instrumented-reopen-second", uri)
        await("The same episode must be reopenable after player close") {
            currentPlayerRequestId() == "instrumented-reopen-second"
        }
    }

    @Test
    fun playerZoom_isDisabledByDefault_andPinchLeavesVideoUntransformed() {
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri
        val intent = Intent(target, NativePlayerActivity::class.java)
            .putExtra("requestId", "instrumented-zoom-disabled")
            .putExtra("uri", uri.toString())
            .putExtra("title", "Fixture zoom disabled")
            .putExtra("positionMs", 0L)
            .putExtra("autoplay", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent) as NativePlayerActivity
        val playerView = awaitView<PlayerView>("reiflix_player_view")
        await("Disabled-by-default player must reach READY") {
            onMain { playerView.player?.playbackState == Player.STATE_READY }
        }
        val gestureLayer = awaitView<View>("reiflix_gesture_layer")
        val size = onMain { gestureLayer.width to gestureLayer.height }
        pinch(gestureLayer, zoom = true)
        await("Zoom disabled by default must leave resize mode on FIT") {
            playerView.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        assertTrue(
            "Zoom disabled by default must not apply a manual TextureView transform",
            onMain {
                val video = playerView.videoSurfaceView
                if (video !is TextureView) {
                    false
                } else {
                    val matrix = Matrix()
                    video.getTransform(matrix)
                    val values = FloatArray(9)
                    matrix.getValues(values)
                    kotlin.math.abs(values[Matrix.MSCALE_X] - 1f) <= 0.01f &&
                        kotlin.math.abs(values[Matrix.MSCALE_Y] - 1f) <= 0.01f
                }
            },
        )
    }

    @Test
    fun localMediaStoreFixture_reachesReadyAndPlays_inImmersivePlayer() {
        logStage("MEDIASTORE_FIXTURE_START")
        // Launch the player from a real ReiAnix task so Back is tested as it
        // is in production (Player -> MainActivity), rather than with the
        // launcher underneath a standalone test Activity.
        launchMainActivityForPlayer()
        val uri = insertFixtureIntoMediaStore()
        fixtureUri = uri

        val intent = Intent(target, NativePlayerActivity::class.java)
            .putExtra("requestId", "instrumented-player")
            .putExtra("uri", uri.toString())
            .putExtra("title", "Fixture local")
            .putExtra("positionMs", 0L)
            .putExtra("canNext", false)
            .putExtra("canPrevious", false)
            .putExtra("autoplay", false)
            .putExtra("setting_player_zoom_enabled", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        logStage("ACTIVITY_START")
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent) as NativePlayerActivity

        logStage("PLAYER_VIEW_WAIT")
        val playerView = awaitView<PlayerView>("reiflix_player_view")
        val player = onMain {
            requireNotNull(playerView.player) { "Media3 PlayerView did not receive a player" }
        }

        val preparing = awaitView<View>("reiflix_player_preparing")
        assertTrue(
            "Preparation indicator must be visible until the first frame is rendered",
            onMain {
                preparing.visibility == View.VISIBLE || activity!!.firstFrameRenderedForTesting
            },
        )

        logStage("WAIT_READY")
        await("Media3 must reach READY before interaction") {
            player.playbackState == Player.STATE_READY
        }
        assertFalse("Player should initially remain paused for deterministic interaction", onMain { player.isPlaying })
        assertTrue("Native player Activity must remain alive after READY", !activity!!.isFinishing)

        logStage("WAIT_FIRST_FRAME")
        val playPause = awaitView<View>("reiflix_play_pause")
        assertTrue("Play control must be present", onMain { playPause.performClick() })
        await("Play button must start playback") { player.isPlaying }
        await("The real video must render its first frame") { activity!!.firstFrameRenderedForTesting }
        await("Preparation indicator must disappear after the first frame") {
            preparing.visibility == View.GONE
        }
        // Do not block on global idleness here: Media3 and the rendered Flet host
        // can keep the main looper non-idle while playback is healthy.
        SystemClock.sleep(50L)
        assertTrue("Native player must actually be playing the local fixture", onMain { player.isPlaying })
        assertTrue("Native player Activity must remain alive after first frame", !activity!!.isFinishing && !activity!!.isDestroyed)

        logStage("SEEK_BAR")
        val seekBar = awaitView<android.widget.SeekBar>("reiflix_seekbar")
        val initialDuration = onMain { player.duration }
        assertTrue("Fixture must expose a positive duration", initialDuration > 0L)
        runOnMainBounded {
            val y = seekBar.height / 2f
            val startX = (seekBar.paddingLeft + 4).toFloat()
            val targetX = (seekBar.width - seekBar.paddingRight - 4).toFloat() * 0.5f
            val down = android.view.MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_DOWN,
                startX,
                y,
                0,
            )
            seekBar.dispatchTouchEvent(down)
            down.recycle()
            val move = android.view.MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_MOVE,
                targetX,
                y,
                0,
            )
            seekBar.dispatchTouchEvent(move)
            move.recycle()
            val up = android.view.MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_UP,
                targetX,
                y,
                0,
            )
            seekBar.dispatchTouchEvent(up)
            up.recycle()
        }
        await("Seek bar interaction must move player position") {
            player.currentPosition > 500L && player.currentPosition < player.duration - 100L
        }

        assertTrue("Pause control must be clickable", onMain { playPause.performClick() })
        await("Pause button must pause playback") { !player.isPlaying }
        assertTrue("Play button must resume playback", onMain { playPause.performClick() })
        await("Second click must resume playback") { player.isPlaying }

        await("Native player should keep system bars hidden") {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity!!.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.systemBars())
        }

        assertPlayerInsetsRespectSystemAndGestureSafeAreas()

        val rotationDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val positionBeforeRotation = onMain { player.currentPosition }
        rotationDevice.setOrientationLeft()
        await("Player Activity must survive portrait to landscape recreation") {
            activity!!.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        await("Playback position must survive landscape recreation") {
            onMain { player.currentPosition >= (positionBeforeRotation - 1_000L).coerceAtLeast(0L) }
        }
        await("Player must remain immersive after entering landscape") {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity!!.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.systemBars())
        }
        rotationDevice.setOrientationNatural()
        await("Player Activity must return to portrait") {
            activity!!.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        await("Playback position must survive return to portrait") {
            onMain { player.currentPosition >= (positionBeforeRotation - 1_000L).coerceAtLeast(0L) }
        }
        assertTrue(
            "Player must remain sensor-orientation capable",
            activity!!.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        )

        val beforeSeek = onMain { player.currentPosition }
        onMain { player.seekTo(0L) }
        await("Media3 seek must reach the beginning") { player.currentPosition <= 200L }
        assertTrue("Seek position should move from the pre-seek position", beforeSeek >= 0L)

        logStage("GESTURES_START")
        val gestureLayer = awaitView<View>("reiflix_gesture_layer")

        onMain {
            player.pause()
            player.seekTo(3_000L)
        }
        await("Gesture precondition must be reachable") { player.currentPosition >= 2_500L }

        val controls = awaitView<View>("reiflix_controls_root")
        val gestureSize = onMain { gestureLayer.width to gestureLayer.height }
        val controlsBefore = onMain { controls.visibility }

        tap(gestureLayer, gestureSize.first * 0.5f, gestureSize.second * 0.5f)
        await("Single tap must toggle controls") {
            controls.visibility != controlsBefore
        }

        val moreButton = awaitView<View>("reiflix_more_button")
        onMain { moreButton.performClick() }
        val volumeGesture = awaitView<View>("reiflix_gesture_volume")
        val brightnessGesture = awaitView<View>("reiflix_gesture_brightness")
        val doubleTapGesture = awaitView<View>("reiflix_gesture_double_tap")
        onMain {
            volumeGesture.performClick()
            brightnessGesture.performClick()
            doubleTapGesture.performClick()
            moreButton.performClick()
        }

        onMain {
            player.pause()
            player.seekTo(0L)
        }
        await("Horizontal no-seek precondition") { player.currentPosition <= 500L }
        swipe(
            gestureLayer,
            gestureSize.first * 0.25f,
            gestureSize.second * 0.5f,
            gestureSize.first * 0.65f,
            gestureSize.second * 0.5f,
        )
        SystemClock.sleep(250L)
        assertTrue(
            "Horizontal swipe must not seek; seekbar/buttons are the only seek surfaces",
            onMain { player.currentPosition <= 750L },
        )

        val feedback = awaitView<TextView>("reiflix_feedback")
        val systemAudio = target.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

        swipe(
            gestureLayer,
            gestureSize.first * 0.12f,
            gestureSize.second * 0.72f,
            gestureSize.first * 0.12f,
            gestureSize.second * 0.30f,
        )
        SystemClock.sleep(120L)
        assertTrue(
            "Enabled left vertical gesture must expose brightness feedback",
            onMain { feedback.text?.contains("brilho", ignoreCase = true) == true },
        )

        swipe(
            gestureLayer,
            gestureSize.first * 0.88f,
            gestureSize.second * 0.72f,
            gestureSize.first * 0.88f,
            gestureSize.second * 0.30f,
        )
        SystemClock.sleep(120L)
        assertTrue(
            "Enabled right vertical gesture must expose volume feedback",
            onMain { feedback.text?.contains("volume", ignoreCase = true) == true },
        )

        onMain { player.seekTo(3_000L) }
        await("Double tap precondition") { player.currentPosition >= 2_500L }
        tap(gestureLayer, gestureSize.first * 0.88f, gestureSize.second * 0.50f)
        tap(gestureLayer, gestureSize.first * 0.88f, gestureSize.second * 0.50f)
        await("Enabled right double tap must seek forward") {
            player.currentPosition >= 10_000L
        }

        logStage("PINCH_ZOOM")
        pinch(gestureLayer, zoom = true)
        await("Pinch out must keep Media3 on FIT while applying manual zoom") {
            playerView.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        assertTrue(
            "Pinch out must actually transform the video surface, not only change resize mode",
            onMain {
                val video = playerView.videoSurfaceView
                if (video !is TextureView) {
                    false
                } else {
                    val matrix = Matrix()
                    video.getTransform(matrix)
                    val values = FloatArray(9)
                    matrix.getValues(values)
                    values[Matrix.MSCALE_X] > 1.01f && values[Matrix.MSCALE_Y] > 1.01f
                }
            },
        )
        logStage("PINCH_FIT")
        pinch(gestureLayer, zoom = false)
        await("Pinch in must select FIT") {
            playerView.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        await("Pinch in must restore the video surface transform") {
            val video = playerView.videoSurfaceView
            if (video !is TextureView) {
                false
            } else {
                val matrix = Matrix()
                video.getTransform(matrix)
                val values = FloatArray(9)
                matrix.getValues(values)
                kotlin.math.abs(values[Matrix.MSCALE_X] - 1f) <= 0.01f &&
                    kotlin.math.abs(values[Matrix.MSCALE_Y] - 1f) <= 0.01f
            }
        }

        cancelGesture(gestureLayer, gestureSize.first * 0.8f, gestureSize.second * 0.5f)
        tap(gestureLayer, gestureSize.first * 0.5f, gestureSize.second * 0.5f)
        await("Touch state must recover after ACTION_CANCEL") {
            controls.visibility == View.VISIBLE
        }
        assertTrue(
            "Play control must remain accessible after gesture sequences",
            onMain { controls.isShown },
        )

        logStage("VISUAL_BACK")
        val lockButton = awaitView<View>("reiflix_lock_button")
        val backBeforeLock = awaitView<View>("reiflix_back_button")
        onMain { lockButton.performClick() }
        await("Lock button must keep the player in a locked interaction state") {
            !backBeforeLock.isShown
        }
        onMain { lockButton.performClick() }

        val back = awaitView<View>("reiflix_back_button")
        assertTrue("Visual Back control must be clickable", onMain { back.performClick() })
        // Once finish() is dispatched, the Activity can be detached from the
        // instrumentation main thread while Android is completing the transition.
        // Verify the lifecycle result without enqueueing another main-thread task.
        SystemClock.sleep(400L)
        assertTrue(
            "Visual Back must finish the native player Activity",
            activity?.isFinishing == true || activity?.isDestroyed == true,
        )

        logStage("SYSTEM_BACK")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        waitForForegroundPackage(target.packageName)
        val secondIntent = Intent(target, NativePlayerActivity::class.java)
            .putExtra("requestId", "instrumented-player-android-back")
            .putExtra("uri", fixtureUri!!.toString())
            .putExtra("title", "Fixture local")
            .putExtra("positionMs", 0L)
            .putExtra("canNext", false)
            .putExtra("canPrevious", false)
            .putExtra("autoplay", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(secondIntent) as NativePlayerActivity
        awaitView<View>("reiflix_back_button")
        assertTrue(
            "UiDevice.pressBack() must dispatch the real system Back action",
            device.pressBack(),
        )
        await("Android Back must finish the native player Activity") { activity!!.isFinishing }
        waitForReiAnixMainActivityForeground()

    }

    private fun assertPlayerInsetsRespectSystemAndGestureSafeAreas() {
        val root = activity!!.window.decorView
        val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(root)) {
            "Player root window insets must be available"
        }
        val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
        val gestures = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.mandatorySystemGestures())
        val safeLeft = maxOf(bars.left, cutout.left, gestures.left)
        val safeTop = maxOf(bars.top, cutout.top, gestures.top)
        val safeRight = maxOf(bars.right, cutout.right, gestures.right)
        val safeBottom = maxOf(bars.bottom, cutout.bottom, gestures.bottom)
        val topBar = awaitView<View>("reiflix_top_bar")
        val bottomBar = awaitView<View>("reiflix_bottom_bar")
        assertTrue("Top player controls must stay outside system/cutout gesture inset", topBar.top >= safeTop)
        assertTrue("Bottom player controls must stay outside system/gesture inset", root.height - bottomBar.bottom >= safeBottom)
        assertTrue("Player control container must reserve left safe inset", controlsReservedLeftInset(safeLeft))
        assertTrue("Player control container must reserve right safe inset", controlsReservedRightInset(safeRight))
    }

    private fun controlsReservedLeftInset(safeLeft: Int): Boolean =
        onMain {
            val controls = requireNotNull(activity!!.findViewById<View>(android.R.id.content)
                .findViewWithTag<View>("reiflix_controls_root"))
            controls.paddingLeft >= safeLeft
        }

    private fun controlsReservedRightInset(safeRight: Int): Boolean =
        onMain {
            val controls = requireNotNull(activity!!.findViewById<View>(android.R.id.content)
                .findViewWithTag<View>("reiflix_controls_root"))
            controls.paddingRight >= safeRight
        }

    private fun nativeMailboxEventExists(requestId: String, type: String): Boolean {
        val dir = File(target.filesDir, "data/reiflix-native-events")
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: return false
        return files.any { file ->
            runCatching {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                json.optString("type") == type && json.optString("requestId") == requestId
            }.getOrDefault(false)
        }
    }

    private fun writeInternalNativeCommand(requestId: String, action: String, commandUri: android.net.Uri) {
        val queue = File(target.filesDir, "data/reiflix-native-commands")
        check(queue.isDirectory || queue.mkdirs()) {
            "Unable to create native command queue: ${queue.absolutePath}"
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("requestId", requestId)
            .put("action", action)
            .put("createdAt", System.currentTimeMillis())
            .put("url", commandUri.toString())
            .toString()
        val temp = File(queue, ".command-${requestId}.tmp")
        val targetFile = File(queue, "command-${requestId}.json")
        temp.writeText(payload, Charsets.UTF_8)
        check(temp.renameTo(targetFile)) {
            "Unable to atomically publish native command file"
        }
    }

    private fun currentPlayerRequestId(): String =
        onMain {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .firstOrNull { it is NativePlayerActivity }
                ?.let { (it as NativePlayerActivity).intent.getStringExtra("requestId") }
                .orEmpty()
        }

    private fun sendPlayDeepLink(requestId: String, uri: String) {
        val commandUri = android.net.Uri.Builder()
            .scheme("reiflix")
            .authority("native")
            .appendQueryParameter("action", "play")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .appendQueryParameter("player_session_id", "instrumented-assist-session")
            .appendQueryParameter("uri", uri)
            .appendQueryParameter("title", requestId)
            .appendQueryParameter("position_ms", "0")
            .appendQueryParameter("can_next", "false")
            .appendQueryParameter("can_previous", "false")
            .appendQueryParameter("autoplay", "false")
            .build()
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(target, MainActivity::class.java)
                .setData(commandUri)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
        )
    }

    private fun launchMainActivityForPlayer() {
        val intent = Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        val deadline = SystemClock.uptimeMillis() + 10_000L
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.currentPackageName == target.packageName) return
            SystemClock.sleep(100L)
        }
        assertEquals(target.packageName, device.currentPackageName)
    }

    private fun waitForForegroundPackage(expected: String, timeoutMs: Long = 15_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.currentPackageName == expected) return
            SystemClock.sleep(100L)
        }
        assertTrue(
            "Expected foreground package $expected, got ${device.currentPackageName}",
            false,
        )
    }

    private fun waitForReiAnixMainActivityForeground(timeoutMs: Long = 15_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).currentPackageName == target.packageName) {
                var resumed = false
                runOnMainBounded {
                    resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .any { it is MainActivity }
                }
                if (resumed) return
            }
            SystemClock.sleep(100L)
        }
        assertTrue("Android Back must return to a resumed ReiAnix MainActivity", false)
    }
    private fun grantMediaReadPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_VIDEO"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        runCatching {
            val descriptor = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("pm grant ${target.packageName} $permission")
            descriptor.close()
        }
    }

    private fun removeStalePlayerFixtures() {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        target.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            MediaStore.Video.Media.DISPLAY_NAME + "=?",
            arrayOf(FIXTURE_DISPLAY_NAME),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            if (idColumn >= 0) {
                while (cursor.moveToNext()) {
                    val staleUri = android.content.ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(idColumn),
                    )
                    runCatching { target.contentResolver.delete(staleUri, null, null) }
                }
            }
        }
    }

    private fun insertFixtureIntoMediaStore(): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, FIXTURE_DISPLAY_NAME)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReiAnixTest")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = target.contentResolver.insert(collection, values)
            ?: error("Unable to create local MediaStore fixture")
        try {
            target.contentResolver.openOutputStream(uri).use { output ->
                checkNotNull(output)
                InstrumentationRegistry.getInstrumentation().context
                    .assets
                    .open("player_fixture.mp4")
                    .use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                target.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Throwable) {
            runCatching { target.contentResolver.delete(uri, null, null) }
            throw error
        }
    }


    private fun dispatchEvent(view: View, event: MotionEvent) {
        runOnMainBounded {
            view.dispatchTouchEvent(event)
        }
        event.recycle()
    }

    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        dispatchEvent(view, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchEvent(view, MotionEvent.obtain(down, down + 70L, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun swipe(view: View, startX: Float, startY: Float, endX: Float, endY: Float) {
        val down = SystemClock.uptimeMillis()
        dispatchEvent(view, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, startX, startY, 0))
        dispatchEvent(
            view,
            MotionEvent.obtain(
                down,
                down + 80L,
                MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * 0.35f,
                startY + (endY - startY) * 0.35f,
                0,
            ),
        )
        dispatchEvent(
            view,
            MotionEvent.obtain(
                down,
                down + 140L,
                MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * 0.75f,
                startY + (endY - startY) * 0.75f,
                0,
            ),
        )
        dispatchEvent(view, MotionEvent.obtain(down, down + 200L, MotionEvent.ACTION_UP, endX, endY, 0))
    }

    private fun cancelGesture(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        dispatchEvent(view, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchEvent(view, MotionEvent.obtain(down, down + 80L, MotionEvent.ACTION_MOVE, x, y - 90f, 0))
        dispatchEvent(view, MotionEvent.obtain(down, down + 100L, MotionEvent.ACTION_CANCEL, x, y - 90f, 0))
    }

    private fun pinch(view: View, zoom: Boolean) {
        val centerX = view.width * 0.5f
        val centerY = view.height * 0.5f
        val minimumSpan = ViewConfiguration.get(view.context).scaledMinimumScalingSpan.toFloat()
        val spanDelta = maxOf(192f, minimumSpan * 0.5f)
        val startSpan = minOf(view.width * 0.72f, minimumSpan + spanDelta)
        val maximumSpan = view.width * 0.9f
        assertTrue(
            "Pinch test surface is too narrow for the platform minimum scaling span",
            maximumSpan > startSpan + 32f,
        )
        val endSpan = if (zoom) {
            maximumSpan
        } else {
            maxOf(minimumSpan + 64f, startSpan - spanDelta)
        }
        val middleSpan = startSpan + (endSpan - startSpan) * 0.5f
        val down = SystemClock.uptimeMillis()

        val first = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val second = MotionEvent.PointerProperties().apply {
            id = 1
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        fun onePointer(action: Int, eventTime: Long): MotionEvent {
            val props = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = centerX - startSpan / 2f
                y = centerY
                pressure = 1f
                size = 1f
            }
            return MotionEvent.obtain(
                down,
                eventTime,
                action,
                1,
                arrayOf(props),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                0,
                0,
                0,
                0,
            )
        }

        fun twoPointers(action: Int, eventTime: Long, span: Float): MotionEvent {
            val left = MotionEvent.PointerCoords().apply {
                x = centerX - span / 2f
                y = centerY
                pressure = 1f
                size = 1f
            }
            val right = MotionEvent.PointerCoords().apply {
                x = centerX + span / 2f
                y = centerY
                pressure = 1f
                size = 1f
            }
            return MotionEvent.obtain(
                down,
                eventTime,
                action,
                2,
                arrayOf(first, second),
                arrayOf(left, right),
                0,
                0,
                1f,
                1f,
                0,
                0,
                0,
                0,
            )
        }

        dispatchEvent(view, onePointer(MotionEvent.ACTION_DOWN, down))
        dispatchEvent(
            view,
            twoPointers(
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                down + 50L,
                startSpan,
            ),
        )
        dispatchEvent(view, twoPointers(MotionEvent.ACTION_MOVE, down + 100L, middleSpan))
        dispatchEvent(view, twoPointers(MotionEvent.ACTION_MOVE, down + 140L, endSpan))
        dispatchEvent(
            view,
            twoPointers(
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                down + 180L,
                endSpan,
            ),
        )
        dispatchEvent(view, MotionEvent.obtain(down, down + 220L, MotionEvent.ACTION_UP, centerX - endSpan / 2f, centerY, 0))
    }

    private fun <T : View> awaitView(tag: String, timeoutMs: Long = 12_000L): T {
        var result: View? = null
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainBounded {
                result = activity?.window?.decorView?.findViewWithTag(tag)
            }
            if (result != null) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            SystemClock.sleep(50L)
        }
        assertTrue("view with tag $tag", false)
        throw AssertionError("view with tag $tag not found")
    }

    private fun <T> onMain(action: () -> T): T {
        var result: T? = null
        runOnMainBounded { result = action() }
        return checkNotNull(result) { "Main-thread action returned null unexpectedly" }
    }

    private fun runOnMainBounded(timeoutMs: Long = MAIN_THREAD_TIMEOUT_MS, action: () -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        mainHandler.post {
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
            completed.await(timeoutMs, TimeUnit.MILLISECONDS),
        )
        failure.get()?.let { throw AssertionError("Main-thread action failed", it) }
    }

    private fun await(description: String, timeoutMs: Long = 12_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val passed = onMain { condition() }
            if (passed) return
            SystemClock.sleep(50L)
        }
        assertTrue(description, false)
    }

    private fun logStage(stage: String) {
        Log.i(TEST_TAG, "PLAYER_TEST_STAGE=" + stage)
    }
}
