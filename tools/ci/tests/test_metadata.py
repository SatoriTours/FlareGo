import unittest
from unittest.mock import patch
from tools.ci.build_metadata import metadata, may_publish, current_snapshot, published


class MetadataTest(unittest.TestCase):
    def test_tag_contract(self):
        result = metadata("tag", "v1.2.3", 4, "a" * 40, 10006)
        self.assertEqual(result["version_name"], "1.2.3")
        self.assertEqual(result["version_code"], 10007)
        self.assertEqual(result["apk_name"], "flarego-1.2.3.apk")
        self.assertEqual(result["channel"], "release")

    def test_snapshot_and_later_tag_monotonic(self):
        first = metadata("branch", "main", 20, "b" * 40, 10000)
        second = metadata("tag", "v0.1.0", 21, "c" * 40, first["version_code"])
        self.assertGreater(second["version_code"], first["version_code"])
        self.assertEqual(first["release_tag"], "latest")
        self.assertEqual(first["apk_name"], "flarego-latest.apk")

    def test_invalid_input(self):
        for tag in ["v01.2.3", "v1.2", "v1.2.3-rc.1", "latest", "v1.2١.3"]:
            with self.assertRaises(ValueError):
                metadata("tag", tag, 1, "a" * 40, 0)
        with self.assertRaises(ValueError):
            metadata("branch", "main", 1, "not-a-sha", 0)

    def test_stale_and_immutable_release(self):
        value = metadata("branch", "main", 20, "b" * 40, 10000)
        self.assertFalse(may_publish(value, value["version_code"], False))
        self.assertTrue(may_publish(value, 10000, False))
        tagged = metadata("tag", "v0.1.0", 21, "c" * 40, 10020)
        self.assertFalse(may_publish(tagged, 10020, True))

    def test_old_commit_rerun_cannot_replace_latest(self):
        self.assertFalse(current_snapshot("a" * 40, "b" * 40))
        self.assertTrue(current_snapshot("a" * 40, "a" * 40))

    def test_interrupted_draft_preserves_allocated_version_floor(self):
        release = {"draft": True, "tag_name": "latest", "assets": [{"name": "build-metadata.json", "id": 42}]}
        with patch("tools.ci.build_metadata.gh_json", side_effect=[[[release]], {"version_code": 10050}]):
            highest, tags = published("fixture/repo")
        self.assertEqual(highest, 10050)
        self.assertNotIn("latest", tags)

    def test_empty_draft_is_recoverable_but_public_missing_metadata_fails(self):
        empty = {"draft": True, "tag_name": "v0.1.0", "assets": []}
        with patch("tools.ci.build_metadata.gh_json", return_value=[[empty]]):
            self.assertEqual(published("fixture/repo"), (0, set()))
        empty["draft"] = False
        with patch("tools.ci.build_metadata.gh_json", return_value=[[empty]]):
            with self.assertRaises(ValueError):
                published("fixture/repo")
