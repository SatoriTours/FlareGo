package com.flarego.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The five pinned event actions of batch C.
 *
 * The form may only submit a non-empty subset of these values; duplicate actions are refused
 * instead of silently deduplicated.
 */
val R2_NOTIFICATION_ACTIONS: Set<String> = linkedSetOf(
    "PutObject",
    "CopyObject",
    "DeleteObject",
    "CompleteMultipartUpload",
    "LifecycleDeletion",
)

/**
 * One new rule to append.
 *
 * This type is the submission shape only: it has no rule ID and no creation time, so a known
 * existing rule's identity can never be re-submitted as if it were new. Prefix/suffix are kept
 * verbatim (never trimmed, decoded or rewritten); an absent filter stays absent.
 *
 * The action list is copied into a private snapshot at construction so a caller that keeps a
 * reference to its mutable list cannot change what this value means. The send path re-validates
 * its own fresh snapshot as well, so even a `copy()` that re-introduces an external alias cannot
 * produce an empty/duplicate/unknown action on the wire.
 */
class R2NotificationRuleSpec(
    actions: List<String>,
    val description: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
) {
    /** A private snapshot: a caller mutating its own list cannot change this intent. */
    val actions: List<String> = actions.toList()

    init {
        validateR2NotificationActions(this.actions)
        requireR2NotificationText(description, "description")
        requireR2NotificationText(prefix, "prefix")
        requireR2NotificationText(suffix, "suffix")
    }
}

/**
 * One provider-echoed rule.
 *
 * [ruleId] and [createdAt] are provider identity only; [actions] is the pinned action list as
 * echoed. An unknown action or missing/ambiguous ID makes the whole configuration display-only.
 */
data class R2NotificationRule(
    val ruleId: String?,
    val actions: List<String>,
    val description: String?,
    val prefix: String?,
    val suffix: String?,
    val createdAt: String?,
)

/**
 * One queue's notification rules.
 *
 * [interpretable] is false when this app cannot fully understand the queue's rules or its identity;
 * such a queue is display-only and can never be used to build a write. [documentBytes] is the size
 * of the raw provider result, used for the client-side 256 KiB editing budget.
 */
data class R2NotificationQueue(
    val queueId: String?,
    val queueName: String?,
    val rules: List<R2NotificationRule>,
    val interpretable: Boolean,
    val readOnlyReason: String? = null,
    val documentBytes: Int = 0,
)

/** The bucket's complete notification configuration; 404/403/unknown are never an empty value. */
data class R2NotificationCollection(
    val bucketName: String?,
    val queues: List<R2NotificationQueue>,
    val interpretable: Boolean,
    val readOnlyReason: String? = null,
)

/**
 * The two reviewed notification deletions.
 *
 * [Selected] must carry non-empty, unique rule IDs; [All] is the explicit "clear every rule of this
 * bucket/queue" mode and never degrades into an empty selection. The ID list is copied into a
 * private snapshot at construction, and the send path re-validates a fresh snapshot before building
 * a body.
 */
sealed interface R2NotificationDeletion {
    class Selected(ruleIds: List<String>) : R2NotificationDeletion {
        /** A private snapshot: a caller mutating its own list cannot change this intent. */
        val ruleIds: List<String> = ruleIds.toList()

        init {
            validateR2NotificationSelectedIds(this.ruleIds)
        }

        override fun equals(other: Any?): Boolean = this === other || (other is Selected && other.ruleIds == ruleIds)

        override fun hashCode(): Int = ruleIds.hashCode()

        override fun toString(): String = "Selected(ruleIds=$ruleIds)"
    }

    data object All : R2NotificationDeletion
}

/** Client-side editing bounds; these are not provider limits. */
const val R2_MAX_NOTIFICATION_RULES: Int = 100
const val R2_MAX_NOTIFICATION_DOCUMENT_BYTES: Int = 256 * 1024
const val R2_NOTIFICATION_MAX_TEXT_CHARS: Int = 1024
const val R2_NOTIFICATION_MAX_QUEUE_CHARS: Int = 256
const val R2_NOTIFICATION_MAX_RULE_ID_CHARS: Int = 256

/** A new-rule action list must be a non-empty unique subset of the pinned actions. */
internal fun validateR2NotificationActions(actions: List<String>) {
    if (actions.isEmpty()) throw invalidInputFailure()
    if (actions.distinct().size != actions.size) throw invalidInputFailure()
    if (actions.any { it !in R2_NOTIFICATION_ACTIONS }) throw invalidInputFailure()
}

/** A selected deletion needs non-empty, unique, printable rule IDs. */
internal fun validateR2NotificationSelectedIds(ruleIds: List<String>) {
    if (ruleIds.isEmpty()) throw invalidInputFailure()
    if (ruleIds.distinct().size != ruleIds.size) throw invalidInputFailure()
    ruleIds.forEach(::requireR2NotificationRuleId)
}

/** Free-text rule fields are bounded and printable; they are never trimmed or decoded. */
internal fun requireR2NotificationText(value: String?, field: String) {
    if (value == null) return
    if (value.length > R2_NOTIFICATION_MAX_TEXT_CHARS) throw invalidInputFailure()
    if (value.any { it.code < 0x20 || it.code == 0x7F }) throw invalidInputFailure()
}

internal fun requireR2NotificationQueueId(queueId: String): String {
    if (queueId.isEmpty() || queueId.length > R2_NOTIFICATION_MAX_QUEUE_CHARS) throw invalidInputFailure()
    if (queueId.any { it.code !in 0x21..0x7E }) throw invalidInputFailure()
    return queueId
}

