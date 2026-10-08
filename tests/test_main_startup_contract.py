import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"


class MainStartupContractTests(unittest.TestCase):
    def test_scan_target_provider_is_defined_before_coordinator_creation(self):
        tree = ast.parse(MAIN.read_text(encoding="utf-8"))

        main_fn = next(
            node
            for node in tree.body
            if isinstance(node, ast.AsyncFunctionDef) and node.name == "main"
        )

        coordinator_line = None
        provider_line = None
        capabilities_line = None

        for node in main_fn.body:
            if (
                isinstance(node, ast.Assign)
                and any(
                    isinstance(target, ast.Name) and target.id == "scan_coordinator"
                    for target in node.targets
                )
            ):
                coordinator_line = node.lineno
            if (
                isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                and node.name == "_authorized_scan_targets"
            ):
                provider_line = node.lineno
            if (
                isinstance(node, ast.Assign)
                and any(
                    isinstance(target, ast.Name) and target.id == "storage_capabilities"
                    for target in node.targets
                )
            ):
                capabilities_line = node.lineno

        self.assertIsNotNone(coordinator_line)
        self.assertIsNotNone(provider_line)
        self.assertIsNotNone(capabilities_line)
        self.assertLess(
            provider_line,
            coordinator_line,
            "_authorized_scan_targets must exist before ScanCoordinator is constructed",
        )
        self.assertLess(
            capabilities_line,
            coordinator_line,
            "storage_capabilities must be initialized before ScanCoordinator is constructed",
        )


if __name__ == "__main__":
    unittest.main()
