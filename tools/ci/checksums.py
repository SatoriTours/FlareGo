"""Prepare integrity files for signed build artifacts without publishing anything."""
import hashlib
import json
from pathlib import Path


def write_checksums(directory=Path("dist")):
    value = json.loads((directory / "build-metadata.json").read_text())
    files = [directory / value["apk_name"], directory / "build-metadata.json"]
    lines = []
    for path in files:
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        lines.append(f"{digest}  {path.name}\n")
    (directory / "SHA256SUMS").write_text("".join(lines))
    return files


if __name__ == "__main__":
    write_checksums()
