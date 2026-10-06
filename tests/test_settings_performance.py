import unittest
from pathlib import Path

from core.settings import SettingsStore


ROOT = Path(__file__).resolve().parents[1]


class _Store:
    def __init__(self):
        self.values = {}
        self.reads = {}
        self.writes = 0

    def get_preference(self, key, default=None):
        self.reads[key] = self.reads.get(key, 0) + 1
        return self.values.get(key, default)

    def set_preference(self, key, value):
        self.writes += 1
        self.values[key] = value


class SettingsPerformanceTests(unittest.TestCase):
    def test_settings_get_caches_repeated_reads(self):
        store = _Store()
        settings = SettingsStore(store)
        store.reads.clear()

        values = [settings.get("player.resume") for _ in range(20)]

        self.assertTrue(all(values))
        self.assertEqual(1, store.reads.get("player.resume"))
        self.assertTrue(settings.get("player.resume"))

    def test_settings_cache_updates_on_set_and_invalidates_explicitly(self):
        store = _Store()
        settings = SettingsStore(store)
        store.reads.clear()

        settings.set("player.resume", False)
        self.assertFalse(settings.get("player.resume"))
        self.assertEqual(0, store.reads.get("player.resume", 0))

        settings.invalidate_cache("player.resume")
        self.assertFalse(settings.get("player.resume"))
        self.assertEqual(1, store.reads.get("player.resume"))

    def test_settings_reset_refreshes_cached_value(self):
        store = _Store()
        settings = SettingsStore(store)
        settings.set("player.resume", False)
        self.assertFalse(settings.get("player.resume"))

        settings.reset("player.resume")
        self.assertTrue(settings.get("player.resume"))
        self.assertEqual(0, store.reads.get("player.resume", 0))



class SettingsLazyConstructionTests(unittest.TestCase):
    def test_root_entry_does_not_eagerly_materialize_all_sections(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertIn("materialize_all = active_category is None and bool(query_text)", source)
        self.assertIn("if active_category is None and not query:", source)
        self.assertIn("section_cache = []", source)
        self.assertIn("def get_folders():", source)
        self.assertIn("def get_summary():", source)
        self.assertIn("def get_last_scan():", source)
        self.assertIn("def get_database_check():", source)

    def test_sqlite_health_check_is_only_materialized_for_diagnostics(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        diagnostic = source[source.index('if should_materialize_section("Diagnóstico"):'):]
        self.assertIn("get_database_check()", diagnostic)
        self.assertIn("def get_database_check()", source)


    def test_focus_guard_remains_intact(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertIn("SettingsFocusState", source)
        self.assertIn("handle_category_focus", source)
        self.assertNotIn("page.run_task(restore_scroll)", source)
        self.assertNotIn('scroll_to(offset=float(stored)', source)


if __name__ == "__main__":
    unittest.main()
