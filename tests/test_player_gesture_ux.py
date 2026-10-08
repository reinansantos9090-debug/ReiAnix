import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER_PATH = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
ANDROID_TEST_PATH = ROOT / "android/app/src/test/kotlin/com/reiflix/reiflix_local/PlayerGesturePolicyTest.kt"


class PlayerGestureUxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER_PATH.read_text(encoding="utf-8")
        cls.android_test = ANDROID_TEST_PATH.read_text(encoding="utf-8")

    def test_single_gesture_owner_and_explicit_state_machine(self):
        self.assertIn("private inner class GestureLayer", self.player)
        self.assertIn("private enum class GestureMode", self.player)
        self.assertIn("GestureMode.DOUBLE_TAP", self.player)
        self.assertIn("GestureMode.PINCH", self.player)
        self.assertIn("GestureMode.PAN", self.player)
        self.assertNotIn("GestureMode.HORIZONTAL_SEEK", self.player)
        self.assertIn("GestureMode.VERTICAL", self.player)
        self.assertNotIn("manualDoubleTap", self.player)
        self.assertNotIn("lastTapUpTime", self.player)
        self.assertNotIn("lastTapX", self.player)
        self.assertNotIn("lastTapY", self.player)

    def test_double_tap_seeks_only_and_is_not_zoom(self):
        self.assertIn("override fun onDoubleTap", self.player)
        self.assertIn("seekBy(-doubleTapSeekMs", self.player)
        self.assertIn("seekBy(doubleTapSeekMs", self.player)
        self.assertIn("PLAYER_DOUBLE_TAP side=center_ignored", self.player)
        self.assertEqual(1, self.player.count("override fun onDoubleTap"))
        self.assertNotIn("manualDoubleTap", self.player)

    def test_zoom_pan_math_is_isolated_in_player_gesture_policy(self):
        for token in (
            "data class PanBounds",
            "fun clampZoom(",
            "fun panBounds(",
            "fun clampTranslation(",
            "fun seekTarget(",
            "fun isMeaningfulMovement(",
        ):
            self.assertIn(token, self.player)

        self.assertIn("PlayerGesturePolicy.clampZoom", self.player)
        self.assertIn("PlayerGesturePolicy.clampTranslation", self.player)
        self.assertIn("zoomEnabled", self.player)
        self.assertIn("MIN_SCALE_FACTOR", self.player)
        self.assertIn("MAX_SCALE_FACTOR", self.player)
        self.assertIn("PlayerGesturePolicy.seekTarget", self.player)
        self.assertIn("val video = player.videoSize", self.player)
        self.assertIn("video.pixelWidthHeightRatio", self.player)

    def test_pan_only_starts_after_zoom_and_is_clamped_to_viewport(self):
        self.assertIn("if (zoomScale > 1.01f && PlayerGesturePolicy.isMeaningfulMovement", self.player)
        self.assertIn("applyPanDelta(event.x - previousX, event.y - previousY)", self.player)
        self.assertIn("val bounds = calculatePanBounds()", self.player)
        self.assertIn("translationX.coerceIn(-bounds.maxX, bounds.maxX)", self.player)

    def test_cancel_and_multi_touch_reset_gesture_state(self):
        self.assertIn("MotionEvent.ACTION_CANCEL", self.player)
        self.assertIn("finishPinchGesture(cancelled = true)", self.player)
        self.assertIn("resetTransientState()", self.player)
        self.assertIn("ACTION_POINTER_DOWN", self.player)
        self.assertIn("ACTION_POINTER_UP", self.player)
        self.assertIn("MotionEvent.ACTION_UP", self.player)
        self.assertIn("cancelGestureDetector()", self.player)
        self.assertIn("if (!zoomEnabled ||", self.player)
        self.assertIn("pinchActive = false", self.player)
        self.assertIn("lastPanX = null", self.player)
        self.assertIn("lastPanY = null", self.player)

    def test_disabled_vertical_gestures_are_silent_and_no_legacy_messages_remain(self):
        self.assertIn("if (brightnessGesturesEnabled)", self.player)
        self.assertIn("if (volumeGesturesEnabled)", self.player)
        self.assertNotIn("Gesto de volume desligado", self.player[self.player.index("private fun handleVerticalGesture"):self.player.index("private fun cancelGestureDetector")])
        self.assertNotIn("Gesto de brilho desligado", self.player[self.player.index("private fun handleVerticalGesture"):self.player.index("private fun cancelGestureDetector")])

    def test_horizontal_swipe_is_ignored_for_seek(self):
        self.assertIn("GESTURE_HORIZONTAL_IGNORED", self.player)
        self.assertNotIn("horizontalSeekDelta", self.player)
        self.assertNotIn("GestureMode.HORIZONTAL_SEEK", self.player)

    def test_reusable_feedback_overlay_and_overlay_cancellation(self):
        self.assertEqual(1, self.player.count("private lateinit var feedback: TextView"))
        self.assertEqual(1, self.player.count("private val feedbackHider = object : Runnable"))
        self.assertIn("handler.removeCallbacks(feedbackHider)", self.player)
        self.assertIn("handler.post(feedbackHider)", self.player)
        self.assertIn('feedback.visibility = View.GONE', self.player)
        self.assertIn('cancelInteractions()', self.player)
        self.assertIn('showTrackSelection', self.player)
        self.assertIn('showSpeedSelection', self.player)
        self.assertIn('showAspectSelection', self.player)

    def test_rotation_and_lifecycle_cancel_active_touch_state(self):
        self.assertIn('findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()', self.player)
        self.assertIn("override fun onConfigurationChanged", self.player)
        self.assertIn("override fun onStop()", self.player)
        self.assertIn("override fun onDestroy()", self.player)

    def test_no_legacy_gesture_tracking_or_duplicate_gesture_authority(self):
        self.assertNotIn("VelocityTracker", self.player)
        self.assertNotIn("velocityTracker", self.player)
        self.assertNotIn("manualDoubleTap", self.player)
        self.assertNotIn("lastTapUpTime", self.player)
        self.assertNotIn("lastTapX", self.player)
        self.assertNotIn("lastTapY", self.player)
        self.assertEqual(1, self.player.count("private inner class GestureLayer"))
        self.assertEqual(1, self.player.count("private val gestureDetector = GestureDetector"))
        self.assertEqual(1, self.player.count("private val scaleDetector = ScaleGestureDetector"))
        self.assertEqual(1, self.player.count("internal object PlayerGesturePolicy"))

    def test_android_policy_tests_cover_new_pure_math(self):
        for token in (
            "zoomIsClampedToConfiguredRange",
            "panBoundsAndTranslationClampUseViewportMath",
            "seekTargetIsAlwaysWithinMediaDuration",
            "movementAndVerticalDeltaUseRelativeViewportValues",
        ):
            self.assertIn(token, self.android_test)


if __name__ == "__main__":
    unittest.main()
