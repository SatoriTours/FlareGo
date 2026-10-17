"""Task 9 pinned request/id/value binding tests; all local and credential-free."""
import copy
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from tools.cloudflare_audit.generate_zone_settings import PINNED_SHA256, default_schema_path, generate_zone_settings_definitions, render_kotlin
from tools.cloudflare_audit.schema import SchemaError, load_schema

ROOT = Path(__file__).resolve().parents[3]
NATIVE = {'ssl','always_use_https','min_tls_version','tls_1_3','http2','http3','development_mode','browser_cache_ttl'}
DEDICATED = {'aegis','automatic_platform_optimization','binary_ast','fonts','h2_prioritization','image_resizing','origin_h2_max_streams','origin_max_http_version','speed_brain','transformations_allowed_origins','transformations_c2pa','transformations_config'}

class ZoneSettingsGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.source = load_schema(default_schema_path(), PINNED_SHA256)

    def test_deterministic_partial_output_and_all_dedicated_plus_native_definitions(self):
        d = generate_zone_settings_definitions(self.source)
        self.assertEqual(DEDICATED, set(d['dedicated']))
        self.assertEqual(NATIVE, {k for k,v in d['generic'].items() if v['status']=='approved'})
        self.assertTrue(all(v['status']=='approved' for v in d['dedicated'].values()))
        self.assertEqual(d, generate_zone_settings_definitions(self.source))
        output = render_kotlin(d)
        self.assertEqual(output, (ROOT/'shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/generated/ZoneSettingSchemas.kt').read_text())
        self.assertLess(len(output.encode()), 60_000)
        self.assertNotIn('zone-settings-get-single-setting', output)  # generated data grants no permission

    def test_generic_key_uses_actual_request_member_and_single_response_id_value_binding(self):
        d=generate_zone_settings_definitions(self.source)
        ssl=d['generic']['ssl']
        self.assertEqual(['off','flexible','full','strict'], ssl['schema']['properties']['value']['enum'])
        self.assertEqual('#/components/schemas/zones_ssl_value',ssl['binding']['valueRef'])
        self.assertEqual(['1.0','1.1','1.2','1.3'],d['generic']['min_tls_version']['schema']['properties']['value']['enum'])
        self.assertEqual(['on','off','zrt'],d['generic']['tls_1_3']['schema']['properties']['value']['enum'])
        self.assertEqual(0,d['generic']['browser_cache_ttl']['schema']['properties']['value']['minimum'])
        self.assertEqual('image_resizing_allowed_origins',d['dedicated']['transformations_allowed_origins']['responseId'])
        self.assertEqual('image_resizing_c2pa',d['dedicated']['transformations_c2pa']['responseId'])

    def test_missing_request_union_member_and_ambiguous_response_mapping_are_needs_review(self):
        source=copy.deepcopy(self.source)
        value=source['components']['schemas']['zones_setting_value']['oneOf']
        value[:]=[x for x in value if x.get('$ref')!='#/components/schemas/zones_http2_value']
        d=generate_zone_settings_definitions(source)
        self.assertEqual('needs_review',d['generic']['http2']['status']);self.assertIsNone(d['generic']['http2']['schema'])
        source=copy.deepcopy(self.source)
        source['components']['schemas']['zones_http2']['allOf'][1]['properties']['id']['enum']=['http2','other']
        d=generate_zone_settings_definitions(source)
        self.assertEqual('needs_review',d['generic']['http2']['status'])

    def test_deprecated_and_readonly_inputs_never_reopen_through_generic_route(self):
        d=generate_zone_settings_definitions(self.source)
        for key in ['mirage','cname_flattening','orange_to_orange','privacy_pass','response_buffering']:
            self.assertEqual('deprecated',d['generic'][key]['status']);self.assertIsNone(d['generic'][key]['schema'])
        for annotation in ['deprecated','readOnly']:
            source=copy.deepcopy(self.source)
            source['components']['schemas']['zones_http2_value'][annotation]=True
            entry=generate_zone_settings_definitions(source)['generic']['http2']
            self.assertNotEqual('approved',entry['status']);self.assertIsNone(entry['schema'])
        self.assertEqual('needs_review',d['generic']['transformations']['status'])

    def test_unknown_validation_ref_cycles_and_keyword_shapes_fail_closed(self):
        for field in [{'not':{'type':'string'}},{'format':'future-format'},{'type':['string','null']},{'$ref':'#/missing'},{'oneOf':[]}]:
            source=copy.deepcopy(self.source)
            source['components']['schemas']['speed_binary_ast_value'].update(field)
            with self.subTest(field=field),self.assertRaises(SchemaError): generate_zone_settings_definitions(source)
        source=copy.deepcopy(self.source)
        source['components']['schemas']['speed_binary_ast_value']={'$ref':'#/components/schemas/speed_binary_ast_value'}
        with self.assertRaises(SchemaError):generate_zone_settings_definitions(source)

    def test_nested_patch_only_where_explicit_request_contract_guarantees_preservation(self):
        d=generate_zone_settings_definitions(self.source)
        self.assertTrue(d['dedicated']['transformations_config']['partialObjectPatch'])
        self.assertFalse(d['dedicated']['aegis']['partialObjectPatch'])
        self.assertEqual(['cf','enabled','wordpress','wp_plugin'],d['dedicated']['automatic_platform_optimization']['schema']['properties']['value']['oneOf'][1]['required'])
        source=copy.deepcopy(self.source)
        source['components']['schemas']['speed_transformations_config_value']['description']='Unknown nested semantics'
        self.assertFalse(generate_zone_settings_definitions(source)['dedicated']['transformations_config']['partialObjectPatch'])
        self.assertEqual(6,len(d['purge']['anyOf']))

    def test_schema_file_priority_bad_explicit_source_and_digest(self):
        with patch.dict(os.environ,{'SCHEMA_FILE':'/tmp/flarego-task9-fixed-schema.json'}):self.assertEqual(Path('/tmp/flarego-task9-fixed-schema.json'),default_schema_path())
        with patch.dict(os.environ,{'SCHEMA_FILE':'/tmp/flarego-no-such-task9-schema'}):
            with self.assertRaises(SchemaError):load_schema(default_schema_path(),PINNED_SHA256)
        with self.assertRaises(SchemaError):load_schema(default_schema_path(),'0'*64)

    def test_invalid_numeric_pattern_and_nested_constraint_shapes_fail_closed(self):
        for update in [{'maximum':False},{'minimum':float('nan')},{'minLength':-1},{'pattern':'['},{'items':[]},{'required':['field',True]}]:
            source=copy.deepcopy(self.source)
            source['components']['schemas']['speed_binary_ast_value'].update(update)
            with self.subTest(update=update),self.assertRaises(SchemaError):generate_zone_settings_definitions(source)

    def test_generic_request_wrappers_cannot_hide_unsupported_constraints(self):
        for location in ['root','branch']:
            source=copy.deepcopy(self.source)
            body=source['paths']['/zones/{zone_id}/settings/{setting_id}']['patch']['requestBody']['content']['application/json']['schema']
            target=source['components']['schemas']['zones_zone_settings_single_request'] if '$ref' in body else body
            if location=='branch':target=target['oneOf'][1]
            target['not']={'type':'object'}
            with self.subTest(location=location),self.assertRaises(SchemaError):generate_zone_settings_definitions(source)
