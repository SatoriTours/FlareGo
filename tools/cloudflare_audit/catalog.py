"""Deterministic CSV/statistics generation for the Cloudflare coverage ledger.

``build_catalog`` combines the pinned schema with the hand-reviewed
``implemented.json`` mapping. Nothing here infers support from source patterns:
an operation is ``implemented_subset`` only when the reviewed mapping names its
exact operationId, method and path.
"""
from __future__ import annotations

import codecs
import csv
import io
import json
from typing import Any

from .schema import enumerate_operations

IMPLEMENTED_STATUS = "implemented_subset"
NOT_IMPLEMENTED_STATUS = "not_implemented"
UNMAPPED_NOTE = "仓库真实适配器未接入；官方可用性与验收范围待逐项核对"
ISOLATED_TEST_EVIDENCE = "isolated_tests_only"
NO_TEST_EVIDENCE = "none"
REVIEW_PENDING = "pending"

ENDPOINT_FIELDS = (
    "method",
    "path",
    "operation_id",
    "tags",
    "summary",
    "flarego_status",
    "coverage_note",
    "test_evidence",
    "deprecated_boolean",
    "deprecation_signals",
    "official_availability",
    "official_stability",
    "token_permissions_raw",
    "security_raw",
    "plan_availability_raw",
    "forge_hidden",
    "fern_ignore",
    "request_content_types",
    "response_content_types",
    "implementation_review",
    "availability_review",
)
PRODUCT_FIELDS = (
    "tag",
    "operations",
    "implemented_subset",
    "not_implemented",
    "deprecated_boolean",
    "any_deprecation_signal",
)
PERMISSION_FIELDS = ("x-api-token-group", "x-cfPermissionsRequired")
CSV_LINE_TERMINATOR = "\r\n"


def deprecation_signals(operation: dict) -> list[str]:
    """Raw deprecation evidence, kept separate from the boolean flag."""
    signals: list[str] = []
    if operation.get("deprecated"):
        signals.append("deprecated=true")
    if operation.get("x-fern-availability") == "deprecated":
        signals.append("x-fern-availability=deprecated")
    cf_deprecation = operation.get("x-cfDeprecation") or {}
    if isinstance(cf_deprecation, dict) and cf_deprecation.get("display"):
        signals.append("x-cfDeprecation.display=true")
    return signals


def _mapping_entries(implemented: dict) -> dict[str, dict]:
    if not isinstance(implemented, dict):
        raise ValueError("implemented mapping must be a JSON object")
    entries = implemented.get("operations")
    if not isinstance(entries, dict):
        raise ValueError("implemented mapping must contain an 'operations' object")
    for operation_id, entry in entries.items():
        if not isinstance(operation_id, str) or not operation_id:
            raise ValueError(f"implemented mapping has an invalid operationId key {operation_id!r}")
        if not isinstance(entry, dict):
            raise ValueError(f"implemented mapping entry {operation_id!r} must be an object")
        for field in ("method", "path"):
            if not isinstance(entry.get(field), str) or not entry[field]:
                raise ValueError(
                    f"implemented mapping entry {operation_id!r} must declare its reviewed {field}"
                )
    return entries


def _check_mapping(operations: list[dict], entries: dict[str, dict]) -> None:
    by_id = {operation["operation_id"]: operation for operation in operations}
    unknown = sorted(set(entries) - set(by_id))
    if unknown:
        raise ValueError(
            "implemented mapping references operationId(s) absent from the pinned schema: "
            + ", ".join(unknown)
        )
    for operation_id, entry in entries.items():
        operation = by_id[operation_id]
        if entry["method"].upper() != operation["method"] or entry["path"] != operation["path"]:
            raise ValueError(
                f"implemented mapping {operation_id!r} declares {entry['method'].upper()} "
                f"{entry['path']} but the pinned schema has {operation['method']} {operation['path']}"
            )


def _endpoint_row(schema: dict, operation: dict, entry: dict | None) -> dict:
    metadata = operation["metadata"]
    permissions = {name: metadata[name] for name in PERMISSION_FIELDS if name in metadata}
    signals = deprecation_signals(metadata)
    if entry is None:
        status = NOT_IMPLEMENTED_STATUS
        coverage_note = UNMAPPED_NOTE
        test_evidence = NO_TEST_EVIDENCE
    else:
        status = IMPLEMENTED_STATUS
        coverage_note = entry.get("scope", UNMAPPED_NOTE)
        test_evidence = entry.get("test_evidence", ISOLATED_TEST_EVIDENCE)
    return {
        "method": operation["method"],
        "path": operation["path"],
        "operation_id": operation["operation_id"],
        "tags": " | ".join(operation["tags"]),
        "summary": metadata.get("summary", ""),
        "flarego_status": status,
        "coverage_note": coverage_note,
        "test_evidence": test_evidence,
        "deprecated_boolean": str(bool(metadata.get("deprecated"))).lower(),
        "deprecation_signals": " | ".join(signals),
        "official_availability": metadata.get("x-fern-availability", "unspecified"),
        "official_stability": metadata.get("x-stability", "unspecified"),
        "token_permissions_raw": json.dumps(permissions, ensure_ascii=False, sort_keys=True),
        "security_raw": json.dumps(
            metadata.get("security", schema.get("security", [])), ensure_ascii=False, sort_keys=True
        ),
        "plan_availability_raw": json.dumps(metadata.get("x-cfPlanAvailability", {}), sort_keys=True),
        "forge_hidden": json.dumps(metadata.get("x-forge-hidden")),
        "fern_ignore": json.dumps(metadata.get("x-fern-ignore")),
        "request_content_types": " | ".join(operation["request_content_types"]),
        "response_content_types": " | ".join(operation["response_content_types"]),
        "implementation_review": REVIEW_PENDING,
        "availability_review": REVIEW_PENDING,
    }


