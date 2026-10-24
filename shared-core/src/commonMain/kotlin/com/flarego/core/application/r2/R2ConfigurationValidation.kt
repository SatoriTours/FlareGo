package com.flarego.core.application.r2

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LifecycleStorageTransition
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_JURISDICTIONS
import com.flarego.core.model.R2_MAX_CONFIGURATION_DOCUMENT_BYTES
import com.flarego.core.model.R2_MAX_CONFIGURATION_RULES
import com.flarego.core.model.invalidInputFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Local validation and encoding for the batch B1 configuration plane.
 *
 * Everything here is a local decision that happens before any request exists: the target
 * must name a real bucket under one of the five pinned jurisdictions, local uploads is
 * default-only, every rule is checked against the pinned schema enums, and an unknown
 * provider shape turns the whole document read-only rather than being overwritten.
 *
 * Numeric and textual bounds that the pinned schema does not state (the age range, the
 * accepted UTC year range, the accepted second range, prefix/origin/header lengths) are
 * **client-side safe editing subsets**, so a value outside them stays readable and is never
 * silently clamped, rounded or rewritten. They are not claims about provider limits.
 */

/** The official `r2_cors-rule.allowed.methods` enum. */
private val CORS_METHODS = setOf("GET", "PUT", "POST", "DELETE", "HEAD")

/** The only `r2_lifecycle-storage-transition.storageClass` value in the pinned schema. */
private val LIFECYCLE_STORAGE_CLASSES = setOf("InfrequentAccess")

/**
 * The pinned schema documents 86400 as the largest CORS preflight cache value, so a larger
 * number is refused locally instead of being rounded or silently clamped. This is a
 * client-side editing bound derived from that description, not a separate provider enum.
 */
private const val MAX_CORS_MAX_AGE_SECONDS = 86_400L

/**
 * Client-side safe editing subset of both age conditions: a positive whole number of seconds
 * that fits a signed 32-bit integer. The pinned schema only says `type: integer` (no format,
 * no maximum), so this is this app's own conservative bound, not a provider limit.
 */
private const val MAX_AGE_SECONDS = Int.MAX_VALUE.toLong()

private const val MAX_RULE_ID_LENGTH = 255
private const val MAX_PREFIX_BYTES = 1024
private const val MAX_ORIGIN_LENGTH = 4096
private const val MAX_HEADER_NAME_LENGTH = 255

private const val READ_ONLY_OVERSIZED = "配置文档超过本地编辑上限（256 KiB），仅可查看原文，不会被截断或覆盖"
private const val READ_ONLY_TOO_MANY_RULES = "规则数量超过本地编辑上限（100 条），仅可查看原文"
private const val READ_ONLY_UNKNOWN_CONTENT = "配置包含应用尚未支持的字段或取值，为避免覆盖未知内容，仅可查看"
private const val READ_ONLY_UNKNOWN_STATE = "无法确认该配置的当前状态，仅可查看"

/** Fixed message for a write against a document that cannot be round-tripped losslessly. */
internal const val R2_CONFIGURATION_READ_ONLY_WRITE =
    "该桶的现有配置包含应用尚未支持的字段或超出本地编辑上限，已阻止写入以避免覆盖未知内容"

private val CORS_RULE_KEYS = setOf("allowed", "exposeHeaders", "id", "maxAgeSeconds")
private val CORS_ALLOWED_KEYS = setOf("headers", "methods", "origins")
private val LIFECYCLE_RULE_KEYS =
    setOf("abortMultipartUploadsTransition", "conditions", "deleteObjectsTransition", "enabled", "id", "storageClassTransitions")
private val LIFECYCLE_CONDITIONS_KEYS = setOf("prefix")
private val TRANSITION_KEYS = setOf("condition")
private val STORAGE_TRANSITION_KEYS = setOf("condition", "storageClass")
private val LIFECYCLE_CONDITION_KEYS = setOf("type", "maxAge", "date")
private val LOCK_RULE_KEYS = setOf("condition", "enabled", "id", "prefix")
private val LOCK_CONDITION_KEYS = setOf("type", "maxAgeSeconds", "date")

