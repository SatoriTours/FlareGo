"""Offline tests for the Cloudflare coverage ownership/qualification ledger.

All fixtures are local and synthetic; the real catalog/ledger checks only read
committed repository files. No network access, no credentials, no cloud calls.
"""
import copy
import csv
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
    account_blocker_active,
    cross_check_schema,
    identity_errors,
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
    """Facts, not hand-written strings, decide blocked/verified.

    Every positive case here is explicitly synthetic: it proves the gate can be
    satisfied, never that a real Cloudflare account was tested.
    """

    def setUp(self):
        self.operations = [
            operation("no-permissions", "GET", "/zones/{zone_id}/things", ["Zone Settings"]),
            operation(
                "hidden", "GET", "/zones/{zone_id}/hidden", ["Zone Settings"], {"x-forge-hidden": True}
            ),
        ]

    def bare_evidence(self, blockers=None):
        return {
            "assignment": "tag: Zone Settings",
            "scope_basis": "path scope /zones/{zone_id}",
            "source_support": "unimplemented",
            "work_package": "zones-and-zone-settings",
            "approvals": ["future_batch_not_yet_approved"],
            "blockers": list(blockers or []),
        }

    def review_without_blockers(self):
        return review_document(
            {
                "no-permissions": review_entry(
                    product_id="zones-and-zone-settings", phase="P2", evidence=self.bare_evidence()
                ),
                "hidden": review_entry(
                    product_id="zones-and-zone-settings", phase="P2", evidence=self.bare_evidence()
                ),
            }
        )

    def test_editing_blockers_cannot_whitelist_missing_permissions(self):
        report = summarize_review(self.operations, self.review_without_blockers())
        self.assertEqual(2, report["blocked"])
        self.assertEqual(2, report["blockers"]["permission_metadata_absent"])
        errors = validate_review(self.operations, self.review_without_blockers())
        self.assertTrue(any("permission_metadata_absent" in error for error in errors), errors)

    def test_editing_blockers_cannot_whitelist_forge_hidden(self):
        errors = validate_review(self.operations, self.review_without_blockers())
        self.assertTrue(any("forge_hidden_flag_requires_review" in error for error in errors), errors)

    def permitted_operation(self):
        return operation(
            "permitted",
            "GET",
            "/zones/{zone_id}/settings/always_use_https",
            ["Zone Settings"],
            {"x-api-token-group": ["Zone Settings Read"]},
        )

    def passing_entry(self, **overrides):
        entry = review_entry(
            product_id="zones-and-zone-settings",
            phase="P2",
            availability_decision="verified_available",
            needs_review=False,
            evidence={
                "assignment": "tag: Zone Settings",
                "scope_basis": "path scope /zones/{zone_id}",
                "source_support": "unimplemented",
                "work_package": "zones-and-zone-settings",
                "approvals": ["future_batch_not_yet_approved"],
                "acceptance": "authenticated_account",
                "acceptance_record": {"environment": "synthetic-fixture", "date": "2026-10-08"},
                "capability_acceptance": "authenticated_account",
                "capability_record": {"environment": "synthetic-fixture", "date": "2026-10-08"},
                "blockers": [],
            },
        )
        entry.update(overrides)
        return entry

    def cleared_review(self, entries):
        return review_document(
            entries,
            blockers=[
                {
                    "code": "authorized_account_absent",
                    "detail": "synthetic clearance fixture",
                    "cleared_by": "synthetic-fixture",
                    "clearance_record": {"environment": "synthetic-fixture", "date": "2026-10-08"},
                }
            ],
        )

    def test_missing_global_blocker_is_not_clearance(self):
        operations = [self.permitted_operation()]
        review = review_document({"permitted": self.passing_entry()})
        review["blockers"] = []
        self.assertTrue(account_blocker_active(review))
        self.assertEqual(0, summarize_review(operations, review)["verified"])
        review["blockers"] = [{"code": "user_approval_absent", "detail": "x"}]
        self.assertTrue(account_blocker_active(review))
        self.assertEqual(0, summarize_review(operations, review)["verified"])
        del review["blockers"]
        self.assertTrue(account_blocker_active(review))
        self.assertEqual(0, summarize_review(operations, review)["verified"])

    def test_explicit_clearance_needs_a_reference_and_a_record(self):
        operations = [self.permitted_operation()]
        partial = review_document(
            {"permitted": self.passing_entry()},
            blockers=[{"code": "authorized_account_absent", "detail": "x", "cleared_by": "fixture"}],
        )
        self.assertEqual(0, summarize_review(operations, partial)["verified"])
        complete = self.cleared_review({"permitted": self.passing_entry()})
        report = summarize_review(operations, complete)
        self.assertFalse(report["authorized_account_blocker_active"])
        self.assertEqual(1, report["verified"])
        self.assertEqual(1, report["capability_verified"])
        self.assertEqual([], validate_review(operations, complete))

    def test_duplicate_account_blocker_records_never_clear(self):
        operations = [self.permitted_operation()]
        cleared = {
            "code": "authorized_account_absent",
            "detail": "synthetic clearance",
            "cleared_by": "synthetic-fixture",
            "clearance_record": {"environment": "synthetic-fixture", "date": "2026-10-08"},
        }
        uncleared = {"code": "authorized_account_absent", "detail": "synthetic uncleared duplicate"}
        for records in ([cleared, uncleared], [uncleared, cleared]):
            document = review_document({"permitted": self.passing_entry()}, blockers=records)
            self.assertTrue(account_blocker_active(document), records)
            report = summarize_review(operations, document)
            self.assertEqual(0, report["verified"], records)
            self.assertEqual(0, report["capability_verified"], records)
            errors = validate_review(operations, document)
            self.assertTrue(
                any("authorized_account_absent" in error and "duplicate" in error for error in errors),
                (records, errors),
            )

    def test_malformed_account_blocker_record_cannot_clear(self):
        operations = [self.permitted_operation()]
        malformed = (
            {},
            {"cleared_by": "synthetic-fixture"},
            {"clearance_record": {"environment": "synthetic-fixture"}},
            {"cleared_by": 42, "clearance_record": {"environment": "synthetic-fixture"}},
            {"cleared_by": "synthetic-fixture", "clearance_record": []},
            {"cleared_by": "synthetic-fixture", "clearance_record": {}},
            {"cleared_by": "  ", "clearance_record": {"environment": "synthetic-fixture"}},
        )
        for record in malformed:
            document = review_document(
                {"permitted": self.passing_entry()},
                blockers=[{"code": "authorized_account_absent", **record}],
            )
            self.assertTrue(account_blocker_active(document), record)
            self.assertEqual(0, summarize_review(operations, document)["verified"], record)
        self.assertEqual(
            1,
            summarize_review(operations, self.cleared_review({"permitted": self.passing_entry()}))["verified"],
        )

    def test_blocked_entry_cannot_claim_capability_verified(self):
        operations = [self.permitted_operation()]
        entry = self.passing_entry()
        entry["evidence"]["blockers"] = ["permission_metadata_absent"]
        review = self.cleared_review({"permitted": entry})
        report = summarize_review(operations, review)
        self.assertEqual(0, report["verified"])
        self.assertEqual(0, report["capability_verified"])

    def test_pending_review_cannot_claim_capability_verified(self):
        operations = [self.permitted_operation()]
        entry = self.passing_entry(needs_review=True)
        review = self.cleared_review({"permitted": entry})
        self.assertEqual(0, summarize_review(operations, review)["capability_verified"])

    def test_unknown_scope_cannot_claim_capability_verified(self):
        operations = [self.permitted_operation()]
        entry = self.passing_entry(scope_kind="unknown")
        review = self.cleared_review({"permitted": entry})
        self.assertEqual(0, summarize_review(operations, review)["capability_verified"])

    def test_opaque_capability_record_is_rejected(self):
        operations = [self.permitted_operation()]
        for record in (True, "fixture", [], {}):
            entry = self.passing_entry()
            entry["evidence"]["capability_record"] = record
            review = self.cleared_review({"permitted": entry})
            self.assertEqual(
                0, summarize_review(operations, review)["capability_verified"], repr(record)
            )
            self.assertTrue(
                any("capability_record" in error for error in validate_review(operations, review)),
                record,
            )

    def test_l0_requires_reason_and_user_approval_reference(self):
        operations = [self.permitted_operation()]
        base = review_entry(
            product_id="zones-and-zone-settings",
            phase="P2",
            target_level="L0",
            evidence=self.bare_evidence(),
        )
        without_reason = review_document({"permitted": copy.deepcopy(base)})
        without_reason["operations"]["permitted"]["evidence"]["user_approval"] = {
            "source": "user",
            "reference": "synthetic-fixture",
        }
        errors = validate_review(operations, without_reason)
        self.assertTrue(any("reduction_reason" in error for error in errors), errors)

        without_approval = review_document({"permitted": copy.deepcopy(base)})
        without_approval["operations"]["permitted"]["evidence"]["reduction_reason"] = "synthetic reason"
        errors = validate_review(operations, without_approval)
        self.assertTrue(any("user_approval" in error for error in errors), errors)

        complete = review_document({"permitted": copy.deepcopy(base)})
        complete["operations"]["permitted"]["evidence"]["reduction_reason"] = "synthetic reason"
        complete["operations"]["permitted"]["evidence"]["user_approval"] = {
            "source": "user",
            "reference": "synthetic-fixture",
        }
        self.assertEqual([], validate_review(operations, complete))

    def test_coordinator_note_cannot_masquerade_as_user_approval(self):
        operations = [self.permitted_operation()]
        for approval in (
            True,
            "approved",
            {"source": "coordinator", "reference": "coordinator-note"},
            {"source": "user", "reference": ""},
            {"reference": "no-source"},
        ):
            review = review_document(
                {
                    "permitted": review_entry(
                        product_id="zones-and-zone-settings",
                        phase="P2",
                        target_level="L0",
                        evidence={
                            **self.bare_evidence(),
                            "reduction_reason": "synthetic reason",
                            "user_approval": approval,
                        },
                    )
                }
            )
            errors = validate_review(operations, review)
            self.assertTrue(any("user_approval" in error for error in errors), approval)

    def test_provider_removed_and_console_only_require_exclusion_evidence(self):
        operations = [self.permitted_operation()]
        for decision in ("provider_removed", "console_only"):
            review = review_document(
                {
                    "permitted": review_entry(
                        product_id="zones-and-zone-settings",
                        phase="P2",
                        availability_decision=decision,
                        evidence=self.bare_evidence(),
                    )
                }
            )
            errors = validate_review(operations, review)
            self.assertTrue(any(decision in error for error in errors), errors)
            self.assertTrue(any("reduction_reason" in error for error in errors), errors)
            self.assertTrue(any("user_approval" in error for error in errors), errors)


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


