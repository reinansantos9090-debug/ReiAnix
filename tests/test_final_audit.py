from pathlib import Path
from tests.native_main_contract import native_main_source
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = native_main_source()


class FinalAuditTests(unittest.TestCase):
    def test_native_player_and_activity_cancel_stale_async_work(self):
        source = native_main_source()
        self.assertIn("pendingPreparation?.cancel(true)", source)
        self.assertIn("private val playbackWorker: ExecutorService", source)
        self.assertIn("private val progressWorker: ExecutorService", source)
        self.assertIn("override fun onDestroy()", source)


if __name__ == "__main__":
    unittest.main()
