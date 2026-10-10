import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
LAYOUT = ROOT / "android/app/src/main/res/layout/native_player_view.xml"
STYLES = ROOT / "android/app/src/main/res/values/styles.xml"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"


class PlayerReconstructionTests(unittest.TestCase):
    def setUp(self):
        self.player = PLAYER.read_text(encoding="utf-8")
        self.layout = LAYOUT.read_text(encoding="utf-8")
        self.styles = STYLES.read_text(encoding="utf-8")
        self.manifest = MANIFEST.read_text(encoding="utf-8")

    def test_responsive_player_uses_native_layout_and_weighted_controls(self):
        self.assertIn("R.layout.native_player_view", self.player)
        self.assertIn("FrameLayout.LayoutParams.MATCH_PARENT", self.player)
        self.assertIn("LinearLayout.LayoutParams(0, dp(48), 1f)", self.player)
        self.assertIn("LinearLayout.LayoutParams(0, dp(40), 1f)", self.player)
        self.assertIn('app:surface_type="texture_view"', self.layout)
        self.assertNotIn("x = 500", self.player)
        self.assertNotIn("y = 1100", self.player)

    def test_auto_hide_has_one_shared_handler_runnable(self):
        self.assertIn("private val controlsHider = object : Runnable", self.player)
        self.assertIn("handler.removeCallbacks(controlsHider)", self.player)
        self.assertIn("handler.postDelayed(controlsHider", self.player)
        self.assertIn("autoHideTimeoutMs", self.player)

    def test_single_double_and_pinch_gesture_arbitration(self):
        for token in (
            "ScaleGestureDetector",
            "GestureDetector",
            "PLAYER_SINGLE_TAP",
            "PLAYER_DOUBLE_TAP",
            "gestureConsumed",
            "pinchActive",
            "GESTURE_HORIZONTAL_IGNORED",
            "vertical_ignored_or_applied",
        ):
            self.assertIn(token, self.player)
        self.assertNotIn("HORIZONTAL_SEEK", self.player)
        self.assertNotIn("GestureMode.HORIZONTAL_SEEK", self.player)
        self.assertIn("GESTURE_HORIZONTAL_IGNORED", self.player)

    def test_double_tap_seek_uses_canonical_intent_settings_and_configured_delta(self):
        self.assertIn("private var doubleTapEnabled = true", self.player)
        self.assertIn('intent.getBooleanExtra("setting_gestures_double_tap", false)', self.player)
        self.assertIn('persistCanonicalPlayerSetting("gestures.double_tap"', self.player)
        self.assertIn("doubleTapSeekMs", self.player)
        self.assertIn("seekBy(-doubleTapSeekMs", self.player)
        self.assertIn("seekBy(doubleTapSeekMs", self.player)
        self.assertIn("showFeedback(feedbackText)", self.player)
        self.assertNotIn("gesturePreferences", self.player)

    def test_zoom_is_bounded_symmetric_and_pan_is_clamped(self):
        self.assertIn("private const val MAX_ZOOM = 2f", self.player)
        self.assertIn("scaleX = zoomScale", self.player)
        self.assertIn("scaleY = zoomScale", self.player)
        self.assertIn("PlayerGesturePolicy.clampZoom", self.player)
        self.assertIn("PlayerGesturePolicy.clampTranslation", self.player)
        self.assertIn("val bounds = calculatePanBounds()", self.player)
        self.assertIn("resetZoomToFit", self.player)
        self.assertIn("MIN_SCALE_FACTOR = 0.90f", self.player)
        self.assertIn("MAX_SCALE_FACTOR = 1.10f", self.player)
        self.assertIn("zoomMatrix", self.player)

    def test_resize_modes_keep_manual_zoom_separate_from_aspect_mode(self):
        self.assertIn('arrayOf("Ajustar", "Preencher")', self.player)
        self.assertIn('"Preencher" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM', self.player)
        self.assertIn('else -> AspectRatioFrameLayout.RESIZE_MODE_FIT', self.player)
        self.assertIn("zoomEnabled", self.player)
        self.assertNotIn("enterManualZoomMode()", self.player)
        pinch = self.player[self.player.index("override fun onScaleBegin"):self.player.index("override fun onScaleEnd")]
        self.assertIn("AspectRatioFrameLayout.RESIZE_MODE_FIT", pinch)
        self.assertNotIn("AspectRatioFrameLayout.RESIZE_MODE_ZOOM", pinch)
        self.assertNotIn("scaleX != scaleY", self.player)

    def test_speed_audio_subtitle_and_seekbar_reflect_real_player(self):
        self.assertIn('actionButton("1.0x"', self.player)
        self.assertIn("onPlaybackParametersChanged", self.player)
        self.assertIn('findViewByTag<TextView>("reiflix_speed_button")', self.player)
        self.assertIn("val audioAvailable = audioCount > 0", self.player)
        self.assertIn("TrackSelectionOverride(", self.player)
        self.assertNotIn("TrackSelectionDialogBuilder", self.player)
        self.assertIn("SEEK_PROGRESS_MAX", self.player)
        self.assertIn("PROGRESS_INTERVAL_MS = 250L", self.player)
        self.assertIn("PROGRESS_PERSIST_INTERVAL_MS = 15_000L", self.player)

    def test_track_dialog_uses_platform_alert_dialog_compatible_with_player_theme(self):
        track_selection = self.player[
            self.player.index("private fun showTrackSelection"):
            self.player.index("private fun seekBy", self.player.index("private fun showTrackSelection"))
        ]
        self.assertIn("import android.app.AlertDialog", self.player)
        self.assertIn("AlertDialog.Builder(this)", track_selection)
        self.assertNotIn("androidx.appcompat.app.AlertDialog", self.player)
        self.assertIn('android:name=".NativePlayerActivity"', self.manifest)
        self.assertIn('android:theme="@style/ReiAnixPlayerTheme"', self.manifest)
        self.assertIn(
            'style name="ReiAnixPlayerTheme" parent="android:style/Theme.Material.NoActionBar"',
            self.styles,
        )

    def test_buffering_error_and_first_frame_are_separate_ui_states(self):
        self.assertIn("Player.STATE_BUFFERING", self.player)
        self.assertIn("preparingIndicator.visibility = View.VISIBLE", self.player)
        self.assertIn("Player.EVENT_RENDERED_FIRST_FRAME", self.player)
        self.assertIn("preparingIndicator.visibility = View.GONE", self.player)
        self.assertIn("player_error", self.player)
        self.assertIn("showPlayerError(", self.player)

    def test_native_media3_player_is_not_recreated_for_controls(self):
        self.assertIn("ExoPlayer.Builder(this).build()", self.player)
        self.assertIn("playerView.player = player", self.player)
        self.assertIn("player.setPlaybackSpeed", self.player)
        self.assertIn("player.trackSelectionParameters", self.player)
        self.assertNotIn("ExoPlayer.Builder(this).build()", self.player[self.player.index("private fun showSpeedSelection"):])
        self.assertNotIn("ExoPlayer.Builder(this).build()", self.player[self.player.index("private fun showTrackSelection"):])

    def test_lifecycle_keeps_rotation_and_pip_in_same_activity(self):
        for token in (
            "onConfigurationChanged",
            "onPictureInPictureModeChanged",
            "configurePictureInPicture",
            "restoreSystemUiBeforeExit",
            "enterImmersiveMode",
            "onBackPressedDispatcher",
            "onNewIntent",
        ):
            self.assertIn(token, self.player)

    def test_disabled_vertical_gestures_are_silent(self):
        gesture_layer = self.player[self.player.index("private inner class GestureLayer"):]
        self.assertNotIn("Gesto de volume desligado", gesture_layer)
        self.assertNotIn("Gesto de brilho desligado", gesture_layer)
        self.assertIn("if (brightnessGesturesEnabled)", gesture_layer)
        self.assertIn("if (volumeGesturesEnabled)", gesture_layer)

    def test_touch_targets_and_accessibility_contract(self):
        self.assertIn("minHeight = dp(44)", self.player)
        for token in (
            'contentDescription = "Voltar"',
            'contentDescription = "Reproduzir ou pausar"',
            'contentDescription = "Barra de progresso"',
            'contentDescription = "Mais opções"',
            'contentDescription = "Picture in Picture"',
        ):
            self.assertIn(token, self.player)


if __name__ == "__main__":
    unittest.main()

def test_invalid_reuse_intent_clears_previous_episode_timeout_before_validation():
    source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
    start = source.index("override fun onNewIntent")
    validation = source.index('val rawUri = newIntent.getStringExtra("uri")', start)
    preflight = source[start:validation]
    assert "handler.removeCallbacks(episodeChangeTimeout)" in preflight
    assert 'episodeChangeTimeoutRequestId = ""' in preflight
    assert 'episodeChangeTimeoutUri = ""' in preflight
