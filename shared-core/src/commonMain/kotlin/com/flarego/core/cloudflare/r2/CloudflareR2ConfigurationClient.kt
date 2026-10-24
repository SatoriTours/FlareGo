package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_CONFIGURATION_READ_ONLY_WRITE
import com.flarego.core.application.r2.requireR2ConfigurationJurisdictionSupported
import com.flarego.core.application.r2.r2ConfigurationReadOnlyReason
import com.flarego.core.application.r2.r2ConfigurationWriteBody
import com.flarego.core.application.r2.supportsR2ConfigurationJurisdiction
import com.flarego.core.application.r2.validateR2ConfigurationTarget
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
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.freezeR2ConfigurationWrite
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2ConfigurationReadKey
import com.flarego.core.model.r2ConfigurationWriteKey
import com.flarego.core.model.r2ConfigurationWriteKind
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.R2ConfigurationSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R2 batch B1 configuration plane over the P1 gateway and the handwritten policy registry.
 *
 * The client only builds reviewed requests:
 * - the target's bucket and jurisdiction are validated locally, and the controlled
 *   `cf-r2-jurisdiction` header is sent exactly for the endpoints that declare it;
 * - local uploads is default-only and a non-default target is refused before any request,
 *   never by silently reading the default bucket;
 * - a write validates its rules, matches the registered method/path, proves the bucket
 *   independently under the same jurisdiction and reads the current snapshot — all
 *   *before* [beforeMutation] and the single typed send;
 * - a `PUT` response is only a receipt; the same-lock read-back belongs to the controller.
 */
