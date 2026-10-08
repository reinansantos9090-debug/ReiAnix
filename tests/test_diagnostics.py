import ast
import unittest

from core.diagnostics import DiagnosticTimeline


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


    def test_home_refresh_phase_callsite_does_not_use_legacy_keyword_aliases(self):
        from pathlib import Path

        source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
        module = ast.parse(source, filename="main.py")
        function = next(
            node for node in module.body
            if isinstance(node, ast.AsyncFunctionDef) and node.name == "main"
        )
        phase_function = next(
            node for node in ast.walk(function)
            if isinstance(node, ast.FunctionDef) and node.name == "_set_home_refresh_phase"
        )
        record_calls = [
            node for node in ast.walk(phase_function)
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "diagnostics"
                and node.func.attr == "record"
            )
        ]

        self.assertEqual(1, len(record_calls))
        keywords = {keyword.arg for keyword in record_calls[0].keywords if keyword.arg is not None}
        for legacy_alias in {"refreshId", "requestId", "previous", "state", "reason"}:
            self.assertNotIn(legacy_alias, keywords)

        for canonical_field in {"request_id", "source", "result"}:
            self.assertIn(canonical_field, keywords)
        for metadata_field in {"refresh_id", "previous_phase", "phase", "transition_reason"}:
            self.assertIn(metadata_field, keywords)


if __name__ == "__main__":
    unittest.main()
