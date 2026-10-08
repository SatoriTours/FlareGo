"""Offline tests for the independent protocol ledger and acceptance gates.

Unit cases use small synthetic ledgers. The real-artifact cases only read the
committed audit files and the pinned local copies when present. No network, no
credentials, no Cloudflare requests.
"""
import copy
import csv
import hashlib
import io
import json
import pathlib
import re
import unittest
from unittest import mock

from tools.cloudflare_audit.review import (
    P1_APPROVED_NEW_REQUESTS,
    S3_SOURCE_INVENTORY,
    s3_feature_digest,
    validate_protocols,
)

REPO = pathlib.Path(__file__).resolve().parents[3]
AUDIT = REPO / "docs/cloudflare-audit"
PROTOCOLS = AUDIT / "protocols.json"
S3_CSV = AUDIT / "r2-s3-operations.csv"
IMPLEMENTED = AUDIT / "implemented.json"
P2_CANDIDATES = AUDIT / "p2-candidate-endpoints.csv"
SNAPSHOT = AUDIT / "snapshot.json"
ENVIRONMENTS_DOC = AUDIT / "acceptance-environments.md"
S3_DOC = REPO / ".superpowers/cloudflare/flarego-cf-s3-compat.md"
PINNED_SCHEMA = REPO / ".superpowers/cloudflare/openapi.json"
S3_DOC_COMMIT = "e3a9cf758c28273178d56918399068c6d2eb8a1d"
S3_DOC_SHA256 = "a3a9f5b86efc022ae7cbe813e49b682f0dbb3dfc7ff1eac5498d3af62c9e75d8"
K2_HOST = "https://{stream_id}.k2.cloudflarestorage.com"


def parse_pinned_s3_table() -> dict:
    """Re-derive expected feature subsets directly from the pinned public table.

    A marked item is a group header when its text ends with ':' and a feature item
    otherwise; the variation selector and indentation are normalized. This mirrors
    the document semantics instead of reusing the generator's output.
    """
    if not S3_DOC.exists():
        return {}
    text = S3_DOC.read_text(encoding="utf-8")
    table: dict[str, dict] = {}
    for mark, name, _url, cell in re.findall(
        r"^\|\s*([✅❌])\s*\[([A-Za-z0-9]+)\]\(([^)]+)\)\s*\|\s*(.*?)\s*\|$", text, re.M
    ):
        supported: list[str] = []
        unsupported: list[str] = []
        experimental: list[str] = []
        groups: list[str] = []
        for chunk in cell.split("<br/>"):
            token = chunk.replace("\ufe0f", "").replace("\u2003", "").strip()
            if not token or token[0] not in "✅❌🚧":
                continue
            body = token[1:].strip()
            if not body:
                continue
            if body.endswith(":"):
                groups.append(body)
                continue
            if token[0] == "✅":
                supported.append(body)
            elif token[0] == "❌":
                unsupported.append(body)
            else:
                experimental.append(body)
        table[name] = {
            "supported": supported,
            "unsupported": unsupported,
            "experimental": experimental,
            "groups": groups,
        }
    return table

S3_SOURCE = {
    "name": "s3-compat",
    "commit": "e3a9cf758c28273178d56918399068c6d2eb8a1d",
    "sha256": "a3a9f5b86efc022ae7cbe813e49b682f0dbb3dfc7ff1eac5498d3af62c9e75d8",
    "url": "https://raw.githubusercontent.com/cloudflare/cloudflare-docs/e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/docs/r2/api/s3/api.mdx",
}
GRAPHQL_SOURCE = {
    "name": "graphql-overview",
    "commit": "e3a9cf758c28273178d56918399068c6d2eb8a1d",
    "sha256": "d75107f225c0f742efc888d7de34936ad0355d7e967aa4770fba52e550a009c0",
    "url": "https://raw.githubusercontent.com/cloudflare/cloudflare-docs/e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/docs/analytics/graphql-api/index.mdx",
}


S3_FIXTURE_FEATURES = {
    "ListBuckets": {
        "supported": "",
        "unsupported": "",
        "experimental": "",
        "status": "not_listed_in_pinned_table",
    },
    "GetBucketAcl": {
        "supported": "",
        "unsupported": "x-amz-expected-bucket-owner",
        "experimental": "",
        "status": "listed",
    },
}


def s3_row(operation: str, supported: bool = True, **overrides) -> dict:
    """Synthetic row whose feature vectors match the frozen source inventory."""
    features = S3_FIXTURE_FEATURES[operation]
    row = {
        "operation": operation,
        "level": "bucket",
        "cloudflare_supports_operation": "true" if supported else "false",
        "flarego_status": "not_implemented" if supported else "provider_unsupported",
        "official_compatibility_page": "https://developers.cloudflare.com/r2/api/s3/api/",
        "aws_operation_reference": f"https://docs.aws.amazon.com/AmazonS3/latest/API/API_{operation}.html",
        "feature_limitations": "参见固定版本兼容表逐字段检查",
        "vendor_supported_features": features["supported"],
        "vendor_unsupported_features": features["unsupported"],
        "vendor_experimental_features": features["experimental"],
        "feature_groups_reviewed": "Bucket Owner:",
        "feature_table_status": features["status"],
        "source_name": "s3-compat",
        "source_commit": S3_SOURCE["commit"],
        "source_sha256": S3_SOURCE["sha256"],
        "source_quote_ref": f"s3-compat table row {operation} Feature column",
        "verification_status": "awaiting_authorized_environment" if supported else "provider_unsupported",
        "verification_evidence": "",
        "acceptance_target_level": "L2" if supported else "n/a",
        "owner_role": "日常工作",
        "phase": "P5",
        "test_prerequisites": (
            "authorized_cloudflare_account|isolated_test_scope" if supported else ""
        ),
        "flarego_implemented": "false",
        "verified": "false",
    }
    assert s3_feature_digest(row) == S3_SOURCE_INVENTORY[operation][1], operation
    row.update(overrides)
    return row



def admission_entry(operation_id: str, method: str = "GET", path: str = "/zones") -> dict:
    read = method in ("GET", "HEAD")
    return {
        "operation_id": operation_id,
        "method": method,
        "path": path,
        "source": "docs/cloudflare-audit/p2-candidate-endpoints.csv",
        "risk": "low" if read else "unknown",
        "risk_review": "pending_manual_review",
        "confirmation_required": not read,
        "verification_boundary": (
            "authenticated_account_acceptance_required"
            if read
            else "authenticated_account_acceptance_and_manual_write_risk_review"
        ),
        "impact_boundary": "synthetic fixture impact boundary",
        "confirmation_boundary": "synthetic fixture confirmation boundary",
        "reconciliation_boundary": "synthetic fixture reconciliation boundary",
        "auto_replay": False,
        "readback_required": not read,
        "proposed": True,
    }


def environment_entry(**overrides) -> dict:
    entry = {
        "environment_id": "registrar_sandbox",
        "label": "registrar sandbox",
        "plan": "sandbox",
        "allowed_action_categories": ["read_only_search", "sandbox_registration"],
        "owner_role": "日常工作",
        "permission_categories": ["registrar_sandbox_api"],
        "cleanup_responsibility": "owner_role",
        "status": "awaiting_authorized_environment",
        "authorization_reference": "",
        "blockers": [{"code": "sandbox_access_not_provided", "detail": "no authorized sandbox environment"}],
        "notes": "real domain registration and payment are never ordinary automated tests",
    }
    entry.update(overrides)
    return entry


