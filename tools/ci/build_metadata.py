"""Single release contract shared by CI and the Android updater."""
import json
import os
from pathlib import Path
import re
import subprocess


def metadata(ref_type, ref_name, run_number, sha, highest):
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or run_number < 1 or highest < 0:
        raise ValueError("Invalid build identity")
    code = max(10000 + run_number, highest + 1)
    if code > 2147483647:
        raise ValueError("Android versionCode exhausted")
    if ref_type == "tag":
        if not re.fullmatch(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", ref_name):
            raise ValueError("Release tag must be vMAJOR.MINOR.PATCH without leading zeroes")
        version = ref_name[1:]
        channel, tag, apk = "release", ref_name, f"flarego-{version}.apk"
    elif ref_type == "branch":
        version = f"0.1.0-dev.{code}"
        channel, tag, apk = "snapshot", "latest", "flarego-latest.apk"
    else:
        raise ValueError("Unsupported ref type")
    return dict(channel=channel, release_tag=tag, version_name=version,
                version_code=code, commit_sha=sha, apk_name=apk)


def may_publish(value, highest, tag_exists):
    return value["version_code"] > highest and not (value["channel"] == "release" and tag_exists)


def current_snapshot(build_sha, head_sha):
    return build_sha == head_sha


def gh_json(*args):
    return json.loads(subprocess.check_output(["gh", *args], text=True))


def published(repo):
    """Fail closed if existing published metadata cannot be read."""
    pages = gh_json("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100")
    versions, tags = [], set()
    for page in pages:
        for release in page:
            if release["draft"]:
                continue
            tags.add(release["tag_name"])
            assets = [a for a in release["assets"] if a["name"] == "build-metadata.json"]
            if len(assets) != 1:
                raise ValueError("Published release has missing/duplicate build metadata")
            value = gh_json("api", f"repos/{repo}/releases/assets/{assets[0]['id']}",
                            "-H", "Accept: application/octet-stream")
            code = value["version_code"]
            if type(code) is not int or not 1 <= code <= 2147483647:
                raise ValueError("Invalid published versionCode")
            versions.append(code)
    return max(versions, default=0), tags


if __name__ == "__main__":
    highest, tags = published(os.environ["GITHUB_REPOSITORY"])
    value = metadata(os.environ["GITHUB_REF_TYPE"], os.environ["GITHUB_REF_NAME"],
                     int(os.environ["GITHUB_RUN_NUMBER"]), os.environ["GITHUB_SHA"], highest)
    if value["channel"] == "release" and value["release_tag"] in tags:
        raise ValueError("Published stable releases are immutable; use a new tag")
    Path("dist").mkdir(exist_ok=True)
    Path("dist/build-metadata.json").write_text(json.dumps(value, indent=2) + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for key, val in value.items():
            output.write(f"{key}={val}\n")
