"""Offline tests for the Cloudflare coverage ownership/qualification ledger.

All fixtures are local and synthetic; the real catalog/ledger checks only read
committed repository files. No network access, no credentials, no cloud calls.
"""
import copy
import json
import pathlib
import tempfile
import unittest

from tools.cloudflare_audit import cli
from tools.cloudflare_audit.review import (
    ACCEPTANCE_OWNER_ROLE,
    AVAILABILITY_DECISIONS,
    DEFAULT_OWNER_ROLE,
    DEPRECATION_DECISIONS,
    OWNER_ROLES,
    PHASES,
    REVIEW_FIELDS,
    SCOPE_KINDS,
    SEMANTICS,
    TARGET_LEVELS,
    load_catalog_operations,
    load_p2_candidates,
    load_review,
    normalize_operations,
    propose_review,
    summarize_review,
    validate_review,
)

REPO = pathlib.Path(__file__).resolve().parents[3]
CATALOG = REPO / "docs/cloudflare-audit/endpoints.csv"
REVIEW = REPO / "docs/cloudflare-audit/review.json"
IMPLEMENTED = REPO / "docs/cloudflare-audit/implemented.json"
P2_CANDIDATES = REPO / "docs/cloudflare-audit/p2-candidate-endpoints.csv"


def operation(operation_id, method, path, tags, metadata=None):
    return {
        "method": method,
        "path": path,
        "operation_id": operation_id,
        "tags": list(tags),
        "metadata": dict(metadata or {}),
    }


def review_entry(**overrides):
    entry = {
        "product_id": "rules-waf-bots",
        "phase": "P5",
        "owner_role": DEFAULT_OWNER_ROLE,
        "scope_kind": "zone",
        "protocol": "rest",
        "semantics": "read",
        "risk": "low",
        "pagination": "unreviewed",
        "availability_decision": "documented_unverified",
        "deprecation_decision": "active",
        "target_level": "L2",
        "test_prerequisites": ["authorized_cloudflare_account"],
        "needs_review": True,
        "evidence": {
            "assignment": "tag: Fixture",
            "scope_basis": "path scope /zones/{zone_id}",
            "source_support": "unimplemented",
            "work_package": "fixture",
            "approvals": ["future_batch_not_yet_approved"],
            "blockers": [],
        },
    }
    entry.update(overrides)
    return entry


def review_document(entries, **overrides):
    document = {
        "format": "flarego.cloudflare-audit.review/1",
        "generated_from": {"review_state": "proposed_not_approved"},
        "blockers": [
            {"code": "authorized_account_absent", "detail": "no authorized account evidence"},
            {"code": "user_approval_absent", "detail": "no per-operation approval"},
        ],
        "operations": entries,
    }
    document.update(overrides)
    return document


def catalog_row(operation_id, method, path, tags, **overrides):
    row = {
        "operation_id": operation_id,
        "method": method,
        "path": path,
        "tags": " | ".join(tags),
        "summary": "",
        "flarego_status": "not_implemented",
        "coverage_note": "仓库真实适配器未接入；官方可用性与验收范围待逐项核对",
        "test_evidence": "none",
        "deprecated_boolean": "false",
        "deprecation_signals": "",
        "official_availability": "unspecified",
        "official_stability": "unspecified",
        "token_permissions_raw": "{}",
        "security_raw": "[]",
        "plan_availability_raw": "{}",
        "forge_hidden": "null",
        "fern_ignore": "null",
        "request_content_types": "",
        "response_content_types": "",
    }
    row.update(overrides)
    return row