def _product_rows(rows: list[dict]) -> list[dict]:
    tags = sorted({tag for row in rows for tag in row["tags"].split(" | ") if tag})
    product_rows = []
    for tag in tags:
        group = [row for row in rows if tag in row["tags"].split(" | ")]
        implemented = sum(row["flarego_status"] == IMPLEMENTED_STATUS for row in group)
        product_rows.append(
            {
                "tag": tag,
                "operations": len(group),
                "implemented_subset": implemented,
                "not_implemented": len(group) - implemented,
                "deprecated_boolean": sum(row["deprecated_boolean"] == "true" for row in group),
                "any_deprecation_signal": sum(bool(row["deprecation_signals"]) for row in group),
            }
        )
    return product_rows


def _counts(schema: dict, rows: list[dict], product_rows: list[dict]) -> dict:
    implemented = sum(row["flarego_status"] == IMPLEMENTED_STATUS for row in rows)
    return {
        "path_templates": len(schema["paths"]),
        "operations": len(rows),
        "distinct_tags": len(product_rows),
        "tag_memberships": sum(row["operations"] for row in product_rows),
        "implemented_subset": implemented,
        "not_implemented": len(rows) - implemented,
        "explicit_deprecated": sum(row["deprecated_boolean"] == "true" for row in rows),
        "any_deprecation_signal": sum(bool(row["deprecation_signals"]) for row in rows),
        "additional_deprecation_signals_without_boolean": sum(
            bool(row["deprecation_signals"]) and row["deprecated_boolean"] != "true" for row in rows
        ),
    }


def build_catalog(schema: dict, implemented: dict) -> dict:
    """Return ``endpoints``, ``products`` and ``counts`` for the pinned schema.

    Sorting is fixed by the METHOD+path identity (path, then method), so the
    result never depends on dict insertion order.
    """
    entries = _mapping_entries(implemented)
    operations = enumerate_operations(schema)
    _check_mapping(operations, entries)
    rows = [_endpoint_row(schema, operation, entries.get(operation["operation_id"])) for operation in operations]
    product_rows = _product_rows(rows)
    return {
        "endpoints": rows,
        "products": product_rows,
        "counts": _counts(schema, rows, product_rows),
    }


def render_csv(field_names: tuple[str, ...], rows: list[dict]) -> bytes:
    """Render rows with a fixed byte format: UTF-8 BOM and CRLF terminators."""
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=list(field_names), lineterminator=CSV_LINE_TERMINATOR)
    writer.writeheader()
    writer.writerows(rows)
    return codecs.BOM_UTF8 + stream.getvalue().encode("utf-8")


def render_catalog_files(catalog: dict) -> dict[str, bytes]:
    """Return the catalog as deterministic ``{filename: bytes}`` artifacts."""
    return {
        "endpoints.csv": render_csv(ENDPOINT_FIELDS, catalog["endpoints"]),
        "products.csv": render_csv(PRODUCT_FIELDS, catalog["products"]),
    }


def pinned_count_problems(catalog: dict, manifest: dict, schema: dict) -> list[str]:
    """Compare generated counts with the reviewed baseline in the manifest."""
    problems: list[str] = []
    schema_meta = manifest.get("schema") or {}
    counts = catalog["counts"]
    for name, expected in (("paths", counts["path_templates"]), ("operations", counts["operations"])):
        if schema_meta.get(name) != expected:
            problems.append(f"manifest schema.{name}={schema_meta.get(name)!r} != generated {expected}")
    if schema_meta.get("openapi_version") != schema.get("openapi"):
        problems.append(
            f"manifest schema.openapi_version={schema_meta.get('openapi_version')!r} "
            f"!= generated {schema.get('openapi')!r}"
        )
    pinned = manifest.get("counts")
    if not isinstance(pinned, dict):
        return problems + ["manifest has no pinned 'counts' object"]
    for name, generated in counts.items():
        if name not in pinned:
            problems.append(f"manifest counts is missing pinned {name}")
        elif pinned[name] != generated:
            problems.append(f"manifest counts.{name}={pinned[name]!r} != generated {generated}")
    return problems


def check_baseline_alignment(manifest: dict, implemented: dict) -> list[str]:
    """The reviewed mapping must target the same FlareGo baseline as the manifest."""
    problems: list[str] = []
    manifest_baseline = manifest.get("flarego_baseline_commit")
    mapping_baseline = implemented.get("baseline_commit")
    if not mapping_baseline:
        problems.append("implemented mapping does not record baseline_commit")
    elif manifest_baseline != mapping_baseline:
        problems.append(
            f"implemented baseline_commit={mapping_baseline!r} != manifest "
            f"flarego_baseline_commit={manifest_baseline!r}"
        )
    return problems


def manifest_with_counts(manifest: dict, counts: dict) -> str:
    """Return the manifest text with regenerated counts, keeping section order."""
    updated: dict[str, Any] = dict(manifest)
    updated["counts"] = counts
    return json.dumps(updated, ensure_ascii=False, indent=2) + "\n"