class IdentityIntegrityTest(unittest.TestCase):
    """I1: operationId and METHOD+path identity must be unique and bound."""

    def op(self, operation_id, method="GET", path="/zones/{zone_id}/settings", tags=("Zone Settings",)):
        return operation(
            operation_id, method, path, list(tags), {"x-api-token-group": ["Zone Settings Read"]}
        )

    def ledger(self, entries):
        return review_document(entries)

    def test_duplicate_operation_id_rows_are_rejected_by_validate(self):
        operations = [self.op("fixture-read"), self.op("fixture-read")]
        errors = validate_review(operations, self.ledger({}))
        self.assertTrue(any("duplicate operationId" in error for error in errors), errors)

    def test_duplicate_identity_rows_are_rejected_by_validate(self):
        operations = [self.op("fixture-read"), self.op("fixture-read-copy")]
        errors = validate_review(operations, self.ledger({}))
        self.assertTrue(any("duplicate METHOD+path" in error for error in errors), errors)

    def test_summarize_and_propose_fail_on_duplicate_identities(self):
        operations = [self.op("fixture-read"), self.op("fixture-read")]
        with self.assertRaises(ValueError):
            summarize_review(operations, self.ledger({"fixture-read": review_entry()}))
        with self.assertRaises(ValueError):
            propose_review(operations, {"operations": {}})

    def test_identity_errors_are_reported_even_with_selected_ids(self):
        operations = [self.op("fixture-read"), self.op("fixture-read")]
        errors = validate_review(operations, self.ledger({}), selected_ids={"other"})
        self.assertTrue(any("duplicate operationId" in error for error in errors), errors)

    def test_unknown_implemented_mapping_reference_fails(self):
        implemented = {"operations": {"ghost-op": {"method": "GET", "path": "/zones/{zone_id}/ghost"}}}
        with self.assertRaises(ValueError) as caught:
            propose_review([self.op("fixture-read")], implemented)
        self.assertIn("ghost-op", str(caught.exception))

    def test_unknown_phase_override_reference_fails(self):
        overrides = {"missing-op": {"phase": "P1", "approvals": ["p1_approved_new_request"]}}
        with self.assertRaises(ValueError) as caught:
            propose_review([self.op("fixture-read")], {"operations": {}}, phase_overrides=overrides)
        self.assertIn("missing-op", str(caught.exception))

    def test_cross_check_schema_compares_identity_bindings(self):
        schema = {
            "openapi": "3.0.3",
            "paths": {"/zones/{zone_id}/settings": {"get": {"operationId": "fixture-read", "responses": {}}}},
        }
        review = self.ledger({"fixture-read": review_entry()})
        matching = [catalog_row("fixture-read", "GET", "/zones/{zone_id}/settings", ["Zone Settings"])]
        self.assertEqual([], cross_check_schema(schema, matching, review))
        wrong_path = [catalog_row("fixture-read", "GET", "/zones/{zone_id}/other", ["Zone Settings"])]
        errors = cross_check_schema(schema, wrong_path, review)
        self.assertTrue(any("METHOD+path" in error for error in errors), errors)
        wrong_method = [catalog_row("fixture-read", "POST", "/zones/{zone_id}/settings", ["Zone Settings"])]
        errors = cross_check_schema(schema, wrong_method, review)
        self.assertTrue(any("METHOD+path" in error for error in errors), errors)

    def test_identity_binding_must_match_between_raw_and_catalog(self):
        raw = [self.op("fixture-read")]
        rows = [
            catalog_row(
                "fixture-read",
                "GET",
                "/zones/{zone_id}/other",
                ["Zone Settings"],
                token_permissions_raw='{"x-api-token-group": ["Zone Settings Read"]}',
            )
        ]
        errors = validate_review(rows, self.ledger({"fixture-read": review_entry()}))
        self.assertEqual([], errors)
        self.assertEqual(1, len(normalize_operations(raw)))
        schema = {
            "openapi": "3.0.3",
            "paths": {"/zones/{zone_id}/settings": {"get": {"operationId": "fixture-read", "responses": {}}}},
        }
        errors = cross_check_schema(schema, rows, self.ledger({"fixture-read": review_entry()}))
        self.assertTrue(any("METHOD+path" in error for error in errors), errors)


