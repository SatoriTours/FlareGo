"""Command line entry point for the reproducible Cloudflare coverage ledger.

``generate`` rebuilds the pinned CSV artifacts, ``propose`` regenerates the
review ledger proposal, ``check`` validates the ledger read-only against the
catalog (optionally cross-checking the pinned schema) and ``diff`` compares two
local schemas into a review report. Everything is offline; no credential or
Cloudflare request is involved.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path

from .catalog import (
    build_catalog,
    check_baseline_alignment,
    manifest_with_counts,
    pinned_count_problems,
    render_catalog_files,
)
from .diff import DIFF_FORMAT, diff_schemas
from .review import (
    cross_check_schema,
    load_catalog_operations,
    load_p2_candidates,
    load_review,
    phase_overrides_for,
    propose_review,
    summarize_review,
    validate_review,
)
from .schema import load_schema

MANIFEST_NAME = "snapshot.json"

# The whole reviewed audit baseline directory is read-only for report output:
# it contains the pinned snapshot, the generated catalogs, the ledger and the
# protocol artifacts (R2 S3 today, GraphQL/realtime later).
REVIEWED_BASELINE_DIRECTORY = ("docs", "cloudflare-audit")
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


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


def check_ledger(review_path: Path, catalog_path: Path, schema_path: Path | None = None) -> dict:
    """Validate and summarize the ledger. Read-only: nothing is written back.

    A structurally invalid ledger yields readable errors and no summary, so the
    CLI can never present a success summary derived from invalid entries.
    """
    review = load_review(review_path)
    operations = load_catalog_operations(catalog_path)
    errors = validate_review(operations, review)
    if schema_path is not None:
        schema = load_schema(schema_path, None)
        errors.extend(cross_check_schema(schema, operations, review))
    if errors:
        return {"errors": errors, "summary": None}
    return {"errors": [], "summary": summarize_review(operations, review)}


def propose_ledger(
    catalog_path: Path,
    implemented_path: Path,
    output_path: Path,
    snapshot_path: Path | None = None,
    p2_candidates_path: Path | None = None,
) -> dict:
    """Regenerate the ledger proposal from the committed catalog and mapping."""
    operations = load_catalog_operations(catalog_path)
    implemented = _read_json(implemented_path)
    candidates = load_p2_candidates(p2_candidates_path) if p2_candidates_path else None
    provenance = {
        "catalog": str(catalog_path),
        "implemented": str(implemented_path),
        "baseline_commit": implemented.get("baseline_commit"),
        "approved_lists": {
            "p1_approved_new_requests": 5,
            "p1_existing_compat": len(implemented.get("operations", {})),
            "p2_candidates": len(candidates or {}),
        },
    }
    if p2_candidates_path is not None:
        provenance["p2_candidates"] = str(p2_candidates_path)
    if snapshot_path is not None:
        snapshot = _read_json(snapshot_path)
        provenance["snapshot"] = str(snapshot_path)
        provenance["schema_commit"] = (snapshot.get("schema") or {}).get("commit")
    document = propose_review(
        operations,
        implemented,
        phase_overrides=phase_overrides_for(implemented, candidates),
        provenance=provenance,
    )
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    return document


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _reviewed_baseline_root() -> Path:
    return (REPOSITORY_ROOT / Path(*REVIEWED_BASELINE_DIRECTORY)).resolve()


def _assert_output_allowed(output: Path, sources: tuple[Path, ...]) -> None:
    """Refuse outputs that would overwrite reviewed evidence or an input."""
    resolved = output.resolve()
    baseline = _reviewed_baseline_root()
    if resolved == baseline or baseline in resolved.parents:
        raise ValueError(
            f"refusing to write inside the reviewed audit baseline directory {baseline}; "
            "choose an output path outside it"
        )
    for source in sources:
        if resolved == source.resolve():
            raise ValueError(f"refusing to overwrite an input schema: {output}")
    if output.is_dir():
        raise ValueError(f"output path is an existing directory: {output}")


def _atomic_write_text(output: Path, text: str) -> None:
    """Write atomically through a uniquely named, exclusively created temp file.

    The temp file is generated by ``mkstemp`` (``O_CREAT|O_EXCL``) in the target
    directory, so a pre-existing ``<output>.tmp`` file, directory or symlink is
    never opened, replaced or removed. Only the file created by this call is
    cleaned up when the write fails.
    """
    output.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=str(output.parent)
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(text)
        os.chmod(temporary, 0o644)
        os.replace(temporary, output)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def diff_schema_files(old_path: Path, new_path: Path, output_path: Path) -> dict:
    """Diff two local schemas and write a deterministic report. Read-only inputs.

    Refuses to write over any file in the reviewed audit baseline or over either
    input, and writes nothing at all when the inputs cannot be diffed.
    """
    old_path = Path(old_path)
    new_path = Path(new_path)
    output = Path(output_path)
    _assert_output_allowed(output, (old_path, new_path))

    old_schema = load_schema(old_path, None)
    new_schema = load_schema(new_path, None)
    delta = diff_schemas(old_schema, new_schema)
    report = {
        "format": DIFF_FORMAT,
        "old": {
            "path": str(old_path),
            "sha256": _file_sha256(old_path),
            "openapi": old_schema.get("openapi"),
        },
        "new": {
            "path": str(new_path),
            "sha256": _file_sha256(new_path),
            "openapi": new_schema.get("openapi"),
        },
        "counts": {
            "added": len(delta["added"]),
            "removed": len(delta["removed"]),
            "changed": len(delta["changed"]),
        },
        **delta,
    }
    _atomic_write_text(output, json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


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

    check_parser = subparsers.add_parser(
        "check", help="validate the review ledger against the catalog (read-only)"
    )
    check_parser.add_argument("--review", type=Path, required=True)
    check_parser.add_argument("--catalog", type=Path, required=True)
    check_parser.add_argument(
        "--schema", type=Path, default=None, help="optional pinned schema for a deep cross-check"
    )

    propose_parser = subparsers.add_parser(
        "propose", help="regenerate the review ledger proposal from the catalog"
    )
    propose_parser.add_argument("--catalog", type=Path, required=True)
    propose_parser.add_argument("--implemented", type=Path, required=True)
    propose_parser.add_argument("--output", type=Path, required=True)
    propose_parser.add_argument("--snapshot", type=Path, default=None)
    propose_parser.add_argument(
        "--p2-candidates",
        type=Path,
        default=None,
        help="approved P2 candidate list (docs/cloudflare-audit/p2-candidate-endpoints.csv)",
    )

    diff_parser = subparsers.add_parser(
        "diff", help="compare two local schemas and write a review report (read-only)"
    )
    diff_parser.add_argument("--old", type=Path, required=True)
    diff_parser.add_argument("--new", type=Path, required=True)
    diff_parser.add_argument("--output", type=Path, required=True)

    args = parser.parse_args(argv)

    try:
        if args.command == "generate":
            catalog = generate(args.schema, args.manifest, args.implemented, args.output)
            print(json.dumps(catalog["counts"], ensure_ascii=False, indent=2))
        elif args.command == "check":
            result = check_ledger(args.review, args.catalog, args.schema)
            for error in result["errors"]:
                print(f"error: {error}", file=sys.stderr)
            if result["summary"] is None:
                print(
                    f"summary withheld: ledger has {len(result['errors'])} structural error(s)",
                    file=sys.stderr,
                )
                return 1
            print(json.dumps(result["summary"], ensure_ascii=False, indent=2, default=dict))
        elif args.command == "diff":
            report = diff_schema_files(args.old, args.new, args.output)
            print(json.dumps(report["counts"], ensure_ascii=False, indent=2))
        else:
            document = propose_ledger(
                args.catalog, args.implemented, args.output, args.snapshot, args.p2_candidates
            )
            print(json.dumps(summarize_review(
                load_catalog_operations(args.catalog), document
            ), ensure_ascii=False, indent=2, default=dict))
    except (ValueError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
