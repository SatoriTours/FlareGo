package com.flarego.core.cloudflare.r2

import com.flarego.core.application.r2.decodeR2AccountMetrics
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.R2_ACCOUNT_METRICS_KEY
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2AccountMetrics
import com.flarego.core.model.ScopeGuard
import com.flarego.core.ports.R2MetricsPort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R2 batch C account-metrics wire.
 *
 * The endpoint is account-level only: the request carries no bucket and no jurisdiction header, so
 * an account summary can never be displayed as a bucket's or a region's numbers. Values keep their
 * provider decimal form; missing/non-finite fields keep the document display-only instead of being
 * defaulted to 0.
 */
class CloudflareR2MetricsClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
) : R2MetricsPort {
    override suspend fun getMetrics(scope: AccountScope): R2AccountMetrics {
        ScopeGuard.requireOwned(scope, connection)
        val path = "/accounts/${encodePath(scope.accountId)}/r2/metrics"
        val response =
            try {
                gateway.send(R2_ACCOUNT_METRICS_KEY, HttpRequest("GET", path))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                throw failure
            } catch (_: Exception) {
                throw CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法连接，请检查网络后重试")
            }
        val root = ResponseDecoders.envelope(response, writing = false)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(false)
        val result = root["result"] as? JsonObject ?: throw invalidResponse(false)
        return decodeR2AccountMetrics(result)
    }
}
