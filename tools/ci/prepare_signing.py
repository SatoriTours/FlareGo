"""Decode a repository secret into a private runner-temporary keystore."""
import base64
import os
from pathlib import Path

required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
if any(not os.environ.get(key) for key in required):
    raise SystemExit("Android signing secrets are missing")
path = Path(os.environ["RUNNER_TEMP"]) / "flarego-release.jks"
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "wb") as output:
    output.write(base64.b64decode(os.environ["ANDROID_KEYSTORE_BASE64"], validate=True))
with open(os.environ["GITHUB_ENV"], "a") as output:
    output.write(f"ANDROID_KEYSTORE_PATH={path}\n")