/** Validates the bucket and jurisdiction of one B configuration target. */
internal fun validateR2ConfigurationTarget(target: R2WriteTarget): R2WriteTarget {
    val bucket = validateR2BucketName(target.bucket)
    if (target.jurisdiction !in R2_JURISDICTIONS) throw invalidInputFailure()
    return target.copy(bucket = bucket)
}

/**
 * True when the pinned schema declares `cf-r2-jurisdiction` for this configuration family.
 * `r2-get-bucket-local-uploads-configuration` and its `PUT` declare no such parameter, so
 * local uploads is default-only.
 */
internal fun supportsR2ConfigurationJurisdiction(kind: R2ConfigurationKind): Boolean =
    kind != R2ConfigurationKind.LOCAL_UPLOADS

/**
 * Refuses a target the reviewed endpoint cannot address, before any request is built.
 *
 * A non-default local-uploads target is never retried against the default jurisdiction.
 */
internal fun requireR2ConfigurationJurisdictionSupported(target: R2WriteTarget, kind: R2ConfigurationKind) {
    if (target.jurisdiction !in R2_JURISDICTIONS) throw invalidInputFailure()
    if (!supportsR2ConfigurationJurisdiction(kind) && target.jurisdiction != "default") {
        throw CloudFailure(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "近端写入复制（local uploads）仅支持 default 地区，非默认地区的桶不会退回读取或写入 default 配置",
        )
    }
}

/**
 * Why a provider document cannot be edited losslessly, or null when it can.
 *
 * The analysis is local and never sends a request. A document above the local editing
 * limits, with a member this app does not model, or with a value the pinned schema does
 * not admit stays readable — and is therefore never replaced by a guessed payload.
 */
internal fun r2ConfigurationReadOnlyReason(kind: R2ConfigurationKind, raw: JsonObject): String? {
    if (raw.toString().encodeToByteArray().size > R2_MAX_CONFIGURATION_DOCUMENT_BYTES) return READ_ONLY_OVERSIZED
    return when (kind) {
        R2ConfigurationKind.CORS,
        R2ConfigurationKind.LIFECYCLE,
        R2ConfigurationKind.LOCK,
        -> {
            if (raw.keys != setOf("rules")) return READ_ONLY_UNKNOWN_CONTENT
            val rules = raw["rules"] as? JsonArray ?: return READ_ONLY_UNKNOWN_STATE
            if (rules.size > R2_MAX_CONFIGURATION_RULES) return READ_ONLY_TOO_MANY_RULES
            for (row in rules) {
                val rule = row as? JsonObject ?: return READ_ONLY_UNKNOWN_CONTENT
                val failure = decodeFailure(kind, rule)
                if (failure != null) {
                    if (failure.code != CloudFailureCode.INVALID_INPUT) throw failure
                    return READ_ONLY_UNKNOWN_CONTENT
                }
            }
            null
        }
        R2ConfigurationKind.LOCAL_UPLOADS -> {
            if (raw.keys != setOf("enabled")) return READ_ONLY_UNKNOWN_CONTENT
            val enabled = raw["enabled"] as? JsonPrimitive
            if (enabled == null || enabled.isString || enabled.booleanOrNull == null) return READ_ONLY_UNKNOWN_STATE
            null
        }
    }
}

/** Runs the shared decoder and reports the failure instead of leaking it to the read path. */
private fun decodeFailure(kind: R2ConfigurationKind, rule: JsonObject): CloudFailure? =
    try {
        when (kind) {
            R2ConfigurationKind.CORS -> decodeR2CorsRule(rule)
            R2ConfigurationKind.LIFECYCLE -> decodeR2LifecycleRule(rule)
            R2ConfigurationKind.LOCK -> decodeR2LockRule(rule)
            R2ConfigurationKind.LOCAL_UPLOADS -> throw invalidInputFailure()
        }
        null
    } catch (failure: CloudFailure) {
        failure
    }

