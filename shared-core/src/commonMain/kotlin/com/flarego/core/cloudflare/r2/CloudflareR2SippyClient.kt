package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_SIPPY_READ_KEY
import com.flarego.core.application.r2.R2_SIPPY_READ_ONLY_WRITE
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.application.r2.decodeR2SippySnapshot
import com.flarego.core.application.r2.encodeR2SippyEnableBody
import com.flarego.core.application.r2.rawR2SippyResult
import com.flarego.core.application.r2.requireDraft
import com.flarego.core.application.r2.r2SippyWriteKey
import com.flarego.core.application.r2.validateR2SippyTarget
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.R2SippySession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R2 batch B3 Sippy wire over the P1 gateway and the handwritten policy registry.
 *
 * The GET is projected through the safe allowlist; its raw result is never retained. A write:
 * 1. validates the target and, for `PUT`, that the lease is still a draft;
 * 2. builds the one-shot sensitive body (all fallible encoding before the guard);
 * 3. proves the bucket and reads the safe current snapshot (every read before the guard);
 * 4. runs [beforeSend] with the snapshot, then the final guard installs the admitted lease;
 * 5. sends exactly once. The lease is consumed even on a guard/send failure, so it can never be
 *    replayed; errors use fixed app-owned text and never echo the sensitive body or response.
 */
class CloudflareR2SippyClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    // Trusted integration seam. The authorized session installs a proof-bound final guard here.
    private val beforeMutation: () -> Unit = {},
) : R2SippySession {
    override suspend fun getSippy(target: R2WriteTarget): R2SippySnapshot = readSnapshot(target)

    override suspend fun putSippy(target: R2WriteTarget, lease: com.flarego.core.application.r2.R2SippySecretLease): ExecutionReceipt =
        writeSippy(R2SippyWrite.PutSippy(target, lease)) {}

    override suspend fun deleteSippy(target: R2WriteTarget): ExecutionReceipt =
        writeSippy(R2SippyWrite.DeleteSippy(target)) {}

    override suspend fun writeSippy(
        write: R2SippyWrite,
        beforeSend: (R2SippySnapshot?) -> Unit,
    ): ExecutionReceipt {
        ScopeGuard.requireOwned(write.target.account, connection)
        val target = validateR2SippyTarget(write.target)
        val operation = r2SippyWriteKey(write)
        val registration = ActionPolicies.registry.registration(operation) ?: throw invalidInputFailure()
        // A consumed/retired lease is refused before even the bucket read, so a repeated
        // confirmation can never produce a second request.
        val lease = (write as? R2SippyWrite.PutSippy)?.lease
        lease?.requireDraft()
        // The one-shot sensitive body is built here. Every refusal path below drops it again, so a
        // failed guard/product callback never leaves the encoded value hanging on this stack.
        val body = lease?.let(::encodeR2SippyEnableBody)
        try {
            val path = sippyLocator(target)
            if (!registration.matches(registration.method, path)) throw invalidInputFailure()
            // Independent bucket proof, then the safe current snapshot: both finish before the guard.
            getBucket(target)
            val observed = readSnapshot(target)
            if (!observed.editable) {
                throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_SIPPY_READ_ONLY_WRITE)
            }
            val request =
                HttpRequest(
                    method = registration.method,
                    path = path,
                    payload = body ?: HttpBody.Empty,
                    headers = jurisdictionHeader(target),
                )
            beforeSend(observed)
            // Admission is the final local step; a second admission of this lease is refused.
            if (lease != null && lease.admitForTransport() == null) throw invalidInputFailure()
            try {
                beforeMutation()
                val response = send(operation, request)
                if (response.status == STATUS_ACCEPTED) return ExecutionReceipt.Accepted()
                // A success envelope is validated but its result (which may echo provider data) is
                // not retained; the controller performs the same-lock read-back.
                val root = ResponseDecoders.envelope(response, writing = true)
                if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
                return ExecutionReceipt.Completed()
            } finally {
                // The transport is over on every path (success, refusal, uncertainty): no replay.
                lease?.completeTransport()
            }
        } finally {
            // Drops the temporary encoded value on every path (a no-op once the transport consumed it).
            body?.discard()
        }
    }

    private suspend fun readSnapshot(target: R2WriteTarget): R2SippySnapshot {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2SippyTarget(target)
        val root = readEnvelope(R2_SIPPY_READ_KEY, sippyLocator(validated), jurisdictionHeader(validated))
        return decodeR2SippySnapshot(validated, rawR2SippyResult(root))
    }

    private suspend fun getBucket(target: R2WriteTarget): R2BucketDocument {
        val root = readEnvelope("r2-get-bucket", bucketLocator(target), jurisdictionHeader(target))
        val row = root["result"] as? JsonObject ?: throw invalidResponse(false)
        val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw invalidResponse(false)
        if (name != target.bucket) throw scopeMismatchFailure()
        return R2BucketDocument(name, target.account, row)
    }

    private suspend fun readEnvelope(key: String, path: String, headers: Map<String, String>): JsonObject {
        val response =
            try {
                gateway.send(key, HttpRequest("GET", path, headers = headers))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                // A low-level transport failure may carry an echoed secret in its message/cause.
                throw sanitizeSippyTransportFailure(failure)
            } catch (_: Exception) {
                throw CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法连接，请检查网络后重试")
            }
        val root = ResponseDecoders.envelope(response, writing = false)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(false)
        return root
    }

    private suspend fun send(key: String, request: HttpRequest): HttpResponse =
        try {
            gateway.send(key, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            throw sanitizeSippyTransportFailure(failure)
        } catch (_: Exception) {
            throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "配置写入结果尚未确认，请先核对云端状态，不要重复发送", uncertain = true)
        }

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val STATUS_ACCEPTED = 202
    }
}