class ReviewValidationTest(unittest.TestCase):
    def setUp(self):
        self.operations_without_permissions = [
            operation("no-permission-metadata", "GET", "/zones/{zone_id}/things", ["Things"])
        ]
        self.review = review_document(
            {
                "no-permission-metadata": review_entry(
                    product_id="unclassified-review",
                    phase="P8",
                    evidence={
                        "assignment": "tag: Things",
                        "scope_basis": "path scope /zones/{zone_id}",
                        "source_support": "unimplemented",
                        "work_package": "unclassified-review",
                        "approvals": ["future_batch_not_yet_approved"],
                        "gap": "product table has no Things row",
                        "blockers": ["permission_metadata_absent", "unclassified_product"],
                    },
                )
            }
        )

    def test_unknown_permission_does_not_grant_availability(self):
        report = summarize_review(self.operations_without_permissions, self.review)
        self.assertEqual(0, report["verified"])
        self.assertEqual(1, report["blocked"])

    def test_unknown_permission_keeps_documented_unverified(self):
        entries = self.review["operations"]
        self.assertNotEqual("verified_available", entries["no-permission-metadata"]["availability_decision"])
        self.assertTrue(entries["no-permission-metadata"]["needs_review"])
        self.assertIn("permission_metadata_absent", entries["no-permission-metadata"]["evidence"]["blockers"])

    def test_validate_accepts_the_fixture(self):
        self.assertEqual([], validate_review(self.operations_without_permissions, self.review))

    def test_missing_owner_is_reported(self):
        review = copy.deepcopy(self.review)
        del review["operations"]["no-permission-metadata"]["owner_role"]
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("owner_role" in error for error in errors), errors)

    def test_missing_phase_and_product_are_reported(self):
        review = copy.deepcopy(self.review)
        review["operations"]["no-permission-metadata"]["phase"] = ""
        review["operations"]["no-permission-metadata"]["product_id"] = ""
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("phase" in error for error in errors), errors)
        self.assertTrue(any("product_id" in error for error in errors), errors)

    def test_invalid_enum_values_are_reported(self):
        review = copy.deepcopy(self.review)
        entry = review["operations"]["no-permission-metadata"]
        entry["phase"] = "P10"
        entry["availability_decision"] = "probably_fine"
        entry["target_level"] = "L1"
        entry["scope_kind"] = "source_subset"
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("phase" in error for error in errors), errors)
        self.assertTrue(any("availability_decision" in error for error in errors), errors)
        self.assertTrue(any("target_level" in error for error in errors), errors)
        self.assertTrue(any("scope_kind" in error for error in errors), errors)

    def test_unknown_operation_id_is_reported(self):
        review = copy.deepcopy(self.review)
        review["operations"]["not-in-catalog"] = review_entry()
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("not-in-catalog" in error for error in errors), errors)

    def test_unregistered_operation_is_reported(self):
        operations = self.operations_without_permissions + [
            operation("second-op", "GET", "/zones/{zone_id}/other", ["Things"])
        ]
        errors = validate_review(operations, self.review)
        self.assertTrue(any("second-op" in error for error in errors), errors)

    def test_l0_without_user_approval_is_an_error(self):
        review = copy.deepcopy(self.review)
        review["operations"]["no-permission-metadata"]["target_level"] = "L0"
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("L0" in error and "approval" in error.lower() for error in errors), errors)

    def test_verified_available_requires_authenticated_evidence(self):
        review = copy.deepcopy(self.review)
        review["operations"]["no-permission-metadata"]["availability_decision"] = "verified_available"
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("verified_available" in error for error in errors), errors)

    def test_provider_removed_and_console_only_require_evidence(self):
        for decision in ("provider_removed", "console_only"):
            review = copy.deepcopy(self.review)
            review["operations"]["no-permission-metadata"]["availability_decision"] = decision
            errors = validate_review(self.operations_without_permissions, review)
            self.assertTrue(any(decision in error for error in errors), errors)

    def test_write_and_unknown_semantics_must_stay_blocked(self):
        for semantics in ("write", "unknown"):
            review = copy.deepcopy(self.review)
            entry = review["operations"]["no-permission-metadata"]
            entry["semantics"] = semantics
            entry["evidence"]["blockers"] = ["permission_metadata_absent"]
            errors = validate_review(self.operations_without_permissions, review)
            self.assertTrue(any("manual_risk_review_required" in error for error in errors), errors)

    def test_unclassified_entry_needs_review_and_gap(self):
        review = copy.deepcopy(self.review)
        entry = review["operations"]["no-permission-metadata"]
        entry["needs_review"] = False
        del entry["evidence"]["gap"]
        errors = validate_review(self.operations_without_permissions, review)
        self.assertTrue(any("unclassified-review" in error for error in errors), errors)
        self.assertTrue(any("gap" in error for error in errors), errors)

    def test_source_support_must_match_catalog_status(self):
        rows = [
            catalog_row(
                "no-permission-metadata",
                "GET",
                "/zones/{zone_id}/things",
                ["Things"],
                flarego_status="implemented_subset",
            )
        ]
        errors = validate_review(rows, self.review)
        self.assertTrue(any("source_support" in error for error in errors), errors)

    def test_selected_ids_scopes_validation_without_mutating_the_ledger(self):
        operations = [
            operation(
                "keep",
                "GET",
                "/zones/{zone_id}/keep",
                ["Zone Settings"],
                {"x-api-token-group": ["Zone Settings Read"]},
            ),
            operation(
                "skip",
                "GET",
                "/zones/{zone_id}/skip",
                ["Zone Settings"],
                {"x-api-token-group": ["Zone Settings Read"]},
            ),
        ]
        review = review_document(
            {"keep": review_entry(), "skip": review_entry(phase="not-a-phase")}
        )
        snapshot = copy.deepcopy(review)
        errors = validate_review(operations, review, selected_ids={"keep"})
        self.assertEqual([], errors)
        self.assertEqual(snapshot, review)
        self.assertTrue(
            any("phase" in error for error in validate_review(operations, review))
        )

    def test_selected_ids_reports_unknown_selection(self):
        errors = validate_review(
            self.operations_without_permissions, self.review, selected_ids={"missing-id"}
        )
        self.assertTrue(any("missing-id" in error for error in errors), errors)

    def test_missing_operations_index_is_reported(self):
        errors = validate_review(self.operations_without_permissions, {"format": "x"})
        self.assertTrue(any("operations" in error for error in errors), errors)

    def test_load_review_rejects_duplicate_keys(self):
        duplicated = (
            '{"format": "x", "operations": {"dup": '
            + json.dumps(review_entry())
            + ', "dup": '
            + json.dumps(review_entry())
            + "}}"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "review.json"
            path.write_text(duplicated, encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                load_review(path)
        self.assertIn("dup", str(caught.exception))

    def test_review_fields_are_the_fixed_contract(self):
        self.assertEqual(14, len(REVIEW_FIELDS))
        self.assertEqual(set(REVIEW_FIELDS), set(review_entry()))
        self.assertIn("P9", PHASES)
        self.assertIn("L3", TARGET_LEVELS)
        self.assertIn("unknown", SEMANTICS)
        self.assertIn("console_only", AVAILABILITY_DECISIONS)
        self.assertIn("migration_required", DEPRECATION_DECISIONS)

    def test_owner_roles_match_configured_profiles(self):
        self.assertEqual(
            ("日常工作", "方案设计，架构思考", "Gemini", "高消耗工作"), OWNER_ROLES
        )
        self.assertEqual("日常工作", DEFAULT_OWNER_ROLE)
        self.assertEqual("方案设计，架构思考", ACCEPTANCE_OWNER_ROLE)


class ScopeKindTest(unittest.TestCase):
    """scope_kind is the API authorization/resource scope, not support status."""

    def setUp(self):
        self.implemented = {
            "baseline_commit": "b" * 40,
            "operations": {
                "zone-op": {"method": "GET", "path": "/zones/{zone_id}/records"},
            },
        }
        self.operations = [
            operation("zone-op", "GET", "/zones/{zone_id}/records", ["DNS Records for a Zone"]),
            operation("zone-other", "GET", "/zones/{zone_id}/other", ["DNS Records for a Zone"]),
            operation("account-op", "GET", "/accounts/{account_id}/things", ["Accounts"]),
            operation("user-op", "GET", "/user/tokens", ["User API Tokens"]),
            operation("org-op", "GET", "/organizations/{organization_id}", ["Organizations"]),
            operation("principal-op", "GET", "/accounts", ["Accounts"]),
            operation("global-op", "GET", "/radar/bgp/timeseries", ["Radar BGP"]),
            operation("tenant-op", "GET", "/tenants/{tenant_id}/accounts", ["Tenants"]),
            operation("plain-op", "GET", "/analytics/sql", ["Analytics SQL"]),
        ]

    def scopes(self):
        review = propose_review(self.operations, self.implemented)
        return {key: entry["scope_kind"] for key, entry in review["operations"].items()}

    def test_scope_kinds_are_api_scopes(self):
        scopes = self.scopes()
        self.assertEqual("zone", scopes["zone-op"])
        self.assertEqual("account", scopes["account-op"])
        self.assertEqual("user", scopes["user-op"])
        self.assertEqual("organization", scopes["org-op"])
        self.assertEqual("global", scopes["global-op"])
        self.assertEqual("principal", scopes["principal-op"])
        self.assertEqual("unknown", scopes["tenant-op"])

    def test_scope_kind_never_encodes_source_support(self):
        review = propose_review(self.operations, self.implemented)
        for entry in review["operations"].values():
            self.assertIn(entry["scope_kind"], SCOPE_KINDS)
            self.assertNotIn(entry["scope_kind"], ("source_subset", "unimplemented"))

    def test_supported_and_unsupported_share_the_same_scope(self):
        review = propose_review(self.operations, self.implemented)
        self.assertEqual("zone", review["operations"]["zone-op"]["scope_kind"])
        self.assertEqual("zone", review["operations"]["zone-other"]["scope_kind"])
        self.assertEqual(
            "source_subset", review["operations"]["zone-op"]["evidence"]["source_support"]
        )
        self.assertEqual(
            "unimplemented", review["operations"]["zone-other"]["evidence"]["source_support"]
        )

    def test_unknown_scope_is_explicit_and_never_silently_global(self):
        review = propose_review(self.operations, self.implemented)
        tenant = review["operations"]["tenant-op"]
        self.assertEqual("unknown", tenant["scope_kind"])
        self.assertTrue(tenant["needs_review"])
        self.assertTrue(tenant["evidence"]["scope_basis"])
        self.assertNotEqual("global", tenant["scope_kind"])

    def test_credential_scoped_path_without_id_is_principal(self):
        review = propose_review(self.operations, self.implemented)
        self.assertEqual("principal", review["operations"]["plain-op"]["scope_kind"])
        self.assertEqual("principal", review["operations"]["principal-op"]["scope_kind"])

    def test_summary_implemented_uses_source_evidence_not_scope_kind(self):
        operations = [
            operation("zone-op", "GET", "/zones/{zone_id}/records", ["DNS Records for a Zone"]),
        ]
        review = review_document(
            {
                "zone-op": review_entry(
                    scope_kind="zone",
                    evidence={
                        "assignment": "tag: Fixture",
                        "source_support": "source_subset",
                        "blockers": [],
                    },
                )
            }
        )
        report = summarize_review(operations, review)
        self.assertEqual(1, report["implemented"])
        self.assertEqual({"zone": 1}, report["by_scope_kind"])
        lying = copy.deepcopy(review)
        lying["operations"]["zone-op"]["evidence"]["source_support"] = "unimplemented"
        lying["operations"]["zone-op"]["scope_kind"] = "account"
        self.assertEqual(0, summarize_review(operations, lying)["implemented"])

    def test_raw_and_catalog_inputs_produce_identical_entries(self):
        raw_operations = [
            operation(
                "zone-op",
                "GET",
                "/zones/{zone_id}/records",
                ["DNS Records for a Zone"],
                {"x-fern-availability": "generally-available", "x-api-token-group": ["DNS Read"]},
            ),
            operation("zone-other", "GET", "/zones/{zone_id}/other", ["DNS Records for a Zone"]),
        ]
        rows = [
            catalog_row(
                "zone-op",
                "GET",
                "/zones/{zone_id}/records",
                ["DNS Records for a Zone"],
                flarego_status="implemented_subset",
                official_availability="generally-available",
                token_permissions_raw='{"x-api-token-group": ["DNS Read"]}',
            ),
            catalog_row("zone-other", "GET", "/zones/{zone_id}/other", ["DNS Records for a Zone"]),
        ]
        from_raw = propose_review(raw_operations, self.implemented)
        from_rows = propose_review(rows, self.implemented)
        self.assertEqual(from_raw["operations"], from_rows["operations"])
        self.assertEqual(
            validate_review(rows, from_rows), validate_review(raw_operations, from_raw)
        )


class BlockerIntegrityTest(unittest.TestCase):
    """Facts, not hand-written strings, decide blocked/verified."""

    def setUp(self):
        self.operations = [
            operation("no-permissions", "GET", "/zones/{zone_id}/things", ["Zone Settings"]),
            operation(
                "hidden", "GET", "/zones/{zone_id}/hidden", ["Zone Settings"], {"x-forge-hidden": True}
            ),
        ]

    def test_editing_blockers_cannot_whitelist_missing_permissions(self):
        review = review_document(
            {
                "no-permissions": review_entry(
                    product_id="zones-and-zone-settings",
                    phase="P2",
                    evidence={
                        "assignment": "tag: Zone Settings",
                        "scope_basis": "path scope /zones/{zone_id}",
                        "source_support": "unimplemented",
                        "work_package": "zones-and-zone-settings",
                        "approvals": ["future_batch_not_yet_approved"],
                        "blockers": [],
                    },
                ),
                "hidden": review_entry(
                    product_id="zones-and-zone-settings",
                    phase="P2",
                    evidence={
                        "assignment": "tag: Zone Settings",
                        "scope_basis": "path scope /zones/{zone_id}",
                        "source_support": "unimplemented",
                        "work_package": "zones-and-zone-settings",
                        "approvals": ["future_batch_not_yet_approved"],
                        "blockers": [],
                    },
                ),
            }
        )
        report = summarize_review(self.operations, review)
        self.assertEqual(2, report["blocked"])
        self.assertEqual(2, report["blockers"]["permission_metadata_absent"])
        errors = validate_review(self.operations, review)
        self.assertTrue(any("permission_metadata_absent" in error for error in errors), errors)

    def test_editing_blockers_cannot_whitelist_forge_hidden(self):
        review = review_document(
            {
                "no-permissions": review_entry(
                    product_id="zones-and-zone-settings",
                    phase="P2",
                    evidence={
                        "assignment": "tag: Zone Settings",
                        "scope_basis": "path scope /zones/{zone_id}",
                        "source_support": "unimplemented",
                        "work_package": "zones-and-zone-settings",
                        "approvals": ["future_batch_not_yet_approved"],
                        "blockers": [],
                    },
                ),
                "hidden": review_entry(
                    product_id="zones-and-zone-settings",
                    phase="P2",
                    evidence={
                        "assignment": "tag: Zone Settings",
                        "scope_basis": "path scope /zones/{zone_id}",
                        "source_support": "unimplemented",
                        "work_package": "zones-and-zone-settings",
                        "approvals": ["future_batch_not_yet_approved"],
                        "blockers": [],
                    },
                ),
            }
        )
        errors = validate_review(self.operations, review)
        self.assertTrue(any("forge_hidden_flag_requires_review" in error for error in errors), errors)

    def test_verified_requires_a_record_and_a_cleared_global_blocker(self):
        operations = [
            operation(
                "permitted",
                "GET",
                "/zones/{zone_id}/settings/always_use_https",
                ["Zone Settings"],
                {"x-api-token-group": ["Zone Settings Read"]},
            )
        ]
        entry = review_entry(
            product_id="zones-and-zone-settings",
            phase="P2",
            availability_decision="verified_available",
            evidence={
                "assignment": "tag: Zone Settings",
                "scope_basis": "path scope /zones/{zone_id}",
                "source_support": "unimplemented",
                "work_package": "zones-and-zone-settings",
                "approvals": ["future_batch_not_yet_approved"],
                "acceptance": "authenticated_account",
                "blockers": [],
            },
        )
        review = review_document({"permitted": entry})
        self.assertEqual(0, summarize_review(operations, review)["verified"])
        self.assertTrue(any("acceptance_record" in error for error in validate_review(operations, review)))

        with_record = copy.deepcopy(review)
        with_record["operations"]["permitted"]["evidence"]["acceptance_record"] = {
            "environment": "isolated",
            "date": "2026-10-08",
        }
        self.assertEqual(0, summarize_review(operations, with_record)["verified"])

        cleared = copy.deepcopy(with_record)
        cleared["blockers"] = [
            blocker for blocker in cleared["blockers"] if blocker["code"] != "authorized_account_absent"
        ]
        cleared["operations"]["permitted"]["evidence"]["user_approval"] = "coordinator:2026-10-08"
        self.assertEqual(1, summarize_review(operations, cleared)["verified"])

    def test_capability_verified_is_reported_separately(self):
        operations = [
            operation(
                "permitted",
                "GET",
                "/zones/{zone_id}/settings/always_use_https",
                ["Zone Settings"],
                {"x-api-token-group": ["Zone Settings Read"]},
            )
        ]
        entry = review_entry(
            product_id="zones-and-zone-settings",
            phase="P2",
            availability_decision="verified_available",
            evidence={
                "assignment": "tag: Zone Settings",
                "scope_basis": "path scope /zones/{zone_id}",
                "source_support": "unimplemented",
                "work_package": "zones-and-zone-settings",
                "approvals": ["future_batch_not_yet_approved"],
                "acceptance": "authenticated_account",
                "acceptance_record": {"environment": "isolated", "date": "2026-10-08"},
                "blockers": [],
            },
        )
        review = review_document({"permitted": entry})
        review["blockers"] = []
        report = summarize_review(operations, review)
        self.assertEqual(1, report["verified"])
        self.assertEqual(0, report["capability_verified"])


class ProposalTest(unittest.TestCase):
    def setUp(self):
        self.implemented = {
            "baseline_commit": "b" * 40,
            "operations": {
                "dns-list": {"method": "GET", "path": "/zones/{zone_id}/dns_records"},
                "dns-delete": {"method": "DELETE", "path": "/zones/{zone_id}/dns_records/{id}"},
            },
        }
        self.operations = [
            operation(
                "dns-list",
                "GET",
                "/zones/{zone_id}/dns_records",
                ["DNS Records for a Zone"],
                {"x-fern-availability": "generally-available", "x-api-token-group": ["DNS Read"]},
            ),
            operation(
                "dns-delete",
                "DELETE",
                "/zones/{zone_id}/dns_records/{id}",
                ["DNS Records for a Zone"],
                {"x-fern-availability": "generally-available"},
            ),
            operation(
                "sql-query",
                "POST",
                "/accounts/{account_id}/d1/database/{id}/query",
                ["D1"],
                {"summary": "Query D1 database"},
            ),
            operation(
                "mystery",
                "GET",
                "/accounts/{account_id}/mystery/things",
                ["Totally Unknown Product"],
                {},
            ),
        ]

    def test_proposal_covers_every_operation_with_owner_and_phase(self):
        review = propose_review(self.operations, self.implemented)
        self.assertEqual(
            {op["operation_id"] for op in self.operations}, set(review["operations"])
        )
        for operation_id, entry in review["operations"].items():
            self.assertTrue(entry["owner_role"], operation_id)
            self.assertIn(entry["phase"], PHASES, operation_id)
            self.assertTrue(entry["product_id"], operation_id)
            self.assertEqual(set(REVIEW_FIELDS), set(entry), operation_id)

    def test_proposal_never_claims_verified_or_full_support(self):
        review = propose_review(self.operations, self.implemented)
        report = summarize_review(self.operations, review)
        self.assertEqual(0, report["verified"])
        self.assertEqual(4, report["registered"])
        self.assertEqual(4, report["needs_review"])
        self.assertEqual(2, report["implemented"])
        dns_list = review["operations"]["dns-list"]
        self.assertEqual("source_subset", dns_list["evidence"]["source_support"])
        self.assertIn(dns_list["target_level"], ("L2", "L3"))
        self.assertNotEqual("verified_available", dns_list["availability_decision"])

    def test_post_semantics_stay_unknown_and_blocked(self):
        review = propose_review(self.operations, self.implemented)
        sql = review["operations"]["sql-query"]
        self.assertEqual("unknown", sql["semantics"])
        self.assertIn("manual_risk_review_required", sql["evidence"]["blockers"])
        self.assertNotEqual("L0", sql["target_level"])

    def test_unknown_product_goes_to_unclassified_review(self):
        review = propose_review(self.operations, self.implemented)
        mystery = review["operations"]["mystery"]
        self.assertEqual("unclassified-review", mystery["product_id"])
        self.assertTrue(mystery["needs_review"])
        self.assertTrue(mystery["evidence"]["gap"])
        self.assertIn("unclassified_product", mystery["evidence"]["blockers"])

    def test_deprecated_operation_keeps_target_and_legacy_marking(self):
        operations = [
            operation(
                "legacy-op",
                "GET",
                "/zones/{zone_id}/page_rules",
                ["Page Rules"],
                {"deprecated": True, "x-fern-availability": "deprecated"},
            )
        ]
        review = propose_review(operations, {"operations": {}})
        entry = review["operations"]["legacy-op"]
        self.assertEqual("deprecated", entry["deprecation_decision"])
        self.assertIn(entry["target_level"], ("L2", "L3"))
        report = summarize_review(operations, review)
        self.assertEqual(1, report["legacy"])
        self.assertEqual(1, report["registered"])

    def test_sdk_ga_with_alpha_summary_is_a_conflict(self):
        operations = [
            operation(
                "usage",
                "GET",
                "/accounts/{account_id}/billable/usage",
                ["Billable Usage"],
                {"summary": "Alpha / Restricted usage", "x-fern-availability": "generally-available"},
            )
        ]
        review = propose_review(operations, {"operations": {}})
        entry = review["operations"]["usage"]
        self.assertIn("availability_conflict", entry["evidence"]["blockers"])
        self.assertEqual("documented_unverified", entry["availability_decision"])

    def test_forge_hidden_alone_is_not_removal(self):
        operations = [
            operation(
                "hidden",
                "GET",
                "/accounts/{account_id}/internal/thing",
                ["Accounts"],
                {"x-forge-hidden": True},
            )
        ]
        review = propose_review(operations, {"operations": {}})
        entry = review["operations"]["hidden"]
        self.assertNotEqual("provider_removed", entry["availability_decision"])
        self.assertIn("forge_hidden_flag_requires_review", entry["evidence"]["blockers"])
        self.assertNotEqual("L0", entry["target_level"])

    def test_proposal_is_deterministic_and_validation_clean(self):
        first = propose_review(self.operations, self.implemented)
        second = propose_review(copy.deepcopy(self.operations), self.implemented)
        self.assertEqual(first, second)
        self.assertEqual([], validate_review(self.operations, first))

    def test_normalization_accepts_raw_and_catalog_inputs(self):
        raw = normalize_operations(self.operations)
        rows = [
            catalog_row(
                "dns-list",
                "GET",
                "/zones/{zone_id}/dns_records",
                ["DNS Records for a Zone"],
                flarego_status="implemented_subset",
                coverage_note="records；partial",
                official_availability="generally-available",
                token_permissions_raw='{"x-api-token-group": ["DNS Read"]}',
            )
        ]
        normalized_rows = normalize_operations(rows)
        self.assertEqual(["DNS Records for a Zone"], normalized_rows[0]["tags"])
        self.assertEqual("implemented_subset", normalized_rows[0]["flarego_status"])
        self.assertTrue(normalized_rows[0]["permissions_present"])
        self.assertEqual("generally-available", raw[0]["availability"])
        self.assertIsNone(raw[0]["flarego_status"])
        self.assertTrue(raw[0]["permissions_present"])

    def test_summary_distinguishes_the_seven_states(self):
        review = propose_review(self.operations, self.implemented)
        report = summarize_review(self.operations, review)
        for key in (
            "registered",
            "target",
            "implemented",
            "verified",
            "legacy",
            "blocked",
            "capability_verified",
            "needs_review",
        ):
            self.assertIn(key, report)
        self.assertEqual(4, report["registered"])
        self.assertEqual(1, report["unclassified"])
        self.assertGreaterEqual(report["blocked"], 1)
        self.assertIn("blockers", report)

    def test_approved_batch_lists_are_recorded_per_operation(self):
        operations = [
            operation("p1-new", "GET", "/accounts", ["Accounts"]),
            operation("p1-compat", "GET", "/zones/{zone_id}/dns_records", ["DNS Records for a Zone"]),
            operation("p2-candidate", "GET", "/zones/{zone_id}", ["Zone"]),
            operation("p2-not-approved", "GET", "/zones/{zone_id}/settings/always_use_https", ["Zone Settings"]),
        ]
        implemented = {
            "operations": {
                "p1-compat": {"method": "GET", "path": "/zones/{zone_id}/dns_records"},
            }
        }
        overrides = {
            "p1-new": {
                "phase": "P1",
                "approvals": ["p1_approved_new_request"],
                "work_package": "P1-account-discovery",
            },
            "p1-compat": {
                "phase": "P1",
                "approvals": ["p1_existing_compat"],
                "work_package": "P1-compat",
            },
            "p2-candidate": {
                "phase": "P2",
                "approvals": ["p2_candidate"],
                "work_package": "P2-T3",
            },
        }
        review = propose_review(operations, implemented, phase_overrides=overrides)
        entries = review["operations"]
        self.assertEqual(["p1_approved_new_request"], entries["p1-new"]["evidence"]["approvals"])
        self.assertEqual(["p1_existing_compat"], entries["p1-compat"]["evidence"]["approvals"])
        self.assertEqual(["p2_candidate"], entries["p2-candidate"]["evidence"]["approvals"])
        self.assertEqual(
            ["future_batch_not_yet_approved"], entries["p2-not-approved"]["evidence"]["approvals"]
        )
        self.assertEqual("P2", entries["p2-not-approved"]["phase"])
        self.assertEqual("P2-T3", entries["p2-candidate"]["evidence"]["work_package"])
        report = summarize_review(operations, review)
        self.assertEqual(
            {
                "p1_approved_new_request": 1,
                "p1_existing_compat": 1,
                "p2_candidate": 1,
                "future_batch_not_yet_approved": 1,
            },
            report["by_implementation_approval"],
        )

    def test_phase_override_must_stay_a_known_phase(self):
        with self.assertRaises(ValueError):
            propose_review(
                [operation("x", "GET", "/zones/{zone_id}", ["Zone"])],
                {"operations": {}},
                phase_overrides={"x": {"phase": "P99", "approvals": []}},
            )

    def test_product_phase_breakdown_survives_mixed_batches(self):
        operations = [
            operation("p1-new", "GET", "/accounts", ["Accounts"]),
            operation("later", "GET", "/accounts/{account_id}", ["Account"]),
        ]
        overrides = {
            "p1-new": {"phase": "P1", "approvals": ["p1_approved_new_request"], "work_package": "P1"},
            "later": {"phase": "P8", "approvals": [], "work_package": "later"},
        }
        review = propose_review(operations, {"operations": {}}, phase_overrides=overrides)
        report = summarize_review(operations, review)
        product_id = review["operations"]["p1-new"]["product_id"]
        self.assertEqual(product_id, review["operations"]["later"]["product_id"])
        self.assertEqual({"P1": 1, "P8": 1}, report["by_product"][product_id]["by_phase"])
        self.assertEqual(["P1", "P8"], report["by_product"][product_id]["phases"])


class RealLedgerTest(unittest.TestCase):
    """Checks the committed ledger against the committed catalog (offline)."""

    def setUp(self):
        if not REVIEW.exists():
            self.skipTest("review.json not generated yet")
        self.operations = load_catalog_operations(CATALOG)
        self.review = load_review(REVIEW)

    def test_real_ledger_is_structurally_clean(self):
        self.assertEqual([], validate_review(self.operations, self.review))

    def test_real_ledger_covers_the_pinned_catalog(self):
        report = summarize_review(self.operations, self.review)
        self.assertEqual(3659, report["registered"])
        self.assertEqual(11, report["implemented"])
        self.assertEqual(0, report["verified"])
        self.assertEqual(0, report["capability_verified"])
        self.assertEqual(3659, report["needs_review"])
        self.assertNotIn("P10", report["by_phase"])

    def test_real_ledger_has_no_verified_or_l0_entries(self):
        for operation_id, entry in self.review["operations"].items():
            self.assertNotEqual("verified_available", entry["availability_decision"], operation_id)
            self.assertNotEqual("L0", entry["target_level"], operation_id)
            self.assertNotEqual("provider_removed", entry["availability_decision"], operation_id)

    def test_real_ledger_scope_is_not_support_status(self):
        scopes = {entry["scope_kind"] for entry in self.review["operations"].values()}
        self.assertNotIn("source_subset", scopes)
        self.assertNotIn("unimplemented", scopes)
        self.assertTrue(scopes <= set(SCOPE_KINDS))
        self.assertIn("account", scopes)
        self.assertIn("zone", scopes)
        self.assertIn("user", scopes)

    def test_real_ledger_approved_batches_are_explicit(self):
        report = summarize_review(self.operations, self.review)
        approvals = report["by_implementation_approval"]
        self.assertEqual(5, approvals["p1_approved_new_request"])
        self.assertEqual(11, approvals["p1_existing_compat"])
        self.assertEqual(54, approvals["p2_candidate"])
        self.assertGreater(approvals["future_batch_not_yet_approved"], 3500)

    def test_real_ledger_unclassified_is_explicit(self):
        report = summarize_review(self.operations, self.review)
        self.assertGreaterEqual(report["unclassified"], 1)
        unclassified = [
            operation_id
            for operation_id, entry in self.review["operations"].items()
            if entry["product_id"] == "unclassified-review"
        ]
        self.assertTrue(unclassified)
        for operation_id in unclassified:
            entry = self.review["operations"][operation_id]
            self.assertTrue(entry["needs_review"], operation_id)
            self.assertTrue(entry["evidence"]["gap"], operation_id)

    def test_real_ledger_products_have_phase_owner_and_spec(self):
        for operation_id, entry in self.review["operations"].items():
            self.assertTrue(entry["owner_role"], operation_id)
            self.assertIn(entry["phase"], PHASES, operation_id)
            self.assertTrue(entry["evidence"]["spec_reference"], operation_id)
            self.assertTrue(entry["evidence"]["scope_basis"], operation_id)

    def test_p2_candidate_file_matches_the_ledger(self):
        candidates = load_p2_candidates(P2_CANDIDATES)
        self.assertEqual(54, len(candidates))
        for operation_id, row in candidates.items():
            entry = self.review["operations"][operation_id]
            self.assertIn("p2_candidate", entry["evidence"]["approvals"], operation_id)
            self.assertEqual(row["proposed_task"], entry["evidence"]["work_package"], operation_id)
            if row["baseline_status"] != "implemented_subset":
                self.assertEqual("P2", entry["phase"], operation_id)

    def test_cli_check_is_clean_and_read_only(self):
        before = CATALOG.read_bytes(), REVIEW.read_bytes()
        result = cli.check_ledger(REVIEW, CATALOG)
        self.assertEqual([], result["errors"])
        self.assertEqual(3659, result["summary"]["registered"])
        self.assertEqual(before, (CATALOG.read_bytes(), REVIEW.read_bytes()))

    def test_cli_check_fails_on_a_broken_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            broken = pathlib.Path(directory) / "review.json"
            document = json.loads(REVIEW.read_text(encoding="utf-8"))
            next(iter(document["operations"].values()))["owner_role"] = ""
            broken.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
            result = cli.check_ledger(broken, CATALOG)
            self.assertTrue(result["errors"])
            self.assertNotEqual(0, cli.main(["check", "--review", str(broken), "--catalog", str(CATALOG)]))


if __name__ == "__main__":
    unittest.main()
