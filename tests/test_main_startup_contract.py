import ast
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"


class MainStartupContractTests(unittest.TestCase):
    def test_native_composition_root_starts_before_scanning(self):
        source = native_main_source()
        self.assertIn("internal object MainEntryPoint", source)
        self.assertIn("dependencies.bootstrap.start()", source)
        self.assertIn("validateCanonicalDatabase()", source)
        self.assertIn("object NativeScanController", source)
        self.assertIn("object NativeScanRunner", source)
        self.assertIn("ReiAnixAppDependencies", source)


if __name__ == "__main__":
    unittest.main()
