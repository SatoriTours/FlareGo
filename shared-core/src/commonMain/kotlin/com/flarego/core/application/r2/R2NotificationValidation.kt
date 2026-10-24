package com.flarego.core.application.r2

import com.flarego.core.model.R2_NOTIFICATION_ACTIONS
import com.flarego.core.model.R2_MAX_NOTIFICATION_DOCUMENT_BYTES
import com.flarego.core.model.R2_MAX_NOTIFICATION_RULES
import com.flarego.core.model.R2NotificationCollection
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationQueue
import com.flarego.core.model.R2NotificationRule
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2NotificationWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.requireR2NotificationQueueId
import com.flarego.core.model.requireR2NotificationRuleId
import com.flarego.core.model.validateR2NotificationActions
import com.flarego.core.model.validateR2NotificationSelectedIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local validation, the append/delete encoders and the safe GET projection of batch C notifications.
 *
 * Nothing here is loaded from a schema at runtime. The rules are:
 * - `PUT` only ever sends new rules: the submission type has no rule ID/creation time, and the send
 *   path re-validates a fresh snapshot of every action list;
 * - a selected deletion needs non-empty, unique IDs that exist in the fresh before document; the
 *   whole-queue clear is a separate no-body mode and never degrades from an empty selection;
 * - the client budget covers the original, the delta and the expected merged result (100 rules /
 *   256 KiB), not just the request body;
 * - a GET result is projected through an exact allowlist. Unknown members/actions, missing or
 *   duplicated identity keep the configuration display-only, and 404/403/failure never becomes an
 *   empty configuration.
 */

/** Fixed message for a display-only notification configuration. */
internal const val R2_NOTIFICATION_READ_ONLY_UNKNOWN = "该事件通知配置包含应用尚未支持的字段或取值，仅可查看"

/** Fixed message for a write refused because the current configuration cannot be interpreted. */
internal const val R2_NOTIFICATION_READ_ONLY_WRITE =
    "该事件通知配置包含应用尚未支持的字段或取值，已阻止写入以避免覆盖未知规则"

/** Fixed message for a write refused by the local budget. */
internal const val R2_NOTIFICATION_BUDGET_EXCEEDED = "该事件通知配置超出本应用的编辑预算（100 条规则 / 256 KiB），仅可查看"

private val COLLECTION_KEYS = setOf("bucketName", "queues")
private val QUEUE_KEYS = setOf("queueId", "queueName", "rules")
private val RULE_KEYS = setOf("actions", "description", "prefix", "suffix", "ruleId", "createdAt")

/** Validates the bucket/jurisdiction of one notification target. */
internal fun validateR2NotificationTarget(target: R2WriteTarget): R2WriteTarget = validateR2ConfigurationTarget(target)

internal fun r2NotificationQueueId(queueId: String): String = requireR2NotificationQueueId(queueId)

/**
 * A frozen snapshot of the new rules to append.
 *
 * The list and each action list are copied; the shape is validated on the snapshot that will be
 * encoded, so a caller mutating its own collection afterwards cannot make this send empty/duplicate
 * or unknown actions.
 */
internal fun snapshotR2NotificationRuleSpecs(rules: List<R2NotificationRuleSpec>): List<R2NotificationRuleSpec> {
    if (rules.isEmpty() || rules.size > R2_MAX_NOTIFICATION_RULES) throw invalidInputFailure()
    val snapshot = rules.toList()
    for (rule in snapshot) {
        validateR2NotificationActions(rule.actions.toList())
        requireR2NotificationTextBound(rule.description)
        requireR2NotificationTextBound(rule.prefix)
        requireR2NotificationTextBound(rule.suffix)
    }
    return snapshot.map { R2NotificationRuleSpec(it.actions.toList(), it.description, it.prefix, it.suffix) }
}

