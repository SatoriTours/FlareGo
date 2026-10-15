package com.flarego.core.cloudflare.protocol

import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Shared stable failure boundary; provider bodies and transport causes stay private. */
internal suspend fun readEnvelope(gateway: CloudflareGateway, key: String, path: String): JsonObject {
    val response = try {
        gateway.send(key, HttpRequest("GET", path))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CloudFailure) {
        throw failure
    } catch (_: Exception) {
        throw CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法连接，请检查网络后重试")
    }
    val root = ResponseDecoders.envelope(response, writing = false)
    if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(false)
    return root
}
