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
import unittest

from tools.cloudflare_audit.review import P1_APPROVED_NEW_REQUESTS, validate_protocols

REPO = pathlib.Path(__file__).resolve().parents[3]
AUDIT = REPO / "docs/cloudflare-audit"
PROTOCOLS = AUDIT / "protocols.json"
S3_CSV = AUDIT / "r2-s3-operations.csv"
IMPLEMENTED = AUDIT / "implemented.json"
P2_CANDIDATES = AUDIT / "p2-candidate-endpoints.csv"
SNAPSHOT = AUDIT / "snapshot.json"
ENVIRONMENTS_DOC = AUDIT / "acceptance-environments.md"
K2_HOST = "https://{stream_id}.k2.cloudflarestorage.com"

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


def s3_row(operation: str, supported: bool = True, **overrides) -> dict:
    row = {
        "operation": operation,
        "level": "bucket",
        "cloudflare_supports_operation": "true" if supported else "false",
        "flarego_status": "not_implemented" if supported else "provider_unsupported",
        "official_compatibility_page": S3_SOURCE["url"],
        "aws_operation_reference": f"https://docs.aws.amazon.com/AmazonS3/latest/API/API_{operation}.html",
        "feature_limitations": "参见固定版本兼容表逐字段检查",
        "vendor_supported_features": "x-amz-storage-class" if supported else "",
        "vendor_unsupported_features": "x-amz-expected-bucket-owner",
        "feature_table_status": "listed",
        "source_commit": S3_SOURCE["commit"],
        "source_sha256": S3_SOURCE["sha256"],
        "source_quote_ref": f"s3-compat table row {operation} Feature column",
        "verification_status": "awaiting_authorized_environment" if supported else "provider_unsupported",
        "acceptance_target_level": "L2" if supported else "n/a",
        "owner_role": "日常工作",
        "phase": "P5",
        "test_prerequisites": (
            "authorized_cloudflare_account|isolated_test_scope" if supported else ""
        ),
        "flarego_implemented": "false",
        "verified": "false",
    }
    row.update(overrides)
    return row


def admission_entry(operation_id: str, method: str = "GET", path: str = "/zones") -> dict:
    return {
        "operation_id": operation_id,
        "method": method,
        "path": path,
        "source": "docs/cloudflare-audit/p2-candidate-endpoints.csv",
        "risk": "low" if method == "GET" else "unknown",
        "confirmation_required": method != "GET",
        "verification_boundary": "authenticated_account_acceptance_required",
        "proposed": True,
    }


