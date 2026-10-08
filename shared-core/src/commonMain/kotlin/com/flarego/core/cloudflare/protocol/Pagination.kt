package com.flarego.core.cloudflare.protocol

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.PageToken
import com.flarego.core.model.invalidInputFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Explicit pagination state for one list action.
 *
 * [Number] carries the page to request next and the page size, [Cursor] carries
 * the cursor to request next plus every cursor already used, and [None] means the
 * endpoint is not paginated at all and must not receive paging parameters.
 */
sealed interface PaginationStrategy {
    data class Number(val page: Int = 1, val perPage: Int = 50) : PaginationStrategy {
        init {
            if (page < 1 || perPage < 1) throw invalidInputFailure()
        }
    }

    data class Cursor(val cursor: String? = null, val seen: Set<String> = emptySet()) : PaginationStrategy

    object None : PaginationStrategy
}

/**
 * What the provider reported about the page that was just read.
 *
 * [next] is null when the provider did not report another page (or the endpoint
 * is not paginated); [total] is null when the provider did not report a usable
 * total, and is preserved as 0 when the provider really reported zero.
 */
data class PageMetadata(val next: PageToken?, val total: Long?)

/**
 * Query building, metadata decoding and strategy advancement for list actions.
 *
 * Metadata is validated strictly: an absent total stays unknown, a legal zero is
 * preserved, and a malformed, negative, fractional or overflowing value is a
 * stable error instead of a fabricated complete total. Nothing is inferred from
 * the number of items on the page.
 */
object Pagination {
    /** The query string for [strategy], without a leading separator. */
    fun query(strategy: PaginationStrategy, perPage: Int = 50): String =
        when (strategy) {
            is PaginationStrategy.Number -> "page=${strategy.page}&per_page=${strategy.perPage}"
            is PaginationStrategy.Cursor ->
                buildString {
                    append("per_page=").append(perPage)
                    val cursor = strategy.cursor
                    if (!cursor.isNullOrBlank()) append("&cursor=").append(encodePath(cursor))
                }
            PaginationStrategy.None -> ""
        }

    /** Decodes the provider metadata of the page just read. */
    fun decode(root: JsonObject, strategy: PaginationStrategy): PageMetadata =
        when (strategy) {
            PaginationStrategy.None -> PageMetadata(null, null)
            is PaginationStrategy.Cursor -> decodeCursor(root, strategy)
            is PaginationStrategy.Number -> decodeNumber(root, strategy)
        }

    /** The strategy for the next request, or null when pagination is finished. */
    fun next(strategy: PaginationStrategy, metadata: PageMetadata): PaginationStrategy? =
        when (strategy) {
            is PaginationStrategy.Number ->
                (metadata.next as? PageToken.Number)?.let { PaginationStrategy.Number(it.value, strategy.perPage) }
            is PaginationStrategy.Cursor ->
                (metadata.next as? PageToken.Cursor)?.let { PaginationStrategy.Cursor(it.value, strategy.seen + it.value) }
            PaginationStrategy.None -> null
        }

    private fun decodeCursor(root: JsonObject, strategy: PaginationStrategy.Cursor): PageMetadata {
        val info = root.resultInfo()
        val total = info?.countOrNull("total_count")
        val cursor = info?.cursorOrNull()
        if (cursor.isNullOrBlank()) return PageMetadata(null, total)
        if (cursor in strategy.seen) throw repeatedCursor()
        return PageMetadata(PageToken.Cursor(cursor), total)
    }

    private fun decodeNumber(root: JsonObject, strategy: PaginationStrategy.Number): PageMetadata {
        val info = root.resultInfo()
        val total = info?.countOrNull("total_count")
        val totalPages = info?.countOrNull("total_pages")
        val perPage = info?.positiveOrNull("per_page")
        val hasNext =
            when {
                totalPages != null -> strategy.page.toLong() < totalPages
                total != null && perPage != null -> strategy.page.toLong() * perPage < total
                else -> false
            }
        return PageMetadata(if (hasNext) PageToken.Number(strategy.page + 1) else null, total)
    }

    private fun JsonObject.resultInfo(): JsonObject? = this["result_info"] as? JsonObject

    /** A present non-negative integer count, or null when absent. Malformed values are rejected. */
    private fun JsonObject.countOrNull(key: String): Long? {
        val primitive = numberOrNull(key) ?: return null
        val value = primitive.longOrNull ?: throw malformedMetadata()
        if (value < 0) throw malformedMetadata()
        return value
    }

    /** A present strictly positive integer, or null when absent. Malformed values are rejected. */
    private fun JsonObject.positiveOrNull(key: String): Long? {
        val primitive = numberOrNull(key) ?: return null
        val value = primitive.longOrNull ?: throw malformedMetadata()
        if (value < 1) throw malformedMetadata()
        return value
    }

    private fun JsonObject.numberOrNull(key: String): JsonPrimitive? {
        val element = this[key] ?: return null
        val primitive = element as? JsonPrimitive ?: throw malformedMetadata()
        if (primitive.isString) throw malformedMetadata()
        return primitive
    }

    private fun JsonObject.cursorOrNull(): String? {
        val element = this["cursor"] ?: return null
        val primitive = element as? JsonPrimitive ?: throw malformedMetadata()
        if (!primitive.isString) throw malformedMetadata()
        return primitive.content
    }
}

private fun malformedMetadata(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_RESPONSE, "分页信息无法识别，请稍后刷新重试")

private fun repeatedCursor(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_RESPONSE, "分页游标重复，请稍后刷新重试")