def protocols_fixture(s3_rows=None) -> dict:
    rows = s3_rows if s3_rows is not None else [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
    return {
        "format": "flarego.cloudflare-audit.protocols/1",
        "generated_from": {"review_state": "proposed_not_approved", "note": "synthetic fixture"},
        "denominators": [
            {"name": "rest", "unit": "operations", "count": 5, "independent": True,
             "source": "synthetic-fixture/rest"},
            {"name": "s3", "unit": "operations", "count": 2, "vendor_supported": 1,
             "provider_unsupported": 1, "independent": True, "source": "synthetic-fixture/s3"},
            {"name": "graphql", "unit": "datasets_and_fields", "count": None,
             "status": "awaiting_authorized_schema", "independent": True,
             "source": "synthetic-fixture/graphql"},
            {"name": "realtime", "unit": "sessions_and_streams", "count": None,
             "status": "awaiting_protocol_evidence", "independent": True,
             "source": "synthetic-fixture/realtime"},
        ],
        "s3": {
            "operations_file": "docs/cloudflare-audit/r2-s3-operations.csv",
            "source": S3_SOURCE,
            "documented": 2,
            "vendor_supported": 1,
            "provider_unsupported": 1,
            "verified": 0,
            "k2_stream_host": {
                "host": K2_HOST,
                "rest_denominator": True,
                "rest_operations": [
                    {"operation_id": "postK2Produce", "method": "POST", "path": "/produce", "rest_denominator": True}
                ],
                "acceptance": "awaiting_authorized_environment",
                "note": "separate stream host; API-base credentials must not be reused",
            },
            "limitations": ["region auto and aliases", "checksum FULL_OBJECT vs COMPOSITE"],
        },
        "graphql": {
            "endpoint": "https://api.cloudflare.com/client/v4/graphql",
            "transport": "POST JSON",
            "source": GRAPHQL_SOURCE,
            "review_status": "awaiting_authorized_schema",
            "schema_evidence": {"authorized_introspection": None, "public_overview_only": True},
            "datasets": [],
            "dataset_count": None,
            "field_count": None,
            "account_scope": "unknown",
            "account_scope_blockers": ["authorized_account_absent"],
            "plan_limits": "unverified",
            "plan_evidence": None,
            "capability_acceptance": [],
            "verified": 0,
            "families": [
                {
                    "family": "http_requests",
                    "need": "HTTP analytics datasets",
                    "owner_role": "日常工作",
                    "phase": "P4",
                    "target_level": "L2",
                    "evidence_status": "awaiting_authorized_schema",
                    "blockers": ["authorized_schema_capture_absent"],
                }
            ],
            "acceptance_prerequisites": [
                "data_and_errors_handling", "sampling_and_time_window", "plan_limits", "not_a_billing_measure",
            ],
        },
        "realtime": {
            "protocols": [
                {
                    "protocol_id": "worker_tail",
                    "connection_protocol": "websocket",
                    "session_operations": [
                        {"operation_id": "worker-script-list-workers", "method": "GET",
                         "path": "/accounts/{account_id}/workers/scripts"}
                    ],
                    "auth_host_policy": "api_base_host_with_scoped_token",
                    "lifecycle": "session_create_then_stream_and_cancel",
                    "owner_role": "日常工作",
                    "phase": "P3",
                    "target_level": "L2",
                    "enumeration_status": "session_endpoints_known_consumer_protocol_awaiting_evidence",
                    "blockers": ["stream_protocol_evidence_absent"],
                    "verified": 0,
                }
            ]
        },
        "environments": [
            environment_entry(),
            environment_entry(
                environment_id="graphql_schema_capture",
                label="authorized GraphQL schema capture",
                plan="analytics_access",
                allowed_action_categories=["schema_introspection_capture", "dataset_read_only_query"],
                permission_categories=["analytics_read"],
                blockers=[{"code": "authorized_schema_capture_absent", "detail": "no capture"}],
                notes="dataset and field universe stays unknown until an authorized capture exists",
            ),
        ],
        "p1_p2_admission": {
            "p1_new": [admission_entry("accounts-list-accounts", "GET", "/accounts")],
            "p1_existing_compat": [admission_entry("zones-get")],
            "p2_candidates": [admission_entry("zones-get"), admission_entry("zones-post", "POST")],
            "counts": {
                "p1_new": 1,
                "p1_existing_compat": 1,
                "p2_candidates": 2,
                "overlap": 1,
                "unique": 3,
                "sum_of_lists": 4,
            },
            "policy": {
                "runtime_allowlist": False,
                "note": "proposal only; lists overlap and must not be summed as a unique total",
            },
        },
        "blockers": [
            {"code": "authorized_account_absent", "detail": "no authorized account"},
            {"code": "authorized_schema_capture_absent", "detail": "no GraphQL introspection"},
            {"code": "stream_host_credentials_absent", "detail": "no stream-scoped credential"},
        ],
    }


def cleared_protocols() -> dict:
    """Synthetic fixture with a complete authorization and clearance record."""
    protocols = protocols_fixture()
    protocols["environments"][1].update(
        status="available",
        authorization_reference="synthetic-user-approval-2026-10-08",
        blockers=[],
    )
    for blocker in protocols["blockers"]:
        blocker["cleared_by"] = "synthetic-user-approval-2026-10-08"
        blocker["clearance_record"] = {
            "reference": "synthetic-user-approval-2026-10-08",
            "environment": "synthetic-fixture",
            "date": "2026-10-08",
        }
    return protocols


SCHEMA_MATRIX = [{"dataset": "synthetic_dataset", "fields": ["synthetic_field"]}]
QUERY_KINDS = ["timeseries"]


def authorized_capture(protocols: dict, *, with_capability: bool = True, environment="graphql_schema_capture") -> dict:
    """Fill in a self-consistent capture, schema matrix and (optionally) acceptance."""
    protocols["graphql"]["schema_evidence"]["authorized_introspection"] = {
        "environment": environment,
        "captured_at": "2026-10-08T00:00:00Z",
        "artifact_sha256": "a" * 64,
        "authorized_by": "synthetic-user-approval-2026-10-08",
        "datasets": copy.deepcopy(SCHEMA_MATRIX),
        "query_kinds": list(QUERY_KINDS),
    }
    protocols["graphql"].update(
        review_status="verified" if with_capability else "awaiting_query_acceptance",
        dataset_count=1,
        field_count=1,
        datasets=copy.deepcopy(SCHEMA_MATRIX),
        account_scope="account",
        account_scope_blockers=[],
        plan_limits="verified",
        plan_evidence={
            "environment": environment,
            "plan_label": "analytics_access",
            "authorized_by": "synthetic-user-approval-2026-10-08",
            "captured_at": "2026-10-08T00:00:00Z",
            "artifact_sha256": "c" * 64,
            "query_scope": {
                "dataset": "synthetic_dataset",
                "fields": ["synthetic_field"],
                "query_kinds": list(QUERY_KINDS),
            },
        },
    )
    protocols["denominators"][2].update(
        unit="fields",
        count=1,
        status="verified" if with_capability else "awaiting_query_acceptance",
    )
    if with_capability:
        protocols["graphql"]["capability_acceptance"] = [
            {
                "record_id": "synthetic-acceptance-1",
                "environment": environment,
                "captured_at": "2026-10-08T00:00:00Z",
                "query_scope": {
                    "dataset": "synthetic_dataset",
                    "fields": ["synthetic_field"],
                    "query_kinds": list(QUERY_KINDS),
                },
                "result": "verified",
                "evidence_artifact": "b" * 64,
            }
        ]
        protocols["graphql"]["verified"] = 1
    return protocols


def caller_context(
    operations: list[dict] | None = None,
    rest_operations: int | None = None,
    s3_subset=("ListBuckets", "GetBucketAcl"),
    source_refs: dict | None = None,
) -> dict:
    """Explicit caller-owned trusted context for sparse synthetic fixtures."""
    ops = copy.deepcopy(operations) if operations is not None else catalog_operations()
    return {
        "kind": "caller_owned",
        "operations": ops,
        "rest_operations": rest_operations if rest_operations is not None else len(ops),
        "s3_subset": list(s3_subset),
        "source_refs": source_refs
        or {
            "rest": "synthetic-fixture/rest",
            "s3": "synthetic-fixture/s3",
            "graphql": "synthetic-fixture/graphql",
            "realtime": "synthetic-fixture/realtime",
        },
    }


def catalog_operations() -> list[dict]:
    return [
        {"operation_id": "accounts-list-accounts", "method": "GET", "path": "/accounts"},
        {"operation_id": "zones-get", "method": "GET", "path": "/zones"},
        {"operation_id": "zones-post", "method": "POST", "path": "/zones"},
        {"operation_id": "postK2Produce", "method": "POST", "path": "/produce"},
        {
            "operation_id": "worker-script-list-workers",
            "method": "GET",
            "path": "/accounts/{account_id}/workers/scripts",
        },
    ]


class ProtocolValidationTest(unittest.TestCase):
    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.protocols = protocols_fixture(self.s3_rows)
        self.catalog = catalog_operations()

    def errors(self, protocols=None, rows=None, catalog="default"):
        catalog_operations = self.catalog if catalog == "default" else catalog
        return validate_protocols(protocols or self.protocols, rows or self.s3_rows, context=caller_context())

    def test_valid_synthetic_ledger_passes(self):
        self.assertEqual([], self.errors())

    def test_provider_unsupported_s3_is_not_an_app_target(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"].update(documented=2, vendor_supported=2, provider_unsupported=0)
        protocols["denominators"][1].update(count=2, vendor_supported=2, provider_unsupported=0)
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("provider_unsupported" in error for error in errors), errors)

    def test_graphql_without_schema_evidence_cannot_be_verified(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["graphql"]["review_status"] = "verified"
        protocols["graphql"]["verified"] = 3
        protocols["graphql"]["dataset_count"] = 12
        protocols["graphql"]["field_count"] = 400
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("schema_evidence" in error for error in errors), errors)
        self.assertTrue(any("awaiting_authorized_schema" in error or "dataset_count" in error for error in errors), errors)

    def test_graphql_doc_url_is_not_authorized_schema_evidence(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["graphql"]["review_status"] = "verified"
        protocols["graphql"]["schema_evidence"] = {"url": GRAPHQL_SOURCE["url"]}
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("schema_evidence" in error for error in errors), errors)

    def test_rest_and_s3_cannot_be_summed_into_a_total_coverage(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["total_coverage"] = 3732
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("total_coverage" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"].append(
            {"name": "combined_total", "unit": "operations", "count": 3732, "independent": True, "source": "x"}
        )
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("denominator.combined_name" in error for error in errors), errors)

    def test_denominator_counts_must_match_the_s3_rows(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["documented"] = 73
        protocols["s3"]["vendor_supported"] = 26
        protocols["s3"]["provider_unsupported"] = 47
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("documented" in error for error in errors), errors)

    def test_duplicate_s3_operations_are_rejected(self):
        rows = [s3_row("ListBuckets"), s3_row("ListBuckets", supported=False)]
        errors = validate_protocols(self.protocols, rows, context=caller_context())
        self.assertTrue(any("s3.duplicate_identity" in error for error in errors), errors)

    def test_unknown_s3_operation_reference_is_rejected(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["k2_stream_host"]["rest_operations"].append(
            {"operation_id": "not-a-real-operation", "method": "POST", "path": "/nope", "rest_denominator": True}
        )
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("catalog.unknown_operation" in error for error in errors), errors)

    def test_unknown_rest_operation_reference_is_rejected(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["realtime"]["protocols"][0]["session_operations"][0]["operation_id"] = "ghost-session"
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("catalog.unknown_operation" in error for error in errors), errors)

    def test_method_path_binding_of_referenced_operations_must_match(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["realtime"]["protocols"][0]["session_operations"][0]["method"] = "POST"
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("METHOD+path" in error for error in errors), errors)

    def test_invalid_csv_boolean_is_rejected(self):
        for value in ("yes", "1", "", "TRUE", "False"):
            rows = [s3_row("ListBuckets", cloudflare_supports_operation=value), s3_row("GetBucketAcl", supported=False)]
            errors = validate_protocols(self.protocols, rows, context=caller_context())
            self.assertTrue(
                any("cloudflare_supports_operation" in error for error in errors), (value, errors)
            )

    def test_false_string_is_not_treated_as_true(self):
        rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["vendor_supported"] = 1
        protocols["s3"]["provider_unsupported"] = 1
        protocols["denominators"][1].update(vendor_supported=1, provider_unsupported=1)
        self.assertEqual([], validate_protocols(protocols, rows, context=caller_context()))
        self.assertEqual("provider_unsupported", rows[1]["flarego_status"])

    def test_malformed_nested_types_do_not_crash(self):
        broken = copy.deepcopy(self.protocols)
        broken["denominators"] = "not-a-list"
        broken["s3"] = ["not-a-dict"]
        broken["graphql"] = "not-a-dict"
        broken["realtime"] = {"protocols": "not-a-list"}
        broken["environments"] = ["not-a-dict"]
        broken["p1_p2_admission"] = "not-a-dict"
        broken["blockers"] = [None, "text"]
        errors = validate_protocols(broken, self.s3_rows, context=caller_context())
        self.assertTrue(errors)
        self.assertTrue(all(isinstance(error, str) for error in errors))
        for value in (None, [], "text", 42):
            errors = validate_protocols(value, self.s3_rows, context=caller_context())
            self.assertTrue(errors, value)
            errors = validate_protocols(self.protocols, value, context=caller_context())
            self.assertTrue(errors, value)

    def test_empty_or_opaque_evidence_does_not_grant_verification(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["verified"] = 1
        rows = [s3_row("ListBuckets", verified="true", verification_evidence=""), s3_row("GetBucketAcl", supported=False)]
        errors = validate_protocols(protocols, rows, context=caller_context())
        self.assertTrue(any("verification_evidence" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["graphql"]["review_status"] = "verified"
        protocols["graphql"]["schema_evidence"] = {"authorized_introspection": "yes"}
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("authorized_introspection" in error for error in errors), errors)

    def test_sensitive_environment_fields_are_rejected(self):
        cases = (
            {"token": "abc"},
            {"api_token": "abc"},
            {"password": "abc"},
            {"credential_path": "/home/user/.config/x"},
            {"private_key": "-----BEGIN"},
            {"account_id": "023e105f4ecef8ad9ca31a8372d0c353"},
            {"nested": {"secret": "x"}},
            {"label": "Bearer abcdefghijklmnop"},
            {"notes": "/home/user/.cloudflare/credentials"},
            {"plan": "023e105f4ecef8ad9ca31a8372d0c353"},
        )
        for extra in cases:
            protocols = copy.deepcopy(self.protocols)
            protocols["environments"][0].update(extra)
            errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
            self.assertTrue(errors, extra)

    def test_environment_registry_requires_status_and_cleanup(self):
        for field in ("status", "cleanup_responsibility", "owner_role", "allowed_action_categories", "permission_categories"):
            protocols = copy.deepcopy(self.protocols)
            del protocols["environments"][0][field]
            errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
            self.assertTrue(any(field in error for error in errors), (field, errors))

    def test_realtime_requires_connection_lifecycle_and_owner(self):
        for field in ("connection_protocol", "auth_host_policy", "lifecycle", "owner_role", "phase", "target_level", "enumeration_status"):
            protocols = copy.deepcopy(self.protocols)
            del protocols["realtime"]["protocols"][0][field]
            errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
            self.assertTrue(any(field in error for error in errors), (field, errors))

    def test_k2_stream_operations_are_not_extra_non_rest_counts(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"].append(
            {
                "name": "realtime",
                "unit": "sessions_and_streams",
                "count": 8,
                "independent": True,
                "source": "k2",
                "operation_ids": ["postK2Produce"],
            }
        )
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("denominator.rest_recount" in error for error in errors), errors)

    def test_p1_p2_counts_must_match_lists_and_overlap(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["counts"]["unique"] = 4
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("unique" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["counts"]["overlap"] = 0
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("overlap" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["counts"]["sum_of_lists"] = 3
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("sum_of_lists" in error for error in errors), errors)

    def test_admission_entries_require_risk_and_verification_boundary(self):
        for field in ("risk", "confirmation_required", "verification_boundary", "source"):
            protocols = copy.deepcopy(self.protocols)
            del protocols["p1_p2_admission"]["p1_new"][0][field]
            errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
            self.assertTrue(any(field in error for error in errors), (field, errors))

    def test_admission_must_not_claim_runtime_allowlist(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["policy"]["runtime_allowlist"] = True
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("runtime_allowlist" in error for error in errors), errors)

    def test_duplicate_blocker_codes_are_rejected(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["blockers"].append({"code": "authorized_account_absent", "detail": "duplicate"})
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("duplicate" in error for error in errors), errors)

    def test_validation_is_read_only(self):
        protocols = copy.deepcopy(self.protocols)
        rows = copy.deepcopy(self.s3_rows)
        snapshot = json.dumps([protocols, rows], sort_keys=True)
        validate_protocols(protocols, rows, context=caller_context())
        self.assertEqual(snapshot, json.dumps([protocols, rows], sort_keys=True))


def summarize_review_verified(protocols: dict) -> int:
    """Literal expectation helper: only validated records count as capability."""
    records = protocols.get("graphql", {}).get("capability_acceptance") or []
    return len([record for record in records if isinstance(record, dict) and record.get("result") == "verified"])


class QualificationGateTest(unittest.TestCase):
    """I1: qualification needs structured, linked and authorized evidence."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()

    def errors(self, protocols):
        return validate_protocols(protocols, self.s3_rows, context=caller_context())

    def capture(self, **overrides):
        protocols = cleared_protocols()
        authorized_capture(protocols)
        if overrides:
            protocols["graphql"]["schema_evidence"]["authorized_introspection"].update(overrides)
        return protocols

    def test_complete_synthetic_authorization_positive(self):
        protocols = self.capture()
        self.assertEqual([], self.errors(protocols))
        self.assertEqual(1, protocols["graphql"]["verified"])

    def test_capture_fields_must_have_structured_types(self):
        for overrides in (
            {"environment": True},
            {"captured_at": ["synthetic-time"]},
            {"captured_at": "not-a-time"},
            {"artifact_sha256": "opaque"},
            {"artifact_sha256": ["a" * 64]},
            {"authorized_by": None},
        ):
            errors = self.errors(self.capture(**overrides))
            self.assertTrue(errors, overrides)

    def test_capture_must_link_a_unique_available_environment(self):
        for environment in ("ghost", "registrar_sandbox", ""):
            errors = self.errors(self.capture(environment=environment))
            self.assertTrue(any("environment" in error for error in errors), (environment, errors))
        protocols = protocols_fixture()
        authorized_capture(protocols, environment="graphql_schema_capture")
        errors = self.errors(protocols)
        self.assertTrue(errors, "awaiting or blocked environment must not authorize")

    def test_verified_count_must_be_a_non_negative_integer(self):
        for value in (-3, True, "1", 1.5, None):
            protocols = self.capture()
            protocols["graphql"]["verified"] = value
            errors = self.errors(protocols)
            self.assertTrue(any("verified" in error for error in errors), (value, errors))

    def test_schema_capture_alone_is_not_capability_acceptance(self):
        protocols = cleared_protocols()
        authorized_capture(protocols, with_capability=False)
        protocols["graphql"]["verified"] = 1
        errors = self.errors(protocols)
        self.assertTrue(any("qualification.verified_mismatch" in error for error in errors), errors)
        self.assertEqual(0, summarize_review_verified(protocols))

    def test_capability_record_needs_query_scope_and_artifact(self):
        for field in ("record_id", "environment", "captured_at", "query_scope", "result", "evidence_artifact"):
            protocols = self.capture()
            del protocols["graphql"]["capability_acceptance"][0][field]
            errors = self.errors(protocols)
            self.assertTrue(any(field in error for error in errors), (field, errors))

    def test_active_global_blocker_prevents_any_verified_count(self):
        protocols = protocols_fixture()
        protocols["environments"][1].update(
            status="available", authorization_reference="synthetic-user-approval"
        )
        authorized_capture(protocols)
        errors = self.errors(protocols)
        self.assertTrue(any("blocker" in error for error in errors), errors)

    def test_required_global_blockers_must_be_present_or_cleared(self):
        protocols = protocols_fixture()
        protocols["blockers"] = []
        errors = self.errors(protocols)
        self.assertTrue(any("authorized_account_absent" in error for error in errors), errors)

    def test_environment_available_requires_an_authorization_reference(self):
        protocols = protocols_fixture()
        protocols["environments"][0].update(status="available", blockers=[])
        errors = self.errors(protocols)
        self.assertTrue(any("authorization_reference" in error for error in errors), errors)

    def test_awaiting_graphql_keeps_scope_and_plan_unverified(self):
        for field in ("account_scope", "plan_limits", "account_scope_blockers"):
            protocols = protocols_fixture()
            del protocols["graphql"][field]
            errors = self.errors(protocols)
            self.assertTrue(any(field in error for error in errors), (field, errors))
        protocols = protocols_fixture()
        protocols["graphql"]["account_scope"] = "023e105f4ecef8ad9ca31a8372d0c353"
        errors = self.errors(protocols)
        self.assertTrue(any("account_scope" in error for error in errors), errors)


class DenominatorContextTest(unittest.TestCase):
    """I2: independent denominators, pinned context and strict K2 template."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()
        self.protocols = protocols_fixture(self.s3_rows)

    def errors(self, protocols=None):
        return validate_protocols(protocols or self.protocols, self.s3_rows, self.catalog)

    def test_all_four_denominators_must_exist_exactly_once(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"] = []
        self.assertTrue(self.errors(protocols))
        for name in ("rest", "s3", "graphql", "realtime"):
            protocols = copy.deepcopy(self.protocols)
            protocols["denominators"] = [d for d in protocols["denominators"] if d["name"] != name]
            errors = self.errors(protocols)
            self.assertTrue(any(name in error for error in errors), (name, errors))
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"].append(dict(protocols["denominators"][0]))
        errors = self.errors(protocols)
        self.assertTrue(any("duplicate" in error for error in errors), errors)

    def test_denominator_counts_must_match_the_sections(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"][1].update(count=4, vendor_supported=4, provider_unsupported=0)
        errors = self.errors(protocols)
        self.assertTrue(any("s3" in error and "count" in error for error in errors), errors)

    def test_rest_denominator_must_match_the_pinned_context(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"][0]["count"] = 1
        errors = self.errors(protocols)
        self.assertTrue(any("rest" in error for error in errors), errors)

    def test_unknown_protocols_must_not_claim_known_counts(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"][2].update(count=123, status="verified")
        errors = self.errors(protocols)
        self.assertTrue(any("graphql" in error for error in errors), errors)

    def test_denominator_count_types_must_be_safe(self):
        for value in (True, -1, "73", 1.5):
            protocols = copy.deepcopy(self.protocols)
            protocols["denominators"][1]["count"] = value
            errors = self.errors(protocols)
            self.assertTrue(any("count" in error for error in errors), (value, errors))

    def test_nested_identity_values_fail_readably(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"][2]["operation_ids"] = [{}]
        errors = self.errors(protocols)
        self.assertTrue(errors)
        self.assertTrue(all(isinstance(error, str) for error in errors))

    def test_missing_catalog_context_is_not_silently_skipped(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["realtime"]["protocols"][0]["session_operations"][0].update(
            operation_id="ghost-session", method="POST", path="/ghost"
        )
        errors = validate_protocols(protocols, self.s3_rows)
        self.assertTrue(any("catalog.unknown_operation" in error for error in errors), errors)

    def test_k2_host_must_match_the_exact_template(self):
        for host in (
            "https://k2.cloudflarestorage.com.example.invalid",
            "https://{stream_id}.k2.cloudflarestorage.com/extra",
            "k2.cloudflarestorage.com",
        ):
            protocols = copy.deepcopy(self.protocols)
            protocols["s3"]["k2_stream_host"]["host"] = host
            errors = self.errors(protocols)
            self.assertTrue(any("k2" in error.lower() for error in errors), (host, errors))


class PrivacyGateTest(unittest.TestCase):
    """I3: environment metadata shape, normalized aliases and embedded secrets."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()
        self.protocols = protocols_fixture(self.s3_rows)

    def errors(self, **overrides):
        protocols = copy.deepcopy(self.protocols)
        protocols["environments"][0].update(overrides)
        return validate_protocols(protocols, self.s3_rows, context=caller_context())

    def test_notes_must_be_a_plain_string(self):
        for value in ({"apiToken": "synthetic"}, ["synthetic"], 42, None):
            errors = self.errors(notes=value)
            self.assertTrue(any("notes" in error for error in errors), (value, errors))

    def test_blocker_items_allow_only_code_and_detail(self):
        for blocker in (
            {"code": "x", "detail": "y", "apiToken": "synthetic"},
            {"code": "x", "detail": {"credentialFile": "synthetic"}},
            "synthetic",
            {"code": "x"},
        ):
            errors = self.errors(blockers=[blocker])
            self.assertTrue(errors, blocker)

    def test_normalized_sensitive_aliases_are_rejected(self):
        for key in ("apiToken", "api-token", "s3Secret", "S3_Secret", "privateKey", "paymentMethod", "Authorization"):
            errors = self.errors(**{key: "synthetic-placeholder"})
            self.assertTrue(errors, key)

    def test_embedded_credential_shapes_are_rejected(self):
        for value in (
            "credential file at /home/synthetic/.config/credentials",
            "token: Bearer abcdefghijklmnop",
            "card 4111 1111 1111 1111",
            "AKIAIOSFODNN7EXAMPLE",
        ):
            errors = self.errors(notes=value)
            self.assertTrue(errors, value)

    def test_legit_categories_and_public_templates_pass(self):
        errors = self.errors(
            notes="only the credential type is registered; endpoint https://api.cloudflare.com/client/v4 and stream host https://{stream_id}.k2.cloudflarestorage.com",
            permission_categories=["api_token_type_only"],
        )
        self.assertEqual([], errors)


class S3SourceBindingTest(unittest.TestCase):
    """I4: S3 source/feature/status binding must be strict."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.protocols = protocols_fixture(self.s3_rows)
        self.catalog = catalog_operations()

    def errors(self, rows):
        return validate_protocols(self.protocols, rows, context=caller_context())

    def test_source_fields_must_match_the_pinned_source(self):
        for overrides in (
            {"source_commit": "opaque"},
            {"source_sha256": "opaque"},
            {"source_quote_ref": "opaque"},
            {"source_name": "other-doc"},
        ):
            rows = copy.deepcopy(self.s3_rows)
            rows[0].update(overrides)
            errors = self.errors(rows)
            self.assertTrue(errors, overrides)

    def test_source_fields_must_be_plain_strings(self):
        for overrides in (
            {"source_commit": {"opaque": True}},
            {"source_sha256": [True]},
            {"source_quote_ref": ["x"]},
        ):
            rows = copy.deepcopy(self.s3_rows)
            rows[0].update(overrides)
            errors = self.errors(rows)
            self.assertTrue(errors, overrides)

    def test_feature_columns_must_be_plain_strings(self):
        for field in ("vendor_supported_features", "vendor_unsupported_features", "vendor_experimental_features"):
            rows = copy.deepcopy(self.s3_rows)
            rows[0][field] = None
            self.assertTrue(self.errors(rows), field)
            rows = copy.deepcopy(self.s3_rows)
            rows[0][field] = ["x-amz-storage-class"]
            self.assertTrue(self.errors(rows), field)

    def test_unknown_operation_identity_is_rejected(self):
        rows = copy.deepcopy(self.s3_rows)
        rows[0]["operation"] = "SyntheticUnrecognizedOperation"
        errors = self.errors(rows)
        self.assertTrue(errors)

    def test_vendor_supported_row_cannot_hide_as_provider_unsupported(self):
        rows = copy.deepcopy(self.s3_rows)
        rows[0]["verification_status"] = "provider_unsupported"
        errors = self.errors(rows)
        self.assertTrue(any("verification_status" in error for error in errors), errors)

    def test_feature_groups_only_hold_group_headers(self):
        for row in s3_rows_fixture_from_source():
            for group in [item for item in row["feature_groups_reviewed"].split(" | ") if item]:
                self.assertTrue(
                    group.strip().endswith(":") or group.strip() in ("Object Lifecycle",),
                    (row["operation"], group),
                )

    def test_synthetic_group_headers_must_end_with_a_colon(self):
        rows = copy.deepcopy(self.s3_rows)
        rows[0]["feature_groups_reviewed"] = "x-amz-storage-class"
        errors = self.errors(rows)
        self.assertTrue(any("feature_groups_reviewed" in error for error in errors), errors)


def s3_rows_fixture_from_source() -> list[dict]:
    if not S3_DOC.exists():
        return []
    return list(csv.DictReader(S3_CSV.open(encoding="utf-8-sig", newline="")))


class FactChainTest(unittest.TestCase):
    """R1: one validated fact chain from source through authorization to acceptance."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()

    def errors(self, protocols):
        return validate_protocols(protocols, self.s3_rows, context=caller_context())

    def positive(self, **capture_overrides):
        protocols = cleared_protocols()
        authorized_capture(protocols)
        if capture_overrides:
            protocols["graphql"]["schema_evidence"]["authorized_introspection"].update(capture_overrides)
        return protocols

    def test_complete_synthetic_authorization_positive(self):
        protocols = self.positive()
        self.assertEqual([], self.errors(protocols))
        self.assertEqual(1, protocols["graphql"]["verified"])

    def test_schema_capture_only_is_discovery_not_capability(self):
        protocols = cleared_protocols()
        authorized_capture(protocols, with_capability=False)
        self.assertEqual([], self.errors(protocols))
        self.assertEqual(0, protocols["graphql"]["verified"])

    def test_record_id_must_be_a_unique_non_empty_string(self):
        for value in (None, "", 42, True, ["synthetic-acceptance-1"]):
            protocols = self.positive()
            protocols["graphql"]["capability_acceptance"][0]["record_id"] = value
            errors = self.errors(protocols)
            self.assertTrue(any("record_id" in error for error in errors), (value, errors))
        protocols = self.positive()
        protocols["graphql"]["capability_acceptance"].append(
            copy.deepcopy(protocols["graphql"]["capability_acceptance"][0])
        )
        protocols["graphql"]["verified"] = 2
        errors = self.errors(protocols)
        self.assertTrue(any("duplicate" in error for error in errors), errors)

    def test_query_scope_must_reference_the_captured_matrix(self):
        cases = (
            {"dataset": True, "fields": [None], "query_kinds": 42},
            {"dataset": "ghost_dataset", "fields": ["synthetic_field"], "query_kinds": ["timeseries"]},
            {"dataset": "synthetic_dataset", "fields": ["ghost_field"], "query_kinds": ["timeseries"]},
            {"dataset": "synthetic_dataset", "fields": ["synthetic_field"], "query_kinds": ["ghost_kind"]},
            {"dataset": "synthetic_dataset", "fields": [], "query_kinds": ["timeseries"]},
        )
        for scope in cases:
            protocols = self.positive()
            protocols["graphql"]["capability_acceptance"][0]["query_scope"] = scope
            errors = self.errors(protocols)
            self.assertTrue(any("query_scope" in error for error in errors), (scope, errors))

    def test_schema_matrix_counts_must_match_the_listed_datasets(self):
        for overrides in (
            {"dataset_count": True},
            {"field_count": -2},
            {"datasets": "opaque"},
            {"dataset_count": 3},
            {"field_count": 5},
            {"datasets": [{"dataset": "synthetic_dataset", "fields": ["synthetic_field", "extra"]}]},
        ):
            protocols = self.positive()
            protocols["graphql"].update(overrides)
            errors = self.errors(protocols)
            self.assertTrue(errors, overrides)

    def test_capture_dates_must_be_real_utc_calendar_dates(self):
        for value in ("2026-99-99T99:99:99Z", "2026-02-30T00:00:00Z", "2026-10-08T00:00:00", "not-a-time"):
            errors = self.errors(self.positive(captured_at=value))
            self.assertTrue(any("captured_at" in error for error in errors), (value, errors))
        protocols = self.positive()
        protocols["graphql"]["capability_acceptance"][0]["captured_at"] = "2026-13-01T00:00:00Z"
        errors = self.errors(protocols)
        self.assertTrue(any("captured_at" in error for error in errors), errors)

    def test_capture_authority_must_link_the_authorized_environment(self):
        for value in ("different-unlinked-synthetic-approval", "", None):
            errors = self.errors(self.positive(authorized_by=value))
            self.assertTrue(any("authorized_by" in error for error in errors), (value, errors))

    def test_clearance_records_must_be_structured(self):
        for record in ({"opaque": True}, {}, {"reference": "synthetic"}, {"reference": "x", "date": "not-a-date"}):
            protocols = cleared_protocols()
            authorized_capture(protocols)
            for blocker in protocols["blockers"]:
                blocker["clearance_record"] = record
            errors = self.errors(protocols)
            self.assertTrue(any("clearance_record" in error for error in errors), (record, errors))

    def test_capture_matrix_must_be_a_typed_dataset_list(self):
        for value in ("opaque", [], [{"dataset": ""}], [{"dataset": "synthetic_dataset"}], [{"dataset": "a", "fields": []}]):
            errors = self.errors(self.positive(datasets=value))
            self.assertTrue(any("datasets" in error for error in errors), (value, errors))


class ContextAndDenominatorTest(unittest.TestCase):
    """R2: explicit context, non-REST denominators and unit definitions."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()
        self.protocols = protocols_fixture(self.s3_rows)

    def test_explicit_empty_context_fails_closed(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["realtime"]["protocols"][0]["session_operations"][0].update(
            operation_id="ghost-session", method="POST", path="/ghost"
        )
        errors = validate_protocols(protocols, self.s3_rows, context={})
        self.assertTrue(any("context.kind_invalid" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"][0]["count"] = 3732
        errors = validate_protocols(protocols, self.s3_rows, context={})
        self.assertTrue(any("context.kind_invalid" in error for error in errors), errors)

    def test_context_must_carry_operations_and_rest_count(self):
        for context in ({}, {"operations": []}, {"rest_operations": 3659}, {"operations": "x", "rest_operations": 1}):
            errors = validate_protocols(self.protocols, self.s3_rows, context=context)
            self.assertTrue(errors, context)

    def test_malformed_pinned_context_json_fails_readably(self):
        from unittest import mock

        original = pathlib.Path.read_text

        def fake_read(path, *args, **kwargs):
            if pathlib.Path(path).name == "snapshot.json":
                return "[]"
            return original(path, *args, **kwargs)

        with mock.patch.object(pathlib.Path, "read_text", fake_read):
            errors = validate_protocols(self.protocols, self.s3_rows)
        self.assertTrue(errors)
        self.assertTrue(all(isinstance(error, str) for error in errors))

    def test_s3_denominator_must_not_recount_rest_operations(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"][1]["operation_ids"] = ["zones-get"]
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("denominator.rest_recount" in error for error in errors), errors)

    def test_graphql_denominator_count_must_match_a_single_unit(self):
        protocols = cleared_protocols()
        authorized_capture(protocols)
        protocols["denominators"][2].update(count=3732, status="verified")
        errors = validate_protocols(protocols, self.s3_rows, context=caller_context())
        self.assertTrue(any("graphql" in error for error in errors), errors)
        protocols = cleared_protocols()
        authorized_capture(protocols)
        protocols["denominators"][2].update(count=1, status="verified", unit="datasets")
        self.assertEqual([], validate_protocols(protocols, self.s3_rows, context=caller_context()))


class PrivacyHardeningTest(unittest.TestCase):
    """R3: no credential material in the registry and no payload echo in errors."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()
        self.protocols = protocols_fixture(self.s3_rows)

    def errors(self, **overrides):
        protocols = copy.deepcopy(self.protocols)
        protocols["environments"][0].update(overrides)
        return validate_protocols(protocols, self.s3_rows, context=caller_context())

    def test_private_key_material_and_config_paths_are_rejected(self):
        for value in (
            "-----BEGIN PRIVATE KEY-----\nsynthetic-placeholder\n-----END PRIVATE KEY-----",
            "credential file at /home/synthetic/.aws/config",
            "see /home/synthetic/.ssh/id_ed25519",
            "token in ~/.config/gcloud/credentials.db",
        ):
            errors = self.errors(notes=value)
            self.assertTrue(errors, value)

    def test_error_messages_do_not_echo_untrusted_payloads(self):
        marker = "synthetic-marker-payload"
        errors = self.errors(label={"privateKey": marker})
        self.assertTrue(errors)
        self.assertTrue(all(marker not in error for error in errors), errors)

    def test_public_templates_and_categories_still_pass(self):
        errors = self.errors(
            notes="credential type only; public host https://{stream_id}.k2.cloudflarestorage.com and https://api.cloudflare.com/client/v4",
            permission_categories=["r2_s3_access_key_type_only"],
            allowed_action_categories=["read_only_search"],
        )
        self.assertEqual([], errors)


class S3InventoryTest(unittest.TestCase):
    """R4: the pinned inventory, not a self-made URL, proves S3 identity and support."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.catalog = catalog_operations()
        self.protocols = protocols_fixture(self.s3_rows)

    def errors(self, rows, protocols=None):
        return validate_protocols(protocols or self.protocols, rows, context=caller_context())

    def test_unknown_identity_with_self_consistent_references_is_rejected(self):
        rows = copy.deepcopy(self.s3_rows)
        rows[0].update(
            operation="SyntheticUnknown",
            aws_operation_reference="https://docs.aws.amazon.com/AmazonS3/latest/API/API_SyntheticUnknown.html",
            source_quote_ref="s3-compat table row SyntheticUnknown Feature column",
        )
        errors = self.errors(rows)
        self.assertTrue(any("s3.unknown_identity" in error for error in errors), errors)

    def test_swapped_support_flags_are_rejected_even_with_equal_counts(self):
        rows = copy.deepcopy(self.s3_rows)
        first, second = rows[0], rows[1]
        first.update(
            cloudflare_supports_operation="false",
            flarego_status="provider_unsupported",
            verification_status="provider_unsupported",
            acceptance_target_level="n/a",
            test_prerequisites="",
        )
        second.update(
            cloudflare_supports_operation="true",
            flarego_status="not_implemented",
            verification_status="awaiting_authorized_environment",
            acceptance_target_level="L2",
            test_prerequisites="synthetic-prerequisites",
        )
        errors = self.errors(rows)
        self.assertTrue(any("s3.support_mismatch" in error for error in errors), errors)

    def test_feature_content_must_match_the_pinned_inventory(self):
        rows = copy.deepcopy(self.s3_rows)
        self.assertEqual([], self.errors(rows))
        rows = copy.deepcopy(self.s3_rows)
        rows[1]["vendor_unsupported_features"] = "x-amz-expected-bucket-owner | x-amz-invented-parameter"
        errors = self.errors(rows)
        self.assertTrue(errors)
        rows = copy.deepcopy(self.s3_rows)
        rows[0]["vendor_supported_features"] = "x-amz-storage-class"
        self.assertTrue(self.errors(rows))

    def test_s3_source_section_must_match_the_pinned_source(self):
        for overrides in (
            {"name": "ghost"},
            {"commit": "opaque"},
            {"sha256": "opaque"},
            {"url": "https://example.invalid/opaque"},
        ):
            protocols = copy.deepcopy(self.protocols)
            protocols["s3"]["source"].update(overrides)
            errors = self.errors(self.s3_rows, protocols)
            self.assertTrue(errors, overrides)


class BatchFactTest(unittest.TestCase):
    """R5: batch semantics must match the pinned schema description."""

    def test_real_batch_boundaries_match_the_pinned_facts(self):
        protocols = json.loads(PROTOCOLS.read_text(encoding="utf-8"))
        entry = next(
            item
            for item in protocols["p1_p2_admission"]["p2_candidates"]
            if item["operation_id"] == "dns-records-for-a-zone-batch-dns-records"
        )
        impact = entry["impact_boundary"]
        reconciliation = entry["reconciliation_boundary"]
        # Pinned schema fact: one database transaction, non-atomic propagation.
        self.assertIn("database transaction", impact.lower())
        self.assertIn("传播", impact)
        self.assertIn("不是原子", impact)
        self.assertNotIn("部分成功", impact)
        self.assertNotIn("混合状态", impact)
        self.assertIn("unknown", reconciliation.lower())
        self.assertIn("传播", reconciliation)
        self.assertIn("no changes applied", reconciliation)
        for phrase in ("DELETES", "PATCHES", "PUTS", "POSTS"):
            self.assertIn(phrase, entry["confirmation_boundary"])
        self.assertFalse(entry["auto_replay"])
        if PINNED_SCHEMA.exists():
            schema = json.loads(PINNED_SCHEMA.read_text(encoding="utf-8"))
            description = schema["paths"]["/zones/{zone_id}/dns_records/batch"]["post"]["description"]
            self.assertIn("single database transaction", description)
            self.assertIn("propagation of changes is not atomic", description)
            for phrase in ("Deletes", "Patches", "Puts", "Posts"):
                self.assertIn(phrase, description)


class ClosedPipelineTest(unittest.TestCase):
    """R1-R4: the closed fact pipeline must reject every unsupported claim."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.context = caller_context()

    def errors(self, protocols, context=None, rows=None):
        return validate_protocols(
            protocols, rows if rows is not None else self.s3_rows, context=context or self.context
        )

    def positive(self):
        protocols = cleared_protocols()
        authorized_capture(protocols)
        return protocols

    def assert_code(self, errors, code):
        self.assertTrue(any(code in error for error in errors), (code, errors))

    # --- R1: authorization / entitlement / plan chain ---------------------

    def test_positive_requires_verified_plan_limits(self):
        for value in ("unverified", "unknown"):
            protocols = self.positive()
            protocols["graphql"]["plan_limits"] = value
            errors = self.errors(protocols)
            self.assert_code(errors, "qualification.plan_limits_mismatch")

    def test_query_acceptance_must_use_the_capture_environment(self):
        protocols = self.positive()
        protocols["environments"][0].update(
            status="available", authorization_reference="synthetic-unrelated-registrar-approval", blockers=[]
        )
        protocols["graphql"]["capability_acceptance"][0]["environment"] = "registrar_sandbox"
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.record_environment_mismatch")

    def test_capture_environment_needs_the_capture_entitlement(self):
        protocols = self.positive()
        protocols["environments"][1].update(
            allowed_action_categories=["domain_registration_only"],
            permission_categories=["registrar_sandbox_api"],
        )
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.entitlement_missing")

    def test_query_environment_needs_the_query_entitlement(self):
        protocols = self.positive()
        protocols["environments"][1]["allowed_action_categories"] = ["schema_introspection_capture"]
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.entitlement_missing")

    def test_plan_evidence_must_link_the_same_environment_and_plan(self):
        cases = (
            ("environment", "registrar_sandbox"),
            ("plan_label", "some-other-plan"),
            ("authorized_by", "synthetic-unrelated-approval"),
        )
        for field, value in cases:
            protocols = self.positive()
            protocols["graphql"]["plan_evidence"][field] = value
            errors = self.errors(protocols)
            self.assertTrue(errors, (field, value))
            self.assertTrue(
                any("plan_evidence" in error for error in errors), (field, errors)
            )

    def test_plan_evidence_scope_must_cover_the_query_scope(self):
        protocols = self.positive()
        protocols["graphql"]["plan_evidence"]["query_scope"]["fields"] = ["other_field"]
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.plan_evidence_scope_mismatch")

    def test_query_cannot_predate_capture_or_plan(self):
        protocols = self.positive()
        protocols["graphql"]["capability_acceptance"][0]["captured_at"] = "2026-10-07T00:00:00Z"
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.record_order")

    def test_missing_plan_evidence_blocks_positive_but_keeps_discovery(self):
        protocols = cleared_protocols()
        authorized_capture(protocols, with_capability=False)
        protocols["graphql"]["plan_evidence"] = None
        protocols["graphql"]["plan_limits"] = "unverified"
        protocols["graphql"]["capability_acceptance"] = [
            {
                "record_id": "synthetic-acceptance-1",
                "environment": "graphql_schema_capture",
                "captured_at": "2026-10-08T00:00:00Z",
                "query_scope": {
                    "dataset": "synthetic_dataset",
                    "fields": ["synthetic_field"],
                    "query_kinds": ["timeseries"],
                },
                "result": "verified",
                "evidence_artifact": "b" * 64,
            }
        ]
        protocols["graphql"]["verified"] = 1
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.plan_evidence_missing")
        self.assert_code(errors, "qualification.verified_mismatch")
        self.assertEqual(1, len(protocols["graphql"]["datasets"]))

    def test_plan_evidence_must_be_null_without_capture(self):
        protocols = protocols_fixture(self.s3_rows)
        protocols["graphql"]["plan_evidence"] = {
            "environment": "graphql_schema_capture",
            "plan_label": "analytics_access",
            "authorized_by": "synthetic-user-approval-2026-10-08",
            "captured_at": "2026-10-08T00:00:00Z",
            "artifact_sha256": "c" * 64,
            "query_scope": {"dataset": "synthetic_dataset", "fields": ["synthetic_field"], "query_kinds": ["timeseries"]},
        }
        errors = self.errors(protocols)
        self.assert_code(errors, "qualification.plan_evidence_invalid")

    # --- R2: context and denominator semantics ----------------------------

    def test_rest_and_s3_units_are_operations_only(self):
        protocols = self.positive()
        protocols["denominators"][0]["unit"] = "fields"
        self.assert_code(self.errors(protocols), "denominator.unit_mismatch")
        protocols = self.positive()
        protocols["denominators"][1]["unit"] = "datasets"
        self.assert_code(self.errors(protocols), "denominator.unit_mismatch")

    def test_graphql_numeric_count_needs_single_unit_and_matching_status(self):
        protocols = self.positive()
        protocols["denominators"][2].update(unit="datasets_and_fields", count=1)
        self.assert_code(self.errors(protocols), "denominator.unit_mismatch")
        protocols = self.positive()
        protocols["denominators"][2].update(unit="fields", count=3732, status="verified")
        self.assert_code(self.errors(protocols), "denominator.count_mismatch")
        protocols = self.positive()
        protocols["denominators"][2].update(unit="fields", count=1, status="awaiting_query_acceptance")
        self.assert_code(self.errors(protocols), "denominator.status_mismatch")
        protocols = self.positive()
        protocols["denominators"][2].update(unit="fields", count=1, status="verified")
        self.assertEqual([], self.errors(protocols))

    def test_denominator_sources_must_match_the_authoritative_labels(self):
        for index in (0, 1, 2, 3):
            protocols = self.positive()
            protocols["denominators"][index]["source"] = "opaque"
            self.assert_code(self.errors(protocols), "denominator.source_mismatch")

    def test_caller_owned_context_must_be_self_consistent(self):
        protocols = self.positive()
        for context in (
            {"kind": "caller_owned", "operations": catalog_operations(), "rest_operations": 99,
             "s3_subset": ["ListBuckets", "GetBucketAcl"],
             "source_refs": {"rest": "a", "s3": "b", "graphql": "c", "realtime": "d"}},
            {"kind": "caller_owned", "operations": catalog_operations(), "rest_operations": 5,
             "s3_subset": [], "source_refs": {"rest": "a", "s3": "b", "graphql": "c", "realtime": "d"}},
            {"kind": "caller_owned", "operations": catalog_operations(), "rest_operations": 5,
             "s3_subset": ["NotARealS3Operation"],
             "source_refs": {"rest": "a", "s3": "b", "graphql": "c", "realtime": "d"}},
            {"kind": "caller_owned", "operations": catalog_operations(), "rest_operations": 5,
             "s3_subset": ["ListBuckets", "GetBucketAcl"], "source_refs": {"rest": "a"}},
            {"operations": catalog_operations(), "rest_operations": 5},
        ):
            errors = self.errors(protocols, context=context)
            self.assertTrue(errors, context)

    def test_three_arg_catalog_must_match_the_pinned_universe(self):
        protocols = self.positive()
        errors = validate_protocols(protocols, self.s3_rows, catalog_operations())
        self.assert_code(errors, "context.catalog_mismatch")

    def test_pinned_context_declaration_must_match_canonical_facts(self):
        protocols = self.positive()
        errors = self.errors(protocols, context={"kind": "pinned", "rest_operations": 3732})
        self.assert_code(errors, "context.pinned_mismatch")

    def test_context_and_third_argument_catalog_must_agree(self):
        protocols = self.positive()
        other = catalog_operations() + [{"operation_id": "extra-op", "method": "GET", "path": "/extra"}]
        errors = validate_protocols(
            protocols, self.s3_rows, other, context=caller_context()
        )
        self.assert_code(errors, "context.catalog_conflict")

    def test_rest_count_must_equal_the_authoritative_universe(self):
        protocols = self.positive()
        protocols["denominators"][0]["count"] = 99
        self.assert_code(self.errors(protocols), "denominator.count_mismatch")
        protocols = self.positive()
        protocols["denominators"][0]["count"] = 5
        self.assertEqual([], self.errors(protocols, context=caller_context(rest_operations=5)))

    # --- R3: privacy ------------------------------------------------------

    def test_quoted_and_parenthesized_private_paths_are_rejected(self):
        for value in (
            "see(/home/synthetic/.aws/config)",
            "file '/home/synthetic/.aws/config'",
            'path: "/home/synthetic/.ssh/id_ed25519"',
            "token in (~/.config/gcloud/credentials.db)",
        ):
            protocols = protocols_fixture(self.s3_rows)
            protocols["environments"][0]["notes"] = value
            self.assertTrue(self.errors(protocols), value)

    def test_short_scalar_values_are_never_echoed(self):
        marker = "Bearer SYNTHETIC-PRIVATE-MARKER"
        protocols = protocols_fixture(self.s3_rows)
        protocols["environments"][0]["status"] = marker
        errors = self.errors(protocols)
        self.assertTrue(errors)
        self.assertTrue(all(marker not in error for error in errors), errors)

    def test_untrusted_identities_are_not_echoed(self):
        marker = "synthetic-untrusted-identity-marker"
        protocols = protocols_fixture(self.s3_rows)
        protocols["realtime"]["protocols"][0]["session_operations"][0]["operation_id"] = marker
        protocols["realtime"]["protocols"][0]["protocol_id"] = marker
        protocols["environments"][0]["environment_id"] = marker
        errors = self.errors(protocols)
        self.assertTrue(errors)
        self.assertTrue(all(marker not in error for error in errors), errors)

    # --- R4: authoritative S3 universe ------------------------------------

    def test_pinned_universe_must_be_complete(self):
        protocols = json.loads(PROTOCOLS.read_text(encoding="utf-8"))
        rows = list(csv.DictReader(S3_CSV.open(encoding="utf-8-sig", newline="")))
        shrunken = [row for row in rows if row["operation"] != "ListBuckets"]
        protocols["s3"]["documented"] = 72
        protocols["s3"]["vendor_supported"] = 25
        protocols["denominators"][1].update(count=72, vendor_supported=25)
        errors = validate_protocols(protocols, shrunken)
        self.assert_code(errors, "s3.universe_incomplete")

    def test_caller_owned_subset_must_match_the_rows(self):
        protocols = self.positive()
        errors = self.errors(protocols, rows=[s3_row("ListBuckets")])
        self.assert_code(errors, "s3.universe_incomplete")

    def test_cross_dimension_table(self):
        table = (
            ("plan_unverified", lambda p: p["graphql"].update(plan_limits="unverified"),
             "qualification.plan_limits_mismatch"),
            ("record_other_environment",
             lambda p: p["graphql"]["capability_acceptance"][0].update(environment="registrar_sandbox"),
             "qualification.record_environment_mismatch"),
            ("capture_wrong_actions",
             lambda p: p["environments"][1].update(allowed_action_categories=["read_only_search"]),
             "qualification.entitlement_missing"),
            ("plan_scope_narrower",
             lambda p: p["graphql"]["plan_evidence"]["query_scope"].update(fields=["other"]),
             "qualification.plan_evidence_scope_mismatch"),
            ("record_out_of_matrix",
             lambda p: p["graphql"]["capability_acceptance"][0]["query_scope"].update(dataset="ghost"),
             "qualification.record_scope_mismatch"),
            ("status_claim_verified_without_records",
             lambda p: (p["graphql"].update(capability_acceptance=[], verified=1, review_status="verified")),
             "qualification.status_mismatch"),
            ("rest_unit_wrong", lambda p: p["denominators"][0].update(unit="fields"),
             "denominator.unit_mismatch"),
            ("s3_source_opaque", lambda p: p["denominators"][1].update(source="opaque"),
             "denominator.source_mismatch"),
            ("realtime_unit_wrong", lambda p: p["denominators"][3].update(unit="widgets"),
             "denominator.unit_mismatch"),
            ("realtime_count_known", lambda p: p["denominators"][3].update(count=8),
             "denominator.count_mismatch"),
            ("s3_support_swapped",
             lambda p: p["s3"].update(vendor_supported=0, provider_unsupported=2),
             "s3.count_mismatch"),
            ("graphql_source_opaque", lambda p: p["denominators"][2].update(source="opaque"),
             "denominator.source_mismatch"),
        )
        for name, mutate, code in table:
            protocols = self.positive()
            mutate(protocols)
            errors = self.errors(protocols)
            self.assertTrue(any(code in error for error in errors), (name, code, errors))


class Fix4ClosureTest(unittest.TestCase):
    """Fix4: exact residual cases from the scoped review (R1-R3)."""

    def setUp(self):
        self.s3_rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        self.context = caller_context()

    def errors(self, protocols, context=None, catalog=None, rows=None):
        return validate_protocols(
            protocols,
            rows if rows is not None else self.s3_rows,
            catalog,
            context=self.context if context is None else context,
        )

    def positive(self):
        protocols = cleared_protocols()
        authorized_capture(protocols)
        return protocols

    # --- R1 --------------------------------------------------------------

    def test_positive_requires_known_account_scope(self):
        protocols = self.positive()
        protocols["graphql"]["account_scope"] = "unknown"
        errors = self.errors(protocols)
        self.assertTrue(any("qualification.account_scope_unknown" in error for error in errors), errors)

    def test_captured_discovered_counts_must_be_declared(self):
        protocols = self.positive()
        protocols["graphql"].update(dataset_count=None, field_count=None)
        errors = self.errors(protocols)
        self.assertTrue(any("qualification.matrix_mismatch" in error for error in errors), errors)

    def test_plan_evidence_may_predate_the_capture(self):
        protocols = self.positive()
        approval = "synthetic-scope-approval"
        protocols["environments"][1]["authorization_reference"] = approval
        protocols["graphql"]["schema_evidence"]["authorized_introspection"]["authorized_by"] = approval
        protocols["graphql"]["plan_evidence"].update(
            authorized_by=approval, captured_at="2026-10-07T00:00:00Z"
        )
        for blocker in protocols["blockers"]:
            blocker["cleared_by"] = approval
            blocker["clearance_record"].update(reference=approval, date="2026-10-07")
        self.assertEqual([], self.errors(protocols))

    def test_query_still_cannot_predate_capture_or_plan(self):
        protocols = self.positive()
        protocols["graphql"]["capability_acceptance"][0]["captured_at"] = "2026-10-07T00:00:00Z"
        errors = self.errors(protocols)
        self.assertTrue(any("qualification.record_order" in error for error in errors), errors)

    # --- R2 --------------------------------------------------------------

    def test_catalog_bindings_are_compared_in_every_mode(self):
        conflicting = copy.deepcopy(catalog_operations())
        conflicting[0].update(method="DELETE", path="/synthetic-wrong-binding")
        protocols = self.positive()
        errors = self.errors(protocols, catalog=conflicting)
        self.assertTrue(any("context.catalog_conflict" in error for error in errors), errors)
        errors = self.errors(protocols, context={"kind": "pinned"}, catalog=conflicting)
        self.assertTrue(any("context.catalog_mismatch" in error for error in errors), errors)
        self.assertEqual([], self.errors(protocols, catalog=catalog_operations()))

    def test_pinned_context_operations_declaration_must_match(self):
        protocols = self.positive()
        errors = self.errors(
            protocols,
            context={
                "kind": "pinned",
                "operations": [{"operation_id": "ghost", "method": "GET", "path": "/ghost"}],
            },
        )
        self.assertTrue(any("context.pinned_mismatch" in error for error in errors), errors)

    def test_pinned_count_must_equal_the_unique_catalog_count(self):
        protocols = json.loads(PROTOCOLS.read_text(encoding="utf-8"))
        rows = list(csv.DictReader(S3_CSV.open(encoding="utf-8-sig", newline="")))
        snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        snapshot["counts"]["operations"] = 3732
        text = json.dumps(snapshot)
        original = pathlib.Path.read_text

        def read_text(path, *args, **kwargs):
            return text if pathlib.Path(path).name == "snapshot.json" else original(path, *args, **kwargs)

        protocols["denominators"][0]["count"] = 3732
        with mock.patch.object(pathlib.Path, "read_text", read_text):
            errors = validate_protocols(protocols, rows)
        self.assertTrue(any("context.pinned_count_mismatch" in error for error in errors), errors)

    def test_null_graphql_count_status_must_match_the_fact_state(self):
        protocols = self.positive()
        protocols["denominators"][2].update(
            unit="datasets_and_fields", count=None, status="awaiting_authorized_schema"
        )
        errors = self.errors(protocols)
        self.assertTrue(any("denominator.status_mismatch" in error for error in errors), errors)
        protocols = self.positive()
        protocols["denominators"][2].update(unit="datasets_and_fields", count=None, status="verified")
        self.assertEqual([], self.errors(protocols))

    def test_declared_numeric_counters_must_be_strict_ints(self):
        protocols = self.positive()
        protocols["denominators"][0]["count"] = 5.0
        self.assertTrue(any("denominator.count_mismatch" in error for error in self.errors(protocols)))
        protocols = self.positive()
        protocols["denominators"][1]["count"] = 2.0
        self.assertTrue(any("denominator.count_mismatch" in error for error in self.errors(protocols)))

    def test_present_supplemental_metadata_must_be_valid(self):
        protocols = self.positive()
        rows = self.s3_rows
        original = pathlib.Path.read_text
        for name, change in (
            ("supplementary_docs", lambda s: s.update(supplementary_docs=["opaque"])),
            ("sources", lambda s: s["supplementary_docs"].update(sources=["opaque"])),
            (
                "s3_url",
                lambda s: [
                    source.update(url="https://example.invalid/opaque")
                    for source in s["supplementary_docs"]["sources"]
                    if source["name"] == "s3-compat"
                ],
            ),
            (
                "s3_sha",
                lambda s: [
                    source.update(sha256="0" * 64)
                    for source in s["supplementary_docs"]["sources"]
                    if source["name"] == "s3-compat"
                ],
            ),
        ):
            snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
            change(snapshot)
            text = json.dumps(snapshot)

            def read_text(path, *args, _text=text, **kwargs):
                return _text if pathlib.Path(path).name == "snapshot.json" else original(path, *args, **kwargs)

            with mock.patch.object(pathlib.Path, "read_text", read_text):
                errors = validate_protocols(protocols, rows)
            self.assertTrue(
                any("context.pinned_source_conflict" in error for error in errors), (name, errors)
            )

    # --- R3 --------------------------------------------------------------

    def test_invalid_context_count_is_not_echoed(self):
        marker = 4111111111111111
        context = caller_context()
        context["rest_operations"] = marker
        errors = validate_protocols(self.positive(), self.s3_rows, context=context)
        self.assertTrue(errors)
        self.assertTrue(all(str(marker) not in error for error in errors), errors)

    def test_backtick_private_path_is_rejected(self):
        protocols = protocols_fixture(self.s3_rows)
        protocols["environments"][0]["notes"] = "path `/home/synthetic/.aws/config`"
        self.assertTrue(self.errors(protocols))


class RealProtocolLedgerTest(unittest.TestCase):
    def setUp(self):
        if not PROTOCOLS.exists():
            self.skipTest("protocols.json not created yet")
        self.protocols = json.loads(PROTOCOLS.read_text(encoding="utf-8"))
        with S3_CSV.open(encoding="utf-8-sig", newline="") as handle:
            self.s3_rows = list(csv.DictReader(handle))
        self.catalog = []
        with (AUDIT / "endpoints.csv").open(encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                self.catalog.append(
                    {"operation_id": row["operation_id"], "method": row["method"], "path": row["path"]}
                )

    def test_real_protocols_validate_clean(self):
        self.assertEqual([], validate_protocols(self.protocols, self.s3_rows, self.catalog))

    def test_real_s3_denominator_and_support_flags(self):
        self.assertEqual(73, len(self.s3_rows))
        supported = [row for row in self.s3_rows if row["cloudflare_supports_operation"] == "true"]
        unsupported = [row for row in self.s3_rows if row["cloudflare_supports_operation"] == "false"]
        self.assertEqual(26, len(supported))
        self.assertEqual(47, len(unsupported))
        self.assertEqual(73, self.protocols["s3"]["documented"])
        self.assertEqual(26, self.protocols["s3"]["vendor_supported"])
        self.assertEqual(47, self.protocols["s3"]["provider_unsupported"])
        self.assertEqual(0, self.protocols["s3"]["verified"])
        for row in self.s3_rows:
            self.assertEqual("false", row["flarego_implemented"])
            self.assertEqual("false", row["verified"])
            self.assertIn(row["verification_status"], ("awaiting_authorized_environment", "provider_unsupported"))
        for row in unsupported:
            self.assertEqual("provider_unsupported", row["flarego_status"])
            self.assertEqual("n/a", row["acceptance_target_level"])
        for row in supported:
            self.assertEqual("not_implemented", row["flarego_status"])
            self.assertEqual("L2", row["acceptance_target_level"])

    def test_real_s3_rows_preserve_identity_order_and_support(self):
        with S3_CSV.open(encoding="utf-8-sig", newline="") as handle:
            header = next(csv.reader(handle))
        for column in (
            "operation", "level", "cloudflare_supports_operation", "flarego_status",
            "official_compatibility_page", "aws_operation_reference", "feature_limitations",
        ):
            self.assertIn(column, header)
        self.assertEqual(
            [
                "operation", "level", "cloudflare_supports_operation", "flarego_status",
                "official_compatibility_page", "aws_operation_reference", "feature_limitations",
            ],
            header[:7],
        )

    def test_real_s3_feature_subsets_come_from_the_pinned_table(self):
        rows = {row["operation"]: row for row in self.s3_rows}
        head = rows["HeadObject"]
        self.assertIn("Range (has no effect in HeadObject)", head["vendor_supported_features"])
        self.assertIn("x-amz-request-payer", head["vendor_unsupported_features"])
        copy_object = rows["CopyObject"]
        self.assertIn("x-amz-copy-source", copy_object["vendor_supported_features"])
        self.assertIn("x-amz-checksum-algorithm", copy_object["vendor_unsupported_features"])
        upload_part_copy = rows["UploadPartCopy"]
        self.assertIn("x-amz-copy-source-if-match", upload_part_copy["vendor_unsupported_features"])
        self.assertIn("x-amz-copy-source-range", upload_part_copy["vendor_supported_features"])
        self.assertEqual("not_listed_in_pinned_table", rows["ListBuckets"]["feature_table_status"])
        self.assertEqual("listed", rows["HeadObject"]["feature_table_status"])
        for row in self.s3_rows:
            self.assertTrue(row["source_commit"])
            self.assertTrue(row["source_sha256"])
            self.assertIn("Feature column", row["source_quote_ref"])

    def test_real_graphql_awaits_authorized_schema(self):
        graphql = self.protocols["graphql"]
        self.assertEqual("awaiting_authorized_schema", graphql["review_status"])
        self.assertIsNone(graphql["dataset_count"])
        self.assertIsNone(graphql["field_count"])
        self.assertEqual([], graphql["datasets"])
        self.assertEqual(0, graphql["verified"])
        self.assertIsNone(graphql["schema_evidence"]["authorized_introspection"])
        self.assertTrue(graphql["families"])
        for family in graphql["families"]:
            self.assertTrue(family["owner_role"])
            self.assertTrue(family["phase"])
            self.assertTrue(family["blockers"])

    def test_real_k2_stream_host_operations_stay_in_rest(self):
        host = self.protocols["s3"]["k2_stream_host"]
        self.assertEqual(K2_HOST, host["host"])
        self.assertTrue(host["rest_denominator"])
        self.assertEqual(8, len(host["rest_operations"]))
        catalog = {row["operation_id"]: row for row in self.catalog}
        for entry in host["rest_operations"]:
            self.assertTrue(entry["rest_denominator"])
            self.assertEqual(catalog[entry["operation_id"]]["method"], entry["method"])
            self.assertEqual(catalog[entry["operation_id"]]["path"], entry["path"])
        for denominator in self.protocols["denominators"]:
            self.assertNotIn("operation_ids", denominator)

    def test_real_admission_lists_match_the_approved_sources(self):
        admission = self.protocols["p1_p2_admission"]
        self.assertEqual(list(P1_APPROVED_NEW_REQUESTS), [e["operation_id"] for e in admission["p1_new"]])
        implemented = json.loads(IMPLEMENTED.read_text(encoding="utf-8"))["operations"]
        self.assertEqual(sorted(implemented), sorted(e["operation_id"] for e in admission["p1_existing_compat"]))
        with P2_CANDIDATES.open(encoding="utf-8-sig", newline="") as handle:
            candidates = {row["operation_id"]: row for row in csv.DictReader(handle)}
        self.assertEqual(sorted(candidates), sorted(e["operation_id"] for e in admission["p2_candidates"]))
        for entry in admission["p2_candidates"]:
            self.assertEqual(candidates[entry["operation_id"]]["method"], entry["method"])
            self.assertEqual(candidates[entry["operation_id"]]["path"], entry["path"])
        counts = admission["counts"]
        self.assertEqual(5, counts["p1_new"])
        self.assertEqual(11, counts["p1_existing_compat"])
        self.assertEqual(54, counts["p2_candidates"])
        self.assertEqual(54, counts["sum_of_lists"] - 5 - 11)
        self.assertEqual(
            len({e["operation_id"] for e in admission["p1_new"]}
                | {e["operation_id"] for e in admission["p1_existing_compat"]}
                | {e["operation_id"] for e in admission["p2_candidates"]}),
            counts["unique"],
        )
        self.assertFalse(admission["policy"]["runtime_allowlist"])

    def test_real_environments_and_document_have_no_sensitive_material(self):
        self.assertTrue(ENVIRONMENTS_DOC.exists())
        text = ENVIRONMENTS_DOC.read_text(encoding="utf-8")
        for forbidden in ("api_token=", "AKIA", "BEGIN PRIVATE KEY", "-----BEGIN", "card_number", "cvv"):
            self.assertNotIn(forbidden, text, forbidden)
        for environment in self.protocols["environments"]:
            self.assertEqual("awaiting_authorized_environment", environment["status"])
            self.assertTrue(environment["cleanup_responsibility"])
        labels = {environment["environment_id"] for environment in self.protocols["environments"]}
        for expected in ("registrar_sandbox", "test_zone_dns", "enterprise_preview", "graphql_schema_capture", "k2_stream_host"):
            self.assertIn(expected, labels)

    def test_real_s3_rows_match_the_frozen_source_inventory(self):
        """Runs without the ignored pinned table, so a clean checkout is not vacuous."""
        self.assertEqual(73, len(S3_SOURCE_INVENTORY))
        self.assertEqual(26, sum(1 for supported, _ in S3_SOURCE_INVENTORY.values() if supported))
        rows = {row["operation"]: row for row in self.s3_rows}
        self.assertEqual(sorted(S3_SOURCE_INVENTORY), sorted(rows))
        for name, (supported, digest) in S3_SOURCE_INVENTORY.items():
            row = rows[name]
            self.assertEqual("true" if supported else "false", row["cloudflare_supports_operation"], name)
            self.assertEqual(digest, s3_feature_digest(row), name)
            self.assertEqual(S3_DOC_SHA256, row["source_sha256"], name)
            self.assertEqual(S3_DOC_COMMIT, row["source_commit"], name)

    def test_real_s3_feature_lists_match_the_pinned_table_exactly(self):
        table = parse_pinned_s3_table()
        if not table:
            self.skipTest("pinned S3 compatibility table not present locally")
        self.assertEqual(73, len(table))
        self.assertEqual(S3_DOC_SHA256, hashlib.sha256(S3_DOC.read_bytes()).hexdigest())
        mismatches = []
        for row in self.s3_rows:
            expected = table[row["operation"]]
            for field, key in (
                ("vendor_supported_features", "supported"),
                ("vendor_unsupported_features", "unsupported"),
                ("vendor_experimental_features", "experimental"),
            ):
                actual = [item.strip() for item in row[field].split(" | ") if item.strip()]
                if actual != expected[key]:
                    mismatches.append((row["operation"], field, expected[key], actual))
            digest = hashlib.sha256(
                "|".join(
                    [
                        ",".join(expected["supported"]),
                        ",".join(expected["unsupported"]),
                        ",".join(expected["experimental"]),
                    ]
                ).encode("utf-8")
            ).hexdigest()
            self.assertEqual(
                S3_SOURCE_INVENTORY[row["operation"]][1], digest, row["operation"]
            )
        self.assertEqual([], mismatches)
        rows = {row["operation"]: row for row in self.s3_rows}
        self.assertIn(
            "x-amz-expected-bucket-owner", rows["GetBucketEncryption"]["vendor_unsupported_features"]
        )
        self.assertIn("id", rows["GetBucketAnalyticsConfiguration"]["vendor_unsupported_features"])
        self.assertIn("id", rows["PutBucketIntelligentTieringConfiguration"]["vendor_unsupported_features"])
        self.assertIn("x-amz-expected-bucket-owner", rows["PutBucketNotification"]["vendor_unsupported_features"])
        self.assertIn("max-uploads", rows["ListMultipartUploads"]["vendor_supported_features"])
        for row in self.s3_rows:
            combined = row["vendor_supported_features"] + row["vendor_unsupported_features"]
            self.assertNotIn("\ufe0f", combined, row["operation"])
            self.assertIn(row["operation"], row["source_quote_ref"], row["operation"])
            self.assertIn(f"/API_{row['operation']}.html", row["aws_operation_reference"])

    def test_real_environment_markdown_matches_the_ledger(self):
        text = ENVIRONMENTS_DOC.read_text(encoding="utf-8")
        for environment in self.protocols["environments"]:
            marker = f"`{environment['environment_id']}`"
            row = next(
                (line for line in text.splitlines() if marker in line and line.startswith("|")), None
            )
            self.assertIsNotNone(row, environment["environment_id"])
            for value in (
                environment["label"],
                environment["plan"],
                environment["owner_role"],
                environment["cleanup_responsibility"],
                environment["status"],
            ):
                self.assertIn(value, row, environment["environment_id"])
            for blocker in environment["blockers"]:
                self.assertIn(blocker["code"], text, environment["environment_id"])
            for category in environment["allowed_action_categories"] + environment["permission_categories"]:
                self.assertIn(category, row, environment["environment_id"])

    def test_real_live_tail_facts_match_the_schema(self):
        entry = next(
            protocol
            for protocol in self.protocols["realtime"]["protocols"]
            if protocol["protocol_id"] == "workers_observability_live_tail"
        )
        self.assertEqual("websocket", entry["connection_protocol"])
        self.assertIn("wsUrl", entry["consumer_url_source"])
        self.assertTrue(any("consumer" in blocker for blocker in entry["blockers"]))
        self.assertNotIn("chunk", entry["connection_protocol"])

    def test_real_turn_key_is_not_a_client_credential_mint(self):
        entry = next(
            protocol
            for protocol in self.protocols["realtime"]["protocols"]
            if protocol["protocol_id"] == "realtime_turn"
        )
        self.assertIn("turn_key", entry["credential_policy"])
        self.assertTrue(any("mint" in blocker for blocker in entry["blockers"]))
        self.assertTrue(any("ttl" in blocker for blocker in entry["blockers"]))
        self.assertTrue(any("host" in blocker for blocker in entry["blockers"]))
        self.assertNotIn("until_expiry", entry["lifecycle"])

    def test_real_p1_sources_point_to_real_plan_sections(self):
        for entry in self.protocols["p1_p2_admission"]["p1_new"]:
            path_part, _, fragment = entry["source"].partition("#")
            target = REPO / path_part
            self.assertTrue(target.is_file(), entry["source"])
            self.assertTrue(fragment, entry["source"])
            self.assertIn(fragment, target.read_text(encoding="utf-8"), entry["source"])

    def test_real_admission_entries_declare_boundaries_and_pending_review(self):
        for group in ("p1_new", "p1_existing_compat", "p2_candidates"):
            for entry in self.protocols["p1_p2_admission"][group]:
                self.assertEqual("pending_manual_review", entry["risk_review"], entry["operation_id"])
                self.assertFalse(entry["auto_replay"], entry["operation_id"])
                for field in (
                    "impact_boundary",
                    "confirmation_boundary",
                    "reconciliation_boundary",
                    "verification_boundary",
                ):
                    self.assertTrue(entry[field], (entry["operation_id"], field))
                if entry["method"] in ("GET", "HEAD"):
                    self.assertFalse(entry["confirmation_required"], entry["operation_id"])
                    self.assertFalse(entry["readback_required"], entry["operation_id"])
                else:
                    self.assertTrue(entry["confirmation_required"], entry["operation_id"])
                    self.assertTrue(entry["readback_required"], entry["operation_id"])
                    self.assertNotEqual("low", entry["risk"], entry["operation_id"])

    def test_real_snapshot_pin_is_unchanged(self):
        snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        self.assertEqual(
            "06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461",
            snapshot["schema"]["sha256"],
        )
        self.assertEqual(3659, snapshot["counts"]["operations"])
        self.assertEqual(73, snapshot["s3_counts"]["documented_operations"])
        self.assertEqual(26, snapshot["s3_counts"]["cloudflare_supported_operations"])


if __name__ == "__main__":
    unittest.main()
