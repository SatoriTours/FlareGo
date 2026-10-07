"""Offline tests for the Cloudflare schema diff and the read-only CI contract.

Fixtures are small synthetic OpenAPI documents built inline; the real pinned
schema checks only read the git-ignored local copy when it is present and
checksum-matching. No network access, no credentials, no cloud calls.
"""
import copy
import hashlib
import json
import os
import pathlib
import re
import tempfile
import unittest
from unittest import mock

from tools.cloudflare_audit import cli
from tools.cloudflare_audit.diff import (
    K2_STREAM_HOST,
    effective_servers,
    diff_schemas,
    fingerprint_operation,
)
from tools.cloudflare_audit.schema import SchemaError

REPO = pathlib.Path(__file__).resolve().parents[3]
WORKFLOW = REPO / ".github/workflows/cloudflare-audit.yml"
SNAPSHOT = REPO / "docs/cloudflare-audit/snapshot.json"
PINNED_SCHEMA = REPO / ".superpowers/cloudflare/openapi.json"
P2_CANDIDATES = REPO / "docs/cloudflare-audit/p2-candidate-endpoints.csv"


def base_schema() -> dict:
    """Small schema with cycles, refs, path/operation overrides and servers."""
    return {
        "openapi": "3.0.3",
        "info": {"title": "fixture", "version": "1.0.0"},
        "servers": [{"url": "https://api.example.com/v1", "description": "root"}],
        "security": [{"api_token": []}],
        "paths": {
            "/things": {
                "parameters": [{"$ref": "#/components/parameters/AccountId"}],
                "get": {
                    "operationId": "things-list",
                    "summary": "List things",
                    "tags": ["Things"],
                    "responses": {"200": {"$ref": "#/components/responses/ThingList"}},
                },
                "post": {
                    "operationId": "example-create",
                    "summary": "Create thing",
                    "tags": ["Things"],
                    "x-api-token-group": ["Things Write"],
                    "x-fern-availability": "generally-available",
                    "requestBody": {"$ref": "#/components/requestBodies/ThingBody"},
                    "responses": {"200": {"$ref": "#/components/responses/ThingList"}},
                },
            },
            "/things/{thing_id}": {
                "security": [{"api_token": []}],
                "get": {
                    "operationId": "things-get",
                    "summary": "Get thing",
                    "tags": ["Things"],
                    "parameters": [
                        {"$ref": "#/components/parameters/AccountId"},
                        {"name": "thing_id", "in": "path", "required": True, "schema": {"type": "string"}},
                    ],
                    "responses": {"200": {"$ref": "#/components/responses/Thing"}},
                },
                "delete": {
                    "operationId": "things-delete",
                    "summary": "Delete thing",
                    "tags": ["Things"],
                    "security": [],
                    "parameters": [{"name": "thing_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                    "responses": {"204": {"description": "gone"}},
                },
            },
            "/produce": {
                "servers": [
                    {
                        "url": "https://{stream_id}.k2.cloudflarestorage.com",
                        "variables": {"stream_id": {"default": "abc", "description": "stream"}},
                    }
                ],
                "post": {
                    "operationId": "k2-produce",
                    "summary": "Produce",
                    "tags": ["K2"],
                    "responses": {"200": {"description": "ok"}},
                },
            },
        },
        "components": {
            "parameters": {
                "AccountId": {"name": "account_id", "in": "query", "schema": {"type": "string"}},
            },
            "requestBodies": {
                "ThingBody": {
                    "required": True,
                    "content": {
                        "application/json": {"schema": {"$ref": "#/components/schemas/Thing"}},
                        "multipart/form-data": {"schema": {"$ref": "#/components/schemas/Thing"}},
                    },
                }
            },
            "responses": {
                "Thing": {
                    "description": "one",
                    "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Thing"}}},
                },
                "ThingList": {
                    "description": "many",
                    "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Thing"}}},
                },
            },
            "schemas": {
                "Thing": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "parent": {"$ref": "#/components/schemas/Thing"},
                        "owner": {"$ref": "#/components/schemas/Owner"},
                    },
                },
                "Owner": {"type": "object", "properties": {"email": {"type": "string"}}},
                "Unused": {"type": "object", "properties": {"note": {"type": "string"}}},
            },
            "securitySchemes": {
                "api_token": {"type": "apiKey", "in": "header", "name": "Authorization"},
            },
        },
    }


def reorder(value):
    """Rebuild dicts in reverse insertion order (list order is semantic)."""
    if isinstance(value, dict):
        return {key: reorder(item) for key, item in reversed(list(value.items()))}
    if isinstance(value, list):
        return [reorder(item) for item in value]
    return value


def changed_fields(delta: dict, operation_id: str) -> list[str]:
    return delta["changed"][operation_id]["fields"]