def protocols_fixture(s3_rows=None) -> dict:
    rows = s3_rows if s3_rows is not None else [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
    return {
        "format": "flarego.cloudflare-audit.protocols/1",
        "generated_from": {"review_state": "proposed_not_approved", "note": "synthetic fixture"},
        "denominators": [
            {"name": "rest", "unit": "operations", "count": 3659, "independent": True, "source": "snapshot.json"},
            {"name": "s3", "unit": "operations", "count": 2, "vendor_supported": 1,
             "provider_unsupported": 1, "independent": True, "source": "r2-s3-operations.csv"},
            {"name": "graphql", "unit": "datasets_and_fields", "count": None,
             "status": "awaiting_authorized_schema", "independent": True, "source": "graphql-overview"},
            {"name": "realtime", "unit": "sessions_and_streams", "count": None,
             "status": "awaiting_protocol_evidence", "independent": True, "source": "catalog"},
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
            {
                "environment_id": "registrar_sandbox",
                "label": "registrar sandbox",
                "plan": "sandbox",
                "allowed_action_categories": ["read_only_search", "sandbox_registration"],
                "owner_role": "日常工作",
                "permission_categories": ["registrar_sandbox_api"],
                "cleanup_responsibility": "owner_role",
                "status": "awaiting_authorized_environment",
                "blockers": [{"code": "sandbox_access_not_provided", "detail": "no authorized sandbox environment"}],
                "notes": "real domain registration and payment are never ordinary automated tests",
            }
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
        ],
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
        return validate_protocols(protocols or self.protocols, rows or self.s3_rows, catalog_operations)

    def test_valid_synthetic_ledger_passes(self):
        self.assertEqual([], self.errors())

    def test_provider_unsupported_s3_is_not_an_app_target(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"].update(documented=2, vendor_supported=2, provider_unsupported=0)
        protocols["denominators"][1].update(count=2, vendor_supported=2, provider_unsupported=0)
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("provider_unsupported" in error for error in errors), errors)

    def test_graphql_without_schema_evidence_cannot_be_verified(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["graphql"]["review_status"] = "verified"
        protocols["graphql"]["verified"] = 3
        protocols["graphql"]["dataset_count"] = 12
        protocols["graphql"]["field_count"] = 400
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("schema_evidence" in error for error in errors), errors)
        self.assertTrue(any("awaiting_authorized_schema" in error or "dataset_count" in error for error in errors), errors)

    def test_graphql_doc_url_is_not_authorized_schema_evidence(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["graphql"]["review_status"] = "verified"
        protocols["graphql"]["schema_evidence"] = {"url": GRAPHQL_SOURCE["url"]}
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("schema_evidence" in error for error in errors), errors)

    def test_rest_and_s3_cannot_be_summed_into_a_total_coverage(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["total_coverage"] = 3732
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("total_coverage" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["denominators"].append(
            {"name": "combined_total", "unit": "operations", "count": 3732, "independent": True, "source": "x"}
        )
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("combined_total" in error for error in errors), errors)

    def test_denominator_counts_must_match_the_s3_rows(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["documented"] = 73
        protocols["s3"]["vendor_supported"] = 26
        protocols["s3"]["provider_unsupported"] = 47
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("documented" in error for error in errors), errors)

    def test_duplicate_s3_operations_are_rejected(self):
        rows = [s3_row("ListBuckets"), s3_row("ListBuckets", supported=False)]
        errors = validate_protocols(self.protocols, rows, self.catalog)
        self.assertTrue(any("duplicate" in error and "ListBuckets" in error for error in errors), errors)

    def test_unknown_s3_operation_reference_is_rejected(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["k2_stream_host"]["rest_operations"].append(
            {"operation_id": "not-a-real-operation", "method": "POST", "path": "/nope", "rest_denominator": True}
        )
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("not-a-real-operation" in error for error in errors), errors)

    def test_unknown_rest_operation_reference_is_rejected(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["realtime"]["protocols"][0]["session_operations"][0]["operation_id"] = "ghost-session"
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("ghost-session" in error for error in errors), errors)

    def test_method_path_binding_of_referenced_operations_must_match(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["realtime"]["protocols"][0]["session_operations"][0]["method"] = "POST"
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("METHOD+path" in error for error in errors), errors)

    def test_invalid_csv_boolean_is_rejected(self):
        for value in ("yes", "1", "", "TRUE", "False"):
            rows = [s3_row("ListBuckets", cloudflare_supports_operation=value), s3_row("GetBucketAcl", supported=False)]
            errors = validate_protocols(self.protocols, rows, self.catalog)
            self.assertTrue(
                any("cloudflare_supports_operation" in error for error in errors), (value, errors)
            )

    def test_false_string_is_not_treated_as_true(self):
        rows = [s3_row("ListBuckets"), s3_row("GetBucketAcl", supported=False)]
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["vendor_supported"] = 1
        protocols["s3"]["provider_unsupported"] = 1
        protocols["denominators"][1].update(vendor_supported=1, provider_unsupported=1)
        self.assertEqual([], validate_protocols(protocols, rows, self.catalog))
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
        errors = validate_protocols(broken, self.s3_rows, self.catalog)
        self.assertTrue(errors)
        self.assertTrue(all(isinstance(error, str) for error in errors))
        for value in (None, [], "text", 42):
            errors = validate_protocols(value, self.s3_rows, self.catalog)
            self.assertTrue(errors, value)
            errors = validate_protocols(self.protocols, value, self.catalog)
            self.assertTrue(errors, value)

    def test_empty_or_opaque_evidence_does_not_grant_verification(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["s3"]["verified"] = 1
        rows = [s3_row("ListBuckets", verified="true", verification_evidence=""), s3_row("GetBucketAcl", supported=False)]
        errors = validate_protocols(protocols, rows, self.catalog)
        self.assertTrue(any("verification_evidence" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["graphql"]["review_status"] = "verified"
        protocols["graphql"]["schema_evidence"] = {"authorized_introspection": "yes"}
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
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
            errors = validate_protocols(protocols, self.s3_rows, self.catalog)
            self.assertTrue(errors, extra)

    def test_environment_registry_requires_status_and_cleanup(self):
        for field in ("status", "cleanup_responsibility", "owner_role", "allowed_action_categories", "permission_categories"):
            protocols = copy.deepcopy(self.protocols)
            del protocols["environments"][0][field]
            errors = validate_protocols(protocols, self.s3_rows, self.catalog)
            self.assertTrue(any(field in error for error in errors), (field, errors))

    def test_realtime_requires_connection_lifecycle_and_owner(self):
        for field in ("connection_protocol", "auth_host_policy", "lifecycle", "owner_role", "phase", "target_level", "enumeration_status"):
            protocols = copy.deepcopy(self.protocols)
            del protocols["realtime"]["protocols"][0][field]
            errors = validate_protocols(protocols, self.s3_rows, self.catalog)
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
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("postK2Produce" in error for error in errors), errors)

    def test_p1_p2_counts_must_match_lists_and_overlap(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["counts"]["unique"] = 4
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("unique" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["counts"]["overlap"] = 0
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("overlap" in error for error in errors), errors)
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["counts"]["sum_of_lists"] = 3
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("sum_of_lists" in error for error in errors), errors)

    def test_admission_entries_require_risk_and_verification_boundary(self):
        for field in ("risk", "confirmation_required", "verification_boundary", "source"):
            protocols = copy.deepcopy(self.protocols)
            del protocols["p1_p2_admission"]["p1_new"][0][field]
            errors = validate_protocols(protocols, self.s3_rows, self.catalog)
            self.assertTrue(any(field in error for error in errors), (field, errors))

    def test_admission_must_not_claim_runtime_allowlist(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["p1_p2_admission"]["policy"]["runtime_allowlist"] = True
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("runtime_allowlist" in error for error in errors), errors)

    def test_duplicate_blocker_codes_are_rejected(self):
        protocols = copy.deepcopy(self.protocols)
        protocols["blockers"].append({"code": "authorized_account_absent", "detail": "duplicate"})
        errors = validate_protocols(protocols, self.s3_rows, self.catalog)
        self.assertTrue(any("duplicate" in error for error in errors), errors)

    def test_validation_is_read_only(self):
        protocols = copy.deepcopy(self.protocols)
        rows = copy.deepcopy(self.s3_rows)
        snapshot = json.dumps([protocols, rows], sort_keys=True)
        validate_protocols(protocols, rows, self.catalog)
        self.assertEqual(snapshot, json.dumps([protocols, rows], sort_keys=True))


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
