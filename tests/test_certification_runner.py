import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts"/"release_certification.py"
sys.path.insert(0,str(ROOT/"scripts"))
import release_certification as runner

class CertificationRunnerTests(unittest.TestCase):
    def test_matrix_has_exactly_201_unique_real_requirements(self):
        self.assertEqual(len(runner.REQUIREMENTS),201)
        ids=[x[0] for x in runner.REQUIREMENTS]
        reqs=[x[2] for x in runner.REQUIREMENTS]
        areas=[x[1] for x in runner.REQUIREMENTS]
        self.assertEqual(ids,list(range(1,202)))
        self.assertEqual(len(ids),len(set(ids)))
        self.assertEqual(len(reqs),len(set(reqs)))
        self.assertTrue(all(reqs))
        self.assertTrue(all(areas))
        self.assertTrue(all(not x.startswith("old generic requirement label item") for x in reqs))

    def test_classifications_are_exactly_the_allowed_set(self):
        self.assertEqual(runner.ALLOWED,{"PASS","PARTIAL","FAIL","NOT VALIDATED","NOT APPLICABLE","BLOCKED"})

    def test_empty_adb_list_is_not_device_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            bindir=Path(tmp)/"bin"; bindir.mkdir()
            adb=bindir/"adb"
            adb.write_text("#!/bin/sh\necho 'List of devices attached'\nexit 0\n",encoding="utf-8")
            adb.chmod(0o755)
            old=os.environ.get("PATH",""); os.environ["PATH"]=str(bindir)+os.pathsep+old
            try:
                probe=runner.adb_probe(Path(tmp))
            finally:
                os.environ["PATH"]=old
            self.assertEqual(probe["tool"],"PASS")
            self.assertEqual(probe["devices"],[])
            self.assertEqual(probe["device_validation"],"NOT VALIDATED")

    def test_failed_command_is_never_pass(self):
        result=runner.run_command("test","intentional failure",[sys.executable,"-c","raise SystemExit(7)"],cwd=ROOT,timeout=30)
        self.assertEqual(result.status,"FAIL")
        self.assertEqual(result.exit_code,7)

    def test_missing_paths_are_not_promoted_to_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            results={
                "pytest":runner.Result("Python","pytest","NOT VALIDATED","not run"),
                "unittest":runner.Result("Python","unittest","NOT VALIDATED","not run"),
            }
            row=runner.make_row(root,runner.REQUIREMENTS[0],results,{"status":"NOT VALIDATED"},[])
            self.assertNotEqual(row["Result"],"PASS")

    def test_source_never_executes_emulator_or_connected_instrumentation(self):
        source=SCRIPT.read_text(encoding="utf-8")
        forbidden=["emulator "+"-avd","connected"+"DebugAndroidTest","connected"+"AndroidTest"]
        for token in forbidden:
            self.assertNotIn(token,source)

    def test_generic_functional_requirement_requires_specific_evidence_for_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"core").mkdir()
            (root/"core"/"library_store.py").write_text("# implementation\n",encoding="utf-8")
            (root/"tests").mkdir()
            (root/"tests"/"test_area.py").write_text("def test_area():\n    assert True\n",encoding="utf-8")
            results={
                "pytest":runner.Result("Python","pytest","PASS","pytest passed"),
                "unittest":runner.Result("Python","unittest","PASS","unittest passed"),
            }
            row=runner.make_row(root,runner.REQUIREMENTS[0],results,{"status":"NOT VALIDATED"},[])
            self.assertEqual(row["Result"],"PARTIAL")
            self.assertIn("requirement-specific",row["Limitation"])

    def test_static_contract_can_produce_pass_with_concrete_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            manifest=root/"android/app/src/main"
            manifest.mkdir(parents=True)
            (manifest/"AndroidManifest.xml").write_text('android:launchMode="singleTask"\n',encoding="utf-8")
            (root/"core").mkdir()
            (root/"tests").mkdir()
            (root/"core"/"navigation.py").write_text("# navigation\n",encoding="utf-8")
            (root/"android/app/src/main/kotlin/com/reiflix/reiflix_local").mkdir(parents=True)
            (root/"android/app/src/main/kotlin/com/reiflix/reiflix_local"/"MainActivity.kt").write_text("// MainActivity\n",encoding="utf-8")
            (root/"android/app/src/main/kotlin/com/reiflix/reiflix_local"/"NativePlayerActivity.kt").write_text("// player\n",encoding="utf-8")
            (root/"tests"/"test_back_lifecycle.py").write_text("",encoding="utf-8")
            (root/"tests"/"test_device_compatibility_contract.py").write_text("",encoding="utf-8")
            (root/"tests"/"test_runtime_android_contract.py").write_text("",encoding="utf-8")
            results={"pytest":runner.Result("Python","pytest","PASS"),"unittest":runner.Result("Python","unittest","PASS")}
            item=next(x for x in runner.REQUIREMENTS if x[2]=="MainActivity uses singleTask launch semantics")
            row=runner.make_row(root,item,results,{"status":"NOT VALIDATED"},[])
            self.assertEqual(row["Result"],"PASS")
            self.assertIn("singleTask",row["Evidence"])

    def test_unittest_discovery_command_targets_tests_directory(self):
        source=SCRIPT.read_text(encoding="utf-8")
        self.assertIn('"unittest","discover","-s","tests","-v"',source)

    def test_unittest_parser_reads_stderr_summary(self):
        result=runner.Result(
            "Python",
            "unittest discovery",
            "FAIL",
            stdout="",
            stderr="Ran 862 tests in 2.0s\\nFAILED (failures=1)",
        )
        parsed=runner.parse_unittest(runner.unittest_output(result))
        self.assertEqual(parsed["discovered"],862)
        self.assertEqual(parsed["failures"],1)

    def test_zero_unittest_discovery_is_never_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"tests").mkdir()
            (root/"tests"/"test_case.py").write_text(
                "import unittest\nclass Example(unittest.TestCase):\n    def test_ok(self):\n        pass\n",
                encoding="utf-8",
            )
            result=runner.Result("Python","unittest discovery","PASS","Ran 0 tests.\nOK",
                                 command="python -m unittest discover -s tests -v")
            result=runner.normalize_unittest_result(root,result)
            self.assertEqual(result.status,"NOT VALIDATED")
            empty=Path(tmp)/"empty"
            empty.mkdir()
            result=runner.normalize_unittest_result(empty,
                runner.Result("Python","unittest discovery","PASS","Ran 0 tests.\nOK"))
            self.assertEqual(result.status,"NOT APPLICABLE")

    def test_collection_audit_ignores_non_test_helper_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"tests").mkdir()
            helper=root/"tests"/"test_helper.py"
            helper.write_text("class AsyncRunTaskMixin:\n    pass\n",encoding="utf-8")
            test=root/"tests"/"test_real.py"
            test.write_text("def test_real():\n    assert True\n",encoding="utf-8")
            result=runner.collection_audit(
                ["tests/test_helper.py","tests/test_real.py"],
                "tests/test_real.py::test_real",
                root,
            )
            self.assertEqual(result["repository_test_files"],1)
            self.assertEqual(result["not_discovered"],[])
            self.assertEqual(result["ignored_non_test_files"],["tests/test_helper.py"])

    def test_fast_apk_workflow_does_not_run_release_certification(self):
        workflow=(ROOT/".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertNotIn("Run release evidence certification (no emulator)",workflow)
        self.assertNotIn("name: release-certification",workflow)
        self.assertIn("Run Python regression suite",workflow)
        self.assertIn("Build APK",workflow)

    def test_rendered_gradle_environment_uses_staged_site_packages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            site_packages=root/"build"/"flutter"/"site-packages"
            site_packages.mkdir(parents=True)
            old=os.environ.pop("SERIOUS_PYTHON_SITE_PACKAGES",None)
            try:
                result=runner.configure_rendered_gradle_environment(root)
                self.assertEqual(result.status,"PASS")
                self.assertEqual(os.environ.get("SERIOUS_PYTHON_SITE_PACKAGES"),str(site_packages))
            finally:
                if old is None:
                    os.environ.pop("SERIOUS_PYTHON_SITE_PACKAGES",None)
                else:
                    os.environ["SERIOUS_PYTHON_SITE_PACKAGES"]=old

    def test_lint_discovery_is_independent_from_lint_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            gradlew=root/"gradlew"
            gradlew.write_text("#!/bin/sh\nprintf 'app:lintReportDebug - lint task\\n'\n",encoding="utf-8")
            gradlew.chmod(0o755)
            discovery,task=runner.discover_lint_task(gradlew,root)
            self.assertEqual(discovery.status,"PASS")
            self.assertEqual(task,":app:lintReportDebug")
            self.assertIn("lintReportDebug",discovery.evidence)

    def test_runner_compiles(self):
        result=subprocess.run([sys.executable,"-m","py_compile",str(SCRIPT)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False)
        self.assertEqual(result.returncode,0,result.stdout)

    def test_small_fixture_generates_201_rows_without_fake_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"tests").mkdir()
            (root/"tests"/"test_sample.py").write_text("def test_sample():\n    assert True\n",encoding="utf-8")
            subprocess.run(["git","init","-q",str(root)],check=True)
            subprocess.run(["git","-C",str(root),"config","user.email","test@example.invalid"],check=True)
            subprocess.run(["git","-C",str(root),"config","user.name","Certification Test"],check=True)
            subprocess.run(["git","-C",str(root),"add","."],check=True)
            subprocess.run(["git","-C",str(root),"commit","-qm","fixture"],check=True)
            out=root/"out.json"; report=root/"report.md"; matrix=root/"matrix.json"
            result=subprocess.run([sys.executable,str(SCRIPT),"--root",str(root),"--skip-gradle","--output",str(out),"--report",str(report),"--matrix",str(matrix)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False,timeout=180)
            self.assertNotEqual(result.returncode,0,result.stdout)
            data=json.loads(out.read_text(encoding="utf-8"))
            rows=json.loads(matrix.read_text(encoding="utf-8"))
            self.assertEqual(len(rows),201)
            self.assertEqual(len({x["ID"] for x in rows}),201)
            self.assertTrue(all(not x["Requirement"].startswith("old generic requirement label item") for x in rows))
            self.assertEqual(sum(data["matrix_counts"].values()),201)
            self.assertEqual(data["classification"],"NOT CERTIFIED")
            self.assertEqual(data["matrix_counts"]["PASS"],0)
            self.assertEqual(data["matrix_counts"]["PARTIAL"],0)
            self.assertEqual(data["matrix_counts"]["FAIL"],0)
            self.assertEqual(data["matrix_counts"]["NOT VALIDATED"],201)

if __name__=="__main__":
    unittest.main()