/**
 * A transport-level CloudFailure is rebuilt at the sensitive boundary with a fixed app-owned
 * message and no cause/request-id echo. The status code and [CloudFailure.uncertain] survive, so a
 * possibly-sent write is never downgraded to "not sent"; cancellation is never a CloudFailure and
 * is rethrown unchanged above.
 */
private fun sanitizeSippyTransportFailure(failure: CloudFailure): CloudFailure {
    val message =
        when (failure.code) {
            CloudFailureCode.SCOPE_MISMATCH -> "该资源不属于当前连接或账号，请切换账号后重试"
            CloudFailureCode.PERMISSION_REQUIRED -> "无访问权限；请检查 Token 的服务权限和账号范围"
            CloudFailureCode.NOT_FOUND -> "未找到请求的资源，请刷新后重试"
            CloudFailureCode.CREDENTIAL_EXPIRED -> "凭据无效或已过期，请重新登录后重试"
            CloudFailureCode.RATE_LIMITED -> "请求过于频繁，请稍后重试"
            CloudFailureCode.UNAVAILABLE -> "服务暂不可用，请稍后重试"
            CloudFailureCode.INVALID_RESPONSE -> "响应无法识别，请稍后重试"
            CloudFailureCode.CONFLICT -> "资源状态已变化，请刷新后重试"
            CloudFailureCode.UNKNOWN_RESULT -> "配置写入结果尚未确认，请先核对云端状态，不要重复发送"
            CloudFailureCode.UNSUPPORTED_CAPABILITY -> "当前环境不支持该操作，请更新应用后重试"
            CloudFailureCode.INVALID_INPUT -> "请求未被接受，请检查输入后重试"
        }
    return CloudFailure(failure.code, message, failure.uncertain)
}

private fun jurisdictionHeader(target: R2WriteTarget): Map<String, String> =
    mapOf("cf-r2-jurisdiction" to target.jurisdiction)

private fun bucketLocator(target: R2WriteTarget): String =
    "/accounts/${encodePath(target.account.accountId)}/r2/buckets/${encodePath(target.bucket)}"

private fun sippyLocator(target: R2WriteTarget): String = "${bucketLocator(target)}/sippy"
