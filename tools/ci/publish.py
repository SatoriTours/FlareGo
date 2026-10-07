"""Publish a complete, checksum-bound APK contract after signature verification."""
import json
import os
from pathlib import Path
import subprocess
from build_metadata import published, may_publish, current_snapshot, gh_json
from checksums import write_checksums
from publish_policy import requested_channel

channel = requested_channel(os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_REF_TYPE"),
                            os.environ.get("GITHUB_REF_NAME", ""), os.environ.get("INPUT_PUBLISH_SNAPSHOT", "false"))
if channel is None:
    raise SystemExit("Publication requires an explicit version tag push or manual snapshot request")

repo = os.environ["GITHUB_REPOSITORY"]
value = json.loads(Path("dist/build-metadata.json").read_text())
if value["channel"] != channel or value["commit_sha"] != os.environ["GITHUB_SHA"]:
    raise SystemExit("Build metadata does not match the requested publication")
if value["channel"] == "snapshot":
    head = gh_json("api", f"repos/{repo}/branches/main")["commit"]["sha"]
    if not current_snapshot(value["commit_sha"], head):
        print("Newer main commit exists; keeping the previous latest release, saving this build as an artifact.")
        raise SystemExit(0)
highest, tags = published(repo)
if not may_publish(value, highest, value["release_tag"] in tags):
    raise SystemExit("Refusing stale build or overwrite of a stable release")
files = write_checksums()
tag = value["release_tag"]
notes = f"Signed Android build\n\nCommit: {value['commit_sha']}\nVersion code: {value['version_code']}\nSHA-256: see SHA256SUMS."
# Keep latest's release draft while replacing files: the updater refuses draft releases.
existing = subprocess.run(["gh", "release", "view", tag, "--repo", repo, "--json", "isDraft"], capture_output=True, text=True)
exists = existing.returncode == 0
if exists and value["channel"] == "release" and not json.loads(existing.stdout)["isDraft"]:
    raise SystemExit("Published stable releases are immutable")
if exists:
    subprocess.run(["gh", "release", "edit", tag, "--repo", repo, "--draft"], check=True)
    if value["channel"] == "snapshot":
        subprocess.run(["gh", "api", "--method", "PATCH", f"repos/{repo}/git/refs/tags/latest",
                        "-f", f"sha={value['commit_sha']}", "-F", "force=true"], check=True,
                       stdout=subprocess.DEVNULL)
    subprocess.run(["gh", "release", "upload", tag, *map(str, files), "dist/SHA256SUMS", "--clobber", "--repo", repo], check=True)
else:
    args = ["gh", "release", "create", tag, *map(str, files), "dist/SHA256SUMS", "--repo", repo,
            "--draft", "--title", "FlareGo " + value["version_name"], "--target", value["commit_sha"],
            "--notes", notes]
    if value["channel"] == "snapshot":
        args.append("--prerelease")
    else:
        args.append("--verify-tag")
    subprocess.run(args, check=True)
args = ["gh", "release", "edit", tag, "--repo", repo, "--draft=false", "--title", "FlareGo " + value["version_name"], "--notes", notes]
args.append("--prerelease" if value["channel"] == "snapshot" else "--latest")
subprocess.run(args, check=True)
