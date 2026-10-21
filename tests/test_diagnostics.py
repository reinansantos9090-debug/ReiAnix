import ast
import unittest

from core.diagnostics import DiagnosticTimeline
from tests.native_main_contract import native_main_source


class DiagnosticTimelineTests(unittest.TestCase):
    def test_home_refresh_phase_uses_supported_record_fields(self):
        timeline = DiagnosticTimeline()
        event = timeline.record(
            "HOME_REFRESH_PHASE_CHANGED",
            request_id="scan-1",
            source="scan_started",
            result="RUNNING",
            refresh_id="refresh-1",
            previous_phase="REQUESTED",
            phase="RUNNING",
            transition_reason="scan_started",
        )

        self.assertEqual("HOME_REFRESH_PHASE_CHANGED", event.name)
        self.assertEqual("scan-1", event.request_id)
        self.assertEqual("scan_started", event.source)
        self.assertEqual("RUNNING", event.result)
        self.assertEqual("refresh-1", event.refresh_id)
        self.assertEqual("REQUESTED", event.extra["previous_phase"])
        self.assertNotIn("refresh_id", event.extra)
        self.assertEqual("RUNNING", event.extra["phase"])
        self.assertEqual("scan_started", event.extra["transition_reason"])

    def test_record_preserves_existing_fields_and_numeric_extra_data(self):
        timeline = DiagnosticTimeline()
        event = timeline.record(
            "HOME_REFRESH_SCAN_COMPLETED",
            request_id="scan-1",
            source="button",
            result="COMPLETED",
            refresh_id="refresh-1",
            duration_ms=123,
        )

        self.assertEqual("scan-1", event.request_id)
        self.assertEqual("button", event.source)
        self.assertEqual("COMPLETED", event.result)
        self.assertEqual("refresh-1", event.refresh_id)
        self.assertEqual({"duration_ms": 123}, event.extra)

    def test_extra_values_are_json_safe(self):
        timeline = DiagnosticTimeline()
        event = timeline.record(
            "EXTRA_DATA",
            nested={"items": [1, object()]},
        )

        self.assertEqual(1, event.extra["nested"]["items"][0])
        self.assertIsInstance(event.extra["nested"]["items"][1], str)


    def test_native_entrypoint_owns_bootstrap_and_diagnostics(self):
        source = native_main_source()
        self.assertIn("internal object MainEntryPoint", source)
        self.assertIn("fun onActivityCreated(activity: ComponentActivity)", source)
        self.assertIn("dependencies.bootstrap.start()", source)
        self.assertIn("ReiAnixDiagnosticTimeline.record(", source)
        self.assertIn("ReiAnixAppDependencies", source)


if __name__ == "__main__":
    unittest.main()