internal fun requireR2NotificationRuleId(ruleId: String) {
    if (ruleId.isEmpty() || ruleId.length > R2_NOTIFICATION_MAX_RULE_ID_CHARS) throw invalidInputFailure()
    if (ruleId.any { it.code !in 0x21..0x7E }) throw invalidInputFailure()
}

/**
 * One operator-frozen event-notification write.
 *
 * The two deletions keep their distinct intent in the type: a [Delete] carries either an explicit
 * non-empty selection or the explicit whole-queue clear, and the intent is part of the plan's
 * desired state (and of the read-only locator's family), so a selected deletion can never be verified
 * with the clear's after-contract or the other way round.
 */
sealed interface R2NotificationWrite {
    val target: R2WriteTarget
    val queueId: String

    /**
     * Append-only: the new rules, never an existing rule's identity.
     *
     * The authoritative content of this command is the private canonical text captured here. The
     * public [rules] projection is a **fresh copy on every read**, so a caller (or a compromised
     * editor) that mutates the list or a nested action list cannot change what is fingerprinted,
     * previewed or sent: serialization, the request fingerprint and equality all read the same frozen
     * canonical text.
     */
    class Append(
        override val target: R2WriteTarget,
        queueId: String,
        rules: List<R2NotificationRuleSpec>,
    ) : R2NotificationWrite {
        override val queueId: String = requireR2NotificationQueueId(queueId)
        private val frozen: String = rulesToCanonical(snapshotRuleSpecs(rules))

        /** A defensive copy: mutating it never affects this command. */
        val rules: List<R2NotificationRuleSpec> get() = rulesFromCanonical(frozen)

        val canonicalRules: String get() = frozen

        override fun equals(other: Any?): Boolean =
            this === other || (other is Append && other.target == target && other.queueId == queueId &&
                other.canonicalRules == canonicalRules)

        override fun hashCode(): Int = 31 * (31 * target.hashCode() + queueId.hashCode()) + canonicalRules.hashCode()

        override fun toString(): String = "Append(target=$target, queueId=$queueId)"
    }

    /**
     * A selected-rule deletion or the explicit whole-queue clear.
     *
     * As with [Append], the authoritative content is the private frozen selection; the public
     * [deletion]/[selectedIds] projections are fresh copies on every read.
     */
    class Delete(
        override val target: R2WriteTarget,
        queueId: String,
        deletion: R2NotificationDeletion,
    ) : R2NotificationWrite {
        override val queueId: String = requireR2NotificationQueueId(queueId)
        private val frozenSelected: List<String> =
            when (deletion) {
                is R2NotificationDeletion.Selected -> {
                    validateR2NotificationSelectedIds(deletion.ruleIds.toList())
                    deletion.ruleIds.toList()
                }
                R2NotificationDeletion.All -> emptyList()
            }

        /** A defensive copy of the exact selected rule IDs (empty for the explicit clear). */
        val selectedIds: List<String> get() = frozenSelected.toList()

        /** A fresh, equivalent deletion value: mutating it never affects this command. */
        val deletion: R2NotificationDeletion
            get() =
                if (frozenSelected.isEmpty()) R2NotificationDeletion.All
                else R2NotificationDeletion.Selected(frozenSelected.toList())

        override fun equals(other: Any?): Boolean =
            this === other || (other is Delete && other.target == target && other.queueId == queueId &&
                other.frozenSelected == frozenSelected)

        override fun hashCode(): Int = 31 * (31 * target.hashCode() + queueId.hashCode()) + frozenSelected.hashCode()

        override fun toString(): String = "Delete(target=$target, queueId=$queueId)"
    }
}

/** Rebuilds fresh rule specs from the frozen canonical text (one-way: never the authority). */
internal fun rulesFromCanonical(canonical: String): List<R2NotificationRuleSpec> {
    val rules = mutableListOf<R2NotificationRuleSpec>()
    for (element in Json.parseToJsonElement(canonical) as JsonArray) {
        val row = element as JsonObject
        val actions = (row["actions"] as JsonArray).map { (it as JsonPrimitive).content }
        rules +=
            R2NotificationRuleSpec(
                actions = actions,
                description = (row["description"] as? JsonPrimitive)?.content,
                prefix = (row["prefix"] as? JsonPrimitive)?.content,
                suffix = (row["suffix"] as? JsonPrimitive)?.content,
            )
    }
    return rules
}

/** A re-validated, private snapshot of new-rule specs (bounded count, copied action lists). */
internal fun snapshotRuleSpecs(rules: List<R2NotificationRuleSpec>): List<R2NotificationRuleSpec> {
    if (rules.isEmpty() || rules.size > R2_MAX_NOTIFICATION_RULES) throw invalidInputFailure()
    return rules.map { R2NotificationRuleSpec(it.actions.toList(), it.description, it.prefix, it.suffix) }
}

/** The canonical, order-stable text of an append's new rules (identity/fingerprint use only). */
internal fun rulesToCanonical(rules: List<R2NotificationRuleSpec>): String =
    buildJsonArray {
        rules.forEach { rule ->
            add(
                buildJsonObject {
                    put("actions", buildJsonArray { rule.actions.forEach { add(JsonPrimitive(it)) } })
                    rule.description?.let { put("description", it) }
                    rule.prefix?.let { put("prefix", it) }
                    rule.suffix?.let { put("suffix", it) }
                },
            )
        }
    }.toString()
