import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SYSTEM_UI = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
PLAYER_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN_PY = ROOT / "main.py"
HOME = ROOT / "views/home_view.py"
GRADLE = ROOT / "android/app/build.gradle.kts"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
INSTRUMENTED_WORKFLOW = ROOT / ".github/workflows/android_instrumented.yml"
INSTRUMENTED_SCRIPT = ROOT / "scripts/run_android_instrumented_diagnostic.sh"


class DeviceCompatibilityContractTests(unittest.TestCase):
    def test_android_sdk_contract_is_explicit_and_stable(self):
        source = GRADLE.read_text(encoding="utf-8")
        self.assertIn("compileSdk = 36", source)
        self.assertIn("minSdk = 24", source)
        self.assertIn("targetSdk = 36", source)

    def test_android15_16_edge_to_edge_has_single_native_authority(self):
        source = SYSTEM_UI.read_text(encoding="utf-8")
        self.assertEqual(source.count("class SystemUiController"), 1)
        self.assertIn("WindowCompat.setDecorFitsSystemWindows(window, false)", source)
        self.assertIn("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS", source)
        application_start = source.index("fun applyApplicationImmersivePolicy(")
        application_end = source.index("    /** Backward-compatible normal policy", application_start)
        application = source[application_start:application_end]
        normal = source[source.index("fun applyNormal("):source.index("private fun applyEdgeToEdgeWindow")]
        self.assertIn("applyImmersive(useContextAppearance)", application)
        self.assertIn("hide(WindowInsetsCompat.Type.systemBars())", source)
        self.assertIn("show(WindowInsetsCompat.Type.systemBars())", normal)
        self.assertIn("UI_MODE_NIGHT_MASK", source)
        self.assertIn("isAppearanceLightStatusBars = !darkTheme", source)
        self.assertIn("isAppearanceLightNavigationBars = !darkTheme", source)

    def test_flet_primary_views_use_safe_area_without_creating_second_system_ui(self):
        source = MAIN_PY.read_text(encoding="utf-8")
        self.assertIn("ft.SafeArea(", source)
        shell_start = source.index("def _view_shell")
        shell_end = source.index("def _invalidate_cached_view", shell_start)
        shell = source[shell_start:shell_end]
        self.assertIn("ft.SafeArea(", shell)
        self.assertIn("controls=[safe_area]", shell)
        self.assertIn("content=control", shell)
        self.assertNotIn("padding=ft.Padding(top=", shell)

    def test_player_uses_dynamic_cutout_insets_and_restores_normal_bars(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())", source)
        self.assertIn("getInsetsIgnoringVisibility(", source)
        self.assertIn("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS", SYSTEM_UI.read_text(encoding="utf-8"))
        self.assertIn("systemUiController = SystemUiController(window)", source)
        self.assertIn("ViewCompat.setOnApplyWindowInsetsListener(root)", source)
        exit_start = source.index("private fun restoreSystemUiBeforeExit")
        exit_end = source.index("private fun applyImmersiveAfterLayout", exit_start)
        exit_policy = source[exit_start:exit_end]
        self.assertIn("systemUiController.applyNormal(useContextAppearance = false)", exit_policy)
        immersive_start = source.index("private fun enterImmersiveMode")
        immersive_end = source.index("private fun restoreSystemUiBeforeExit", immersive_start)
        immersive = source[immersive_start:immersive_end]
        self.assertIn("systemUiController.applyImmersive()", immersive)

    def test_android16_large_screen_does_not_introduce_an_opt_out_hack(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertNotIn("PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY", manifest)
        self.assertNotIn("windowOptOutEdgeToEdgeEnforcement", manifest)
        workflow = INSTRUMENTED_WORKFLOW.read_text(encoding="utf-8")
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("SCREEN_ORIENTATION_FULL_SENSOR", player)
        self.assertIn("requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR", player)

    def test_home_filter_dialog_uses_logical_window_width(self):
        source = HOME.read_text(encoding="utf-8")
        start = source.index("def open_filters")
        end = source.index("async def toggle_search", start)
        block = source[start:end]
        self.assertIn("page.width", block)
        self.assertIn("dialog_width = min(470.0", block)
        self.assertIn("field_width = min(220.0", block)
        self.assertIn("wrap=True", block)
        self.assertNotIn("width=470)", block)

    def test_certification_has_real_emulator_matrix(self):
        workflow = INSTRUMENTED_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("ReiAnix Android Instrumented Runtime Matrix", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("name: Android API 36", workflow)
        self.assertIn("api-level: 36", workflow)
        self.assertIn("reactivecircus/android-emulator-runner@v2", workflow)
        self.assertIn("connectedDebugAndroidTest", workflow)
        self.assertNotIn("push:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("matrix:", workflow)
        self.assertNotIn("api: [30, 36]", workflow)
        self.assertNotIn("Android TV API 36", workflow)
        self.assertNotIn("ReiAnix Android No-Emulator Contract Checks", workflow)

    def test_predictive_back_uses_androidx_dispatcher_without_fake_gesture_implementation(self):
        main = MAIN_ACTIVITY.read_text(encoding="utf-8")
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertIn("onBackPressedDispatcher.addCallback", main)
        self.assertIn("engine.navigationChannel.popRoute()", main)
        self.assertIn('android:enableOnBackInvokedCallback="true"', manifest)
        self.assertNotIn("OnBackInvokedDispatcher.registerOnBackInvokedCallback", main)

    def test_player_gesture_boundary_uses_dynamic_mandatory_gesture_insets(self):
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("WindowInsetsCompat.Type.mandatorySystemGestures()", player)
        self.assertIn("isSystemGestureEdge", player)
        self.assertIn("PlayerGesturePolicy.Direction.HORIZONTAL", player)
        self.assertIn("GESTURE_HORIZONTAL_IGNORED", player)
        self.assertNotIn("type=horizontal_seek", player)
        self.assertIn("ACTION_POINTER_DOWN", player)
        self.assertIn("ACTION_POINTER_UP", player)
        self.assertIn("ACTION_CANCEL", player)


if __name__ == "__main__":
    unittest.main()
