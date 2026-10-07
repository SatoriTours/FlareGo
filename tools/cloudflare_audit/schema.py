"""Load and enumerate the pinned Cloudflare OpenAPI schema.

Read-only and offline: the schema is a local file, no credential or network
access happens here. ``$ref`` values are resolved against the same document
using JSON Pointer (RFC 6901) rules; an unresolved or cyclic reference is an
explicit failure so a partially resolved schema can never be counted.
"""
from __future__ import annotations

import hashlib
import json
import re
import urllib.parse
from pathlib import Path
from typing import Any

HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options", "trace")

# RFC 6901 array index: "0" or a non-zero leading digit; "-" (append) is not resolvable here.
_ARRAY_INDEX = re.compile(r"0|[1-9][0-9]*")
_INVALID_PERCENT = re.compile(r"%(?![0-9A-Fa-f]{2})")


class SchemaError(ValueError):
    """Raised when the pinned schema cannot be trusted or fully resolved."""


def load_schema(path: Path, expected_sha256: str | None = None) -> dict:
    """Read a local schema and optionally enforce its pinned SHA-256.

    Raises :class:`SchemaError` (a ``ValueError``) for unreadable files, a
    digest mismatch, invalid JSON or a document that cannot be enumerated, so
    callers never continue generation with unverified input.
    """
    path = Path(path)
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise SchemaError(f"cannot read schema {path}: {error}") from error

    if expected_sha256 is not None:
        actual = hashlib.sha256(raw).hexdigest()
        if actual != expected_sha256:
            raise SchemaError(
                f"schema sha256 mismatch for {path}: expected {expected_sha256}, got {actual}"
            )

    try:
        schema = json.loads(raw)
    except json.JSONDecodeError as error:
        raise SchemaError(f"schema {path} is not valid JSON: {error}") from error
    if not isinstance(schema, dict):
        raise SchemaError(f"schema {path} root must be a JSON object")
    if not isinstance(schema.get("paths"), dict):
        raise SchemaError(f"schema {path} has no 'paths' object to enumerate")
    return schema


def _decode_fragment(pointer: str) -> str:
    """Return the percent-decoded JSON Pointer carried by a local URI fragment."""
    if not isinstance(pointer, str) or not pointer.startswith("#/"):
        raise SchemaError(
            f"unsupported reference {pointer!r}: only local '#/' JSON Pointers resolve"
        )
    fragment = pointer[1:]
    if _INVALID_PERCENT.search(fragment):
        raise SchemaError(f"reference {pointer!r} has an invalid percent encoding")
    try:
        return urllib.parse.unquote(fragment, encoding="utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise SchemaError(
            f"reference {pointer!r} has an invalid percent encoding: not valid UTF-8 ({error})"
        ) from error


def _unescape(token: str) -> str:
    """RFC 6901 escaping: ``~1`` then ``~0``."""
    return token.replace("~1", "/").replace("~0", "~")


def _array_index(token: str, pointer: str) -> int:
    if token == "-":
        raise SchemaError(
            f"reference {pointer!r} uses the array append index '-' which has no member to resolve"
        )
    if not _ARRAY_INDEX.fullmatch(token):
        raise SchemaError(f"reference {pointer!r} has an invalid array index {token!r}")
    return int(token)


def resolve_json_pointer(schema: dict, pointer: str) -> Any:
    """Resolve a local ``#/...`` JSON Pointer (RFC 6901) inside ``schema``.

    The URI fragment is percent-decoded before it is split into reference
    tokens, then each token is unescaped (``~1`` then ``~0``). Dict members and
    real array members both resolve; malformed indices (negative, leading zero,
    non-numeric, or the append token ``-``) and malformed percent encodings
    fail explicitly because this audit is read-only.
    """
    decoded = _decode_fragment(pointer)
    tokens = decoded[1:].split("/")

    node: Any = schema
    for token in tokens:
        key = _unescape(token)
        if isinstance(node, dict):
            if key not in node:
                raise SchemaError(f"cannot resolve reference {pointer!r}: missing token {key!r}")
            node = node[key]
        elif isinstance(node, list):
            index = _array_index(key, pointer)
            if index >= len(node):
                raise SchemaError(
                    f"cannot resolve reference {pointer!r}: array index {index} is out of range"
                )
            node = node[index]
        else:
            raise SchemaError(
                f"cannot resolve reference {pointer!r}: cannot descend into scalar value at token {key!r}"
            )
    return node


