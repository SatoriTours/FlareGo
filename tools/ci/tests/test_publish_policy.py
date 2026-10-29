import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.ci.build_metadata import metadata
from tools.ci.publish_policy import requested_channel


class PublishPolicyTest(unittest.TestCase):
    def test_main_push_automatically_publishes_snapshot(self):
        for flag in ("false", "true", ""):
            with self.subTest(flag=flag):
                self.assertEqual("snapshot", requested_channel("push", "branch", "main", flag))

    def test_other_branches_prs_and_default_manual_runs_do_not_publish(self):
        for flag in ("false", "true"):
            self.assertIsNone(requested_channel("push", "branch", "feature", flag))
        self.assertIsNone(requested_channel("workflow_dispatch", "branch", "main", "false"))
        self.assertIsNone(requested_channel("pull_request", "branch", "main", "true"))
        self.assertIsNone(requested_channel("pull_request", "tag", "v0.2.0", "true"))
        self.assertIsNone(requested_channel("push", "tag", "latest", "true"))

    def test_explicit_tag_push_or_manual_snapshot_can_publish(self):
        self.assertEqual("release", requested_channel("push", "tag", "v0.2.0", "false"))
        self.assertEqual("snapshot", requested_channel("workflow_dispatch", "branch", "main", "true"))
        self.assertIsNone(requested_channel("workflow_dispatch", "branch", "feature", "true"))
        self.assertIsNone(requested_channel("workflow_dispatch", "tag", "v0.2.0", "true"))

    def test_direct_feature_push_is_rejected_before_any_publish_work(self):
        script = Path(__file__).resolve().parents[1] / "publish.py"
        env = dict(os.environ, GITHUB_EVENT_NAME="push", GITHUB_REF_TYPE="branch",
                   GITHUB_REF_NAME="feature", INPUT_PUBLISH_SNAPSHOT="false")
        result = subprocess.run(
            [sys.executable, str(script)], env=env, capture_output=True, text=True, timeout=10,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Publication requires", result.stderr)

    def run_main_publish(self, directory, head):
        root = Path(directory)
        (root / "dist").mkdir()
        value = metadata("branch", "main", 21, "a" * 40, 0)
        (root / "dist/build-metadata.json").write_text(json.dumps(value))
        (root / "dist/flarego-latest.apk").write_bytes(b"isolated-test-payload-not-a-real-apk")
        fake_bin = root / "bin"
        fake_bin.mkdir()
        fake_gh = fake_bin / "gh"
        fake_gh.write_text(
            "#!" + sys.executable + "\n" + """
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
with Path(os.environ["FIXTURE_CALLS"]).open("a") as log:
    log.write(json.dumps(args) + "\\n")
if args == ["api", "repos/fixture/repo/branches/main"]:
    print(json.dumps({"commit": {"sha": os.environ["FIXTURE_HEAD"]}}))
elif args == ["api", "--paginate", "--slurp", "repos/fixture/repo/releases?per_page=100"]:
    print("[[]]")
elif args[:3] == ["release", "view", "latest"]:
    sys.exit(1)
elif args[:3] in (["release", "create", "latest"], ["release", "edit", "latest"]):
    pass
else:
    sys.exit("Unexpected fake gh call: " + repr(args))
"""
        )
        fake_gh.chmod(0o755)
        env = dict(
            os.environ, PATH=str(fake_bin) + os.pathsep + os.environ["PATH"],
            GITHUB_EVENT_NAME="push", GITHUB_REF_TYPE="branch", GITHUB_REF_NAME="main",
            INPUT_PUBLISH_SNAPSHOT="false", GITHUB_REPOSITORY="fixture/repo",
            GITHUB_SHA="a" * 40, FIXTURE_HEAD=head, FIXTURE_CALLS=str(root / "calls.jsonl"),
        )
        script = Path(__file__).resolve().parents[1] / "publish.py"
        result = subprocess.run(
            [sys.executable, str(script)], cwd=root, env=env,
            capture_output=True, text=True, timeout=10,
        )
        calls_file = root / "calls.jsonl"
        calls = [json.loads(line) for line in calls_file.read_text().splitlines()] if calls_file.exists() else []
        return result, calls

    def test_current_main_push_publishes_complete_prerelease_contract(self):
        with tempfile.TemporaryDirectory(prefix="flarego-publish-test-") as directory:
            result, calls = self.run_main_publish(directory, "a" * 40)
            self.assertEqual(result.returncode, 0, result.stderr)
            create = next(call for call in calls if call[:3] == ["release", "create", "latest"])
            self.assertIn("--prerelease", create)
            self.assertIn("--draft", create)
            self.assertEqual(create[create.index("--target") + 1], "a" * 40)
            for name in ("dist/flarego-latest.apk", "dist/build-metadata.json", "dist/SHA256SUMS"):
                self.assertIn(name, create)
            self.assertEqual(calls[-1][:3], ["release", "edit", "latest"])
            self.assertIn("--draft=false", calls[-1])
            self.assertIn("--prerelease", calls[-1])
            self.assertNotIn("--latest", calls[-1])
            root = Path(directory) / "dist"
            for line in (root / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split("  ", 1)
                self.assertEqual(hashlib.sha256((root / name).read_bytes()).hexdigest(), digest)

    def test_old_main_push_does_not_touch_latest_when_newer_commit_exists(self):
        with tempfile.TemporaryDirectory(prefix="flarego-publish-test-") as directory:
            result, calls = self.run_main_publish(directory, "b" * 40)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Newer main commit exists", result.stdout)
            self.assertEqual(calls, [["api", "repos/fixture/repo/branches/main"]])
            self.assertFalse((Path(directory) / "dist/SHA256SUMS").exists())
