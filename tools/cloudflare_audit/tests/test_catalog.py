"""Offline tests for the pinned Cloudflare coverage catalog.

These tests use only the small checked-in fixture; they never download the
public schema and never touch a Cloudflare account or credential.
"""
import codecs
import copy
import csv
import hashlib
import io
import json
import pathlib
import tempfile
import unittest

from tools.cloudflare_audit import cli
from tools.cloudflare_audit.catalog import IMPLEMENTED_STATUS, build_catalog, render_catalog_files
from tools.cloudflare_audit.schema import enumerate_operations, load_schema

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"


def read_fixture() -> dict:
    return json.loads((FIXTURES / "schema.json").read_text(encoding="utf-8"))


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.fixture_path = FIXTURES / "schema.json"
        self.schema = read_fixture()
        self.implemented = {
            "baseline_commit": "a" * 40,
            "operations": {
                "beta-create": {
                    "method": "POST",
                    "path": "/beta/{id}",
                    "source_file": "shared-core/.../CloudflareSession.kt",
                    "scope": "reviewed subset；partial scope",
                    "test_evidence": "isolated_tests_only",
                    "acceptance": "unverified_authenticated_cloud_account",
                }
            },
        }

    def test_multitag_is_not_an_extra_operation(self):
        result = build_catalog(self.schema, self.implemented)
        self.assertEqual(3, result["counts"]["operations"])
        self.assertEqual(4, result["counts"]["tag_memberships"])
        self.assertEqual(1, result["counts"]["implemented_subset"])

    def test_counts_keep_templates_tags_and_deprecation_separate(self):
        counts = build_catalog(self.schema, self.implemented)["counts"]
        self.assertEqual(2, counts["path_templates"])
        self.assertEqual(3, counts["distinct_tags"])
        self.assertEqual(2, counts["not_implemented"])
        self.assertEqual(1, counts["explicit_deprecated"])
        self.assertEqual(1, counts["any_deprecation_signal"])
        self.assertEqual(0, counts["additional_deprecation_signals_without_boolean"])

    def test_operations_are_ordered_by_path_then_method(self):
        operations = enumerate_operations(self.schema)
        self.assertEqual(
            [("GET", "/alpha"), ("DELETE", "/beta/{id}"), ("POST", "/beta/{id}")],
            [(operation["method"], operation["path"]) for operation in operations],
        )

    def test_escaped_json_pointer_reference_is_resolved(self):
        operations = {
            operation["operation_id"]: operation for operation in enumerate_operations(self.schema)
        }
        alpha = operations["alpha-list"]
        self.assertEqual(["text/plain"], alpha["request_content_types"])
        self.assertEqual(["application/json"], alpha["response_content_types"])
        self.assertEqual(["Alpha", "Shared"], alpha["tags"])
        self.assertEqual("generally-available", alpha["metadata"]["x-fern-availability"])

    def test_duplicate_operation_id_fails(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/beta/{id}"]["post"]["operationId"] = "alpha-list"
        with self.assertRaises(ValueError):
            enumerate_operations(schema)

    def test_missing_operation_id_fails(self):
        schema = copy.deepcopy(self.schema)
        del schema["paths"]["/beta/{id}"]["post"]["operationId"]
        with self.assertRaises(ValueError):
            enumerate_operations(schema)

    def test_unresolved_local_reference_fails(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/alpha"]["get"]["requestBody"] = {
            "$ref": "#/components/requestBodies/missing"
        }
        with self.assertRaises(ValueError):
            enumerate_operations(schema)

    def test_cyclic_local_reference_fails(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/alpha"]["get"]["requestBody"] = {"$ref": "#/components/requestBodies/loop-a"}
        schema["components"]["requestBodies"]["loop-a"] = {
            "$ref": "#/components/requestBodies/loop-b"
        }
        schema["components"]["requestBodies"]["loop-b"] = {
            "$ref": "#/components/requestBodies/loop-a"
        }
        with self.assertRaises(ValueError):
            enumerate_operations(schema)

    def test_load_schema_rejects_bad_sha256(self):
        with self.assertRaises(ValueError):
            load_schema(self.fixture_path, "0" * 64)

    def test_load_schema_accepts_pinned_sha256(self):
        digest = hashlib.sha256(self.fixture_path.read_bytes()).hexdigest()
        self.assertEqual(2, len(load_schema(self.fixture_path, digest)["paths"]))
        self.assertEqual(2, len(load_schema(self.fixture_path, None)["paths"]))

    def test_load_schema_rejects_document_without_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            empty = pathlib.Path(directory) / "no-paths.json"
            empty.write_text("{\"openapi\": \"3.0.3\"}", encoding="utf-8")
            with self.assertRaises(ValueError):
                load_schema(empty, None)

    def test_implemented_mapping_must_hit_an_existing_operation(self):
        implemented = copy.deepcopy(self.implemented)
        implemented["operations"]["does-not-exist"] = {"method": "GET", "path": "/nope"}
        with self.assertRaises(ValueError):
            build_catalog(self.schema, implemented)

    def test_implemented_mapping_method_and_path_must_match_schema(self):
        implemented = {"operations": {"beta-create": {"method": "GET", "path": "/beta/{id}"}}}
        with self.assertRaises(ValueError):
            build_catalog(self.schema, implemented)
        implemented = {"operations": {"beta-create": {"method": "POST", "path": "/beta"}}}
        with self.assertRaises(ValueError):
            build_catalog(self.schema, implemented)

    def test_status_and_scope_come_from_the_mapping(self):
        endpoints = {
            endpoint["operation_id"]: endpoint
            for endpoint in build_catalog(self.schema, self.implemented)["endpoints"]
        }
        mapped = endpoints["beta-create"]
        self.assertEqual(IMPLEMENTED_STATUS, mapped["flarego_status"])
        self.assertEqual("reviewed subset；partial scope", mapped["coverage_note"])
        self.assertEqual("isolated_tests_only", mapped["test_evidence"])
        unmapped = endpoints["alpha-list"]
        self.assertEqual("not_implemented", unmapped["flarego_status"])
        self.assertEqual("none", unmapped["test_evidence"])

    def test_repeated_generation_is_byte_identical(self):
        first = render_catalog_files(build_catalog(self.schema, self.implemented))
        second = render_catalog_files(build_catalog(copy.deepcopy(self.schema), self.implemented))
        self.assertEqual(first, second)

    def test_generation_ignores_dict_insertion_order(self):
        expected = render_catalog_files(build_catalog(self.schema, self.implemented))
        shuffled = copy.deepcopy(self.schema)
        shuffled["paths"] = dict(reversed(list(shuffled["paths"].items())))
        for path, item in shuffled["paths"].items():
            shuffled["paths"][path] = dict(reversed(list(item.items())))
        self.assertEqual(expected, render_catalog_files(build_catalog(shuffled, self.implemented)))

    def test_csv_files_use_utf8_bom_and_crlf(self):
        files = render_catalog_files(build_catalog(self.schema, self.implemented))
        self.assertEqual({"endpoints.csv", "products.csv"}, set(files))
        for name, data in files.items():
            self.assertTrue(data.startswith(codecs.BOM_UTF8), name)
            self.assertEqual(data.count(b"\n"), data.count(b"\r\n"), name)

    def test_multiline_summary_survives_csv_round_trip(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/beta/{id}"]["delete"]["summary"] = "Delete beta (deprecated)\n"
        files = render_catalog_files(build_catalog(schema, self.implemented))
        text = files["endpoints.csv"].decode("utf-8-sig")
        rows = {row["operation_id"]: row for row in csv.DictReader(io.StringIO(text, newline=""))}
        self.assertEqual("Delete beta (deprecated)\n", rows["beta-delete"]["summary"])

    def test_endpoint_rows_expose_raw_metadata(self):
        endpoints = {
            endpoint["operation_id"]: endpoint
            for endpoint in build_catalog(self.schema, self.implemented)["endpoints"]
        }
        alpha = endpoints["alpha-list"]
        self.assertEqual('{"free": true, "pro": false}', alpha["plan_availability_raw"])
        self.assertEqual('[{"api_token": []}]', alpha["security_raw"])
        self.assertEqual("", alpha["deprecation_signals"])
        beta = endpoints["beta-create"]
        self.assertEqual("true", beta["deprecated_boolean"])
        self.assertEqual("deprecated=true | x-cfDeprecation.display=true", beta["deprecation_signals"])
        self.assertEqual('{"x-api-token-group": ["Beta Write"]}', beta["token_permissions_raw"])
        self.assertEqual("null", beta["forge_hidden"])

    # --- I1: path-item references (review finding) -------------------------

    def referenced_schema(self) -> dict:
        """Move /alpha behind a local path-item $ref (review I1 reproduction)."""
        schema = copy.deepcopy(self.schema)
        item = schema["paths"].pop("/alpha")
        schema["components"].setdefault("x-review-path-items", {})["Alpha"] = item
        schema["paths"]["/alpha"] = {"$ref": "#/components/x-review-path-items/Alpha"}
        return schema

    def test_path_item_reference_is_enumerated(self):
        operations = enumerate_operations(self.referenced_schema())
        self.assertEqual(
            [("GET", "/alpha"), ("DELETE", "/beta/{id}"), ("POST", "/beta/{id}")],
            [(operation["method"], operation["path"]) for operation in operations],
        )
        alpha = operations[0]
        self.assertEqual("alpha-list", alpha["operation_id"])
        self.assertEqual(["Alpha", "Shared"], alpha["tags"])
        self.assertEqual("List alpha", alpha["metadata"]["summary"])
        self.assertEqual(["text/plain"], alpha["request_content_types"])

    def test_path_item_reference_chain_is_enumerated(self):
        schema = self.referenced_schema()
        schema["components"]["x-review-path-items"]["Alias"] = {
            "$ref": "#/components/x-review-path-items/Alpha"
        }
        schema["paths"]["/alpha"] = {"$ref": "#/components/x-review-path-items/Alias"}
        self.assertEqual(3, len(enumerate_operations(schema)))

    def test_path_item_reference_keeps_catalog_byte_identical(self):
        inline = render_catalog_files(build_catalog(self.schema, self.implemented))
        referenced = render_catalog_files(build_catalog(self.referenced_schema(), self.implemented))
        self.assertEqual(inline, referenced)

    def test_missing_path_item_reference_fails(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/alpha"] = {"$ref": "#/components/x-review-path-items/Missing"}
        with self.assertRaises(ValueError):
            enumerate_operations(schema)

    def test_cyclic_path_item_reference_fails(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/alpha"] = {"$ref": "#/paths/~1alpha"}
        with self.assertRaises(ValueError):
            enumerate_operations(schema)

    def test_external_path_item_reference_fails(self):
        for reference in (
            "https://example.com/schema.json#/paths/~1alpha",
            "./other.json#/paths/~1alpha",
        ):
            schema = copy.deepcopy(self.schema)
            schema["paths"]["/alpha"] = {"$ref": reference}
            with self.assertRaises(ValueError):
                enumerate_operations(schema)

    def test_path_item_reference_siblings_fail_closed(self):
        for sibling in (
            {"summary": "local summary"},
            {"get": {"operationId": "local-get", "tags": ["Alpha"], "responses": {}}},
            {"parameters": []},
        ):
            schema = self.referenced_schema()
            schema["paths"]["/alpha"].update(sibling)
            with self.assertRaises(ValueError) as caught:
                enumerate_operations(schema)
            self.assertIn(next(iter(sibling)), str(caught.exception))

    def test_path_item_reference_sibling_at_intermediate_hop_fails_closed(self):
        schema = self.referenced_schema()
        schema["components"]["x-review-path-items"]["Alias"] = {
            "$ref": "#/components/x-review-path-items/Alpha",
            "summary": "alias summary",
        }
        schema["paths"]["/alpha"] = {"$ref": "#/components/x-review-path-items/Alias"}
        with self.assertRaises(ValueError) as caught:
            enumerate_operations(schema)
        self.assertIn("summary", str(caught.exception))

    # --- I2: RFC 6901 pointer resolution (review finding) ------------------

    def schema_with_array_request_body(self) -> dict:
        schema = copy.deepcopy(self.schema)
        body = schema["components"]["requestBodies"].pop("Alpha/Body~JSON")
        schema["components"]["x-request-body-list"] = [body]
        return schema

    def test_array_member_pointer_is_resolved(self):
        schema = self.schema_with_array_request_body()
        schema["paths"]["/alpha"]["get"]["requestBody"] = {
            "$ref": "#/components/x-request-body-list/0"
        }
        operations = {
            operation["operation_id"]: operation for operation in enumerate_operations(schema)
        }
        self.assertEqual(["text/plain"], operations["alpha-list"]["request_content_types"])

    def test_percent_encoded_pointer_token_is_resolved(self):
        schema = copy.deepcopy(self.schema)
        schema["components"]["requestBodies"]["Alpha Body"] = schema["components"][
            "requestBodies"
        ].pop("Alpha/Body~JSON")
        schema["paths"]["/alpha"]["get"]["requestBody"] = {
            "$ref": "#/components/requestBodies/Alpha%20Body"
        }
        operations = {
            operation["operation_id"]: operation for operation in enumerate_operations(schema)
        }
        self.assertEqual(["text/plain"], operations["alpha-list"]["request_content_types"])

    def test_percent_encoded_separator_follows_rfc6901_fragment_decoding(self):
        # RFC 6901 section 6 percent-decodes the fragment before splitting it into tokens,
        # so %2F becomes a token separator rather than a literal slash inside a key.
        schema = copy.deepcopy(self.schema)
        schema["components"]["requestBodies"]["Nested"] = {"content": {"application/json": {}}}
        schema["paths"]["/alpha"]["get"]["requestBody"] = {
            "$ref": "#/components%2FrequestBodies%2FNested"
        }
        operations = {
            operation["operation_id"]: operation for operation in enumerate_operations(schema)
        }
        self.assertEqual(["application/json"], operations["alpha-list"]["request_content_types"])

    def test_invalid_array_index_fails(self):
        for token in ("-", "01", "-1", "a", "1.0"):
            schema = self.schema_with_array_request_body()
            schema["paths"]["/alpha"]["get"]["requestBody"] = {
                "$ref": f"#/components/x-request-body-list/{token}"
            }
            with self.assertRaises(ValueError) as caught:
                enumerate_operations(schema)
            self.assertIn("index", str(caught.exception))

    def test_out_of_range_array_index_fails(self):
        schema = self.schema_with_array_request_body()
        schema["paths"]["/alpha"]["get"]["requestBody"] = {
            "$ref": "#/components/x-request-body-list/1"
        }
        with self.assertRaises(ValueError) as caught:
            enumerate_operations(schema)
        self.assertIn("index", str(caught.exception))

    def test_invalid_percent_encoding_fails(self):
        for reference in (
            "#/components/requestBodies/Alpha%zzBody",
            "#/components/requestBodies/Alpha%2",
            "#/components/requestBodies/Alpha%FFBody",
        ):
            schema = copy.deepcopy(self.schema)
            schema["paths"]["/alpha"]["get"]["requestBody"] = {"$ref": reference}
            with self.assertRaises(ValueError) as caught:
                enumerate_operations(schema)
            self.assertIn("percent", str(caught.exception))

    def test_pointer_into_scalar_fails(self):
        schema = copy.deepcopy(self.schema)
        schema["paths"]["/alpha"]["get"]["requestBody"] = {
            "$ref": "#/components/schemas/Alpha/type/0"
        }
        with self.assertRaises(ValueError) as caught:
            enumerate_operations(schema)
        self.assertIn("scalar", str(caught.exception))

    # --- deferred minor: CLI pin/count/baseline regression coverage --------

    def write_json(self, directory: str, name: str, value: dict) -> pathlib.Path:
        path = pathlib.Path(directory) / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path

    def synthetic_manifest(self, schema: dict, implemented: dict, schema_path: pathlib.Path) -> dict:
        counts = build_catalog(schema, implemented)["counts"]
        return {
            "flarego_baseline_commit": implemented["baseline_commit"],
            "schema": {
                "sha256": hashlib.sha256(schema_path.read_bytes()).hexdigest(),
                "paths": counts["path_templates"],
                "operations": counts["operations"],
                "openapi_version": schema["openapi"],
            },
            "counts": counts,
        }

    def test_cli_generate_writes_verified_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            schema_path = pathlib.Path(directory) / "schema.json"
            schema_path.write_bytes(self.fixture_path.read_bytes())
            manifest_path = self.write_json(
                directory,
                "manifest.json",
                self.synthetic_manifest(self.schema, self.implemented, schema_path),
            )
            implemented_path = self.write_json(directory, "implemented.json", self.implemented)
            output = pathlib.Path(directory) / "out"
            catalog = cli.generate(schema_path, manifest_path, implemented_path, output)
            self.assertEqual(3, catalog["counts"]["operations"])
            for name, data in render_catalog_files(catalog).items():
                self.assertEqual(data, (output / name).read_bytes(), name)
            self.assertEqual(
                manifest_path.read_text(encoding="utf-8"),
                (output / "snapshot.json").read_text(encoding="utf-8"),
            )

    def test_cli_generate_refuses_changed_baselines(self):
        with tempfile.TemporaryDirectory() as directory:
            schema_path = pathlib.Path(directory) / "schema.json"
            schema_path.write_bytes(self.fixture_path.read_bytes())
            manifest = self.synthetic_manifest(self.schema, self.implemented, schema_path)
            implemented_path = self.write_json(directory, "implemented.json", self.implemented)
            output = pathlib.Path(directory) / "out"

            bad_sha = copy.deepcopy(manifest)
            bad_sha["schema"]["sha256"] = "0" * 64
            count_drift = copy.deepcopy(manifest)
            count_drift["counts"]["operations"] = 99
            other_baseline = copy.deepcopy(self.implemented)
            other_baseline["baseline_commit"] = "f" * 40
            cases = (
                (bad_sha, implemented_path),
                (count_drift, implemented_path),
                (manifest, self.write_json(directory, "other-baseline.json", other_baseline)),
            )
            for index, (value, mapping_path) in enumerate(cases):
                manifest_path = self.write_json(directory, f"manifest-{index}.json", value)
                with self.assertRaises(ValueError):
                    cli.generate(schema_path, manifest_path, mapping_path, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
