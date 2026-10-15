from pathlib import Path
import csv
import unittest

from core.library_parser import VIDEO_EXTENSIONS, parse_video_path
from core.media_identity import identity_from_document, identity_from_relative_path


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "android/app/src/test/resources"
KOTLIN_BASELINE_TEST = (
    ROOT
    / "android/app/src/test/kotlin/com/reiflix/reiflix_local/data/media/MediaDomainParityBaselineTest.kt"
)


def read_fixture(name):
    with (FIXTURES / name).open("r", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def optional(value):
    return None if value == "-" else value


class MediaDomainParityBaselineTests(unittest.TestCase):
    def test_python_filename_parser_matches_shared_domain_corpus(self):
        cases = read_fixture("media_parser_parity.tsv")
        self.assertGreaterEqual(len(cases), 7)

        for case in cases:
            with self.subTest(case=case["case_id"]):
                parsed = parse_video_path(
                    case["path"],
                    None if case["library_root"] == "-" else case["library_root"],
                )
                self.assertEqual(case["anime_title"], parsed.anime_title)
                self.assertEqual(
                    None if case["season"] == "-" else int(case["season"]),
                    parsed.season,
                )
                self.assertEqual(
                    None if case["episode_number"] == "-" else float(case["episode_number"]),
                    parsed.episode,
                )
                self.assertEqual(
                    None if case["absolute_number"] == "-" else int(case["absolute_number"]),
                    parsed.absolute_number,
                )
                self.assertEqual(case["kind"], parsed.episode_type)
                self.assertEqual(case["confidence"], parsed.confidence)
                self.assertEqual(
                    case["supported"] == "true",
                    parsed.extension.lower() in VIDEO_EXTENSIONS,
                )

    def test_python_media_identity_matches_shared_domain_corpus(self):
        cases = read_fixture("media_identity_parity.tsv")
        self.assertGreaterEqual(len(cases), 8)

        for case in cases:
            with self.subTest(case=case["case_id"]):
                relative_path = optional(case["relative_path"])
                volume_name = optional(case["volume_name"])
                tree_uri = optional(case["tree_uri"])
                if case["operation"] == "relative":
                    actual = identity_from_relative_path(relative_path, volume_name)
                else:
                    actual = identity_from_document(
                        case["uri"],
                        relative_path,
                        volume_name,
                        tree_uri,
                    )
                self.assertEqual(optional(case["expected_identity"]), actual)

    def test_kotlin_jvm_baseline_consumes_the_same_shared_fixtures(self):
        source = KOTLIN_BASELINE_TEST.read_text(encoding="utf-8")
        self.assertIn('readFixture("media_parser_parity.tsv")', source)
        self.assertIn('readFixture("media_identity_parity.tsv")', source)
        self.assertIn("MediaFilenameParser.parseVideoPath", source)
        self.assertIn("LocalMediaIdentity.identityFromDocument", source)
        self.assertIn("LocalMediaIdentity.identityFromRelativePath", source)

    def test_baseline_preserves_schema_and_user_owned_mapping_contracts(self):
        store = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
        sqlite_models = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteModels.kt"
        ).read_text(encoding="utf-8")
        mappers = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/SqliteDomainMappers.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("SCHEMA_VERSION = 29", store)
        self.assertIn("metadataManualFieldsJson", sqlite_models)
        self.assertIn("metadataManualFieldsJson = metadataManualFieldsJson", mappers)
        self.assertIn("personalNote = personalNote", mappers)
        self.assertIn("progressSeconds = progress", mappers)
        self.assertIn('mediaIdentity?.takeIf { it.isNotBlank() }', mappers)


if __name__ == "__main__":
    unittest.main()
