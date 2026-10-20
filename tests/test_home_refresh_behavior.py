import ast
import unittest
from pathlib import Path

from views.home_view import HOME_PULL_REFRESH_THRESHOLD, _pull_refresh_should_trigger
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]


class HomeRefreshBehaviorTests(unittest.TestCase):
    def test_pull_below_threshold_does_not_trigger(self):
        self.assertFalse(
            _pull_refresh_should_trigger(
                gesture_active=True,
                at_top=True,
                overscroll=HOME_PULL_REFRESH_THRESHOLD - 1,
            )
        )

    def test_pull_at_or_above_threshold_triggers(self):
        for distance in (HOME_PULL_REFRESH_THRESHOLD, HOME_PULL_REFRESH_THRESHOLD + 24):
            with self.subTest(distance=distance):
                self.assertTrue(
                    _pull_refresh_should_trigger(
                        gesture_active=True,
                        at_top=True,
                        overscroll=distance,
                    )
                )

    def test_pull_outside_top_does_not_trigger(self):
        self.assertFalse(
            _pull_refresh_should_trigger(
                gesture_active=True,
                at_top=False,
                overscroll=HOME_PULL_REFRESH_THRESHOLD + 20,
            )
        )

    def test_pull_is_blocked_while_home_is_loading_or_refreshing(self):
        self.assertFalse(
            _pull_refresh_should_trigger(
                gesture_active=True,
                at_top=True,
                overscroll=HOME_PULL_REFRESH_THRESHOLD + 20,
                page_loading=True,
            )
        )
        self.assertFalse(
            _pull_refresh_should_trigger(
                gesture_active=True,
                at_top=True,
                overscroll=HOME_PULL_REFRESH_THRESHOLD + 20,
                refresh_state="REFRESHING",
            )
        )

    def test_pull_requires_an_active_gesture(self):
        self.assertFalse(
            _pull_refresh_should_trigger(
                gesture_active=False,
                at_top=True,
                overscroll=HOME_PULL_REFRESH_THRESHOLD + 20,
            )
        )

    def test_home_uses_one_refresh_callback_for_button_and_pull(self):
        source = (ROOT / "views/home_view.py").read_text(encoding="utf-8")
        # There is one shared handler; both the button and pull gesture route
        # through it. A duplicate intent while the state is REFRESHING is rejected
        # locally instead of dispatching a second refresh request.
        self.assertEqual(source.count("async def handle_manual_refresh("), 1)
        self.assertIn('await on_refresh_library(source=source)', source)
        self.assertIn('await handle_manual_refresh(None, source="pull")', source)
        self.assertIn("on_click=handle_manual_refresh", source)
        self.assertNotIn("async def pull_refresh_library", source)
        self.assertNotIn("async def refresh_pull_library", source)

    def test_refresh_pipeline_stays_on_native_scan_controller(self):
        source = native_main_source()
        self.assertIn("object NativeScanController", source)
        self.assertIn("object NativeScanRunner", source)
        self.assertIn("fun begin(scanId: String", source)
        self.assertIn("fun updateProgress(scanId: String", source)
        self.assertIn("fun complete(scanId: String", source)
        self.assertIn("NativeIndex", source)
    def test_refresh_state_is_projected_from_native_scan_state(self):
        source = native_main_source()
        self.assertIn("val isRefreshing: StateFlow<Boolean>", source)
        self.assertIn("scanInProgress", source)
        self.assertIn("ReiAnixComposeLibraryHost", source)
        self.assertIn("collectAsStateWithLifecycle", source)
    def test_retained_home_source_remains_valid_python(self):
        source = (ROOT / "views/home_view.py").read_text(encoding="utf-8")
        ast.parse(source, filename="views/home_view.py")

if __name__ == "__main__":
    unittest.main()