/** The exact `{"rules":[...]}` body of one append; only new rules and only pinned fields. */
internal fun encodeR2NotificationAppendBody(rules: List<R2NotificationRuleSpec>): String {
    val snapshot = snapshotR2NotificationRuleSpecs(rules)
    val body = buildJsonObject {
        put("rules", buildJsonArray { snapshot.forEach { add(ruleSpecJson(it)) } })
    }.toString()
    requireR2NotificationDocumentBound(body)
    return body
}

/**
 * The expected merged rule list after the append, with the client budget applied to both the count
 * and an upper-bound byte size. This is a local safety limit, not a provider claim.
 */
internal fun requireR2NotificationAppendBudget(before: R2NotificationQueue, delta: List<R2NotificationRuleSpec>) {
    if (before.rules.size + delta.size > R2_MAX_NOTIFICATION_RULES) {
        throw com.flarego.core.model.CloudFailure(
            com.flarego.core.model.CloudFailureCode.UNSUPPORTED_CAPABILITY,
            R2_NOTIFICATION_BUDGET_EXCEEDED,
        )
    }
    val mergedRules = buildJsonArray {
        before.rules.forEach { add(ruleJsonForBudget(it)) }
        delta.forEach { add(ruleSpecJson(it)) }
    }
    // The budget applies to the complete *known* queue configuration, not just the rules array plus
    // a fixed envelope: queueId/queueName are known provider fields and can be large. Provider-
    // assigned identity fields of new rules (ruleId/createdAt) cannot be predicted here; the
    // after-read remains the authority for those, and this check is a conservative client bound on
    // everything this app can already see.
    val knownConfiguration = buildJsonObject {
        before.queueId?.let { put("queueId", it) }
        before.queueName?.let { put("queueName", it) }
        put("rules", mergedRules)
    }
    if (knownConfiguration.toString().encodeToByteArray().size > R2_MAX_NOTIFICATION_DOCUMENT_BYTES) {
        throw com.flarego.core.model.CloudFailure(
            com.flarego.core.model.CloudFailureCode.UNSUPPORTED_CAPABILITY,
            R2_NOTIFICATION_BUDGET_EXCEEDED,
        )
    }
}

/** An already-frozen deletion body: the body (null for the explicit clear) and its selected IDs. */
internal data class R2NotificationDeleteIntent(val body: String?, val selectedIds: List<String>)

/**
 * Freezes and validates one deletion. A selected deletion yields unique non-empty IDs; the clear
 * yields no body. This is computed from a fresh snapshot, so a later caller mutation cannot make
 * the wire empty.
 */
