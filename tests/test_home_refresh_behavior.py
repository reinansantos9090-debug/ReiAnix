import ast
import unittest
from pathlib import Path

from views.home_view import HOME_PULL_REFRESH_THRESHOLD, _pull_refresh_should_trigger


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

    def test_refresh_pipeline_stays_on_scan_coordinator(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        block = source[source.index("async def request_home_refresh"):source.index("async def refresh_home_library", source.index("async def request_home_refresh"))]
        self.assertIn("await refresh_library(_home_refresh_context=home_refresh_context)", block)
        self.assertIn("ScanOrigin.USER_REFRESH", source)
        self.assertNotIn("bridge.scan_all_storage()", block)
        self.assertNotIn("bridge.scan_media_store()", block)
        self.assertNotIn("bridge.rescan_tree(", block)
    def test_refresh_state_has_navigation_deferred_completion_guard(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn('home_state["_manual_refresh_pending"] = True', source)
        self.assertIn('elif navigation.current != "home":', source)
        self.assertIn("durable catalog update", source)
        self.assertIn('home_state["_manual_refresh_pending"] = True', source)

    def test_sources_remain_valid_python(self):
        for relative in ("main.py", "views/home_view.py"):
            source = (ROOT / relative).read_text(encoding="utf-8")
            ast.parse(source, filename=relative)


if __name__ == "__main__":
    unittest.main()
