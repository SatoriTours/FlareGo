package com.flarego.core.model

/**
 * One provider metric number, kept as its exact JSON lexical form.
 *
 * The pinned schema types every metric as a JSON `number`. A `Double` is not an exact representation
 * of that contract: `9007199254740993` would become `9007199254740992`, a long decimal would be
 * rounded and a tiny exponent such as `1e-400` would underflow to `0`. This value keeps the
 * provider's literal text and only uses `Double` as a range gate.
 *
 * [parse] accepts the strict JSON number grammar, bounded in length, and rejects a value whose
 * magnitude is outside the supported numeric range (`1e400` overflows a Double) — such a document is
 * reported as unsupported instead of being shown as a different number. It never produces a value
 * that was not in the provider document.
 */
class R2MetricNumber private constructor(val lexical: String) {
    override fun equals(other: Any?): Boolean = this === other || (other is R2MetricNumber && other.lexical == lexical)

    override fun hashCode(): Int = lexical.hashCode()

    override fun toString(): String = lexical

    companion object {
        /** A local lexical bound, not a provider limit. */
        const val MAX_LEXICAL_CHARS: Int = 64

        private val JSON_NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

        fun parse(lexical: String): R2MetricNumber? {
            if (lexical.isEmpty() || lexical.length > MAX_LEXICAL_CHARS) return null
            if (!JSON_NUMBER.matches(lexical)) return null
            // Range gate only: the stored value is the provider's literal, never this conversion.
            val magnitude = lexical.toDoubleOrNull() ?: return null
            if (magnitude.isNaN() || magnitude.isInfinite()) return null
            return R2MetricNumber(lexical)
        }
    }
}

/**
 * One object-size metric triple.
 *
 * The pinned schema requires none of the three fields, so a missing field stays null, never 0. A
 * present value keeps the provider's exact lexical number; a string/boolean/null or an unsupported
 * magnitude keeps the whole document display-only.
 */
data class R2SizeMetrics(
    val metadataSize: R2MetricNumber?,
    val objects: R2MetricNumber?,
    val payloadSize: R2MetricNumber?,
)

/** The uploaded/published metric pair of one storage class. */
data class R2ClassMetrics(
    val uploaded: R2SizeMetrics?,
    val published: R2SizeMetrics?,
)

/**
 * Account-level R2 metrics.
 *
 * They are account summaries only: never a bucket's numbers, never a jurisdiction-specific view and
 * never evidence that a write succeeded. Without a provider timestamp only "fetched at" is shown by
 * the UI; this model therefore carries no fabricated unit or timestamp.
 */
data class R2AccountMetrics(
    val standard: R2ClassMetrics?,
    val infrequentAccess: R2ClassMetrics?,
    val interpretable: Boolean,
    val readOnlyReason: String? = null,
)