/** Decodes one CORS rule, rejecting every unknown member, enum and value. */
internal fun decodeR2CorsRule(row: JsonObject): R2CorsRule {
    row.requireOnlyKnownKeys(CORS_RULE_KEYS)
    val allowed = row.requiredObject("allowed")
    allowed.requireOnlyKnownKeys(CORS_ALLOWED_KEYS)
    return R2CorsRule(
        id = row.optionalString("id"),
        allowed =
            R2CorsAllowed(
                methods = allowed.requiredStringArray("methods"),
                origins = allowed.requiredStringArray("origins"),
                headers = allowed.optionalStringArray("headers"),
            ),
        exposeHeaders = row.optionalStringArray("exposeHeaders"),
        maxAgeSeconds = row.optionalLong("maxAgeSeconds"),
    ).also(::validateR2CorsRule)
}

/** Decodes one lifecycle rule, rejecting every unknown member, enum and value. */
internal fun decodeR2LifecycleRule(row: JsonObject): R2LifecycleRule {
    row.requireOnlyKnownKeys(LIFECYCLE_RULE_KEYS)
    val conditions = row.requiredObject("conditions")
    conditions.requireOnlyKnownKeys(LIFECYCLE_CONDITIONS_KEYS)
    val delete =
        row.optionalObject("deleteObjectsTransition")?.let { transition ->
            transition.requireOnlyKnownKeys(TRANSITION_KEYS)
            decodeR2LifecycleCondition(transition.requiredObject("condition"))
        }
    val abort =
        row.optionalObject("abortMultipartUploadsTransition")?.let { transition ->
            transition.requireOnlyKnownKeys(TRANSITION_KEYS)
            decodeR2LifecycleCondition(transition.requiredObject("condition")) as? R2LifecycleCondition.Age
                ?: throw invalidInputFailure()
        }
    val transitions =
        row.optionalArray("storageClassTransitions").map { element ->
            val transition = element as? JsonObject ?: throw invalidInputFailure()
            transition.requireOnlyKnownKeys(STORAGE_TRANSITION_KEYS)
            R2LifecycleStorageTransition(
                condition = decodeR2LifecycleCondition(transition.requiredObject("condition")),
                storageClass = transition.requiredString("storageClass"),
            )
        }
    return R2LifecycleRule(
        id = row.requiredString("id"),
        enabled = row.requiredBoolean("enabled"),
        conditions = R2LifecycleConditions(conditions.requiredString("prefix")),
        deleteObjectsTransition = delete,
        abortMultipartUploadsTransition = abort,
        storageClassTransitions = transitions,
    ).also(::validateR2LifecycleRule)
}

/** Decodes one lock rule, rejecting every unknown member, enum and value. */
internal fun decodeR2LockRule(row: JsonObject): R2LockRule {
    row.requireOnlyKnownKeys(LOCK_RULE_KEYS)
    val condition = row.requiredObject("condition")
    condition.requireOnlyKnownKeys(LOCK_CONDITION_KEYS)
    return R2LockRule(
        id = row.requiredString("id"),
        enabled = row.requiredBoolean("enabled"),
        prefix = row.optionalString("prefix") ?: "",
        condition = decodeR2LockCondition(condition),
    ).also(::validateR2LockRule)
}

/** The CORS rule limits of the pinned schema, plus the local client-side editing bounds. */
internal fun validateR2CorsRule(rule: R2CorsRule) {
    val allowed = rule.allowed
    if (allowed.methods.isEmpty() || allowed.methods.any { it !in CORS_METHODS }) throw invalidInputFailure()
    if (allowed.origins.isEmpty()) throw invalidInputFailure()
    allowed.origins.forEach(::requireOrigin)
    allowed.headers.forEach(::requireHeaderName)
    rule.exposeHeaders.forEach(::requireHeaderName)
    rule.id?.let(::requireRuleId)
    rule.maxAgeSeconds?.let { if (it !in 0..MAX_CORS_MAX_AGE_SECONDS) throw invalidInputFailure() }
}

