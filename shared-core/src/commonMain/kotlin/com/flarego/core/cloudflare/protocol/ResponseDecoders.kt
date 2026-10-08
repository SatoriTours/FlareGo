package com.flarego.core.cloudflare.protocol

import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Decoders for the response kinds a registered action can declare.
 *
 * Non-2xx statuses map to stable, app-owned errors. Provider text, provider error
 * messages and raw bodies never enter an error message, an error cause or a log;
 * a partially successful provider result stays in the returned object so the
 * product can decide what it means. A 204/304 is an explicit status and bypasses
 * envelope parsing entirely — an empty object is never invented for it.
 */
object ResponseDecoders {
    /** True for the statuses that carry explicit no-content semantics. */
    fun isExplicitlyEmpty(status: Int): Boolean = status == 204 || status == 304

    /**
     * Validates the Cloudflare envelope shape of a successful response and returns
     * it unchanged. `success` must be present and a real boolean; the result and
     * any partial data are retained in memory. `writing` only controls whether an
     * unrecognised outcome is reported as uncertain, never whether a write is
     * declared unexecuted.
     */
    fun envelope(response: HttpResponse, writing: Boolean): JsonObject {
        requireSuccessfulStatus(response.status, writing)
        if (isExplicitlyEmpty(response.status)) throw invalidResponse(writing)
        val root = parse(response, writing) as? JsonObject ?: throw invalidResponse(writing)
        val success = (root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (success == null) throw invalidResponse(writing)
        return root
    }

    /** Parses a RAW_JSON response without requiring the envelope shape. */
    fun json(response: HttpResponse, writing: Boolean): JsonElement {
        requireSuccessfulStatus(response.status, writing)
        if (isExplicitlyEmpty(response.status)) throw invalidResponse(writing)
        return parse(response, writing)
    }

    /** Returns a TEXT response body unchanged on success. */
    fun text(response: HttpResponse, writing: Boolean): String {
        requireSuccessfulStatus(response.status, writing)
        if (isExplicitlyEmpty(response.status)) throw invalidResponse(writing)
        return response.body
    }

    private fun parse(response: HttpResponse, writing: Boolean): JsonElement =
        try {
            Json.parseToJsonElement(response.body)
        } catch (_: Exception) {
            throw invalidResponse(writing)
        }

    private fun requireSuccessfulStatus(status: Int, writing: Boolean) {
        if (status in 200..299) return
        throw when (status) {
            400, 422 -> CloudFailure(CloudFailureCode.INVALID_INPUT, "请求未被接受，请检查输入后重试")
            401 -> CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "凭据无效或已过期，请重新登录后重试")
            403 -> CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "无访问权限；请检查 Token 的服务权限和账号范围")
            404 -> CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源，请刷新后重试")
            409 -> CloudFailure(CloudFailureCode.CONFLICT, "资源状态已变化，请刷新后重试")
            429 -> CloudFailure(CloudFailureCode.RATE_LIMITED, "请求过于频繁，请稍后重试", uncertain = writing)
            in 500..599 -> CloudFailure(CloudFailureCode.UNAVAILABLE, "服务暂不可用，请稍后重试", uncertain = writing)
            else -> CloudFailure(CloudFailureCode.INVALID_RESPONSE, "响应状态无法识别（HTTP $status），请稍后重试")
        }
    }
}

internal fun invalidResponse(writing: Boolean): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_RESPONSE, "响应无法识别，请稍后重试", uncertain = writing)
