import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class PullRefreshContractTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_pull_to_refresh_uses_the_existing_manual_refresh_handler(self):
        source = self.read("views/home_view.py")
        self.assertIn('async def handle_manual_refresh(_event=None, *, source="button")', source)
        self.assertIn('await handle_manual_refresh(None, source="pull")', source)
        self.assertNotIn("async def pull_refresh_library", source)
        self.assertNotIn("async def refresh_pull_library", source)

    def test_pull_refresh_uses_documented_user_idle_and_overscroll_notifications(self):
        source = self.read("views/home_view.py")
        self.assertIn('event_type == "USER"', source)
        self.assertIn('direction == "IDLE"', source)
        self.assertIn('event_type == "OVERSCROLL"', source)
        self.assertIn('getattr(event, "overscroll"', source)
        self.assertIn("pull_gesture_at_top", source)
        self.assertIn("extent_before <= 1.0", source)
        self.assertIn("pull_overscroll[0] >= pull_threshold", source)
        for marker in (
            "PULL_GESTURE_START",
            "PULL_OVERSCROLL",
            "PULL_THRESHOLD_REACHED",
            "PULL_REFRESH_TRIGGERED",
            "PULL_REFRESH_COMPLETED",
            "PULL_REFRESH_CANCELLED",
            "PULL_REFRESH_REJECTED",
        ):
            self.assertIn(marker, source)
        self.assertNotIn('event_type == "START"', source)
        self.assertNotIn('event_type == "END"', source)

    def test_pull_refresh_does_not_fire_from_normal_scroll_or_horizontal_rows(self):
        source = self.read("views/home_view.py")
        self.assertIn("ft.Row(scroll=ft.ScrollMode.AUTO", source)
        scroll = source[source.index("def on_home_scroll"):source.index("def card(", source.index("def on_home_scroll"))]
        self.assertIn('if event_type == "USER":', scroll)
        self.assertIn('elif event_type == "OVERSCROLL":', scroll)
        self.assertIn('elif event_type == "UPDATE":', scroll)
        self.assertIn("pull_gesture_at_top[0] = False", scroll)
        self.assertIn("extent_before <= 1.0", scroll)
        self.assertIn("_scroll_direction_name", scroll)

    def test_pull_refresh_reuses_stage31_concurrency_state(self):
        source = self.read("views/home_view.py")
        self.assertIn('if refresh_state[0] == "REFRESHING":', source)
        self.assertIn('performance.counter("home.pull_refresh.rejected")', source)
        self.assertIn('set_refresh_state("REFRESHING", update=False)', source)
        self.assertIn('refresh_button[0]', source)

    def test_pull_indicator_has_no_second_data_refresh_pipeline(self):
        source = self.read("views/home_view.py")
        self.assertIn("pull_refresh_indicator[0]", source)
        self.assertIn('ft.ProgressRing(width=16, height=16', source)
        self.assertIn('visible=False', source)
        for forbidden in ("scan_all_storage", "scan_media_store", "rescan_tree", "library.ingest_documents"):
            self.assertNotIn(forbidden, source)

    def test_pull_refresh_resets_visual_state_after_terminal_result(self):
        source = self.read("views/home_view.py")
        start = source.index("def set_refresh_state")
        end = source.index("async def handle_manual_refresh", start)
        block = source[start:end]
        self.assertIn('if normalized in {"IDLE", "SUCCESS", "ERROR"}:', block)
        self.assertIn("pull_refresh_active[0] = False", block)
        self.assertIn('normalized == "REFRESHING"', block)

    def test_button_contract_remains_intact(self):
        source = self.read("views/home_view.py")
        self.assertIn('tooltip="Atualizar biblioteca"', source)
        self.assertIn('on_click=handle_manual_refresh', source)
        self.assertIn('button.icon = ft.Icons.SYNC', source)
        self.assertIn('button.disabled = True', source)

    def test_pull_refresh_lifecycle_uses_existing_view_task_registry(self):
        source = self.read("views/home_view.py")
        pull_start = source[source.index("async def _trigger_pull_refresh"):source.index("def on_home_scroll")]
        self.assertIn("_start_view_task", source)
        self.assertIn("_trigger_pull_refresh", pull_start)
        self.assertIn("_scroll_direction_name", source)
        self.assertIn("cancel_view_tasks", source)
        self.assertIn("view_tasks", source)

    def test_refresh_does_not_touch_player_or_playback_code(self):
        source = self.read("views/home_view.py")
        self.assertNotIn("NativePlayerActivity", source)
        self.assertNotIn("Media3", source)
        self.assertNotIn("player_session_id", source)

    def test_flet_version_remains_pinned(self):
        pyproject = self.read("pyproject.toml")
        requirements = self.read("requirements.txt")
        self.assertIn('flet==0.86.5', pyproject)
        self.assertIn('flet==0.86.5', requirements)

    def test_source_is_valid_python(self):
        for path in ("views/home_view.py",):
            ast.parse(self.read(path), filename=path)


if __name__ == "__main__":
    unittest.main()