class MalformedInputTest(unittest.TestCase):
    """I3: malformed nested values must fail readably, never grant facts."""

    def setUp(self):
        self.operation = operation(
            "permitted",
            "GET",
            "/zones/{zone_id}/settings",
            ["Zone Settings"],
            {"x-api-token-group": ["Zone Settings Read"]},
        )

    def test_normalize_rejects_invalid_permission_json(self):
        rows = [catalog_row("permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"], token_permissions_raw="not-json")]
        with self.assertRaises(ValueError) as caught:
            normalize_operations(rows)
        self.assertIn("token_permissions_raw", str(caught.exception))
        errors = validate_review(rows, review_document({}))
        self.assertTrue(any("token_permissions_raw" in error for error in errors), errors)

    def test_null_or_empty_permission_json_is_not_positive(self):
        for raw in ("null", "[]", "{}", '{"unexpected": true}', '"text"'):
            rows = [catalog_row("permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"], token_permissions_raw=raw)]
            normalized = normalize_operations(rows)
            self.assertFalse(normalized[0]["permissions_present"], raw)
        rows = [
            catalog_row(
                "permitted",
                "GET",
                "/zones/{zone_id}/settings",
                ["Zone Settings"],
                token_permissions_raw='{"x-api-token-group": ["Zone Settings Read"]}',
            )
        ]
        self.assertTrue(normalize_operations(rows)[0]["permissions_present"])

    def test_check_ledger_reports_malformed_evidence_without_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            review_path = pathlib.Path(directory) / "review.json"
            catalog_path = pathlib.Path(directory) / "endpoints.csv"
            document = review_document({"permitted": review_entry()})
            document["operations"]["permitted"]["evidence"] = "wrong type"
            review_path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
            row = catalog_row(
                "permitted",
                "GET",
                "/zones/{zone_id}/settings",
                ["Zone Settings"],
                token_permissions_raw='{"x-api-token-group": ["Zone Settings Read"]}',
            )
            with catalog_path.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(row))
                writer.writeheader()
                writer.writerow(row)
            result = cli.check_ledger(review_path, catalog_path)
            self.assertTrue(result["errors"])
            self.assertIsNone(result["summary"])
            self.assertNotEqual(0, cli.main(["check", "--review", str(review_path), "--catalog", str(catalog_path)]))

    def test_non_string_blocker_and_approval_items_are_reported(self):
        entry = review_entry()
        entry["evidence"]["blockers"] = [1, None]
        entry["evidence"]["approvals"] = [{"code": "x"}]
        errors = validate_review([self.operation], review_document({"permitted": entry}))
        self.assertTrue(any("blockers" in error for error in errors), errors)
        self.assertTrue(any("approval" in error for error in errors), errors)

    def test_summarize_does_not_crash_on_malformed_evidence(self):
        entry = review_entry()
        entry["evidence"] = "wrong type"
        report = summarize_review([self.operation], review_document({"permitted": entry}))
        self.assertEqual(1, report["blocked"])
        self.assertEqual(1, report["blockers"]["malformed_evidence"])
        self.assertEqual(0, report["verified"])
        self.assertEqual(0, report["capability_verified"])

    def test_missing_essential_fields_are_reported(self):
        errors = validate_review([self.operation], review_document({"permitted": {}}))
        self.assertTrue(any("missing required field" in error for error in errors), errors)

    # --- R3: known permission keys must use their documented shape ---

    def test_known_permission_key_internal_shape_is_enforced(self):
        invalid = (
            '{"x-api-token-group": true}',
            '{"x-api-token-group": 42}',
            '{"x-api-token-group": "Zone Settings Read"}',
            '{"x-api-token-group": []}',
            '{"x-api-token-group": [null]}',
            '{"x-api-token-group": [""]}',
            '{"x-api-token-group": [["nested"]]}',
            '{"x-cfPermissionsRequired": {"unexpected": true}}',
            '{"x-cfPermissionsRequired": {"enum": []}}',
            '{"x-cfPermissionsRequired": {"enum": "x"}}',
            '{"x-cfPermissionsRequired": {"enum": [1]}}',
            '{"x-cfPermissionsRequired": []}',
        )
        for raw in invalid:
            rows = [
                catalog_row(
                    "permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"],
                    token_permissions_raw=raw,
                )
            ]
            normalized = normalize_operations(rows)[0]
            self.assertFalse(normalized["permissions_present"], raw)
            self.assertTrue(normalized["permission_shape_errors"], raw)
            errors = validate_review(rows, review_document({"permitted": review_entry()}))
            self.assertTrue(
                any("x-api-token-group" in error or "x-cfPermissionsRequired" in error for error in errors),
                (raw, errors),
            )
        valid = (
            '{"x-api-token-group": ["Zone Settings Read"]}',
            '{"x-cfPermissionsRequired": {"enum": ["#organization:edit"]}}',
            '{"x-api-token-group": ["A"], "x-cfPermissionsRequired": {"enum": ["#x:edit"], "type": "edit"}}',
        )
        for raw in valid:
            rows = [
                catalog_row(
                    "permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"],
                    token_permissions_raw=raw,
                )
            ]
            normalized = normalize_operations(rows)[0]
            self.assertTrue(normalized["permissions_present"], raw)
            self.assertEqual([], normalized["permission_shape_errors"], raw)

    def test_null_permission_values_are_absent_not_errors(self):
        for raw in (
            '{"x-api-token-group": null}',
            '{"x-cfPermissionsRequired": null}',
            "null",
            "{}",
        ):
            rows = [
                catalog_row(
                    "permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"],
                    token_permissions_raw=raw,
                )
            ]
            normalized = normalize_operations(rows)[0]
            self.assertFalse(normalized["permissions_present"], raw)
            self.assertEqual([], normalized["permission_shape_errors"], raw)

    def test_raw_and_csv_permission_semantics_match(self):
        variants = (
            {"x-api-token-group": ["Zone Settings Read"]},
            {"x-api-token-group": True},
            {"x-api-token-group": []},
            {"x-api-token-group": [None]},
            {"x-cfPermissionsRequired": {"enum": ["#organization:edit"]}},
            {"x-cfPermissionsRequired": {"unexpected": True}},
            {"x-api-token-group": None},
            {},
        )
        for metadata in variants:
            raw_operation = operation(
                "permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"], dict(metadata)
            )
            rows = [
                catalog_row(
                    "permitted", "GET", "/zones/{zone_id}/settings", ["Zone Settings"],
                    token_permissions_raw=json.dumps(metadata),
                )
            ]
            from_raw = normalize_operations([raw_operation])[0]
            from_csv = normalize_operations(rows)[0]
            self.assertEqual(from_raw["permissions_present"], from_csv["permissions_present"], metadata)
            self.assertEqual(from_raw["permission_shape_errors"], from_csv["permission_shape_errors"], metadata)


