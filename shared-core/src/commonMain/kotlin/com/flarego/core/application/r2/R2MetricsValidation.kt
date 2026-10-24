package com.flarego.core.application.r2

import com.flarego.core.model.R2AccountMetrics
import com.flarego.core.model.R2ClassMetrics
import com.flarego.core.model.R2MetricNumber
import com.flarego.core.model.R2SizeMetrics
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The safe projection of the account-level R2 metrics.
 *
 * Nothing here is loaded from a schema at runtime. The pinned schema types every metric as a
 * `number` and requires none, so:
 * - an absent field stays null and is never defaulted to 0;
 * - a present value keeps its exact JSON lexical form through [R2MetricNumber]; it is never
 *   truncated, rounded through a Double or underflowed to 0;
 * - a non-number (string/boolean/null) or an unsupported magnitude (e.g. `1e400`) is not guessed:
 *   the whole document becomes display-only with the affected field left null;
 * - an unknown member keeps the document display-only instead of being dropped and rewritten.
 */

/** Fixed message for a display-only metrics document. */
internal const val R2_METRICS_READ_ONLY_UNKNOWN = "账号指标包含应用尚未支持的字段或取值，仅可查看"

private val METRICS_KEYS = setOf("standard", "infrequentAccess")
private val CLASS_KEYS = setOf("uploaded", "published")
private val SIZE_KEYS = setOf("metadataSize", "objects", "payloadSize")

internal fun decodeR2AccountMetrics(raw: JsonObject): R2AccountMetrics {
    var interpretable = raw.keys.all { it in METRICS_KEYS }
    val standard = raw.classMetricsOrNull("standard", onUnknown = { interpretable = false })
    val infrequentAccess = raw.classMetricsOrNull("infrequentAccess", onUnknown = { interpretable = false })
    return R2AccountMetrics(
        standard = standard,
        infrequentAccess = infrequentAccess,
        interpretable = interpretable,
        readOnlyReason = if (interpretable) null else R2_METRICS_READ_ONLY_UNKNOWN,
    )
}

private fun JsonObject.classMetricsOrNull(key: String, onUnknown: () -> Unit): R2ClassMetrics? {
    if (!containsKey(key)) return null
    val raw = this[key] as? JsonObject
    if (raw == null) {
        onUnknown()
        return null
    }
    if (!raw.keys.all { it in CLASS_KEYS }) onUnknown()
    return R2ClassMetrics(
        uploaded = raw.sizeMetricsOrNull("uploaded", onUnknown),
        published = raw.sizeMetricsOrNull("published", onUnknown),
    )
}

private fun JsonObject.sizeMetricsOrNull(key: String, onUnknown: () -> Unit): R2SizeMetrics? {
    if (!containsKey(key)) return null
    val raw = this[key] as? JsonObject
    if (raw == null) {
        onUnknown()
        return null
    }
    if (!raw.keys.all { it in SIZE_KEYS }) onUnknown()
    return R2SizeMetrics(
        metadataSize = raw.metricOrNull("metadataSize", onUnknown),
        objects = raw.metricOrNull("objects", onUnknown),
        payloadSize = raw.metricOrNull("payloadSize", onUnknown),
    )
}

/**
 * One metric number. A JSON string, boolean, null, missing member and unsupported magnitude are all
 * treated as "not a metric this app can show"; the value stays null and the document is display-only.
 */
private fun JsonObject.metricOrNull(key: String, onUnknown: () -> Unit): R2MetricNumber? {
    if (!containsKey(key)) return null
    val primitive = this[key] as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || primitive.isString) {
        onUnknown()
        return null
    }
    val number = R2MetricNumber.parse(primitive.content)
    if (number == null) {
        onUnknown()
        return null
    }
    return number
}
