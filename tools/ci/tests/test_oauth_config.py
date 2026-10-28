import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


class OAuthBuildConfigTest(unittest.TestCase):
    def test_actions_injects_public_oauth_properties_without_replacing_existing_config(self):
        workflow = (Path(__file__).resolve().parents[3] / ".github/workflows/android.yml").read_text()
        step = re.search(
            r"^      - name: Configure public Cloudflare OAuth\n(.*?)(?=^      - |\Z)",
            workflow, re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(step, "Actions must configure OAuth before its Gradle builds")
        block = step.group(1)
        environment = dict(re.findall(r"^          ([A-Z_]+): (.+)$", block, re.MULTILINE))
        script = re.search(r"^        run: \|\n((?:          .*\n)+)", block, re.MULTILINE)
        self.assertIsNotNone(script)
        command = "\n".join(line[10:] for line in script.group(1).splitlines())
        with tempfile.TemporaryDirectory(prefix="flarego-oauth-ci-") as directory:
            properties = Path(directory) / "gradle.properties"
            properties.write_text("org.gradle.jvmargs=-Xmx2g\n")
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", command],
                cwd=directory, env={**os.environ, **environment},
                capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            values = dict(
                line.split("=", 1) for line in properties.read_text().splitlines()
                if line and not line.startswith("#")
            )
        self.assertEqual(values["org.gradle.jvmargs"], "-Xmx2g")
        self.assertEqual(values["flarego.oauthClientId"], "9bd1b2bf245bd1b78506a7c0b6fbc11a")
        self.assertEqual(
            values["flarego.oauthRedirectUri"],
            "https://satori.tours/flarego/cloudflare/callback",
        )
        self.assertEqual(values["flarego.oauthScopes"], "account.read")
        self.assertFalse(any("secret" in key.lower() for key in values))