/** The lifecycle rule limits of the pinned schema, plus the local client-side editing bounds. */
internal fun validateR2LifecycleRule(rule: R2LifecycleRule) {
    requireRuleId(rule.id)
    requirePrefix(rule.conditions.prefix)
    rule.deleteObjectsTransition?.let(::validateR2LifecycleCondition)
    rule.abortMultipartUploadsTransition?.let { requireAge(it.maxAge) }
    rule.storageClassTransitions.forEach { transition ->
        validateR2LifecycleCondition(transition.condition)
        if (transition.storageClass !in LIFECYCLE_STORAGE_CLASSES) throw invalidInputFailure()
    }
}

/** The lock rule limits of the pinned schema, plus the local client-side editing bounds. */
internal fun validateR2LockRule(rule: R2LockRule) {
    requireRuleId(rule.id)
    requirePrefix(rule.prefix)
    when (val condition = rule.condition) {
        is R2LockCondition.Age -> requireAge(condition.maxAgeSeconds)
        is R2LockCondition.Date -> requireUtcDate(condition.utcDate)
        R2LockCondition.Indefinite -> Unit
    }
}

/** The complete JSON body of one write, or null for a delete. Validated before any request. */
internal fun r2ConfigurationWriteBody(write: R2ConfigurationWrite): JsonObject? =
    when (write) {
        is R2ConfigurationWrite.PutCors -> rulesBody(write.rules, { it.id }, ::encodeR2CorsRule)
        is R2ConfigurationWrite.DeleteCors -> null
        is R2ConfigurationWrite.PutLifecycle -> rulesBody(write.rules, { it.id }, ::encodeR2LifecycleRule)
        is R2ConfigurationWrite.PutLocalUploads -> buildJsonObject { put("enabled", write.enabled) }
        is R2ConfigurationWrite.PutLock -> rulesBody(write.rules, { it.id }, ::encodeR2LockRule)
        is R2ConfigurationWrite.DeleteLock -> null
    }

/**
 * Builds one full-replacement rule body.
 *
 * The submitted order is preserved, an empty list stays an explicitly empty rule set, and
 * the complete JSON text is bounded in UTF-8 bytes so an oversized target is refused before
 * anything is sent.
 */
private fun <T> rulesBody(rules: List<T>, idOf: (T) -> String?, encode: (T) -> JsonObject): JsonObject {
    if (rules.size > R2_MAX_CONFIGURATION_RULES) throw invalidInputFailure()
    val ids = rules.mapNotNull(idOf)
    if (ids.toSet().size != ids.size) throw invalidInputFailure()
    val body = buildJsonObject { put("rules", buildJsonArray { rules.forEach { add(encode(it)) } }) }
    if (body.toString().encodeToByteArray().size > R2_MAX_CONFIGURATION_DOCUMENT_BYTES) throw invalidInputFailure()
    return body
}

/** Encodes one CORS rule; [R2CorsAllowed] and [R2CorsRule.exposeHeaders] are always sent. */
internal fun encodeR2CorsRule(rule: R2CorsRule): JsonObject {
    validateR2CorsRule(rule)
    return buildJsonObject {
        rule.id?.let { put("id", it) }
        put(
            "allowed",
            buildJsonObject {
                put("methods", JsonArray(rule.allowed.methods.map(::JsonPrimitive)))
                put("origins", JsonArray(rule.allowed.origins.map(::JsonPrimitive)))
                put("headers", JsonArray(rule.allowed.headers.map(::JsonPrimitive)))
            },
        )
        put("exposeHeaders", JsonArray(rule.exposeHeaders.map(::JsonPrimitive)))
        rule.maxAgeSeconds?.let { put("maxAgeSeconds", it) }
    }
}

