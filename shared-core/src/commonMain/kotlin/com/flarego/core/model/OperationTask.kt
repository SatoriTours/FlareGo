package com.flarego.core.model

/**
 * Lifecycle of one recoverable provider task.
 *
 * Persisted values are read through [parse]: a value written by a future version
 * falls back to the safe [UNKNOWN] state instead of crashing.
 */
enum class TaskState {
    QUEUED,
    RUNNING,
    ACCEPTED,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    UNKNOWN,
    RECONCILING,
    ACTION_REQUIRED;

    companion object {
        /** Unknown, missing or differently-cased persisted names fall back to [UNKNOWN]. */
        fun parse(raw: String?): TaskState {
            val name = raw?.trim() ?: return UNKNOWN
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

/**
 * One recoverable task: fixed safe metadata only.
 *
 * The record stores a safe locator and fingerprints — never a payload, provider
 * body, header map or credential. Identity fields are kept verbatim (no trimming,
 * case folding or scope guessing); [pollPath] is only a safe relative location and
 * storing it grants no request or polling permission.
 */
data class OperationTask(
    val id: String,
    val connectionId: String,
    val scope: CloudScope,
    val actionKey: String,
    val resourceRef: ResourceRef?,
    val requestFingerprint: String,
    val desiredHash: String?,
    val state: TaskState,
    val safeDetail: String,
    val providerRequestId: String?,
    val pollPath: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        if (id.isBlank() || connectionId.isBlank() || actionKey.isBlank() || safeDetail.isBlank()) {
            throw invalidInputFailure()
        }
        requireSha256(requestFingerprint)
        desiredHash?.let { requireSha256(it) }
        if (createdAtMs < 0 || updatedAtMs < createdAtMs) throw invalidInputFailure()
        if (connectionIdOf(scope) != connectionId) throw invalidInputFailure()
        resourceRef?.let { if (it.scope != scope) throw invalidInputFailure() }
        providerRequestId?.let { requireSafeIdentifier(it) }
        pollPath?.let { requireSafePollPath(it, scope) }
    }
}

private const val SHA256_HEX_LENGTH = 64
private const val MAX_IDENTIFIER_LENGTH = 128
private const val MAX_POLL_PATH_LENGTH = 4096

private fun connectionIdOf(scope: CloudScope): String =
    when (scope) {
        is AccountScope -> scope.connectionId
        is ZoneScope -> scope.connectionId
        is UserScope -> scope.connectionId
        is OrganizationScope -> scope.connectionId
    }

private fun requireSha256(value: String) {
    if (value.length != SHA256_HEX_LENGTH) throw invalidInputFailure()
    if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw invalidInputFailure()
}

private fun requireSafeIdentifier(value: String) {
    if (value.isBlank() || value.length > MAX_IDENTIFIER_LENGTH) throw invalidInputFailure()
    if (value.any { it.code !in 0x20..0x7E }) throw invalidInputFailure()
}

/**
 * A poll path is a reviewed relative location: no scheme or authority, no
 * fragment, no controls, no backslash, no traversal in raw or repeatedly encoded
 * form, no escape that changes the segment structure, and it must address the
 * task's own scope. Anything that cannot be proven safe is rejected.
 */
private fun requireSafePollPath(path: String, scope: CloudScope) {
    if (path.length < 2 || path.length > MAX_POLL_PATH_LENGTH) throw invalidInputFailure()
    if (!path.startsWith("/") || path.startsWith("//")) throw invalidInputFailure()
    for (character in path) {
        if (character.code !in 0x21..0x7E || character == '\\' || character == '#') throw invalidInputFailure()
    }
    if (!hasValidEscapes(path)) throw invalidInputFailure()
    // Separate the original URI components first: a query value must never hide a dot
    // segment, and a decoded `%3F` must never be re-read as a fresh separator that
    // hides the rest of the path from the structural checks.
    val rawPath = path.substringBefore('?')
    val rawQuery = path.substringAfter('?', "")
    if (rawPath.length < 2) throw invalidInputFailure()
    requireRawPathShape(rawPath)
    val pathSegmentCount = rawPath.split('/').size
    var decodedPath = rawPath
    var pathRounds = 0
    while (true) {
        requireDecodedPathShape(decodedPath, pathSegmentCount)
        val next = decodeValidEscapes(decodedPath) ?: break
        decodedPath = next
        if (++pathRounds > rawPath.length) throw invalidInputFailure()
    }
    requireOwnScope(decodedPath, scope)
    requireOpaqueQueryShape(rawQuery)
}

private fun requireRawPathShape(rawPath: String) {
    if (!rawPath.startsWith("/") || rawPath.startsWith("//")) throw invalidInputFailure()
    for (segment in rawPath.split('/')) {
        if (segment == "." || segment == "..") throw invalidInputFailure()
    }
}

private fun requireDecodedPathShape(decodedPath: String, rawSegmentCount: Int) {
    if (!decodedPath.startsWith("/") || decodedPath.contains("//")) throw invalidInputFailure()
    if (decodedPath.split('/').size != rawSegmentCount) throw invalidInputFailure()
    for (segment in decodedPath.split('/')) {
        if (segment == "." || segment == "..") throw invalidInputFailure()
    }
    for (character in decodedPath) {
        if (character.code !in 0x20..0x7E || character == '\\' || character == '#') throw invalidInputFailure()
    }
}

/**
 * Query values are opaque data: they may legitimately contain dots and separators,
 * and only real control code points are rejected at every decoded level.
 */
private fun requireOpaqueQueryShape(rawQuery: String) {
    if (rawQuery.isEmpty()) return
    var decoded = rawQuery
    var rounds = 0
    while (true) {
        for (character in decoded) {
            if (character.code <= 0x1F || character.code in 0x7F..0x9F) throw invalidInputFailure()
        }
        val next = decodeValidEscapes(decoded) ?: break
        decoded = next
        if (++rounds > rawQuery.length) throw invalidInputFailure()
    }
}

private fun requireOwnScope(path: String, scope: CloudScope) {
    val segments = path.split('/')
    val root = segments.getOrNull(1).orEmpty()
    val identity = segments.getOrNull(2).orEmpty()
    if (identity.isEmpty()) throw invalidInputFailure()
    val expected =
        when (scope) {
            is AccountScope -> "accounts" to scope.accountId
            is ZoneScope -> "zones" to scope.zoneId
            is UserScope -> "user" to null
            is OrganizationScope -> "organizations" to scope.organizationId
        }
    if (root != expected.first) throw invalidInputFailure()
    if (expected.second != null && identity != expected.second) throw invalidInputFailure()
}

private fun hasValidEscapes(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            if (index + 2 >= value.length) return false
            if (!value[index + 1].isHexDigit() || !value[index + 2].isHexDigit()) return false
            index += 3
        } else {
            index++
        }
    }
    return true
}

private fun decodeValidEscapes(value: String): String? {
    val decoded = StringBuilder(value.length)
    var index = 0
    var changed = false
    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 2 < value.length && value[index + 1].isHexDigit() && value[index + 2].isHexDigit()) {
            decoded.append(value.substring(index + 1, index + 3).toInt(16).toChar())
            index += 3
            changed = true
        } else {
            decoded.append(character)
            index++
        }
    }
    return if (changed) decoded.toString() else null
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