def pinned_schema_status() -> tuple[pathlib.Path, str]:
    """Resolve the pinned schema and its usability state.

    A checksum-verified ``SCHEMA_FILE`` provided by CI is authoritative: a
    missing or mismatching file is an error, never a silent skip. Only a locally
    absent fallback copy may be skipped transparently.
    """
    expected = json.loads(SNAPSHOT.read_text(encoding="utf-8"))["schema"]["sha256"]
    from_env = os.environ.get("SCHEMA_FILE")
    if from_env:
        path = pathlib.Path(from_env)
        if not path.exists():
            return path, "ci_missing"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        return path, "ok" if digest == expected else "ci_checksum_mismatch"
    if not PINNED_SCHEMA.exists():
        return PINNED_SCHEMA, "missing"
    digest = hashlib.sha256(PINNED_SCHEMA.read_bytes()).hexdigest()
    return PINNED_SCHEMA, "ok" if digest == expected else "local_checksum_mismatch"


class DiffBasicsTest(unittest.TestCase):
    def setUp(self):
        self.old_schema = base_schema()

    def test_same_schema_has_empty_diff(self):
        delta = diff_schemas(self.old_schema, copy.deepcopy(self.old_schema))
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])
        self.assertEqual({}, delta["changed"])

    def test_key_order_only_change_has_no_diff(self):
        delta = diff_schemas(self.old_schema, reorder(self.old_schema))
        self.assertEqual({"added": {}, "removed": {}, "changed": {}}, {
            "added": delta["added"], "removed": delta["removed"], "changed": delta["changed"]
        })

    def test_referenced_body_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["schemas"]["Thing"]["properties"]["id"]["type"] = "integer"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("example-create", delta["changed"])
        self.assertIn("requestBody", changed_fields(delta, "example-create"))
        self.assertIn("responses", changed_fields(delta, "things-list"))

    def test_indirect_reference_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["schemas"]["Owner"]["properties"]["email"]["format"] = "email"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("example-create", delta["changed"])
        self.assertIn("requestBody", changed_fields(delta, "example-create"))

    def test_unreferenced_component_change_is_not_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["schemas"]["Unused"]["properties"]["note"]["type"] = "integer"
        delta = diff_schemas(self.old_schema, schema)
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])
        self.assertEqual({}, delta["changed"])

    def test_operation_rename_is_removed_and_added(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["post"]["operationId"] = "thing-create"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("example-create", delta["removed"])
        self.assertIn("thing-create", delta["added"])
        self.assertEqual({}, delta["changed"])

    def test_operation_id_kept_with_changed_route_is_changed(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things/other"] = {"post": schema["paths"]["/things"].pop("post")}
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("example-create", delta["changed"])
        self.assertIn("path", changed_fields(delta, "example-create"))
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])

    def test_operation_id_kept_with_changed_method_is_changed(self):
        schema = copy.deepcopy(self.old_schema)
        item = schema["paths"]["/things"]
        item["put"] = item.pop("post")
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("example-create", delta["changed"])
        self.assertIn("method", changed_fields(delta, "example-create"))

    def test_new_method_is_added(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["delete"] = {
            "operationId": "things-delete-collection",
            "summary": "Delete all",
            "responses": {"204": {"description": "gone"}},
        }
        delta = diff_schemas(self.old_schema, schema)
        self.assertEqual(["things-delete-collection"], list(delta["added"]))
        self.assertEqual({"method": "DELETE", "path": "/things"}, delta["added"]["things-delete-collection"])

    def test_permission_expansion_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["post"]["x-api-token-group"] = ["Things Write", "Things Admin"]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("permissions", changed_fields(delta, "example-create"))

    def test_availability_and_deprecation_changes_are_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["post"]["x-fern-availability"] = "deprecated"
        schema["paths"]["/things"]["post"]["deprecated"] = True
        delta = diff_schemas(self.old_schema, schema)
        fields = changed_fields(delta, "example-create")
        self.assertIn("availability", fields)
        self.assertIn("deprecated", fields)

    def test_non_json_media_type_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        body = schema["components"]["requestBodies"]["ThingBody"]["content"]
        body["multipart/form-data"]["schema"] = {"type": "string", "format": "binary"}
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("requestBody", changed_fields(delta, "example-create"))

    def test_removed_media_type_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        del schema["components"]["requestBodies"]["ThingBody"]["content"]["multipart/form-data"]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("requestBody", changed_fields(delta, "example-create"))

    def test_cycle_is_safe_and_changes_inside_cycles_are_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["schemas"]["Thing"]["properties"]["parent"] = {
            "type": "array",
            "items": {"$ref": "#/components/schemas/Thing"},
        }
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("example-create", delta["changed"])
        self.assertIn("requestBody", changed_fields(delta, "example-create"))
        identical = diff_schemas(copy.deepcopy(schema), copy.deepcopy(schema))
        self.assertEqual({}, identical["changed"])

    def test_unresolvable_and_external_refs_fail_readably(self):
        for replacement in (
            {"$ref": "#/components/schemas/Missing"},
            {"$ref": "#/components/schemas/Thing/nope"},
            {"$ref": "./other.json#/components/schemas/Thing"},
            {"$ref": "https://example.com/schema.json#/Thing"},
        ):
            schema = copy.deepcopy(self.old_schema)
            schema["components"]["requestBodies"]["ThingBody"]["content"]["application/json"]["schema"] = replacement
            with self.assertRaises(ValueError) as caught:
                diff_schemas(self.old_schema, schema)
            self.assertTrue(str(caught.exception), replacement)

    def test_rejected_schema_has_no_operations(self):
        with self.assertRaises(ValueError):
            diff_schemas({"openapi": "3.0.3"}, {"openapi": "3.0.3"})


class ServerContextTest(unittest.TestCase):
    def setUp(self):
        self.old_schema = base_schema()

    def test_root_server_change_reaches_inheriting_operations(self):
        schema = copy.deepcopy(self.old_schema)
        schema["servers"] = [{"url": "https://api.example.com/v2", "description": "root"}]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("servers", changed_fields(delta, "things-list"))
        self.assertIn("servers", changed_fields(delta, "example-create"))
        self.assertNotIn("k2-produce", delta["changed"])

    def test_operation_server_override_ignores_root_change(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["get"]["servers"] = [{"url": "https://override.example.com"}]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("servers", changed_fields(delta, "things-list"))
        self.assertNotIn("example-create", delta["changed"])

    def test_empty_operation_server_override_is_not_inheritance(self):
        old = base_schema()
        old["paths"]["/things"]["get"]["servers"] = []
        new = copy.deepcopy(old)
        new["servers"] = [{"url": "https://api.example.com/v2"}]
        delta = diff_schemas(old, new)
        self.assertNotIn("things-list", delta["changed"])
        self.assertIn("example-create", delta["changed"])
        added_override = diff_schemas(base_schema(), old)
        self.assertIn("servers", changed_fields(added_override, "things-list"))

    def test_server_variable_default_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        variables = schema["paths"]["/produce"]["servers"][0]["variables"]
        variables["stream_id"]["default"] = "other"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("servers", changed_fields(delta, "k2-produce"))

    def test_effective_servers_follow_openapi_inheritance(self):
        schema = base_schema()
        inherited = effective_servers(schema, schema["paths"]["/things"], schema["paths"]["/things"]["get"])
        self.assertEqual("https://api.example.com/v1", inherited[0]["url"])
        overridden = effective_servers(
            schema, schema["paths"]["/produce"], schema["paths"]["/produce"]["post"]
        )
        self.assertEqual(K2_STREAM_HOST, overridden[0]["url"])


class ParameterContextTest(unittest.TestCase):
    def setUp(self):
        self.old_schema = base_schema()

    def test_shared_path_parameter_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["parameters"]["AccountId"]["schema"]["type"] = "integer"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("parameters", changed_fields(delta, "things-list"))
        self.assertIn("parameters", changed_fields(delta, "example-create"))

    def test_operation_parameter_override_is_detected(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["get"]["parameters"] = [
            {"name": "account_id", "in": "query", "schema": {"type": "integer"}}
        ]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("parameters", changed_fields(delta, "things-list"))
        self.assertNotIn("example-create", delta["changed"])

    def test_inline_path_parameter_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        params = schema["paths"]["/things/{thing_id}"]["get"]["parameters"]
        params[1]["schema"]["type"] = "integer"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("parameters", changed_fields(delta, "things-get"))
        self.assertNotIn("things-delete", delta["changed"])

    def test_path_parameter_added_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["parameters"].append(
            {"name": "per_page", "in": "query", "schema": {"type": "integer"}}
        )
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("parameters", changed_fields(delta, "things-list"))


    def test_parameter_alias_cycle_fails_readably(self):
        old = base_schema()
        old["components"]["parameters"]["LoopA"] = {"$ref": "#/components/parameters/LoopB"}
        old["components"]["parameters"]["LoopB"] = {"$ref": "#/components/parameters/LoopA"}
        old["paths"]["/things"]["get"]["parameters"] = [{"$ref": "#/components/parameters/LoopA"}]
        with self.assertRaises(ValueError):
            diff_schemas(old, copy.deepcopy(old))

    def test_bad_parameter_alias_pointer_fails(self):
        old = base_schema()
        old["paths"]["/things"]["get"]["parameters"] = [{"$ref": "#/components/parameters/Missing"}]
        with self.assertRaises(ValueError):
            diff_schemas(old, copy.deepcopy(old))

    def test_indirect_parameter_alias_override_is_resolved(self):
        old = base_schema()
        old["components"]["parameters"]["Alias"] = {"$ref": "#/components/parameters/AccountId"}
        old["paths"]["/things"]["parameters"] = [
            {"name": "account_id", "in": "query", "schema": {"type": "string"}}
        ]
        old["paths"]["/things"]["get"]["parameters"] = [{"$ref": "#/components/parameters/Alias"}]
        new = copy.deepcopy(old)
        new["paths"]["/things"]["parameters"][0]["schema"]["type"] = "integer"
        delta = diff_schemas(old, new)
        self.assertIn("parameters", changed_fields(delta, "example-create"))
        self.assertNotIn("things-list", delta["changed"])

    def test_multi_level_parameter_alias_is_resolved(self):
        old = base_schema()
        old["components"]["parameters"]["Alias"] = {"$ref": "#/components/parameters/Alias2"}
        old["components"]["parameters"]["Alias2"] = {"$ref": "#/components/parameters/AccountId"}
        old["paths"]["/things"]["parameters"] = [
            {"name": "account_id", "in": "query", "schema": {"type": "string"}}
        ]
        old["paths"]["/things"]["get"]["parameters"] = [{"$ref": "#/components/parameters/Alias"}]
        new = copy.deepcopy(old)
        new["paths"]["/things"]["parameters"][0]["schema"]["type"] = "integer"
        delta = diff_schemas(old, new)
        self.assertIn("parameters", changed_fields(delta, "example-create"))
        self.assertNotIn("things-list", delta["changed"])

    def test_ref_path_parameter_with_inline_operation_override(self):
        old = base_schema()
        old["paths"]["/things"]["get"]["parameters"] = [
            {"name": "account_id", "in": "query", "schema": {"type": "string"}}
        ]
        new = copy.deepcopy(old)
        new["components"]["parameters"]["AccountId"]["schema"]["type"] = "integer"
        delta = diff_schemas(old, new)
        self.assertIn("parameters", changed_fields(delta, "example-create"))
        self.assertNotIn("things-list", delta["changed"])

    def test_inline_path_parameter_with_ref_operation_override(self):
        old = base_schema()
        old["paths"]["/things"]["parameters"] = [
            {"name": "account_id", "in": "query", "schema": {"type": "string"}}
        ]
        old["paths"]["/things"]["get"]["parameters"] = [
            {"$ref": "#/components/parameters/AccountId"}
        ]
        new = copy.deepcopy(old)
        new["paths"]["/things"]["parameters"][0]["schema"]["type"] = "integer"
        delta = diff_schemas(old, new)
        self.assertIn("parameters", changed_fields(delta, "example-create"))
        self.assertNotIn("things-list", delta["changed"])

    def test_used_parameter_change_is_still_detected_with_aliases(self):
        old = base_schema()
        old["components"]["parameters"]["Alias"] = {"$ref": "#/components/parameters/AccountId"}
        old["paths"]["/things"]["get"]["parameters"] = [{"$ref": "#/components/parameters/Alias"}]
        new = copy.deepcopy(old)
        new["components"]["parameters"]["AccountId"]["schema"]["type"] = "integer"
        delta = diff_schemas(old, new)
        self.assertIn("parameters", changed_fields(delta, "things-list"))


class SecurityContextTest(unittest.TestCase):
    def setUp(self):
        self.old_schema = base_schema()

    def test_root_security_change_reaches_inheriting_operations(self):
        schema = copy.deepcopy(self.old_schema)
        schema["security"] = [{"api_token": []}, {"user_service_key": []}]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("security", changed_fields(delta, "things-list"))
        self.assertIn("security", changed_fields(delta, "example-create"))
        self.assertNotIn("things-delete", delta["changed"])

    def test_operation_security_override_ignores_root_change(self):
        old = base_schema()
        old["paths"]["/things"]["get"]["security"] = [{"user_service_key": []}]
        new = copy.deepcopy(old)
        new["security"] = [{"api_token": []}, {"user_service_key": []}]
        delta = diff_schemas(old, new)
        self.assertIn("security", changed_fields(delta, "example-create"))
        self.assertNotIn("things-list", delta["changed"])

    def test_empty_security_disables_requirement_without_inheriting(self):
        schema = copy.deepcopy(self.old_schema)
        schema["security"] = [{"user_service_key": []}]
        delta = diff_schemas(self.old_schema, schema)
        self.assertNotIn("things-delete", delta["changed"])
        schema2 = copy.deepcopy(self.old_schema)
        schema2["paths"]["/things/{thing_id}"]["delete"]["security"] = [{"api_token": []}]
        delta2 = diff_schemas(self.old_schema, schema2)
        self.assertIn("security", changed_fields(delta2, "things-delete"))

    def test_referenced_security_scheme_definition_change_is_reported(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["securitySchemes"]["api_token"]["name"] = "X-Auth-Token"
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("security", changed_fields(delta, "things-list"))

    def test_undefined_security_scheme_is_recorded_not_crashing(self):
        old = base_schema()
        old["security"] = [{"ghost": []}]
        new = copy.deepcopy(old)
        self.assertEqual({}, diff_schemas(old, new)["changed"])
        new["components"]["securitySchemes"]["ghost"] = {"type": "apiKey", "in": "header", "name": "G"}
        delta = diff_schemas(old, new)
        self.assertIn("security", changed_fields(delta, "things-list"))

    def test_vendor_path_security_is_reported_as_reviewable_change(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things/{thing_id}"]["security"] = [{"user_service_key": []}]
        delta = diff_schemas(self.old_schema, schema)
        self.assertIn("path_security", changed_fields(delta, "things-get"))
        self.assertNotIn("security", changed_fields(delta, "things-get"))

    def test_fingerprint_exposes_field_categories(self):
        schema = base_schema()
        operation = {
            "method": "POST",
            "path": "/things",
            "operation_id": "example-create",
            "tags": ["Things"],
            "metadata": schema["paths"]["/things"]["post"],
        }
        fingerprint = fingerprint_operation(schema, schema["paths"]["/things"], operation)
        self.assertEqual(
            {
                "method", "path", "servers", "parameters", "security", "permissions",
                "deprecated", "availability", "requestBody", "responses", "metadata",
            },
            set(fingerprint),
        )


class DiffCliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.old_path = self.root / "old.json"
        self.new_path = self.root / "new.json"
        self.old_path.write_text(json.dumps(base_schema()), encoding="utf-8")
        changed = copy.deepcopy(base_schema())
        changed["components"]["schemas"]["Thing"]["properties"]["id"]["type"] = "integer"
        self.new_path.write_text(json.dumps(changed), encoding="utf-8")

    def test_cli_diff_writes_deterministic_report(self):
        first = self.root / "first.json"
        second = self.root / "second.json"
        self.assertEqual(
            0, cli.main(["diff", "--old", str(self.old_path), "--new", str(self.new_path), "--output", str(first)])
        )
        self.assertEqual(
            0, cli.main(["diff", "--old", str(self.old_path), "--new", str(self.new_path), "--output", str(second)])
        )
        self.assertEqual(first.read_bytes(), second.read_bytes())
        report = json.loads(first.read_text(encoding="utf-8"))
        self.assertEqual("flarego.cloudflare-audit.diff/1", report["format"])
        self.assertEqual({"added": 0, "removed": 0, "changed": 3}, report["counts"])
        self.assertIn("example-create", report["changed"])
        self.assertNotIn("things-delete", report["changed"])
        self.assertEqual(64, len(report["old"]["sha256"]))
        self.assertEqual(
            hashlib.sha256(self.old_path.read_bytes()).hexdigest(), report["old"]["sha256"]
        )

    def test_cli_diff_same_schema_reports_empty_change(self):
        output = self.root / "same.json"
        self.assertEqual(
            0, cli.main(["diff", "--old", str(self.old_path), "--new", str(self.old_path), "--output", str(output)])
        )
        report = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual({"added": 0, "removed": 0, "changed": 0}, report["counts"])
        self.assertEqual({}, report["changed"])

    def test_cli_diff_invalid_input_fails_without_partial_output(self):
        broken = self.root / "broken.json"
        broken.write_text("{not json", encoding="utf-8")
        output = self.root / "out.json"
        self.assertNotEqual(
            0, cli.main(["diff", "--old", str(broken), "--new", str(self.new_path), "--output", str(output)])
        )
        self.assertFalse(output.exists())
        self.assertNotEqual(
            0,
            cli.main(
                ["diff", "--old", str(self.root / "missing.json"), "--new", str(self.new_path), "--output", str(output)]
            ),
        )
        self.assertFalse(output.exists())

    def test_cli_diff_unresolvable_ref_fails_without_output(self):
        schema = copy.deepcopy(base_schema())
        schema["components"]["requestBodies"]["ThingBody"]["content"]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/Missing"
        }
        bad = self.root / "bad-ref.json"
        bad.write_text(json.dumps(schema), encoding="utf-8")
        output = self.root / "out.json"
        self.assertNotEqual(
            0, cli.main(["diff", "--old", str(self.old_path), "--new", str(bad), "--output", str(output)])
        )
        self.assertFalse(output.exists())

    def test_cli_diff_refuses_protected_baseline_output(self):
        protected = REPO / "docs/cloudflare-audit/snapshot.json"
        before = protected.read_bytes()
        self.assertNotEqual(
            0,
            cli.main(
                ["diff", "--old", str(self.old_path), "--new", str(self.new_path), "--output", str(protected)]
            ),
        )
        self.assertEqual(before, protected.read_bytes())

    def test_cli_diff_refuses_output_over_an_input(self):
        before = self.old_path.read_bytes()
        self.assertNotEqual(
            0,
            cli.main(
                ["diff", "--old", str(self.old_path), "--new", str(self.new_path), "--output", str(self.old_path)]
            ),
        )
        self.assertEqual(before, self.old_path.read_bytes())


class WorkflowContractTest(unittest.TestCase):
    """Static contract checks for the read-only CI workflow (no remote run)."""

    @classmethod
    def setUpClass(cls):
        if not WORKFLOW.exists():
            raise unittest.SkipTest("cloudflare-audit workflow not created yet")
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))

    def test_workflow_triggers_and_read_only_permissions(self):
        self.assertIn("pull_request:", self.text)
        self.assertIn("workflow_dispatch:", self.text)
        self.assertIn("candidate_commit", self.text)
        self.assertGreaterEqual(self.text.count("contents: read"), 2)

    def test_workflow_has_no_write_or_release_semantics(self):
        for forbidden in (
            "contents: write",
            "id-token",
            "secrets.",
            "publish_snapshot",
            "android.yml",
            "actions/upload-release-asset",
            "gh release",
            "git push",
            "git tag",
        ):
            self.assertNotIn(forbidden, self.text, forbidden)

    def test_workflow_runs_explicit_test_directories(self):
        self.assertIn("discover -s tools/cloudflare_audit/tests", self.text)
        self.assertIn("discover -s tools/ci/tests", self.text)
        self.assertNotIn("discover -s tools ", self.text)
        self.assertIn("NO TESTS RAN", self.text)

    def test_workflow_pins_actions_and_schema(self):
        uses = [line.strip() for line in self.text.splitlines() if line.strip().startswith("uses:")]
        self.assertTrue(uses)
        for line in uses:
            self.assertRegex(line, r"@[0-9a-f]{40}\b")
            self.assertIn("#", line)
        self.assertIn(self.snapshot["schema"]["commit"], self.text)
        self.assertIn(self.snapshot["schema"]["sha256"], self.text)
        self.assertIn(self.snapshot["schema"]["url"], self.text)

    def test_workflow_candidate_input_is_strictly_validated(self):
        self.assertRegex(self.text, r"\[0-9a-f\]\{40\}")
        self.assertIn("raw.githubusercontent.com/cloudflare/api-schemas", self.text)
        self.assertNotIn("/latest/", self.text)
        self.assertNotIn("api.cloudflare.com", self.text)

    def test_workflow_verifies_checksum_and_does_not_write_back(self):
        self.assertIn("sha256sum", self.text)
        self.assertIn("git diff --exit-code", self.text)
        self.assertIn("cli check", self.text)
        self.assertIn("cli generate", self.text)

    def test_workflow_cache_key_includes_the_pinned_sha(self):
        self.assertIn("cf-schema-cache/${SCHEMA_COMMIT}-${SCHEMA_SHA256}", self.text)
        self.assertIn("sha256sum --check --strict", self.text)

    def test_workflow_candidate_guard_is_whole_string(self):
        self.assertIn("fullmatch", self.text)
        self.assertIn("[0-9a-f]{40}", self.text)
        self.assertNotIn("grep -Eq '^[0-9a-f]{40}$'", self.text)

    def test_workflow_no_write_gate_uses_content_snapshots(self):
        self.assertIn("tree-before", self.text)
        self.assertIn("tree-after", self.text)
        self.assertIn("git status --porcelain --untracked-files=all", self.text)
        self.assertEqual(1, self.text.count("git diff --exit-code"))

    def test_workflow_exports_the_verified_schema_path_before_the_tests(self):
        self.assertIn("SCHEMA_FILE", self.text)
        self.assertIn("GITHUB_ENV", self.text)
        self.assertLess(
            self.text.index("SCHEMA_FILE=$schema_file"),
            self.text.index("discover -s tools/cloudflare_audit/tests"),
        )


class AddedRemovedReferenceTest(unittest.TestCase):
    """I2: added/removed-only operations must validate their reachable refs."""

    def setUp(self):
        self.old_schema = base_schema()

    def added_operation_schema(self, reference: str) -> dict:
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/new"] = {
            "post": {
                "operationId": "added-broken",
                "summary": "Added",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": reference}}}},
                "responses": {"200": {"description": "ok"}},
            }
        }
        return schema

    def test_added_only_operation_with_bad_nested_ref_fails(self):
        for reference in (
            "#/components/schemas/Missing",
            "#/components/schemas/Thing/nope",
            "./other.json#/components/schemas/Thing",
            "https://example.invalid/schema.json#/Thing",
        ):
            with self.assertRaises(ValueError, msg=reference):
                diff_schemas(self.old_schema, self.added_operation_schema(reference))

    def test_removed_only_operation_with_bad_nested_ref_fails(self):
        for reference in (
            "#/components/schemas/Missing",
            "https://example.invalid/schema.json#/Thing",
        ):
            with self.assertRaises(ValueError, msg=reference):
                diff_schemas(self.added_operation_schema(reference), self.old_schema)

    def test_added_only_operation_with_valid_refs_is_added(self):
        schema = self.added_operation_schema("#/components/schemas/Thing")
        delta = diff_schemas(self.old_schema, schema)
        self.assertEqual(["added-broken"], list(delta["added"]))
        self.assertEqual({}, delta["changed"])
        self.assertEqual({}, delta["removed"])
        reverse = diff_schemas(schema, self.old_schema)
        self.assertEqual(["added-broken"], list(reverse["removed"]))
        self.assertEqual({}, reverse["changed"])

    def test_bad_ref_in_a_new_method_fails(self):
        schema = copy.deepcopy(self.old_schema)
        schema["paths"]["/things"]["delete"] = {
            "operationId": "things-delete-all",
            "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Missing"}}}},
            "responses": {"204": {"description": "gone"}},
        }
        with self.assertRaises(ValueError):
            diff_schemas(self.old_schema, schema)

    def test_bad_ref_after_rename_fails(self):
        schema = copy.deepcopy(self.old_schema)
        item = schema["paths"]["/things"]["post"]
        item["requestBody"] = {
            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Missing"}}}
        }
        item["operationId"] = "thing-create"
        with self.assertRaises(ValueError):
            diff_schemas(self.old_schema, schema)

    def test_unreferenced_component_stays_irrelevant(self):
        schema = copy.deepcopy(self.old_schema)
        schema["components"]["schemas"]["Unused"]["properties"]["note"]["type"] = "integer"
        delta = diff_schemas(self.old_schema, schema)
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])
        self.assertEqual({}, delta["changed"])