/** Encodes one lifecycle rule. The age field is `maxAge`, in seconds. */
internal fun encodeR2LifecycleRule(rule: R2LifecycleRule): JsonObject {
    validateR2LifecycleRule(rule)
    return buildJsonObject {
        put("id", rule.id)
        put("enabled", rule.enabled)
        put("conditions", buildJsonObject { put("prefix", rule.conditions.prefix) })
        rule.deleteObjectsTransition?.let { transition ->
            put("deleteObjectsTransition", buildJsonObject { put("condition", encodeR2LifecycleCondition(transition)) })
        }
        rule.abortMultipartUploadsTransition?.let { transition ->
            put("abortMultipartUploadsTransition", buildJsonObject { put("condition", encodeR2LifecycleCondition(transition)) })
        }
        put(
            "storageClassTransitions",
            buildJsonArray {
                rule.storageClassTransitions.forEach { transition ->
                    add(
                        buildJsonObject {
                            put("condition", encodeR2LifecycleCondition(transition.condition))
                            put("storageClass", transition.storageClass)
                        },
                    )
                }
            },
        )
    }
}

/** Encodes one lock rule. The age field is `maxAgeSeconds`, in seconds. */
internal fun encodeR2LockRule(rule: R2LockRule): JsonObject {
    validateR2LockRule(rule)
    return buildJsonObject {
        put("id", rule.id)
        put("enabled", rule.enabled)
        put("prefix", rule.prefix)
        put("condition", encodeR2LockCondition(rule.condition))
    }
}

private fun encodeR2LifecycleCondition(condition: R2LifecycleCondition): JsonObject =
    when (condition) {
        is R2LifecycleCondition.Age ->
            buildJsonObject {
                put("type", "Age")
                put("maxAge", condition.maxAge)
            }
        is R2LifecycleCondition.Date ->
            buildJsonObject {
                put("type", "Date")
                put("date", condition.utcDate)
            }
    }

private fun encodeR2LockCondition(condition: R2LockCondition): JsonObject =
    when (condition) {
        is R2LockCondition.Age ->
            buildJsonObject {
                put("type", "Age")
                put("maxAgeSeconds", condition.maxAgeSeconds)
            }
        is R2LockCondition.Date ->
            buildJsonObject {
                put("type", "Date")
                put("date", condition.utcDate)
            }
        R2LockCondition.Indefinite -> buildJsonObject { put("type", "Indefinite") }
    }

private fun validateR2LifecycleCondition(condition: R2LifecycleCondition) {
    when (condition) {
        is R2LifecycleCondition.Age -> requireAge(condition.maxAge)
        is R2LifecycleCondition.Date -> requireUtcDate(condition.utcDate)
    }
}

private fun decodeR2LifecycleCondition(condition: JsonObject): R2LifecycleCondition {
    condition.requireOnlyKnownKeys(LIFECYCLE_CONDITION_KEYS)
    return when (condition.requiredString("type")) {
        "Age" -> {
            if (condition.containsKey("date")) throw invalidInputFailure()
            R2LifecycleCondition.Age(condition.optionalLong("maxAge") ?: throw invalidInputFailure())
        }
        "Date" -> {
            if (condition.containsKey("maxAge")) throw invalidInputFailure()
            R2LifecycleCondition.Date(condition.requiredString("date"))
        }
        else -> throw invalidInputFailure()
    }
}

private fun decodeR2LockCondition(condition: JsonObject): R2LockCondition =
    when (condition.requiredString("type")) {
        "Age" -> {
            if (condition.containsKey("date")) throw invalidInputFailure()
            R2LockCondition.Age(condition.optionalLong("maxAgeSeconds") ?: throw invalidInputFailure())
        }
        "Date" -> {
            if (condition.containsKey("maxAgeSeconds")) throw invalidInputFailure()
            R2LockCondition.Date(condition.requiredString("date"))
        }
        "Indefinite" -> {
            if (condition.keys != setOf("type")) throw invalidInputFailure()
            R2LockCondition.Indefinite
        }
        else -> throw invalidInputFailure()
    }

/**
 * Both age conditions of this client's writing subset are whole seconds in
 * `1..Int.MAX_VALUE`; nothing is clamped or rounded. The pinned schema does not state a
 * maximum for either field, so the bound is local (see [MAX_AGE_SECONDS]).
 */
private fun requireAge(seconds: Long) {
    if (seconds !in 1..MAX_AGE_SECONDS) throw invalidInputFailure()
}

