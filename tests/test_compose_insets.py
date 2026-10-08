import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"


class ComposeInsetsContractTests(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_navigation_shell_uses_safe_drawing_and_consumes_scaffold_insets(self):
        shell = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/shell/ReiAnixAppShell.kt"
        )
        navigation = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
        )
        # Scaffold and safe-drawing ownership live in the shared App Shell.
        self.assertIn("Scaffold(", shell)
        self.assertIn("import androidx.compose.foundation.layout.safeDrawing", shell)
        self.assertIn("contentWindowInsets = WindowInsets.safeDrawing", shell)
        # Navigation consumes the shell-provided scaffold insets.
        self.assertIn(".padding(innerPadding)", navigation)
        self.assertIn(".consumeWindowInsets(innerPadding)", navigation)

    def test_scrollable_top_level_screens_use_remaining_column_height(self):
        home = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
        )
        library = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"
        )
        details = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
        )
        self.assertIn("private fun ColumnScope.HomeObservedContent(", home)
        self.assertIn("private fun ColumnScope.HomeContent(", home)
        self.assertIn(".weight(1f)", home)
        self.assertIn("private fun ColumnScope.LibraryReadyContent(", library)
        self.assertIn(".weight(1f)", library)
        self.assertIn("private fun ColumnScope.ReiAnixDetailsReady(", details)
        self.assertIn(".weight(1f)", details)
        storage = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt"
        )
        self.assertIn(".weight(1f)", storage)

    def test_library_and_search_do_not_guess_a_56dp_system_navigation_height(self):
        library = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"
        )
        search = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
        )
        self.assertNotIn("Spacing.huge + 56.dp", library)
        self.assertNotIn("Spacing.huge + 56.dp", search)

    def test_search_uses_ime_padding_while_library_does_not(self):
        library = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"
        )
        search = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
        )
        # Search keeps IME protection; Library deliberately relies on the
        # surrounding layout/insets contract and must not reintroduce it.
        self.assertIn(".imePadding()", search)
        self.assertNotIn(".imePadding()", library)

    def test_main_activity_keeps_adjust_resize_for_legacy_android_ime_behavior(self):
        manifest = self.read("android/app/src/main/AndroidManifest.xml")
        self.assertIn('android:windowSoftInputMode="adjustResize"', manifest)

    def test_native_player_insets_and_lifecycle_policy_remain_intact(self):
        player = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
        )
        system_ui = self.read(
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt"
        )
        self.assertIn("private fun applyRootInsets(insets: WindowInsetsCompat)", player)
        self.assertIn("WindowInsetsCompat.Type.systemBars()", player)
        self.assertIn("WindowInsetsCompat.Type.displayCutout()", player)
        self.assertIn("WindowInsetsCompat.Type.mandatorySystemGestures()", player)
        self.assertIn("private fun restoreSystemUiBeforeExit()", player)
        self.assertIn("private fun applyImmersiveAfterLayout()", player)
        self.assertIn("WindowCompat.setDecorFitsSystemWindows(window, false)", system_ui)
        self.assertIn("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS", system_ui)


if __name__ == "__main__":
    unittest.main()