internal fun encodeR2NotificationDeleteIntent(deletion: R2NotificationDeletion): R2NotificationDeleteIntent =
    when (deletion) {
        is R2NotificationDeletion.Selected -> {
            val ids = deletion.ruleIds.toList()
            validateR2NotificationSelectedIds(ids)
            val body = buildJsonObject {
                put("ruleIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            }.toString()
            requireR2NotificationDocumentBound(body)
            R2NotificationDeleteIntent(body, ids)
        }
        R2NotificationDeletion.All -> R2NotificationDeleteIntent(null, emptyList())
    }

/** The fresh before document must still contain every selected rule ID. */
internal fun requireR2NotificationSelectionPresent(queue: R2NotificationQueue, selectedIds: List<String>) {
    if (selectedIds.isEmpty()) return
    if (!queue.interpretable) {
        throw com.flarego.core.model.CloudFailure(
            com.flarego.core.model.CloudFailureCode.UNSUPPORTED_CAPABILITY,
            R2_NOTIFICATION_READ_ONLY_WRITE,
        )
    }
    val current = queue.rules.mapNotNull { it.ruleId }.toSet()
    if (!current.containsAll(selectedIds)) throw invalidInputFailure()
}

/** Projects the bucket's complete configuration; unknown members keep it display-only. */
internal fun decodeR2NotificationCollection(raw: JsonObject): R2NotificationCollection {
    var interpretable = raw.keys.all { it in COLLECTION_KEYS }
    val bucketName = raw.stringOrNull("bucketName")
    if (bucketName == null || bucketName.isBlank()) interpretable = false
    val queuesRaw = raw["queues"] as? JsonArray
    if (queuesRaw == null) interpretable = false
    val queues = queuesRaw.orEmpty().map { element ->
        val entry = element as? JsonObject
        if (entry == null) {
            interpretable = false
            R2NotificationQueue(null, null, emptyList(), false, R2_NOTIFICATION_READ_ONLY_UNKNOWN)
        } else {
            decodeR2NotificationQueue(entry).also { if (!it.interpretable) interpretable = false }
        }
    }
    // Duplicate queue IDs make the bucket's configuration ambiguous.
    if (queues.mapNotNull { it.queueId }.size != queues.mapNotNull { it.queueId }.distinct().size) interpretable = false
    return R2NotificationCollection(
        bucketName = bucketName,
        queues = queues,
        interpretable = interpretable,
        readOnlyReason = if (interpretable) null else R2_NOTIFICATION_READ_ONLY_UNKNOWN,
    )
}

/**
 * Projects one queue.
 *
 * [expectedQueueId] binds a single-queue read to the requested queue. Missing/invalid identity, a
 * duplicate rule ID, an unknown action, a present-but-invalid field or an over-budget document all
 * keep the queue display-only, so it can never become a write baseline.
 */
internal fun decodeR2NotificationQueue(raw: JsonObject, expectedQueueId: String? = null): R2NotificationQueue {
    val documentBytes = raw.toString().encodeToByteArray().size
    var interpretable = raw.keys.all { it in QUEUE_KEYS }
    if (documentBytes > R2_MAX_NOTIFICATION_DOCUMENT_BYTES) interpretable = false
    val queueIdRaw = raw.stringOrNull("queueId")
    val queueId = queueIdRaw?.let { value -> runCatching { requireR2NotificationQueueId(value); value }.getOrNull() }
    if (queueId == null) interpretable = false
    if (expectedQueueId != null && queueId != expectedQueueId) interpretable = false
    val queueName = raw.stringOrNull("queueName")
    if (raw.containsKey("queueName") && queueName == null) interpretable = false
    val rulesRaw = raw["rules"] as? JsonArray
    if (rulesRaw == null) interpretable = false
    if (rulesRaw != null && rulesRaw.size > R2_MAX_NOTIFICATION_RULES) interpretable = false
    val rules = rulesRaw.orEmpty().map { element ->
        val entry = element as? JsonObject
        if (entry == null) {
            interpretable = false
            R2NotificationRule(null, emptyList(), null, null, null, null)
        } else {
            val decoded = decodeR2NotificationRule(entry)
            if (!decoded.valid) interpretable = false
            decoded.rule
        }
    }
    val ids = rules.mapNotNull { it.ruleId }
    if (ids.size != rules.size) interpretable = false
    if (ids.distinct().size != ids.size) interpretable = false
    return R2NotificationQueue(
        queueId = queueId,
        queueName = queueName,
        rules = rules,
        interpretable = interpretable,
        readOnlyReason = if (interpretable) null else R2_NOTIFICATION_READ_ONLY_UNKNOWN,
        documentBytes = documentBytes,
    )
}

private data class DecodedRule(val rule: R2NotificationRule, val valid: Boolean)

/**
 * One provider-echoed rule. Identity ([ruleId]/[createdAt]) is display-only but must be present and
 * unambiguous; the action list is the pinned enum and an unknown/duplicate action makes the whole
 * configuration display-only.
 */
private fun decodeR2NotificationRule(raw: JsonObject): DecodedRule {
    var valid = raw.keys.all { it in RULE_KEYS }
    val actionsRaw = raw["actions"] as? JsonArray
    if (actionsRaw == null) valid = false
    val actions = actionsRaw.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    if (actionsRaw != null && actions.size != actionsRaw.size) valid = false
    if (actions.isEmpty() || actions.any { it !in R2_NOTIFICATION_ACTIONS }) valid = false
    if (actions.distinct().size != actions.size) valid = false
    val ruleIdRaw = raw.stringOrNull("ruleId")
    val ruleId = ruleIdRaw?.let { value -> runCatching { requireR2NotificationRuleId(value); value }.getOrNull() }
    if (ruleId == null) valid = false
    for (key in listOf("description", "prefix", "suffix", "createdAt")) {
        if (raw.containsKey(key) && raw.stringOrNull(key) == null) valid = false
    }
    val rule = R2NotificationRule(
        ruleId = ruleId,
        actions = actions,
        description = raw.stringOrNull("description"),
        prefix = raw.stringOrNull("prefix"),
        suffix = raw.stringOrNull("suffix"),
        createdAt = raw.stringOrNull("createdAt"),
    )
    return DecodedRule(rule, valid)
}

/** The local document budget; it is not a provider limit. */
internal fun requireR2NotificationDocumentBound(body: String) {
    if (body.encodeToByteArray().size > R2_MAX_NOTIFICATION_DOCUMENT_BYTES) throw invalidInputFailure()
}

private fun requireR2NotificationTextBound(value: String?) {
    if (value == null) return
    if (value.length > com.flarego.core.model.R2_NOTIFICATION_MAX_TEXT_CHARS) throw invalidInputFailure()
    if (value.any { it.code < 0x20 || it.code == 0x7F }) throw invalidInputFailure()
}

private fun ruleSpecJson(rule: R2NotificationRuleSpec): JsonObject = buildJsonObject {
    put("actions", buildJsonArray { rule.actions.forEach { add(JsonPrimitive(it)) } })
    rule.description?.let { put("description", it) }
    rule.prefix?.let { put("prefix", it) }
    rule.suffix?.let { put("suffix", it) }
}

private fun ruleJsonForBudget(rule: R2NotificationRule): JsonObject = buildJsonObject {
    put("actions", buildJsonArray { rule.actions.forEach { add(JsonPrimitive(it)) } })
    rule.ruleId?.let { put("ruleId", it) }
    rule.description?.let { put("description", it) }
    rule.prefix?.let { put("prefix", it) }
    rule.suffix?.let { put("suffix", it) }
    rule.createdAt?.let { put("createdAt", it) }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** The reviewed write operation (and therefore the authority operation) of one notification write. */
internal fun r2NotificationWriteKey(write: R2NotificationWrite): String =
    when (write) {
        is R2NotificationWrite.Append -> R2TaskTargets.NOTIFICATION_APPEND_ACTION
        is R2NotificationWrite.Delete -> R2TaskTargets.NOTIFICATION_DELETE_ACTION
    }

/** The exact intent of one notification write, as persisted in its read-only locator family. */
internal fun r2NotificationIntentOf(write: R2NotificationWrite): R2NotificationIntent =
    when (write) {
        is R2NotificationWrite.Append -> R2NotificationIntent.APPEND
        is R2NotificationWrite.Delete ->
            when (write.deletion) {
                is R2NotificationDeletion.Selected -> R2NotificationIntent.SELECTED
                R2NotificationDeletion.All -> R2NotificationIntent.ALL
            }
    }

/** The complete known public state of one queue; the frozen baseline and its later comparison. */
internal fun r2NotificationQueueState(queue: R2NotificationQueue): JsonObject =
    buildJsonObject {
        put("queueId", queue.queueId ?: "")
        put("queueName", queue.queueName ?: "")
        put(
            "rules",
            buildJsonArray {
                queue.rules.sortedBy { it.ruleId ?: "" }.forEach { rule ->
                    add(
                        buildJsonObject {
                            put("ruleId", rule.ruleId ?: "")
                            put("actions", buildJsonArray { rule.actions.forEach { add(JsonPrimitive(it)) } })
                            rule.description?.let { put("description", it) }
                            rule.prefix?.let { put("prefix", it) }
                            rule.suffix?.let { put("suffix", it) }
                            rule.createdAt?.let { put("createdAt", it) }
                        },
                    )
                }
            },
        )
    }
