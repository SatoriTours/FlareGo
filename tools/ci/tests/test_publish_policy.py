import os
from pathlib import Path
import subprocess
import sys
import unittest

from tools.ci.publish_policy import requested_channel


class PublishPolicyTest(unittest.TestCase):
    def test_branch_commits_and_default_manual_runs_do_not_publish(self):
        self.assertIsNone(requested_channel("push", "branch", "main", "false"))
        self.assertIsNone(requested_channel("push", "branch", "main", "true"))
        self.assertIsNone(requested_channel("workflow_dispatch", "branch", "main", "false"))
        self.assertIsNone(requested_channel("pull_request", "tag", "v0.2.0", "true"))

    def test_explicit_tag_push_or_manual_snapshot_can_publish(self):
        self.assertEqual("release", requested_channel("push", "tag", "v0.2.0", "false"))
        self.assertEqual("snapshot", requested_channel("workflow_dispatch", "branch", "main", "true"))
        self.assertIsNone(requested_channel("workflow_dispatch", "branch", "feature", "true"))
        self.assertIsNone(requested_channel("workflow_dispatch", "tag", "v0.2.0", "true"))

    def test_direct_ordinary_push_invocation_is_rejected_before_any_publish_work(self):
        script = Path(__file__).resolve().parents[1] / "publish.py"
        env = dict(os.environ, GITHUB_EVENT_NAME="push", GITHUB_REF_TYPE="branch",
                   GITHUB_REF_NAME="main", INPUT_PUBLISH_SNAPSHOT="false")
        result = subprocess.run([sys.executable, str(script)], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("requires an explicit", result.stderr)
