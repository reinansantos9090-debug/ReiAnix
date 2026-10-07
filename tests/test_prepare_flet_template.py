import os
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from scripts.prepare_flet_template import HOOK


class FletTemplateManifestTests(unittest.TestCase):
    def test_generated_template_preserves_activity_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "rendered"
            overlay = root / "overlay-app"
            (project / "android/app/src/main").mkdir(parents=True)
            (project / "android").mkdir(parents=True, exist_ok=True)
            (project / "android/build.gradle.kts").write_text(
                'plugins {\n'
                '    id("com.android.application") version "8.6.1" apply false\n'
                '    id("org.jetbrains.kotlin.android") version "2.0.21" apply false\n'
                '}\n',
                encoding="utf-8",
            )
            (project / "android/app/src/main/kotlin").mkdir(parents=True)
            (project / "android/app/src/main/res/values").mkdir(parents=True)
            (project / "android/app/build.gradle.kts").write_text(
                'plugins { id("com.android.application") version "8.9.1" }\n'
                'android { compileSdk = 36 }\n',
                encoding="utf-8",
            )
            gradlew = project / "android/gradlew"
            gradlew.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            gradlew.chmod(0o755)
            (project / "android/app/src/main/AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<application><activity android:name=".MainActivity" '
                'android:exported="true" /></application></manifest>',
                encoding="utf-8",
            )
            (overlay / "src/main/kotlin/com/reiflix/reiflix_local").mkdir(parents=True)
            for name in (
                "SystemUiController.kt",
                "MainActivity.kt",
                "NativeMailbox.kt",
                "SafScanner.kt",
                "MediaStoreScanner.kt",
                "BroadStorageScanner.kt",
                "NativePlayerActivity.kt",
                "GoogleIdentity.kt",
            ):
                (overlay / "src/main/kotlin/com/reiflix/reiflix_local" / name).write_text(
                    "// test fixture\n", encoding="utf-8"
                )
            (overlay / "src/main/res/values").mkdir(parents=True)
            (overlay / "src/main/res/values/styles.xml").write_text(
                "<resources/>", encoding="utf-8"
            )
            (overlay / "src/main/res/values/reianix_ids.xml").write_text(
                '<resources><item name="reianix_compose_app_shell" type="id" /></resources>',
                encoding="utf-8",
            )
            (overlay / "src/test/kotlin/com/reiflix/reiflix_local").mkdir(parents=True)
            (overlay / "src/test/kotlin/com/reiflix/reiflix_local/FixtureTest.kt").write_text(
                "package com.reiflix.reiflix_local\nclass FixtureTest",
                encoding="utf-8",
            )

            hook = HOOK.replace("__REIFLIX_OVERLAY_APP__", repr(str(overlay)))
            previous = Path.cwd()
            try:
                os.chdir(project)
                exec(compile(hook, "<generated-flet-hook>", "exec"), {"__name__": "__main__"})
            finally:
                os.chdir(previous)

            self.assertIn('for test_root in ("src/test", "src/androidTest")', hook)
            self.assertIn(":app:testDebugUnitTest", hook)
            prepare_source = (Path(__file__).parents[1] / "scripts" / "prepare_flet_template.py").read_text(encoding="utf-8")
            self.assertIn("NativeRequestState.kt", prepare_source)
            self.assertIn("NativeCommandDispatcher.kt", prepare_source)
            self.assertIn("StorageAuthorization.kt", prepare_source)
            self.assertIn("native_player_view.xml", prepare_source)
            self.assertTrue(
                (project / "android/app/src/test/kotlin/com/reiflix/reiflix_local/FixtureTest.kt").is_file()
            )

            manifest = ET.parse(project / "android/app/src/main/AndroidManifest.xml").getroot()
            ns = {"android": "http://schemas.android.com/apk/res/android"}
            main = next(
                (
                    activity for activity in manifest.findall(".//activity")
                    if activity.get("{http://schemas.android.com/apk/res/android}name")
                    in {".MainActivity", "com.reiflix.reiflix_local.MainActivity"}
                ),
                None,
            )
            self.assertIsNotNone(main)
            self.assertEqual(main.get("{http://schemas.android.com/apk/res/android}launchMode"), "singleTask")
            self.assertEqual(main.get("{http://schemas.android.com/apk/res/android}documentLaunchMode"), "never")
            player = next(
                activity for activity in manifest.findall(".//activity")
                if activity.get("{http://schemas.android.com/apk/res/android}name")
                == "com.reiflix.reiflix_local.NativePlayerActivity"
            )
            self.assertEqual(player.get("{http://schemas.android.com/apk/res/android}launchMode"), "singleTop")
            generated_gradle = (project / "android/app/build.gradle.kts").read_text(encoding="utf-8")
            self.assertIn('namespace = "com.reiflix.reiflix_local"', generated_gradle)
            self.assertIn(
                "android.permission.READ_MEDIA_VIDEO",
                [
                    node.get("{http://schemas.android.com/apk/res/android}name")
                    for node in manifest.findall("uses-permission")
                ],
            )
            self.assertIn(
                'android:launchMode="singleTop"',
                (project / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"),
            )
            generated_root_gradle = (project / "android/build.gradle.kts").read_text(encoding="utf-8")
            self.assertNotIn("org.jetbrains.kotlin.plugin.compose", generated_root_gradle)
            self.assertIn('id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"', generated_gradle)
            self.assertIn("ReiAnix Compose Compiler Gradle plugin", generated_gradle)
            self.assertIn("compose = true", generated_gradle)
            self.assertIn("androidx.compose:compose-bom:2026.06.00", generated_gradle)
            self.assertIn("androidx.compose.material:material-icons-core", generated_gradle)
            self.assertIn("androidx.navigation:navigation-compose:2.9.8", generated_gradle)
            self.assertIn("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0", generated_gradle)



if __name__ == "__main__":
    unittest.main()