class DiffOutputSafetyTest(unittest.TestCase):
    """I1: atomic staging and complete reviewed-baseline protection."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.old_path = self.root / "old.json"
        self.new_path = self.root / "new.json"
        self.old_path.write_text(json.dumps(base_schema()), encoding="utf-8")
        changed = copy.deepcopy(base_schema())
        changed["components"]["schemas"]["Thing"]["properties"]["id"]["type"] = "integer"
        self.new_path.write_text(json.dumps(changed), encoding="utf-8")

    def run_diff(self, old, new, output):
        return cli.main(["diff", "--old", str(old), "--new", str(new), "--output", str(output)])

    def test_existing_staging_file_is_never_clobbered(self):
        output = self.root / "report.json"
        staging = self.root / "report.json.tmp"
        staging.write_text("synthetic pre-existing staging file", encoding="utf-8")
        before = staging.read_bytes()
        self.assertEqual(0, self.run_diff(self.old_path, self.new_path, output))
        self.assertTrue(output.exists())
        self.assertEqual(before, staging.read_bytes())
        self.assertEqual(3, json.loads(output.read_text(encoding="utf-8"))["counts"]["changed"])

    def test_staging_symlink_target_is_never_modified(self):
        output = self.root / "report.json"
        staging = self.root / "report.json.tmp"
        target = self.root / "sentinel.txt"
        target.write_text("synthetic sentinel", encoding="utf-8")
        staging.symlink_to(target)
        before = target.read_bytes()
        self.assertEqual(0, self.run_diff(self.old_path, self.new_path, output))
        self.assertEqual(before, target.read_bytes())
        self.assertTrue(staging.is_symlink())

    def test_input_named_like_a_staging_path_is_preserved(self):
        output = self.root / "report.json"
        old = self.root / "report.json.tmp"
        old.write_text(json.dumps(base_schema()), encoding="utf-8")
        before = old.read_bytes()
        self.assertEqual(0, self.run_diff(old, self.new_path, output))
        self.assertTrue(old.exists())
        self.assertEqual(before, old.read_bytes())

    def test_refuses_any_output_inside_the_reviewed_audit_baseline(self):
        fake_root = self.root / "repo"
        baseline = fake_root / "docs/cloudflare-audit"
        names = ("r2-s3-operations.csv", "protocols.json", "snapshot.json", "nested/new.json")
        for name in names:
            path = baseline / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic reviewed artifact", encoding="utf-8")
        with mock.patch.object(cli, "REPOSITORY_ROOT", fake_root):
            for name in names:
                target = baseline / name
                before = target.read_bytes()
                with self.assertRaises(ValueError, msg=name):
                    cli.diff_schema_files(self.old_path, self.new_path, target)
                self.assertEqual(before, target.read_bytes())
                self.assertNotEqual(0, self.run_diff(self.old_path, self.new_path, target))
                self.assertEqual(before, target.read_bytes())

    def test_symlinked_output_into_the_baseline_is_refused(self):
        fake_root = self.root / "repo"
        baseline = fake_root / "docs/cloudflare-audit"
        baseline.mkdir(parents=True)
        reviewed = baseline / "review.json"
        reviewed.write_text("synthetic reviewed ledger", encoding="utf-8")
        link = self.root / "link.json"
        link.symlink_to(reviewed)
        before = reviewed.read_bytes()
        with mock.patch.object(cli, "REPOSITORY_ROOT", fake_root):
            with self.assertRaises(ValueError):
                cli.diff_schema_files(self.old_path, self.new_path, link)
        self.assertEqual(before, reviewed.read_bytes())

    def test_symlinked_input_is_protected(self):
        link = self.root / "link.json"
        link.symlink_to(self.old_path)
        before = self.old_path.read_bytes()
        with self.assertRaises(ValueError):
            cli.diff_schema_files(self.old_path, self.new_path, link)
        self.assertEqual(before, self.old_path.read_bytes())

    def test_failed_diff_leaves_no_output_or_temporary(self):
        schema = copy.deepcopy(base_schema())
        schema["components"]["requestBodies"]["ThingBody"]["content"]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/Missing"
        }
        bad = self.root / "bad.json"
        bad.write_text(json.dumps(schema), encoding="utf-8")
        output = self.root / "out.json"
        with self.assertRaises(ValueError):
            cli.diff_schema_files(self.old_path, bad, output)
        self.assertFalse(output.exists())
        expected = {self.old_path.name, self.new_path.name, bad.name}
        self.assertEqual(expected, {path.name for path in self.root.iterdir()})

    def test_write_failure_removes_only_its_own_temporary(self):
        output = self.root / "out.json"
        with mock.patch.object(cli.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):
                cli.diff_schema_files(self.old_path, self.new_path, output)
        self.assertFalse(output.exists())
        self.assertEqual(
            [], [path.name for path in self.root.iterdir() if path.name.endswith(".tmp")]
        )

    def test_output_directory_is_refused_without_temporary(self):
        output = self.root / "outdir"
        output.mkdir()
        with self.assertRaises(ValueError):
            cli.diff_schema_files(self.old_path, self.new_path, output)
        self.assertTrue(output.is_dir())
        self.assertEqual([], [path.name for path in self.root.iterdir() if path.name.startswith(".outdir")])


class PinnedSchemaStatusTest(unittest.TestCase):
    """I4: a CI-provided pinned schema must run, never silently skip."""

    def test_ci_schema_file_is_used_and_checksum_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = pathlib.Path(directory) / "openapi.json"
            fake.write_text("{}", encoding="utf-8")
            with mock.patch.dict(os.environ, {"SCHEMA_FILE": str(fake)}):
                path, state = pinned_schema_status()
            self.assertEqual(fake, path)
            self.assertEqual("ci_checksum_mismatch", state)

    def test_missing_ci_schema_file_is_not_a_silent_skip(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = pathlib.Path(directory) / "missing.json"
            with mock.patch.dict(os.environ, {"SCHEMA_FILE": str(missing)}):
                path, state = pinned_schema_status()
            self.assertEqual("ci_missing", state)

    def test_ci_path_pointing_at_the_real_pin_is_ok(self):
        candidate = os.environ.get("SCHEMA_FILE")
        if not candidate:
            if not PINNED_SCHEMA.exists():
                self.skipTest("no pinned schema available locally or from CI")
            candidate = str(PINNED_SCHEMA)
        with mock.patch.dict(os.environ, {"SCHEMA_FILE": candidate}):
            path, state = pinned_schema_status()
        self.assertEqual(pathlib.Path(candidate), path)
        self.assertEqual("ok", state)

    def test_local_fallback_state_is_reported(self):
        environment = {key: value for key, value in os.environ.items() if key != "SCHEMA_FILE"}
        with mock.patch.dict(os.environ, environment, clear=True):
            path, state = pinned_schema_status()
        self.assertIn(state, ("ok", "missing", "local_checksum_mismatch"))
        self.assertEqual(PINNED_SCHEMA, path)


class RealSchemaTest(unittest.TestCase):
    """Real pinned-schema checks; skipped when the local copy is absent."""

    def setUp(self):
        path, state = pinned_schema_status()
        if state == "ok":
            self.schema = json.loads(path.read_text(encoding="utf-8"))
            return
        if state in ("missing", "local_checksum_mismatch"):
            self.skipTest(f"pinned schema not available locally ({state})")
        self.fail(f"CI-provided SCHEMA_FILE is not usable ({state}: {path})")

    def test_fixed_schema_same_version_has_empty_diff(self):
        delta = diff_schemas(self.schema, copy.deepcopy(self.schema))
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])
        self.assertEqual({}, delta["changed"])

    def test_fixed_schema_effective_hosts_include_the_k2_stream_host(self):
        produce = self.schema["paths"]["/produce"]
        servers = effective_servers(self.schema, produce, produce["post"])
        self.assertEqual(K2_STREAM_HOST, servers[0]["url"])
        things = self.schema["paths"]["/zones/{zone_id}/dns_records"]
        servers = effective_servers(self.schema, things, things["get"])
        self.assertEqual("https://api.cloudflare.com/client/v4", servers[0]["url"])

    def test_fixed_schema_undefined_vendor_schemes_do_not_break_fingerprints(self):
        operation = {
            "method": "POST",
            "path": "/pages/assets/upload",
            "operation_id": "pages-assets-upload",
            "tags": ["Pages Assets"],
            "metadata": self.schema["paths"]["/pages/assets/upload"]["post"],
        }
        fingerprint = fingerprint_operation(
            self.schema, self.schema["paths"]["/pages/assets/upload"], operation
        )
        self.assertIn("security", fingerprint)

    def test_fixed_schema_deep_reference_change_reports_affected_operations(self):
        candidate = copy.deepcopy(self.schema)
        candidate["components"]["schemas"]["mcn_bad_response"]["allOf"][1]["properties"]["errors"][
            "minLength"
        ] = 2
        delta = diff_schemas(self.schema, candidate)
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])
        self.assertGreaterEqual(len(delta["changed"]), 20)
        for operation_id, entry in delta["changed"].items():
            self.assertIn("responses", entry["fields"], operation_id)
        self.assertIn("catalog-syncs-create", delta["changed"])
        self.assertNotIn("zones-get", delta["changed"])

    def test_fixed_schema_k2_host_change_reports_only_k2_operations(self):
        candidate = copy.deepcopy(self.schema)
        candidate["paths"]["/produce"]["servers"][0]["variables"]["stream_id"]["default"] = "other"
        delta = diff_schemas(self.schema, candidate)
        self.assertEqual(["postK2Produce"], list(delta["changed"]))
        self.assertEqual(["servers"], delta["changed"]["postK2Produce"]["fields"])
        self.assertEqual({}, delta["added"])
        self.assertEqual({}, delta["removed"])


if __name__ == "__main__":
    unittest.main()