class IdentityShapeTest(unittest.TestCase):
    """R1: identity fields must be validated before hashing or derivation."""

    def op(self, **overrides):
        base = {
            "operation_id": "fixture-read",
            "method": "GET",
            "path": "/zones/{zone_id}/settings",
            "tags": ["Zone Settings"],
            "metadata": {"x-api-token-group": ["Zone Settings Read"]},
        }
        base.update(overrides)
        return base

    def test_valid_identity_is_accepted(self):
        self.assertEqual([], identity_errors([self.op()]))

    def test_method_case_is_normalized_not_rejected(self):
        normalized = normalize_operations([self.op(method="get")])
        self.assertEqual("GET", normalized[0]["method"])

    def test_invalid_identity_values_are_reported_readably(self):
        cases = (
            {"operation_id": None},
            {"operation_id": ""},
            {"operation_id": []},
            {"method": None},
            {"method": ""},
            {"method": []},
            {"method": "BOGUS"},
            {"path": None},
            {"path": []},
            {"path": ""},
            {"path": "zones/{zone_id}/settings"},
        )
        for overrides in cases:
            operation_value = self.op(**overrides)
            errors = identity_errors([operation_value])
            self.assertTrue(errors, overrides)
            self.assertTrue(all(isinstance(error, str) and error for error in errors), overrides)
            self.assertTrue(validate_review([operation_value], review_document({})), overrides)
            with self.assertRaises(ValueError):
                propose_review([operation_value], {"operations": {}})
            with self.assertRaises(ValueError):
                summarize_review([operation_value], review_document({}))

    def test_invalid_identity_never_reaches_positive_facts(self):
        for value in (None, [], ""):
            with self.assertRaises(ValueError) as caught:
                propose_review([self.op(path=value)], {"operations": {}})
            self.assertIn("path", str(caught.exception))
        for value in (None, [], ""):
            with self.assertRaises(ValueError):
                propose_review([self.op(method=value)], {"operations": {}})

    def test_cli_check_fails_readably_on_invalid_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            row = catalog_row(
                "permitted", "GET", "", ["Zone Settings"],
                token_permissions_raw='{"x-api-token-group": ["Zone Settings Read"]}',
            )
            catalog_path = directory / "endpoints.csv"
            review_path = directory / "review.json"
            with catalog_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(row))
                writer.writeheader()
                writer.writerow(row)
            review_path.write_text(json.dumps(review_document({})), encoding="utf-8")
            result = cli.check_ledger(review_path, catalog_path)
            self.assertTrue(result["errors"])
            self.assertIsNone(result["summary"])
            self.assertNotEqual(
                0, cli.main(["check", "--review", str(review_path), "--catalog", str(catalog_path)])
            )


