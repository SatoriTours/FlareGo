"""Deterministic, cycle-safe Cloudflare schema diff.

``diff_schemas`` compares two OpenAPI documents by operation identity
(``operationId`` plus the METHOD+path binding) and reports ``added``,
``removed`` and ``changed``. Referenced structures are expanded through the
local ``$ref`` graph with a canonical, cycle-safe fingerprint, so a change in a
shared component is reported for every operation that actually references it and
never for operations that do not. Unresolvable pointers and unsupported external
references fail readably instead of silently missing a change.

The diff is review evidence only: it never grants runtime permission, never
writes back to the reviewed baseline and needs no account or credential.
"""
from __future__ import annotations

from typing import Any

from .review import K2_STREAM_HOST
from .schema import HTTP_METHODS, SchemaError, enumerate_operations, resolve_json_pointer, resolve_path_item

DIFF_FORMAT = "flarego.cloudflare-audit.diff/1"

# Canonical field order of the reported change categories.
FIELD_ORDER = (
    "path",
    "method",
    "servers",
    "parameters",
    "security",
    "path_security",
    "permissions",
    "deprecated",
    "availability",
    "requestBody",
    "responses",
    "metadata",
)
PERMISSION_FIELDS = ("x-api-token-group", "x-cfPermissionsRequired")
DEPRECATION_FIELDS = ("deprecated", "x-cfDeprecation")
AVAILABILITY_FIELDS = ("x-fern-availability", "x-stability", "x-cfPlanAvailability")
HANDLED_OPERATION_FIELDS = (
    "parameters",
    "requestBody",
    "responses",
    "security",
    "servers",
    *PERMISSION_FIELDS,
    *DEPRECATION_FIELDS,
    *AVAILABILITY_FIELDS,
)
_MAX_DEPTH = 400
UNDEFINED_SCHEME = "undefined_in_components"


def _canonical(node: Any, schema: dict, *, stack: tuple[str, ...] = (), cache: dict | None = None, depth: int = 0) -> Any:
    """Return a canonical, ref-expanded, cycle-safe copy of ``node``.

    Dict key order is normalized (so key-order-only edits are not changes) while
    list order is preserved (list order is semantic). A ``$ref`` is replaced by
    both its text and its expanded target; a reference re-entered on the current
    path becomes a stable ``{"$cycle": ref}`` marker so recursive payload graphs
    terminate without hiding changes inside the cycle.
    """
    if depth > _MAX_DEPTH:
        raise SchemaError(f"schema graph is too deep to fingerprint (>{_MAX_DEPTH} levels)")
    if isinstance(node, dict):
        reference = node.get("$ref")
        if isinstance(reference, str):
            if reference in stack:
                return {"$cycle": reference}
            key = (reference, frozenset(stack))
            if cache is not None and key in cache:
                expanded = cache[key]
            else:
                target = resolve_json_pointer(schema, reference)
                expanded = _canonical(
                    target, schema, stack=stack + (reference,), cache=cache, depth=depth + 1
                )
                if cache is not None:
                    cache[key] = expanded
            result: dict[str, Any] = {"$ref": reference, "target": expanded}
            siblings = {
                name: _canonical(value, schema, stack=stack, cache=cache, depth=depth + 1)
                for name, value in node.items()
                if name != "$ref"
            }
            if siblings:
                result["siblings"] = siblings
            return result
        return {
            name: _canonical(value, schema, stack=stack, cache=cache, depth=depth + 1)
            for name, value in sorted(node.items())
        }
    if isinstance(node, list):
        return [
            _canonical(value, schema, stack=stack, cache=cache, depth=depth + 1) for value in node
        ]
    return node


def effective_servers(schema: dict, path_item: dict, operation: dict) -> list:
    """Effective server list following OpenAPI inheritance and override rules.

    Operation servers override path-item servers, which override root servers. An
    explicitly empty array overrides to no servers; only an absent key inherits.
    """
    if "servers" in operation:
        return list(operation["servers"] or [])
    if "servers" in path_item:
        return list(path_item["servers"] or [])
    return list(schema.get("servers") or [])


def _parameter_key(schema: dict, parameter: Any) -> tuple[str, str] | None:
    resolved = parameter
    if isinstance(parameter, dict) and isinstance(parameter.get("$ref"), str):
        resolved = resolve_json_pointer(schema, parameter["$ref"])
    if not isinstance(resolved, dict):
        return None
    name, location = resolved.get("name"), resolved.get("in")
    if isinstance(name, str) and isinstance(location, str):
        return (name, location)
    return None


def effective_parameters(schema: dict, path_item: dict, operation: dict) -> list:
    """Merge shared path-level parameters with operation overrides by (name, in)."""
    path_parameters = path_item.get("parameters") or []
    operation_parameters = operation.get("parameters") or []
    overridden = {_parameter_key(schema, parameter) for parameter in operation_parameters}
    overridden.discard(None)
    return [
        parameter
        for parameter in path_parameters
        if _parameter_key(schema, parameter) not in overridden
    ] + list(operation_parameters)


def effective_security(schema: dict, operation: dict) -> list:
    """Effective security requirements: operation override, else root.

    ``security: []`` is an explicit override that removes the requirement; it does
    not inherit root security.
    """
    if "security" in operation:
        return list(operation["security"] or [])
    return list(schema.get("security") or [])


