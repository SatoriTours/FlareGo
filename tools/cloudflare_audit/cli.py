"""Command line entry point for the reproducible Cloudflare coverage ledger.

Only ``generate`` exists in this task: it verifies the pinned schema against
the manifest SHA-256, rebuilds the CSV artifacts and refuses to write anything
when the reviewed baseline no longer matches. It is offline and read-only with
respect to Cloudflare.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .catalog import (
    build_catalog,
    check_baseline_alignment,
    manifest_with_counts,
    pinned_count_problems,
    render_catalog_files,
)
from .schema import load_schema

MANIFEST_NAME = "snapshot.json"


def _read_json(path: Path) -> dict:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as error:
        raise ValueError(f"cannot read {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ValueError(f"{path} is not valid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def generate(schema_path: Path, manifest_path: Path, implemented_path: Path, output_dir: Path) -> dict:
    """Regenerate the catalog, failing loudly on any baseline drift."""
    manifest = _read_json(manifest_path)
    schema_meta = manifest.get("schema")
    expected_sha256 = schema_meta.get("sha256") if isinstance(schema_meta, dict) else None
    if not isinstance(expected_sha256, str) or not expected_sha256:
        raise ValueError(f"{manifest_path} must pin schema.sha256 for a verifiable generation")

    schema = load_schema(schema_path, expected_sha256)
    implemented = _read_json(implemented_path)
    catalog = build_catalog(schema, implemented)

    problems = pinned_count_problems(catalog, manifest, schema)
    problems += check_baseline_alignment(manifest, implemented)
    if problems:
        raise ValueError(
            "refusing to regenerate from a changed baseline:\n- " + "\n- ".join(problems)
        )

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for name, data in render_catalog_files(catalog).items():
        (output / name).write_bytes(data)
    (output / MANIFEST_NAME).write_text(
        manifest_with_counts(manifest, catalog["counts"]), encoding="utf-8"
    )
    return catalog


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python3 -m tools.cloudflare_audit.cli")
    subparsers = parser.add_subparsers(dest="command", required=True)
    generate_parser = subparsers.add_parser(
        "generate", help="regenerate endpoints.csv, products.csv and snapshot.json"
    )
    generate_parser.add_argument("--schema", type=Path, required=True)
    generate_parser.add_argument("--manifest", type=Path, required=True)
    generate_parser.add_argument("--implemented", type=Path, required=True)
    generate_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        catalog = generate(args.schema, args.manifest, args.implemented, args.output)
    except (ValueError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(catalog["counts"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