class ComposedGateTest(unittest.TestCase):
    """S1: existing structure-error signals must reach the summary gate.

    Uses the synthetic positive fixture from :class:`BlockerIntegrityTest` so the
    unique complete clearance stays provably able to pass.
    """

    def setUp(self):
        fixture = BlockerIntegrityTest()
        self.operation = fixture.permitted_operation()
        self.cleared = fixture.cleared_review({"permitted": fixture.passing_entry()})

    def mixed_permission_cases(self):
        return (
            {"x-api-token-group": True, "x-cfPermissionsRequired": {"enum": ["#organization:edit"]}},
            {"x-api-token-group": ["Zone Settings Read"], "x-cfPermissionsRequired": {"enum": [None]}},
            {"x-api-token-group": [], "x-cfPermissionsRequired": {"enum": ["#organization:edit"]}},
            {"x-api-token-group": ["Zone Settings Read"], "x-cfPermissionsRequired": {"enum": []}},
        )

    def test_mixed_valid_and_invalid_permission_fields_block_qualification(self):
        for metadata in self.mixed_permission_cases():
            raw = operation(
                "permitted", "GET", self.operation["path"], self.operation["tags"], metadata
            )
            row = catalog_row(
                "permitted", "GET", self.operation["path"], self.operation["tags"],
                token_permissions_raw=json.dumps(metadata),
            )
            for label, value in (("raw", raw), ("csv", row)):
                normalized = normalize_operations([value])[0]
                self.assertTrue(normalized["permissions_present"], (metadata, label))
                self.assertTrue(normalized["permission_shape_errors"], (metadata, label))
                report = summarize_review([value], self.cleared)
                self.assertGreaterEqual(report["blocked"], 1, (metadata, label))
                self.assertEqual(0, report["verified"], (metadata, label))
                self.assertEqual(0, report["capability_verified"], (metadata, label))
                self.assertIn("permission_metadata_invalid_shape", report["blockers"], (metadata, label))
                self.assertTrue(validate_review([value], self.cleared), (metadata, label))
                proposal = propose_review([value], {"operations": {}})
                blockers = proposal["operations"]["permitted"]["evidence"]["blockers"]
                self.assertIn("permission_metadata_invalid_shape", blockers, (metadata, label))
                errors = validate_review([value], proposal)
                self.assertTrue(
                    any("invalid shape" in error for error in errors), (metadata, label, errors)
                )
                self.assertEqual(
                    0, summarize_review([value], proposal)["capability_verified"], (metadata, label)
                )
                self.assertEqual(0, summarize_review([value], proposal)["verified"], (metadata, label))

    def test_malformed_global_blocker_records_block_clearance(self):
        malformed = (None, "bad-record", {"code": []}, {"detail": "no code"})
        for record in malformed:
            for position in ("append", "prepend"):
                document = copy.deepcopy(self.cleared)
                if position == "append":
                    document["blockers"].append(record)
                else:
                    document["blockers"].insert(0, record)
                self.assertTrue(account_blocker_active(document), (record, position))
                report = summarize_review([self.operation], document)
                self.assertEqual(0, report["verified"], (record, position))
                self.assertEqual(0, report["capability_verified"], (record, position))
                self.assertTrue(validate_review([self.operation], document), (record, position))
        self.assertFalse(account_blocker_active(self.cleared))
        self.assertEqual(1, summarize_review([self.operation], self.cleared)["verified"])

    def test_single_valid_or_null_permission_field_keeps_existing_semantics(self):
        for metadata in (
            {"x-cfPermissionsRequired": {"enum": ["#organization:edit"]}},
            {"x-api-token-group": None, "x-cfPermissionsRequired": {"enum": ["#organization:edit"]}},
            {"x-api-token-group": ["Zone Settings Read"], "x-cfPermissionsRequired": None},
        ):
            rows = [
                catalog_row(
                    "permitted", "GET", self.operation["path"], self.operation["tags"],
                    token_permissions_raw=json.dumps(metadata),
                )
            ]
            normalized = normalize_operations(rows)[0]
            self.assertTrue(normalized["permissions_present"], metadata)
            self.assertEqual([], normalized["permission_shape_errors"], metadata)
            self.assertEqual(0, summarize_review(rows, self.cleared)["blocked"], metadata)


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
        self.assertTrue(report["authorized_account_blocker_active"])

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

    def test_real_ledger_unclassified_bucket_is_empty(self):
        report = summarize_review(self.operations, self.review)
        self.assertEqual(0, report["unclassified"])
        self.assertNotIn("unclassified-review", {e["product_id"] for e in self.review["operations"].values()})

    def test_real_ledger_tail_families_are_owned(self):
        report = summarize_review(self.operations, self.review)
        for product_id, count in (
            ("field-extractors-alpha", 3),
            ("account-reporting-control-plane", 6),
            ("k2-streams-beta-control-data", 14),
        ):
            product = report["by_product"][product_id]
            self.assertEqual(count, product["registered"], product_id)
            self.assertEqual(["P8"], product["phases"], product_id)
            self.assertEqual(DEFAULT_OWNER_ROLE, product["owner_role"], product_id)
        for operation_id, entry in self.review["operations"].items():
            if entry["product_id"] in (
                "field-extractors-alpha",
                "account-reporting-control-plane",
                "k2-streams-beta-control-data",
            ):
                self.assertTrue(entry["needs_review"], operation_id)
                self.assertNotEqual("L0", entry["target_level"], operation_id)
                self.assertTrue(entry["evidence"]["classification_evidence"], operation_id)
                self.assertTrue(entry["evidence"]["blockers"], operation_id)

    def test_real_ledger_k2_data_plane_keeps_unknown_scope_and_host_evidence(self):
        data_plane = [
            operation_id
            for operation_id, entry in self.review["operations"].items()
            if entry["product_id"] == "k2-streams-beta-control-data"
            and entry["scope_kind"] == "unknown"
        ]
        self.assertEqual(8, len(data_plane))
        for operation_id in data_plane:
            entry = self.review["operations"][operation_id]
            self.assertEqual("unknown", entry["scope_kind"])
            self.assertEqual(
                "https://{stream_id}.k2.cloudflarestorage.com",
                entry["evidence"]["protocol_review"]["host"],
            )
            self.assertIn("k2_stream_host_acceptance_required", entry["evidence"]["blockers"])
            self.assertIn("separate_stream_host_acceptance", entry["test_prerequisites"])
            self.assertNotEqual("principal", entry["scope_kind"])
        control = [
            entry
            for entry in self.review["operations"].values()
            if entry["product_id"] == "k2-streams-beta-control-data" and entry["scope_kind"] == "account"
        ]
        self.assertEqual(6, len(control))

    def test_real_ledger_advanced_ssl_and_zone_rules_are_p5(self):
        for operation_id, entry in self.review["operations"].items():
            tags = set(self.review_tag(operation_id))
            if tags & {"Custom SSL for a Zone", "Certificate Packs", "Custom Origin Trust Store", "Total TLS", "DCV Delegation"}:
                self.assertEqual("P5", entry["phase"], operation_id)
                self.assertEqual("certificates-advanced-and-mtls", entry["product_id"], operation_id)
            if tags & {"Page Rules", "Zone Lockdown", "Zone Snippets", "Available Page Rules settings"}:
                self.assertEqual("P5", entry["phase"], operation_id)
                self.assertEqual("rules-waf-bots", entry["product_id"], operation_id)
        report = summarize_review(self.operations, self.review)
        self.assertEqual(54, report["by_implementation_approval"]["p2_candidate"])

    def review_tag(self, operation_id):
        row = self.catalog_row(operation_id)
        return [tag for tag in row["tags"].split(" | ") if tag]

    def catalog_row(self, operation_id):
        if not hasattr(self, "_rows"):
            self._rows = {row["operation_id"]: row for row in self.operations}
        return self._rows[operation_id]

    def test_real_ledger_products_have_phase_owner_and_spec(self):
        for operation_id, entry in self.review["operations"].items():
            self.assertTrue(entry["owner_role"], operation_id)
            self.assertIn(entry["phase"], PHASES, operation_id)
            self.assertTrue(entry["evidence"]["spec_reference"], operation_id)
            self.assertTrue(entry["evidence"]["scope_basis"], operation_id)

    def test_real_catalog_permission_shapes_are_valid(self):
        normalized = normalize_operations(self.operations)
        shape_problems = [op["operation_id"] for op in normalized if op["permission_shape_errors"]]
        self.assertEqual([], shape_problems)
        absent = sum(1 for op in normalized if not op["permissions_present"])
        report = summarize_review(self.operations, self.review)
        self.assertEqual(report["blockers"]["permission_metadata_absent"], absent)
        self.assertNotIn("permission_metadata_invalid_shape", report["blockers"])

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
