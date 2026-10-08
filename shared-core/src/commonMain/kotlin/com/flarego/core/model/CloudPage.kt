package com.flarego.core.model

/** Continuation token of a paginated read. */
sealed interface PageToken {
    data class Number(val value: Int) : PageToken {
        init {
            if (value <= 0) throw invalidInputFailure()
        }
    }

    data class Cursor(val value: String) : PageToken {
        init {
            if (value.isBlank()) throw invalidInputFailure()
        }
    }
}

/**
 * Requested page of a list read. Provider page-size maxima differ, so no global
 * upper bound is applied here; the limit only has to be a positive integer.
 */
data class PageRequest(val limit: Int = 50, val token: PageToken? = null) {
    init {
        if (limit <= 0) throw invalidInputFailure()
    }
}

/**
 * One page of provider data.
 *
 * [next] and [total] stay null when the provider did not report them; they are
 * never inferred from the item count.
 */
data class CloudPage<T>(val items: List<T>, val next: PageToken?, val total: Long?) {
    init {
        if (total != null && total < 0) throw invalidInputFailure()
    }
}
