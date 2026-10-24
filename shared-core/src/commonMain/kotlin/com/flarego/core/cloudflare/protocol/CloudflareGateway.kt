package com.flarego.core.cloudflare.protocol

import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import kotlinx.coroutines.delay

/**
 * The only path from this app to the provider.
 *
 * The gateway looks the action up in the handwritten registry, checks that the
 * request really matches the registered method and relative path template, and
 * applies the bounded read-only rate-limit policy. It never guesses read/write
 * semantics from the HTTP method: [ActionSemantics] comes from the registration.
 *
 * What this proves is routing, not ownership: it can show that an action addresses
 * a user, account or zone shaped API path, but the current connection's ownership
 * of that account or zone is proven by the explicit scope guard, never inferred
 * from a path string here.
 *
 * Retries are limited to a READ whose policy enables them and whose response is a
 * 429. Writes, server errors, transport failures, unknown outcomes and requests
 * carrying a one-shot stream are never retried, because a one-shot body cannot be
 * replayed and a write's real state belongs to the operation lifecycle.
 */
class CloudflareGateway(
    private val transport: HttpTransport,
    private val policies: ActionRegistry = ActionPolicies.registry,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = { 0L },
    private val parseHttpDate: (String) -> Long? = { null },
) {
    /** The registered policy for [actionKey], or null when the action is unknown. */
    fun policyOf(actionKey: String): ActionPolicy? = policies.policy(actionKey)

    /**
     * Sends one registered action. An unknown action key or a request that does not
     * match the registered method/path template is rejected with a stable error
     * before the transport is called at all.
     */
    suspend fun send(actionKey: String, request: HttpRequest): HttpResponse {
        val registration = policies.registration(actionKey) ?: throw unknownAction()
        if (!registration.matches(request.method, request.path)) throw policyMismatch()
        // The catalog protection header is admitted only for the reviewed create-job POST and only
        // with its fixed value. A GET/other route or any other value is refused before the wire.
        if (!isCatalogCheckHeaderAdmitted(actionKey, request.method, request.headers)) throw policyMismatch()
        var response = transport.execute(request)
        val policy = registration.policy
        if (policy.semantics != ActionSemantics.READ) return response
        if (!policy.retry.enabled || response.status != RETRYABLE_STATUS || !isRepeatable(request)) return response
        var spentMs = 0L
        var retries = 0
        while (true) {
            if (retries >= policy.retry.maxRetries) throw rateLimited()
            val waitMs = suggestedWaitMs(response) ?: backoffMs(retries)
            // `spentMs` never exceeds the budget, so the remaining budget and the
            // cumulative comparison are both overflow-free.
            if (waitMs > policy.retry.maxWaitMs - spentMs) throw rateLimited()
            sleeper(waitMs)
            spentMs += waitMs
            retries++
            response = transport.execute(request)
            if (response.status != RETRYABLE_STATUS) return response
        }
    }

    /**
     * The provider's suggested wait in milliseconds, or null when it did not send
     * one. A present but unusable value fails closed instead of falling back to the
     * shorter backoff, so a request is never sent earlier than the provider asked.
     */
    private fun suggestedWaitMs(response: HttpResponse): Long? {
        val entries = response.headers.entries.filter { it.key.equals(RETRY_AFTER, ignoreCase = true) }
        if (entries.isEmpty()) return null
        // A matching entry with no value is a present invalid header: it fails closed
        // before flattening, so a case variant with one usable value cannot mask it.
        if (entries.any { it.value.isEmpty() }) throw rateLimited()
        val values = entries.flatMap { it.value }
        if (values.size > 1) throw rateLimited()
        val raw = values.single().trim()
        if (raw.isEmpty()) throw rateLimited()
        if (raw.all { it in '0'..'9' }) {
            // delta-seconds is strictly a sequence of digits: no sign, no spaces.
            val seconds = raw.toLongOrNull() ?: return ReadRetryPolicy.MAX_READ_WAIT_MS + 1
            if (seconds > ReadRetryPolicy.MAX_READ_WAIT_MS / 1000) return ReadRetryPolicy.MAX_READ_WAIT_MS + 1
            return seconds * 1000
        }
        val epochMs = parseHttpDate(raw) ?: throw rateLimited()
        val now = nowMs()
        if (epochMs <= now) return 0L
        // Saturating difference: an overflowing subtraction is far beyond the budget,
        // never a negative "past date" that would send immediately.
        val waitMs = epochMs - now
        return if (waitMs < 0) ReadRetryPolicy.MAX_READ_WAIT_MS + 1 else waitMs
    }

    private fun backoffMs(retries: Int): Long = if (retries == 0) 1_000L else 2_000L

    private fun isRepeatable(request: HttpRequest): Boolean =
        when (val payload = request.payload) {
            is HttpBody.Stream -> false
            // A one-shot sensitive body is never a retry candidate, even if a caller ever marks it read.
            is HttpBody.SensitiveJson -> false
            is HttpBody.Multipart -> payload.parts.none { it.body is HttpBody.Stream }
            else -> true
        }

    private companion object {
        const val RETRYABLE_STATUS = 429
        const val RETRY_AFTER = "Retry-After"
    }
}

internal fun unknownAction(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_INPUT, "该操作未在本地策略中登记，请更新应用后重试")

internal fun policyMismatch(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_INPUT, "请求与该操作的登记方法或路径不匹配，已拒绝发送")

internal fun rateLimited(): CloudFailure =
    CloudFailure(CloudFailureCode.RATE_LIMITED, "请求过于频繁，请稍后重试；请等待建议时间后再试，不要立即重发")
