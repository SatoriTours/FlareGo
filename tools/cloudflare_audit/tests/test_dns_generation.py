"""Offline contract tests against the SHA-pinned public schema."""
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

from tools.cloudflare_audit.generate_dns import (
    PINNED_SHA256, generate_dns_definitions, render_kotlin, default_schema_path,
)
from tools.cloudflare_audit.schema import SchemaError, load_schema

ROOT = Path(__file__).resolve().parents[3]
TYPES = set('A AAAA CAA CERT CNAME DNSKEY DS HTTPS LOC MX NAPTR NS OPENPGPKEY PTR SMIMEA SRV SSHFP SVCB TLSA TXT URI'.split())


class DnsGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = load_schema(default_schema_path(), PINNED_SHA256)

    def test_ci_schema_path_takes_precedence_and_is_never_silently_skipped(self):
        with patch.dict(os.environ, {'SCHEMA_FILE': '/tmp/ci-fixed-openapi.json'}):
            self.assertEqual(Path('/tmp/ci-fixed-openapi.json'), default_schema_path())
        with patch.dict(os.environ, {'SCHEMA_FILE': '/tmp/does-not-exist-flarego-schema'}):
            with self.assertRaises(SchemaError):
                load_schema(default_schema_path(), PINNED_SHA256)
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(ROOT / '.superpowers/cloudflare/openapi.json', default_schema_path())

    def test_exact_types_deterministic_and_checked_in_output(self):
        definitions = generate_dns_definitions(self.source)
        self.assertEqual(TYPES, set(definitions))
        self.assertEqual(definitions, generate_dns_definitions(self.source))
        self.assertEqual(render_kotlin(definitions), (ROOT / 'shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/generated/DnsSchemas.kt').read_text())
        self.assertLess(len(render_kotlin(definitions).encode()), 160_000)

    def test_methods_refs_required_and_readonly_remain_faithful(self):
        source = copy.deepcopy(self.source)
        components = source['components']['schemas']
        # Distinguish methods even though the pinned snapshot happens to reuse branches.
        components['test_patch'] = copy.deepcopy(components['dns-records_ARecord'])
        components['test_patch']['required'] = ['name']
        components['test_patch']['allOf'][1]['required'] = ['content', 'server_value']
        components['test_patch']['allOf'][1]['properties']['server_value'] = {'type': 'string', 'readOnly': True}
        patch = copy.deepcopy(components['dns-records_dns-record-patch'])
        patch['anyOf'][0] = copy.deepcopy(components['dns-records_dns-record-without-data'])
        patch['anyOf'][0]['oneOf'][0] = {'$ref': '#/components/schemas/test_patch'}
        source['paths']['/zones/{zone_id}/dns_records/{dns_record_id}']['patch']['requestBody']['content']['application/json']['schema'] = patch
        result = generate_dns_definitions(source)
        self.assertEqual({'POST', 'PATCH', 'PUT'}, set(result['A']))
        self.assertNotEqual(result['A']['POST'], result['A']['PATCH'])
        local = result['A']['PATCH']['$defs']['test_patch']
        self.assertEqual(['name'], local['required'])
        self.assertEqual(['content'], local['allOf'][1]['required'])
        self.assertNotIn('server_value', local['allOf'][1]['properties'])
        self.assertIn('#/$defs/dns-records_dns-record-shared-fields', json.dumps(local))
        caa = result['CAA']['POST']['$defs']['dns-records_CAARecord']
        self.assertNotIn('content', caa['allOf'][1]['properties'])
        # No invented required lists in the actual pinned snapshot.
        self.assertNotIn('"required"', json.dumps(generate_dns_definitions(self.source)))

    def test_fail_closed_on_unknown_keywords_shapes_refs_and_variants(self):
        mutations = [
            lambda c: c['dns-records_ARecord'].update({'not': {'type': 'string'}}),
            lambda c: c['dns-records_ARecord'].update({'futureValidation': True}),
            lambda c: c['dns-records_ARecord'].update({'allOf': []}),
            lambda c: c['dns-records_ARecord'].update({'type': ['object', 'null']}),
            lambda c: c['dns-records_ARecord']['allOf'][1]['properties']['content'].update({'format': 'future-format'}),
            lambda c: c['dns-records_ARecord']['allOf'][1]['properties']['content'].update({'format': []}),
            lambda c: c['dns-records_ARecord']['allOf'][1]['properties'].update({'bad': False}),
            lambda c: c['dns-records_ARecord']['allOf'][1]['properties'].update({'bad': {'$ref': '#/missing'}}),
            lambda c: c['dns-records_ARecord']['allOf'][1]['properties'].update({'bad': {'$ref': '#/components/schemas/dns-records_ARecord'}}),
            lambda c: c['dns-records_dns-record-without-data']['oneOf'].append({'$ref': '#/components/schemas/dns-records_ARecord'}),
            lambda c: c['dns-records_ARecord']['allOf'][1]['properties']['type'].update({'enum': ['FUTURE']}),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                source = copy.deepcopy(self.source)
                mutate(source['components']['schemas'])
                with self.assertRaises(SchemaError):
                    generate_dns_definitions(source)

    def test_readonly_required_is_excluded_across_inherited_properties(self):
        source = copy.deepcopy(self.source)
        caa = source['components']['schemas']['dns-records_CAARecord']
        caa['required'] = ['name', 'content', 'data']
        generated = generate_dns_definitions(source)['CAA']['POST']['$defs']['dns-records_CAARecord']
        self.assertEqual(['name', 'data'], generated['required'])

    def test_readonly_in_one_allof_member_cannot_be_written_through_a_sibling(self):
        source = copy.deepcopy(self.source)
        record = source['components']['schemas']['dns-records_ARecord']
        record['allOf'].append({'type': 'object', 'properties': {'content': {'type': 'string', 'readOnly': True}}})
        record['allOf'][1]['required'] = ['content', 'type']
        generated = generate_dns_definitions(source)['A']['POST']['$defs']['dns-records_ARecord']
        self.assertNotIn('content', generated['allOf'][1]['properties'])
        self.assertEqual(['type'], generated['allOf'][1]['required'])

    def test_wrong_source_digest_fails(self):
        with self.assertRaises(SchemaError):
            load_schema(default_schema_path(), '0' * 64)


if __name__ == '__main__':
    unittest.main()
