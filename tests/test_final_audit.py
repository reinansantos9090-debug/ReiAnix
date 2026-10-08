from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"


class FinalAuditTests(unittest.TestCase):
    def test_page_disconnect_cancels_player_transition_task(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn('player_transition_task = {"task": None}', source)
        disconnect = source[
            source.index("def _handle_page_disconnect"):
            source.index("try:\n        page.on_disconnect", source.index("def _handle_page_disconnect"))
        ]
        self.assertIn('transition_task = player_transition_task.get("task")', disconnect)
        self.assertIn("transition_task.cancel()", disconnect)
        self.assertIn('player_transition_task["task"] = None', disconnect)


if __name__ == "__main__":
    unittest.main()