/**
 * The client's date writing subset: an explicit `Z` zone, a year in `1970..9999`, real
 * calendar components and whole seconds `00..59`. RFC3339 also permits a `:60` leap second at
 * a real leap-second instant; this app deliberately does not carry a leap-second table, so
 * such a value is outside its writing subset — it stays readable and is never rewritten, and
 * the app makes no claim that every leap-second timestamp is impossible.
 */
private fun requireUtcDate(value: String) {
    val match = RFC3339_UTC.matchEntire(value) ?: throw invalidInputFailure()
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toInt()
    if (year !in 1970..9999 || month !in 1..12) throw invalidInputFailure()
    if (day !in 1..daysInMonth(year, month)) throw invalidInputFailure()
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) throw invalidInputFailure()
}

private fun daysInMonth(year: Int, month: Int): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    }

private fun requireRuleId(value: String) {
    if (value.isBlank() || value.length > MAX_RULE_ID_LENGTH || value.any { it.isControl() }) throw invalidInputFailure()
}

private fun requirePrefix(value: String) {
    if (value.encodeToByteArray().size > MAX_PREFIX_BYTES || value.any { it.isControl() }) throw invalidInputFailure()
}

private fun requireOrigin(value: String) {
    if (value.isEmpty() || value.length > MAX_ORIGIN_LENGTH || value.any { it.isWhitespace() || it.isControl() }) {
        throw invalidInputFailure()
    }
}

private fun requireHeaderName(value: String) {
    if (value.isEmpty() || value.length > MAX_HEADER_NAME_LENGTH || value.any { !it.isHeaderNameCharacter() }) {
        throw invalidInputFailure()
    }
}

private fun Char.isControl(): Boolean = code <= 0x1F || code in 0x7F..0x9F

private fun Char.isHeaderNameCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "!#$%&'*+-.^_`|~"

/** A JSON object with only the members this app models; an unknown member is refused, never dropped. */
private fun JsonObject.requireOnlyKnownKeys(allowed: Set<String>) {
    if (keys.any { it !in allowed }) throw invalidInputFailure()
}

private fun JsonObject.requiredObject(key: String): JsonObject = this[key] as? JsonObject ?: throw invalidInputFailure()

private fun JsonObject.optionalObject(key: String): JsonObject? =
    when (val element = this[key]) {
        null -> null
        is JsonObject -> element
        else -> throw invalidInputFailure()
    }

private fun JsonObject.requiredString(key: String): String = optionalString(key) ?: throw invalidInputFailure()

private fun JsonObject.optionalString(key: String): String? =
    when (val element = this[key]) {
        null -> null
        is JsonPrimitive -> if (element.isString) element.content else throw invalidInputFailure()
        else -> throw invalidInputFailure()
    }

private fun JsonObject.requiredBoolean(key: String): Boolean {
    val element = this[key] as? JsonPrimitive ?: throw invalidInputFailure()
    if (element.isString) throw invalidInputFailure()
    return element.booleanOrNull ?: throw invalidInputFailure()
}

private fun JsonObject.optionalLong(key: String): Long? =
    when (val element = this[key]) {
        null -> null
        is JsonPrimitive -> if (element.isString) throw invalidInputFailure() else element.longOrNull ?: throw invalidInputFailure()
        else -> throw invalidInputFailure()
    }

private fun JsonObject.requiredStringArray(key: String): List<String> = stringArray(key)

private fun JsonObject.optionalStringArray(key: String): List<String> = if (containsKey(key)) stringArray(key) else emptyList()

private fun JsonObject.optionalArray(key: String): List<kotlinx.serialization.json.JsonElement> =
    when (val element = this[key]) {
        null -> emptyList()
        is JsonArray -> element
        else -> throw invalidInputFailure()
    }

private fun JsonObject.stringArray(key: String): List<String> {
    val array = this[key] as? JsonArray ?: throw invalidInputFailure()
    return array.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalidInputFailure()
    }
}

/**
 * The client's accepted UTC date text: RFC3339 with an explicit `Z` designator, no local
 * offset is ever guessed. The second range is enforced in [requireUtcDate].
 */
private val RFC3339_UTC = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(\\.\\d{1,9})?Z$")