class CloudflareR2ConfigurationClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    // Trusted integration seam. The authorized session installs a proof-bound final guard;
    // raw fixture clients intentionally retain their typed-transport-only semantics.
    private val beforeMutation: () -> Unit = {},
) : R2ConfigurationSession {

    override suspend fun getBucket(target: R2WriteTarget): R2BucketDocument {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2ConfigurationTarget(target)
        // `r2-get-bucket` declares the controlled jurisdiction parameter, so the proof of
        // ownership is asked for the same jurisdiction the configuration write will use.
        val root = readConfigurationEnvelope("r2-get-bucket", bucketLocator(validated), jurisdictionHeader(validated.jurisdiction))
        val row = root["result"] as? JsonObject ?: throw invalidResponse(false)
        val name =
            (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: throw invalidResponse(false)
        if (name != validated.bucket) throw scopeMismatchFailure()
        return R2BucketDocument(name, validated.account, row)
    }

    override suspend fun getCors(target: R2WriteTarget): R2ConfigurationDocument = readDocument(target, R2ConfigurationKind.CORS)

    override suspend fun getLifecycle(target: R2WriteTarget): R2ConfigurationDocument = readDocument(target, R2ConfigurationKind.LIFECYCLE)

    override suspend fun getLocalUploads(target: R2WriteTarget): R2ConfigurationDocument = readDocument(target, R2ConfigurationKind.LOCAL_UPLOADS)

    override suspend fun getLock(target: R2WriteTarget): R2ConfigurationDocument = readDocument(target, R2ConfigurationKind.LOCK)

    override suspend fun putCors(target: R2WriteTarget, rules: List<R2CorsRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutCors(target, rules)) {}

    override suspend fun deleteCors(target: R2WriteTarget): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.DeleteCors(target)) {}

    override suspend fun putLifecycle(target: R2WriteTarget, rules: List<R2LifecycleRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLifecycle(target, rules)) {}

    override suspend fun putLocalUploads(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLocalUploads(target, enabled)) {}

    override suspend fun putLock(target: R2WriteTarget, rules: List<R2LockRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLock(target, rules)) {}

    override suspend fun deleteLock(target: R2WriteTarget): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.DeleteLock(target)) {}

    override suspend fun writeConfiguration(
        write: R2ConfigurationWrite,
        beforeSend: (R2ConfigurationDocument?) -> Unit,
    ): ExecutionReceipt =
        try {
            perform(write, beforeSend)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            // A failure after a possible send is never a reason to replay it.
            if (failure.uncertain) ExecutionReceipt.Unknown() else throw failure
        }

    private suspend fun perform(
        write: R2ConfigurationWrite,
        beforeSend: (R2ConfigurationDocument?) -> Unit,
    ): ExecutionReceipt {
        val frozen = freezeR2ConfigurationWrite(write)
        ScopeGuard.requireOwned(frozen.target.account, connection)
        val target = validateR2ConfigurationTarget(frozen.target)
        val kind = r2ConfigurationWriteKind(frozen)
        requireR2ConfigurationJurisdictionSupported(target, kind)
        val operation = r2ConfigurationWriteKey(frozen)
        val registration = ActionPolicies.registry.registration(operation) ?: throw invalidInputFailure()
        // The full body is validated and encoded before anything is sent, so an illegal or
        // oversized rule set can never reach the provider.
        val body = r2ConfigurationWriteBody(frozen)
        val request =
            HttpRequest(
                method = registration.method,
                path = configurationLocator(target, kind),
                payload = body?.let { HttpBody.Text(it.toString(), JSON_CONTENT_TYPE) } ?: HttpBody.Empty,
                headers = jurisdictionHeaders(target.jurisdiction, kind),
            )
        if (!registration.matches(request.method, request.path)) throw invalidInputFailure()
        // Independent ownership proof, then the current snapshot: both are fallible and both
        // finish before the final guard, so nothing suspends between the guard and the send.
        getBucket(target)
        val observed = readDocument(target, kind)
        // A document this app cannot round-trip losslessly is never replaced by a guess.
        if (!observed.editable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_CONFIGURATION_READ_ONLY_WRITE)
        }
        beforeSend(observed)
        beforeMutation()
        val response = send(operation, request)
        if (response.status == STATUS_ACCEPTED) return ExecutionReceipt.Accepted()
        val root = ResponseDecoders.envelope(response, writing = true)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
        // A PUT response is a receipt; it is never assumed to contain the new document.
        return ExecutionReceipt.Completed()
    }

    private suspend fun readDocument(target: R2WriteTarget, kind: R2ConfigurationKind): R2ConfigurationDocument {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2ConfigurationTarget(target)
        requireR2ConfigurationJurisdictionSupported(validated, kind)
        val root =
            readConfigurationEnvelope(
                r2ConfigurationReadKey(kind),
                configurationLocator(validated, kind),
                jurisdictionHeaders(validated.jurisdiction, kind),
            )
        val raw = rawConfigurationResult(root, kind)
        val reason = r2ConfigurationReadOnlyReason(kind, raw)
        return R2ConfigurationDocument(validated, kind, raw, reason == null, reason)
    }

    /**
     * The provider's own result object, checked only for the shape the pinned schema
     * declares. Anything else is a read failure, never an empty configuration.
     */
    private fun rawConfigurationResult(root: JsonObject, kind: R2ConfigurationKind): JsonObject {
        val result = root["result"] as? JsonObject ?: throw invalidResponse(false)
        when (kind) {
            R2ConfigurationKind.CORS,
            R2ConfigurationKind.LIFECYCLE,
            R2ConfigurationKind.LOCK,
            -> result["rules"]?.let { if (it !is JsonArray) throw invalidResponse(false) }
            R2ConfigurationKind.LOCAL_UPLOADS ->
                result["enabled"]?.let { element ->
                    val primitive = element as? JsonPrimitive
                    if (primitive == null || primitive.isString || primitive.booleanOrNull == null) throw invalidResponse(false)
                }
        }
        return result
    }

    private suspend fun readConfigurationEnvelope(key: String, path: String, headers: Map<String, String>): JsonObject {
        val response =
            try {
                gateway.send(key, HttpRequest("GET", path, headers = headers))
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

    private suspend fun send(key: String, request: HttpRequest): HttpResponse =
        try {
            gateway.send(key, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            throw failure
        } catch (_: Exception) {
            throw unknownConfigurationResult()
        }

    private companion object {
        const val JURISDICTION_HEADER = "cf-r2-jurisdiction"
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val STATUS_ACCEPTED = 202
    }
}

private fun jurisdictionHeader(jurisdiction: String): Map<String, String> = mapOf("cf-r2-jurisdiction" to jurisdiction)

private fun jurisdictionHeaders(jurisdiction: String, kind: R2ConfigurationKind): Map<String, String> =
    if (supportsR2ConfigurationJurisdiction(kind)) jurisdictionHeader(jurisdiction) else emptyMap()

private fun bucketLocator(target: R2WriteTarget): String =
    "/accounts/${encodePath(target.account.accountId)}/r2/buckets/${encodePath(target.bucket)}"

private fun configurationLocator(target: R2WriteTarget, kind: R2ConfigurationKind): String =
    bucketLocator(target) +
        when (kind) {
            R2ConfigurationKind.CORS -> "/cors"
            R2ConfigurationKind.LIFECYCLE -> "/lifecycle"
            R2ConfigurationKind.LOCAL_UPLOADS -> "/local-uploads"
            R2ConfigurationKind.LOCK -> "/lock"
        }

private fun unknownConfigurationResult() =
    CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "配置写入结果尚未确认，请先核对云端状态，不要重复发送", uncertain = true)
