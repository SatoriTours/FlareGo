"""Generate small, method-specific DNS write schemas from the pinned OpenAPI.

No network or runtime OpenAPI dependency. References, compositions and required
lists are retained. Only annotations and read-only properties are removed;
product-level completeness rules do not masquerade as official required lists.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import os
from pathlib import Path
import re

from tools.cloudflare_audit.schema import SchemaError, dereference, load_schema, resolve_json_pointer

PINNED_SHA256 = '06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461'
DNS_TYPES = frozenset('A AAAA CAA CERT CNAME DNSKEY DS HTTPS LOC MX NAPTR NS OPENPGPKEY PTR SMIMEA SRV SSHFP SVCB TLSA TXT URI'.split())
ANNOTATIONS = {'description', 'title', 'example', 'default', 'x-auditable', 'x-stainless-collection-type'}
KEYWORDS = {'$ref', 'type', 'enum', 'required', 'properties', 'items', 'allOf', 'anyOf', 'oneOf',
            'nullable', 'readOnly', 'writeOnly', 'minimum', 'maximum', 'exclusiveMinimum',
            'exclusiveMaximum', 'multipleOf', 'minLength', 'maxLength', 'pattern', 'format',
            'minItems', 'maxItems', 'uniqueItems', 'minProperties', 'maxProperties', 'additionalProperties'}
FORMATS = {'ipv4', 'ipv6', 'hostname'}


def default_schema_path() -> Path:
    """CI supplies SCHEMA_FILE; a bad explicit path never falls back silently."""
    configured = os.environ.get('SCHEMA_FILE')
    if configured is not None:
        return Path(configured)
    return Path(__file__).resolve().parents[2] / '.superpowers/cloudflare/openapi.json'


def _fail(message):
    raise SchemaError(message)


def _check_shape(node):
    if not isinstance(node, dict):
        _fail('DNS schema must be an object (boolean/tuple schemas are unsupported)')
    unknown = set(node) - KEYWORDS - ANNOTATIONS
    if unknown:
        _fail(f'unsupported DNS schema keyword(s): {sorted(unknown)}')
    if '$ref' in node and set(node) != {'$ref'}:
        _fail('DNS $ref siblings are unsupported')
    if 'type' in node and node['type'] not in ('object', 'array', 'string', 'number', 'integer', 'boolean'):
        _fail(f'unsupported DNS schema type: {node["type"]!r}')
    for key in ('nullable', 'readOnly', 'writeOnly', 'uniqueItems', 'exclusiveMinimum', 'exclusiveMaximum'):
        if key in node and not isinstance(node[key], bool):
            _fail(f'{key} must be boolean (OpenAPI 3.0 semantics)')
    for key in ('minimum', 'maximum', 'multipleOf'):
        if key in node and (isinstance(node[key], bool) or not isinstance(node[key], (int, float)) or not math.isfinite(node[key])):
            _fail(f'{key} must be a finite number')
    if 'multipleOf' in node and node['multipleOf'] <= 0:
        _fail('multipleOf must be positive')
    for key in ('minLength', 'maxLength', 'minItems', 'maxItems', 'minProperties', 'maxProperties'):
        if key in node and (type(node[key]) is not int or node[key] < 0):
            _fail(f'{key} must be a nonnegative integer')
    if 'enum' in node and (not isinstance(node['enum'], list) or not node['enum']):
        _fail('enum must be a nonempty array')
    if 'required' in node and (not isinstance(node['required'], list) or any(not isinstance(x, str) for x in node['required']) or len(set(node['required'])) != len(node['required'])):
        _fail('required must be an array of distinct property names')
    if 'format' in node and (not isinstance(node['format'], str) or node['format'] not in FORMATS):
        _fail(f'unsupported DNS format: {node["format"]!r}')
    if 'pattern' in node:
        try:
            re.compile(node['pattern'])
        except (TypeError, re.error) as error:
            _fail(f'invalid pattern: {error}')
    if 'properties' in node and not isinstance(node['properties'], dict):
        _fail('properties must be an object')
    for key in ('allOf', 'anyOf', 'oneOf'):
        if key in node and (not isinstance(node[key], list) or not node[key]):
            _fail(f'{key} must be a nonempty array')
    if 'additionalProperties' in node and not isinstance(node['additionalProperties'], (bool, dict)):
        _fail('additionalProperties must be boolean or a schema')


def _split_records(source, node, trail=()):
    """Retain union wrappers and every branch; never guess the first branch."""
    _check_shape(node)
    if '$ref' in node:
        ref = node['$ref']
        if ref in trail:
            _fail(f'cyclic DNS reference: {ref}')
        resolved = resolve_json_pointer(source, ref)
        # A record leaf remains a reference; grouping references are expanded
        # just enough to select each discriminated record with its envelopes.
        result = _split_records(source, resolved, trail + (ref,))
        if len(result) == 1:
            return {next(iter(result)): node}
        return result
    unions = [key for key in ('anyOf', 'oneOf') if key in node]
    if unions:
        if len(unions) != 1 or 'allOf' in node or 'properties' in node:
            _fail('unsupported record union shape')
        union = unions[0]
        result = {}
        for branch in node[union]:
            for record_type, selected in _split_records(source, branch, trail).items():
                if record_type in result:
                    _fail(f'duplicate DNS record branch: {record_type}')
                result[record_type] = {**{k: v for k, v in node.items() if k != union}, union: [selected]}
        return result
    def discriminators(part, refs=()):
        _check_shape(part)
        if '$ref' in part:
            ref = part['$ref']
            if ref in refs:
                _fail('cyclic discriminator reference')
            return discriminators(resolve_json_pointer(source, ref), refs + (ref,))
        found = []
        discriminator = part.get('properties', {}).get('type')
        if discriminator is not None:
            discriminator = dereference(source, discriminator, context='DNS discriminator', reject_siblings=True)
            if discriminator.get('type') != 'string' or len(discriminator.get('enum', [])) != 1:
                _fail('record type must be a single string enum')
            found.extend(discriminator['enum'])
        for branch in part.get('allOf', []):
            found.extend(discriminators(branch, refs))
        return found
    types = discriminators(node)
    if len(types) != 1 or types[0] not in DNS_TYPES:
        _fail(f'unsupported/ambiguous DNS record discriminator: {types}')
    return {types[0]: node}


def _local_schema(source, selected):
    defs = {}
    active = set()
    contexts = {}
    def readonly(node, trail=()):
        _check_shape(node)
        ref = node.get('$ref')
        if ref is not None:
            if ref in trail:
                _fail('cyclic readOnly reference')
            return readonly(resolve_json_pointer(source, ref), trail + (ref,))
        resolved = dereference(source, node, context='DNS readOnly property', reject_siblings=True)
        return resolved.get('readOnly') is True or any(readonly(x, trail) for x in resolved.get('allOf', []))
    def readonly_names(node, trail=()):
        _check_shape(node)
        if '$ref' in node:
            ref = node['$ref']
            if ref in trail:
                _fail('cyclic readOnly property reference')
            return readonly_names(resolve_json_pointer(source, ref), trail + (ref,))
        names = {name for name, prop in node.get('properties', {}).items() if readonly(prop)}
        for part in node.get('allOf', []):
            names |= readonly_names(part, trail)
        for union in ('anyOf', 'oneOf'):
            if union in node:
                branches = [readonly_names(part, trail) for part in node[union]]
                if any(branch != branches[0] for branch in branches):
                    _fail('unsupported branch-dependent readOnly properties')
                names |= branches[0]
        return names
    def convert(node, inherited_readonly=frozenset()):
        _check_shape(node)
        if '$ref' in node:
            ref = node['$ref']
            if not isinstance(ref, str) or not ref.startswith('#/components/schemas/') or '/' in ref[len('#/components/schemas/'):]:
                _fail(f'unsupported DNS reference: {ref!r}')
            name = ref.split('/')[-1]
            if ref in active:
                _fail(f'cyclic DNS reference: {ref}')
            if name in contexts and contexts[name] != inherited_readonly:
                _fail('unsupported context-dependent readOnly reference')
            if name not in defs:
                contexts[name] = inherited_readonly
                active.add(ref)
                defs[name] = convert(resolve_json_pointer(source, ref), inherited_readonly)
                active.remove(ref)
            return {'$ref': f'#/$defs/{name}'}
        result = {k: copy.deepcopy(v) for k, v in node.items() if k not in ANNOTATIONS}
        removed = set(inherited_readonly) | readonly_names(node)
        if 'properties' in node:
            properties = {}
            for name, value in node['properties'].items():
                converted = convert(value)  # Even readOnly schemas must be understood.
                if name not in removed:
                    properties[name] = converted
            result['properties'] = properties
        if 'required' in node:
            result['required'] = [name for name in node['required'] if name not in removed]
        for key in ('allOf', 'anyOf', 'oneOf'):
            if key in node:
                result[key] = [convert(x, frozenset(removed)) for x in node[key]]
        for key in ('items', 'additionalProperties'):
            if key in node and isinstance(node[key], dict):
                result[key] = convert(node[key])
            elif key == 'items' and key in node:
                _fail('items must be a schema object')
        return result
    root = convert(selected)
    return {**root, '$defs': dict(sorted(defs.items()))}


def generate_dns_definitions(schema: dict) -> dict[str, dict]:
    result = {record_type: {} for record_type in sorted(DNS_TYPES)}
    for method, path in (
        ('POST', '/zones/{zone_id}/dns_records'),
        ('PATCH', '/zones/{zone_id}/dns_records/{dns_record_id}'),
        ('PUT', '/zones/{zone_id}/dns_records/{dns_record_id}'),
    ):
        try:
            body = dereference(schema, schema['paths'][path][method.lower()]['requestBody'], context=f'{method} body', reject_siblings=True)
            root = body['content']['application/json']['schema']
        except (KeyError, TypeError) as error:
            raise SchemaError(f'missing DNS {method} JSON request schema') from error
        branches = _split_records(schema, root)
        if set(branches) != DNS_TYPES:
            _fail(f'{method} DNS types differ from the pinned 21: {sorted(branches)}')
        for record_type, branch in branches.items():
            result[record_type][method] = _local_schema(schema, branch)
    return result


def render_kotlin(definitions: dict[str, dict]) -> str:
    lines = [
        '// Generated by python3 -m tools.cloudflare_audit.generate_dns. DO NOT EDIT.',
        f'// Pinned OpenAPI SHA256: {PINNED_SHA256}',
        'package com.flarego.core.cloudflare.generated', '',
        'import com.flarego.core.model.DnsWriteMethod',
        'import kotlinx.serialization.json.*', '',
        'object DnsSchemas {',
        '    private val definitions: Map<String, Map<DnsWriteMethod, JsonObject>> by lazy {',
        '        mapOf(',
    ]
    for record_type, methods in sorted(definitions.items()):
        lines.append(f'            "{record_type}" to mapOf(')
        for method, definition in sorted(methods.items()):
            encoded = json.dumps(definition, sort_keys=True, separators=(',', ':'), ensure_ascii=True)
            encoded = encoded.replace('$', "${'$'}").replace('"""', '${"\\"\\"\\""}')
            lines.append(f'                DnsWriteMethod.{method} to Json.parseToJsonElement("""{encoded}""").jsonObject,')
        lines.append('            ),')
    lines += ['        )', '    }', '', '    val types: Set<String> get() = definitions.keys', '',
              '    fun forType(type: String, method: DnsWriteMethod = DnsWriteMethod.POST): JsonObject? =',
              '        definitions[type]?.get(method)', '}', '']
    return '\n'.join(lines)


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--schema', type=Path, default=default_schema_path())
    parser.add_argument('--output', type=Path, default=root / 'shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/generated/DnsSchemas.kt')
    args = parser.parse_args()
    generated = render_kotlin(generate_dns_definitions(load_schema(args.schema, PINNED_SHA256)))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generated, encoding='utf-8')


if __name__ == '__main__':
    main()
