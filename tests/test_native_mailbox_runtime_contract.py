import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAILBOX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt"
NATIVE_SCAN_RUNNER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt"
NATIVE_INDEX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeIndex.kt"


class NativeMailboxRuntimeContractTests(unittest.TestCase):
    def test_mailbox_detects_the_embedded_legacy_payload_conservatively(self):
        source = MAILBOX.read_text(encoding="utf-8")
        self.assertIn('private const val LEGACY_PYTHON_BUNDLE = "app.zip"', source)
        self.assertIn('context.applicationContext.assets.list("")', source)
        self.assertIn("legacyPythonRuntimePresent", source)
        self.assertIn("fail closed and retain event publication", source)
        self.assertIn("if (!hasLegacyPythonRuntime(context)) return true", source)

    def test_native_apk_does_not_enqueue_compatibility_events_without_a_consumer(self):
        source = MAILBOX.read_text(encoding="utf-8")
        write = source.split("fun write(context: Context, event: JSONObject): Boolean {", 1)[1].split(
            "fun writeBestEffort", 1
        )[0]
        best_effort = source.split("fun writeBestEffort(context: Context, event: JSONObject): Boolean {", 1)[1].split(
            "private fun writeInternal", 1
        )[0]
        self.assertLess(write.index("if (!hasLegacyPythonRuntime(context)) return true"), write.index("writeInternal"))
        self.assertLess(best_effort.index("if (!hasLegacyPythonRuntime(context)) return true"), best_effort.index("bestEffortExecutor.execute"))

    def test_python_to_android_command_dispatcher_is_only_started_for_hybrid_apks(self):
        bootstrap = BOOTSTRAP.read_text(encoding="utf-8")
        main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("NativeMailbox.hasLegacyPythonRuntime(appContext)", bootstrap)
        self.assertIn("if (legacyRuntimePresent)", bootstrap)
        self.assertIn("NativeCommandDispatcher.start(appContext)", bootstrap)
        self.assertIn("NATIVE_ONLY_NO_LEGACY_COMMAND_CONSUMER", bootstrap)
        self.assertIn("no embedded app.zip runtime", bootstrap)
        self.assertNotIn("NativeCommandDispatcher", main_activity)

    def test_kotlin_scanner_owns_recovery_even_when_legacy_delivery_is_not_required(self):
        runner = NATIVE_SCAN_RUNNER.read_text(encoding="utf-8")
        index = NATIVE_INDEX.read_text(encoding="utf-8")
        self.assertIn("NativeIndex.pendingSafRecovery", runner)
        self.assertIn("NativeIndex.markRecoveryEventPublished", runner)
        self.assertIn("fun pendingSafRecovery", index)
        self.assertIn("fun markRecoveryEventPublished", index)
        self.assertIn("NATIVE_POSITIVE_RECOVERY_PENDING", runner)


if __name__ == "__main__":
    unittest.main()