def _security_fingerprint(schema: dict, requirements: list, cache: dict | None) -> dict:
    schemes: dict[str, Any] = {}
    definitions = (schema.get("components") or {}).get("securitySchemes") or {}
    for requirement in requirements:
        if not isinstance(requirement, dict):
            continue
        for name in requirement:
            if name in schemes:
                continue
            definition = definitions.get(name)
            schemes[name] = (
                UNDEFINED_SCHEME
                if definition is None
                else _canonical(definition, schema, cache=cache)
            )
    return {
        "effective": _canonical(requirements, schema, cache=cache),
        "schemes": {name: schemes[name] for name in sorted(schemes)},
    }


def fingerprint_operation(
    schema: dict, path_item: dict, operation: dict, *, cache: dict | None = None
) -> dict:
    """Return the canonical review fingerprint of one operation.

    Categories: identity (method/path), effective servers, effective parameters,
    effective security plus referenced security-scheme definitions, vendor
    permissions, deprecation and availability metadata, request body, responses,
    vendor path-level security context (needs review, not standard inheritance)
    and the remaining operation metadata.
    """
    metadata = operation["metadata"]
    fingerprint: dict[str, Any] = {
        "method": operation["method"],
        "path": operation["path"],
        "servers": _canonical(effective_servers(schema, path_item, metadata), schema, cache=cache),
        "parameters": _canonical(effective_parameters(schema, path_item, metadata), schema, cache=cache),
        "security": _security_fingerprint(
            schema, effective_security(schema, metadata), cache
        ),
        "permissions": {
            field: _canonical(metadata[field], schema, cache=cache)
            for field in PERMISSION_FIELDS
            if field in metadata
        },
        "deprecated": {
            field: _canonical(metadata[field], schema, cache=cache)
            for field in DEPRECATION_FIELDS
            if field in metadata
        },
        "availability": {
            field: _canonical(metadata[field], schema, cache=cache)
            for field in AVAILABILITY_FIELDS
            if field in metadata
        },
        "requestBody": _canonical(metadata.get("requestBody"), schema, cache=cache),
        "responses": _canonical(metadata.get("responses"), schema, cache=cache),
        "metadata": _canonical(
            {name: value for name, value in metadata.items() if name not in HANDLED_OPERATION_FIELDS},
            schema,
            cache=cache,
        ),
    }
    if "security" in path_item:
        # OpenAPI does not define path-level security inheritance; keep any vendor
        # context as an explicitly reviewable difference instead of treating it as
        # a standard qualification.
        fingerprint["path_security"] = _canonical(path_item["security"], schema, cache=cache)
    return fingerprint


def _operation_index(schema: dict) -> dict[str, dict]:
    paths = schema.get("paths")
    if not isinstance(paths, dict):
        raise SchemaError("schema has no 'paths' object to diff")
    index: dict[str, dict] = {}
    path_items: dict[str, dict] = {}
    for operation in enumerate_operations(schema):
        path = operation["path"]
        if path not in path_items:
            path_items[path] = resolve_path_item(schema, path, paths[path])
        index[operation["operation_id"]] = {**operation, "path_item": path_items[path]}
    return index


def _describe(operation: dict) -> dict:
    return {"method": operation["method"], "path": operation["path"]}


def diff_schemas(old: dict, new: dict) -> dict:
    """Compare two schemas and return deterministic ``added``/``removed``/``changed``.

    Operations are indexed by ``operationId``; the METHOD+path binding is part of
    the fingerprint, so a kept ID with a changed route or method is ``changed``
    while a changed ID is reported as ``removed`` plus ``added``.
    """
    old_operations = _operation_index(old)
    new_operations = _operation_index(new)
    old_ids, new_ids = set(old_operations), set(new_operations)

    added = {operation_id: _describe(new_operations[operation_id]) for operation_id in sorted(new_ids - old_ids)}
    removed = {operation_id: _describe(old_operations[operation_id]) for operation_id in sorted(old_ids - new_ids)}

    old_cache: dict = {}
    new_cache: dict = {}
    changed: dict[str, dict] = {}
    for operation_id in sorted(old_ids & new_ids):
        old_operation = old_operations[operation_id]
        new_operation = new_operations[operation_id]
        old_fingerprint = fingerprint_operation(
            old, old_operation["path_item"], old_operation, cache=old_cache
        )
        new_fingerprint = fingerprint_operation(
            new, new_operation["path_item"], new_operation, cache=new_cache
        )
        fields = [
            field
            for field in FIELD_ORDER
            if old_fingerprint.get(field) != new_fingerprint.get(field)
        ]
        if fields:
            changed[operation_id] = {
                "method": new_operation["method"],
                "path": new_operation["path"],
                "previous_method": old_operation["method"],
                "previous_path": old_operation["path"],
                "fields": fields,
            }
    return {"added": added, "removed": removed, "changed": changed}


__all__ = [
    "DIFF_FORMAT",
    "FIELD_ORDER",
    "K2_STREAM_HOST",
    "diff_schemas",
    "effective_parameters",
    "effective_security",
    "effective_servers",
    "fingerprint_operation",
]
