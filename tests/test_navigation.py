import unittest
from pathlib import Path

from core.navigation import NavigationController, SafSelectionState


class NavigationControllerTests(unittest.TestCase):
    def setUp(self):
        self.now = [100.0]
        self.navigation = NavigationController(clock=lambda: self.now[0])

    def test_home_is_root_and_first_back_requests_exit_confirmation(self):
        self.assertEqual(self.navigation.current, "home")
        self.assertEqual(self.navigation.back(), "exit_requested")
        self.assertEqual(self.navigation.current, "home")

    def test_second_home_back_inside_window_exits(self):
        self.navigation.back()
        self.now[0] += 1.9
        self.assertEqual(self.navigation.back(), "exit")

    def test_home_back_after_window_requests_exit_confirmation(self):
        self.navigation.back()
        self.now[0] += 2.1
        self.assertEqual(self.navigation.back(), "exit_requested")

    def test_library_details_back_returns_to_library(self):
        self.navigation.push("library")
        self.navigation.push("details")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "library")

    def test_search_details_back_returns_to_search(self):
        self.navigation.push("search")
        self.navigation.push("details")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "search")

    def test_details_back_returns_to_real_origin(self):
        self.navigation.push("details")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "home")

    def test_organize_details_back_returns_to_organize(self):
        self.navigation.push("organize")
        self.navigation.push("details")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "organize")

    def test_settings_back_returns_to_origin(self):
        self.navigation.push("organize")
        self.navigation.push("settings")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "organize")

    def test_settings_nested_back_is_owned_by_the_same_navigation_controller(self):
        self.navigation.push("settings")
        self.navigation.push_settings("Player")
        self.navigation.push_settings("Legenda")

        self.assertEqual(self.navigation.settings_path, ("Player", "Legenda"))
        self.assertEqual(self.navigation.back(), "settings_inner")
        self.assertEqual(self.navigation.current, "settings")
        self.assertEqual(self.navigation.settings_path, ("Player",))
        self.assertEqual(self.navigation.back(), "settings_inner")
        self.assertEqual(self.navigation.settings_path, ())
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "home")

    def test_all_current_settings_categories_return_to_settings_then_home(self):
        categories = (
            "Conta", "Geral", "Aparência", "Biblioteca", "Player", "Gestos",
            "Áudio e Legendas", "Metadata", "Artwork", "Armazenamento",
            "Dados e Cache", "Backup e Restauração", "Privacidade", "Varredura",
            "Diagnóstico", "Sobre",
        )
        for label in categories:
            navigation = NavigationController(clock=lambda: 100.0)
            navigation.push("settings")
            navigation.push_settings(label)
            self.assertEqual(navigation.back(), "settings_inner", label)
            self.assertEqual(navigation.current, "settings", label)
            self.assertEqual(navigation.settings_path, (), label)
            self.assertEqual(navigation.back(), "previous", label)
            self.assertEqual(navigation.current, "home", label)

    def test_switching_top_level_screen_clears_nested_settings_path(self):
        self.navigation.push("settings")
        self.navigation.push_settings("Aparência")
        self.navigation.push("organize")
        self.assertEqual(self.navigation.settings_path, ())
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "settings")

    def test_invalid_top_level_route_cannot_enter_navigation_stack(self):
        with self.assertRaises(ValueError):
            self.navigation.push("not-a-route")

    def test_navigation_snapshot_restores_top_level_and_nested_settings_state(self):
        self.navigation.push("details")
        snapshot = self.navigation.snapshot()

        restored = NavigationController(clock=lambda: self.now[0])
        self.assertTrue(restored.restore(snapshot))
        self.assertEqual(restored.stack, ("home", "details"))
        self.assertEqual(restored.settings_path, ())

        settings_snapshot = {
            "stack": ["home", "settings"],
            "settings_path": ["Player", "Legenda"],
        }
        self.assertTrue(restored.restore(settings_snapshot))
        self.assertEqual(restored.current, "settings")
        self.assertEqual(restored.settings_path, ("Player", "Legenda"))

    def test_invalid_navigation_snapshot_is_rejected_without_mutating_state(self):
        self.navigation.push("details")
        self.assertFalse(
            self.navigation.restore(
                {"stack": ["details"], "settings_path": ["Player"]}
            )
        )
        self.assertEqual(self.navigation.stack, ("home", "details"))
        self.assertEqual(self.navigation.settings_path, ())

    def test_native_player_is_not_a_second_navigation_route(self):
        self.navigation.push("details")
        self.assertEqual(self.navigation.current, "details")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.current, "home")

    def test_main_has_single_logical_back_router_with_duplicate_suppression(self):
        source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
        self.assertIn('def navigate_back(source="unknown")', source)
        self.assertIn("BACK_DEBOUNCE_SECONDS = 0.30", source)
        self.assertIn("duplicate BACK suppressed", source)
        self.assertIn('"[NAV] DIALOG_BACK', source)
        self.assertIn('"[NAV] NAVIGATE_BACK', source)
        self.assertNotIn("settings_system_back", source)
        self.assertIn("def close_home_search():", source)
        self.assertIn('"[NAV] SEARCH_BACK consumed on Home"', source)

    def test_visual_back_callbacks_identify_their_origin_screen(self):
        source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
        self.assertIn('lambda: navigate_back("visual:organize")', source)
        self.assertIn('lambda: navigate_back("visual:details")', source)
        self.assertIn('lambda: navigate_back("visual:settings")', source)


    def test_main_uses_flet_views_as_the_system_navigation_surface(self):
        source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
        self.assertIn("page.views.clear()", source)
        self.assertIn("page.views.extend(views)", source)
        self.assertIn("page.on_view_pop = handle_flet_view_pop", source)
        self.assertIn("NavigationController", source)
        self.assertNotIn("page.clean()", source)

    def test_home_exit_uses_navigation_policy_instead_of_process_exit(self):
        source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
        self.assertNotIn("exitProcess(", source)
        self.assertIn('elif action == "exit":', source)
        self.assertIn("page.window.close()", source)

    def test_main_does_not_route_android_back_through_native_mailbox(self):
        source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
        self.assertNotIn("event_type == 'android_back'", source)
        self.assertNotIn('navigate_back("android_back")', source)

    def test_multiple_back_events_never_underflow_history(self):
        self.navigation.push("organize")
        self.assertEqual(self.navigation.back(), "previous")
        self.assertEqual(self.navigation.back(), "exit_requested")
        self.assertEqual(self.navigation.stack, ("home",))


class SafSelectionStateTests(unittest.TestCase):
    def test_button_is_pending_only_while_android_selection_is_unresolved(self):
        state = SafSelectionState()
        self.assertTrue(state.begin())
        self.assertTrue(state.pending)
        self.assertFalse(state.begin())
        state.finish()  # cancellation
        self.assertFalse(state.pending)

    def test_success_error_and_invalid_result_all_release_selection(self):
        state = SafSelectionState()
        for _result in ("success", "error", None):
            self.assertTrue(state.begin())
            state.finish()
            self.assertFalse(state.pending)
