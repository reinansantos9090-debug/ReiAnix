import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main"
MAIN = ANDROID / "kotlin/com/reiflix/reiflix_local/MainActivity.kt"
APPLICATION = ANDROID / "kotlin/com/reiflix/reiflix_local/ReiAnixApplication.kt"
HOST = ANDROID / "kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
MANIFEST = ANDROID / "AndroidManifest.xml"
JVM_WORKFLOW = ROOT / ".github/workflows/kotlin_jvm_tests.yml"


class NativeEntryContractTests(unittest.TestCase):
    def test_application_and_launcher_use_android_lifecycle_base_classes(self):
        main = MAIN.read_text(encoding="utf-8")
        application = APPLICATION.read_text(encoding="utf-8")
        self.assertIn("import androidx.activity.ComponentActivity", main)
        self.assertIn("class MainActivity : ComponentActivity()", main)
        self.assertIn("import android.app.Application", application)
        self.assertIn("class ReiAnixApplication : Application()", application)
        self.assertNotIn("FlutterFragmentActivity", main)
        self.assertNotIn("FlutterApplication", application)
        self.assertNotIn("flutterEngine", main)

    def test_bootstrap_validation_precedes_the_real_compose_host(self):
        main = MAIN.read_text(encoding="utf-8")
        create = main[main.index("override fun onCreate(savedInstanceState: Bundle?)"):
                      main.index("public override fun onNewIntent")]
        self.assertIn("appDependencies.bootstrap.start()", create)
        self.assertIn("appDependencies.bootstrap.validateCanonicalDatabase()", create)
        self.assertLess(create.index("validateCanonicalDatabase()"),
                        create.index("ReiAnixComposeLibraryHost("))
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)", create)
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", create)

    def test_activity_mounts_one_existing_compose_root_and_navigation_graph(self):
        host = HOST.read_text(encoding="utf-8")
        self.assertIn("activity.findViewById<ViewGroup>(android.R.id.content)", host)
        self.assertIn("ComposeView(activity)", host)
        self.assertIn("view.setContent", host)
        self.assertIn("ReiAnixComposeRoot(", host)
        self.assertIn("ReiAnixNavigationHost(", host)
        self.assertIn("rememberNavController()", host)

    def test_legacy_hide_cannot_blank_the_native_app_surface(self):
        host = HOST.read_text(encoding="utf-8")
        section = host[host.index("fun hide()"):host.index("fun handleBack(): Boolean")]
        self.assertNotIn("composeView?.visibility = View.GONE", section)
        self.assertIn("view.visibility = View.VISIBLE", section)

    def test_manifest_still_declares_the_same_native_launcher_and_player(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertIn('android:name=".ReiAnixApplication"', manifest)
        self.assertIn('android:name=".MainActivity"', manifest)
        self.assertIn('android:name=".NativePlayerActivity"', manifest)
        self.assertIn('android.intent.action.MAIN', manifest)
        self.assertIn('android.intent.category.LAUNCHER', manifest)
        self.assertIn('android:exported="true"', manifest)

    def test_kotlin_jvm_workflow_remains_manual_only(self):
        workflow = JVM_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("push:", workflow)


if __name__ == "__main__":
    unittest.main()
