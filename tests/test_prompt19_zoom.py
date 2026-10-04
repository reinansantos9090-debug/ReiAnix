import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
REQUEST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt"
MAIN = ROOT / "main.py"
SETTINGS = ROOT / "core/settings.py"
SETTINGS_VIEW = ROOT / "views/settings_view.py"
STORE_TEST = ROOT / "tests/test_settings_store.py"
ANDROID_REQUEST_TEST = ROOT / "android/app/src/test/kotlin/com/reiflix/reiflix_local/NativePlayerRequestTest.kt"


class Prompt19ZoomContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.request = REQUEST.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.settings = SETTINGS.read_text(encoding="utf-8")
        cls.settings_view = SETTINGS_VIEW.read_text(encoding="utf-8")
        cls.store_test = STORE_TEST.read_text(encoding="utf-8")
        cls.android_request_test = ANDROID_REQUEST_TEST.read_text(encoding="utf-8")

    def test_zoom_setting_defaults_to_false_and_is_persisted(self):
        self.assertIn('SettingDefinition("player.zoom_enabled", "bool", False)', self.settings)
        self.assertIn('"player.zoom_enabled"', self.settings)
        self.assertIn('row("player.zoom_enabled", "Zoom por gesto"', self.settings_view)
        self.assertIn('"player.zoom_enabled": settings.get("player.zoom_enabled")', self.main)
        self.assertIn('self.assertFalse(self.settings.get("player.zoom_enabled"))', self.store_test)
        self.assertIn('self.settings.set("player.zoom_enabled", True)', self.store_test)

    def test_python_to_android_request_has_explicit_zoom_flag_and_false_fallback(self):
        self.assertIn('"player.zoom_enabled": settings.get("player.zoom_enabled")', self.main)
        self.assertIn('val zoomEnabled: Boolean', self.request)
        self.assertIn('zoomEnabled = get("setting_player_zoom_enabled")?.toBooleanStrictOrNull() ?: false', self.request)
        self.assertIn('.putExtra("setting_player_zoom_enabled", zoomEnabled)', self.request)
        self.assertIn('"setting_player_zoom_enabled" to "true"', self.android_request_test)
        self.assertIn('assertTrue(request.zoomEnabled)', self.android_request_test)
        self.assertIn('assertFalse(request.zoomEnabled)', self.android_request_test)

    def test_manual_zoom_is_a_single_fit_based_transform(self):
        pinch = self.player[
            self.player.index("override fun onScaleBegin"):
            self.player.index("override fun onScaleEnd")
        ]
        self.assertIn("zoomEnabled", pinch)
        self.assertIn("effectiveRawFactor", pinch)
        self.assertIn("MIN_SCALE_FACTOR", pinch)
        self.assertIn("MAX_SCALE_FACTOR", pinch)
        self.assertIn("AspectRatioFrameLayout.RESIZE_MODE_FIT", pinch)
        self.assertNotIn("AspectRatioFrameLayout.RESIZE_MODE_ZOOM", pinch)
        self.assertNotIn("zoomScale = 1.15f", self.player)
        self.assertNotIn("enterManualZoomMode", self.player)
        self.assertIn("private const val MAX_ZOOM = 2f", self.player)

    def test_fill_remains_an_aspect_mode_and_is_not_combined_with_manual_zoom(self):
        self.assertIn('arrayOf("Ajustar", "Preencher")', self.player)
        aspect = self.player[
            self.player.index("private fun applyAspectMode"):
            self.player.index("private fun captureTrackFormatSummaries")
        ]
        self.assertIn('"Preencher" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM', aspect)
        self.assertNotIn('"Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM', aspect)
        self.assertNotIn("enterManualZoomMode", aspect)

    def test_pan_uses_fit_geometry_only_and_is_clamped(self):
        start = self.player.index("private fun calculatePanBounds")
        end = self.player.index("private fun updateAspectButtonFromZoom", start)
        block = self.player[start:end]
        self.assertIn("fun panBounds(", self.player)
        self.assertIn("displayedWidth = baseWidth * zoomScale", block)
        self.assertIn("contentWidth * fitScale", block)
        self.assertNotIn("zoomedScale", block)
        self.assertIn("clampTranslation", self.player)

    def test_reset_clears_scale_and_translation_and_restores_aspect(self):
        reset = self.player[
            self.player.index("fun resetZoomToFit"):
            self.player.index("private fun finishPinchGesture", self.player.index("fun resetZoomToFit"))
        ]
        self.assertIn("zoomScale = 1f", reset)
        self.assertIn("zoomTranslationX = 0f", reset)
        self.assertIn("zoomTranslationY = 0f", reset)
        self.assertIn('video.setTransform(Matrix())', reset)
        animation = self.player[
            self.player.index("private fun animateZoomToFit"):
            self.player.index("private fun handleTap", self.player.index("private fun animateZoomToFit"))
        ]
        self.assertIn("restoreConfiguredAspectMode()", animation)

    def test_episode_reuse_resets_zoom_state(self):
        reuse = self.player[
            self.player.index("override fun onNewIntent"):
            self.player.index("private fun currentEpisodeId")
        ]
        self.assertIn('resetZoomToFit()', reuse)
        self.assertIn('resizeModeFromSetting(newIntent.getStringExtra("setting_player_aspect_ratio"))', reuse)

    def test_gesture_conflicts_are_cancelled_and_non_zoom_gestures_remain_separate(self):
        self.assertIn("restoreLongPressSpeed()", self.player)
        self.assertIn("cancelGestureDetector()", self.player)
        self.assertIn("GestureMode.DOUBLE_TAP", self.player)
        self.assertIn("GestureMode.VERTICAL", self.player)
        self.assertIn("seekBy(-doubleTapSeekMs", self.player)
        self.assertIn("seekBy(doubleTapSeekMs", self.player)
        self.assertNotIn("doubleTap.*zoom", self.player)

    def test_zoom_path_has_no_python_mailbox_or_storage_work(self):
        gesture = self.player[
            self.player.index("private inner class GestureLayer"):
            self.player.index("internal object PlayerGesturePolicy")
        ]
        for token in ("NativeMailbox", "SQLiteDatabase", "ContentResolver", "FileOutputStream"):
            self.assertNotIn(token, gesture)

    def test_transform_matrix_is_reused(self):
        self.assertIn("private val zoomMatrix = Matrix()", self.player)
        self.assertIn("zoomMatrix.reset()", self.player)
        self.assertIn("video.setTransform(zoomMatrix)", self.player)

    def test_required_zoom_bounds_and_fallback_contracts_exist(self):
        for token in (
            "private const val MIN_ZOOM = 1f",
            "private const val MAX_ZOOM = 2f",
            "private const val MIN_SCALE_FACTOR = 0.90f",
            "private const val MAX_SCALE_FACTOR = 1.10f",
            "private const val ZOOM_FEEDBACK_INTERVAL_MS = 80L",
            "resetZoomToFit",
            "restoreConfiguredAspectMode",
            "zoomEnabled",
        ):
            self.assertIn(token, self.player)


if __name__ == "__main__":
    unittest.main()