def dereference(schema: dict, node: Any, *, context: str, reject_siblings: bool = False) -> Any:
    """Follow top-level local ``$ref`` chains, failing on missing/cyclic refs.

    Only the outer reference chain is followed; nested schema recursion is not
    traversed, so legitimately recursive payload schemas are not rejected.
    With ``reject_siblings`` every hop that carries local fields next to its
    ``$ref`` fails closed instead of being partially merged or silently
    discarded.
    """
    seen: list[str] = []
    current = node
    while isinstance(current, dict) and "$ref" in current:
        if reject_siblings:
            siblings = sorted(key for key in current if key != "$ref")
            if siblings:
                raise SchemaError(
                    f"{context} combines $ref with local field(s) {', '.join(siblings)}; "
                    "reference merge semantics are not implemented, so resolution fails closed"
                )
        reference = current["$ref"]
        if reference in seen:
            chain = " -> ".join(seen + [reference])
            raise SchemaError(f"cyclic reference while resolving {context}: {chain}")
        seen.append(reference)
        current = resolve_json_pointer(schema, reference)
    return current


def resolve_path_item(schema: dict, path: str, item: Any) -> dict:
    """Resolve a path item, following local ``$ref`` chains fail-closed.

    A referenced path item is enumerated exactly like an inline one. Because
    this tool does not implement OpenAPI path-item merge semantics, any ``$ref``
    in the chain that carries local siblings is an explicit failure instead of
    being silently dropped or partially merged.
    """
    if not isinstance(item, dict):
        raise SchemaError(f"path item {path!r} must be an object")
    resolved = dereference(schema, item, context=f"path item {path!r}", reject_siblings=True)
    if not isinstance(resolved, dict):
        raise SchemaError(f"path item {path!r} must resolve to an object")
    return resolved


def enumerate_operations(schema: dict) -> list[dict]:
    """Return every HTTP operation, ordered by path then method.

    Each item keeps the concrete route identity from ``paths``, METHOD,
    operationId, tags, the raw operation object and the resolved
    request/response media types. Ordering is independent of dict insertion
    order.
    """
    paths = schema.get("paths")
    if not isinstance(paths, dict):
        raise SchemaError("schema has no 'paths' object to enumerate")

    operations: list[dict] = []
    seen_ids: dict[str, str] = {}
    for path in sorted(paths):
        item = resolve_path_item(schema, path, paths[path])
        for method in sorted(item):
            if method not in HTTP_METHODS:
                continue
            operation = item[method]
            identity = f"{method.upper()} {path}"
            if not isinstance(operation, dict):
                raise SchemaError(f"operation {identity} must be an object")
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not operation_id:
                raise SchemaError(f"operation {identity} has no operationId")
            if operation_id in seen_ids:
                raise SchemaError(
                    f"duplicate operationId {operation_id!r}: {seen_ids[operation_id]} and {identity}"
                )
            seen_ids[operation_id] = identity

            tags = operation.get("tags", [])
            if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
                raise SchemaError(f"operation {identity} has invalid tags {tags!r}")

            request_body = dereference(
                schema, operation.get("requestBody", {}), context=f"{identity} requestBody"
            )
            if not isinstance(request_body, dict):
                raise SchemaError(f"operation {identity} requestBody must resolve to an object")
            request_types = sorted(request_body.get("content", {}))

            responses = operation.get("responses", {})
            if not isinstance(responses, dict):
                raise SchemaError(f"operation {identity} responses must be an object")
            response_types: set[str] = set()
            for status, response in responses.items():
                resolved = dereference(
                    schema, response, context=f"{identity} response {status}"
                )
                if not isinstance(resolved, dict):
                    raise SchemaError(
                        f"operation {identity} response {status} must resolve to an object"
                    )
                response_types.update(resolved.get("content", {}))

            operations.append(
                {
                    "method": method.upper(),
                    "path": path,
                    "operation_id": operation_id,
                    "tags": list(tags),
                    "metadata": operation,
                    "request_content_types": request_types,
                    "response_content_types": sorted(response_types),
                }
            )
    return operations
