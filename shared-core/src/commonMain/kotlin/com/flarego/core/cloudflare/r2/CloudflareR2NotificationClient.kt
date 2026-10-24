package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_NOTIFICATION_READ_ONLY_WRITE
import com.flarego.core.application.r2.decodeR2NotificationCollection
import com.flarego.core.application.r2.decodeR2NotificationQueue
import com.flarego.core.application.r2.encodeR2NotificationAppendBody
import com.flarego.core.application.r2.encodeR2NotificationDeleteIntent
import com.flarego.core.application.r2.requireR2NotificationAppendBudget
import com.flarego.core.application.r2.requireR2NotificationSelectionPresent
import com.flarego.core.application.r2.snapshotR2NotificationRuleSpecs
import com.flarego.core.application.r2.r2NotificationQueueId
import com.flarego.core.application.r2.validateR2NotificationTarget
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.R2_APPEND_NOTIFICATION_KEY
import com.flarego.core.cloudflare.protocol.R2_DELETE_NOTIFICATION_KEY
import com.flarego.core.cloudflare.protocol.R2_GET_NOTIFICATION_KEY
import com.flarego.core.cloudflare.protocol.R2_LIST_NOTIFICATIONS_KEY
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2NotificationCollection
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationQueue
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.R2NotificationPort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R2 batch C event-notification wire over the P1 gateway and the handwritten policy registry.
 *
 * Reads are projected through the safe allowlist; a 404/403/failure is never an empty configuration.
 * A write:
 * 1. validates the target, queue and deletion shape locally (every fallible local step);
 * 2. independently reads the fresh configuration and refuses a foreign/unknown one before sending;
 * 3. runs [beforeMutation], the trusted seam for Task 2's proof-bound final guard;
 * 4. sends exactly once — both writes never retry. The pinned `PUT` appends only the new rules; the
 *    `DELETE` is either a non-empty explicit selection or the separate no-body whole-queue clear.
 */
class CloudflareR2NotificationClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    /**
     * Trusted integration seam: the client's **own** fresh pre-write observation of the target queue.
     *
     * Task 2's authorized session installs the proof-bound final guard here, so the guard compares the
     * exact document that is about to be modified (not another, unassociated GET) and runs immediately
     * before the single typed send.
     */
    private val beforeMutation: (R2NotificationQueue) -> Unit = {},
) : R2NotificationPort {
    override suspend fun listConfigurations(target: R2WriteTarget): R2NotificationCollection {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2NotificationTarget(target)
        val root = readEnvelope(R2_LIST_NOTIFICATIONS_KEY, configurationLocator(validated), jurisdictionHeader(validated))
        val collection = decodeR2NotificationCollection(resultOf(root))
        // A foreign bucket echo is display-only, never accepted as this bucket's configuration.
        if (collection.bucketName != null && collection.bucketName != validated.bucket) {
            return collection.copy(interpretable = false, readOnlyReason = com.flarego.core.application.r2.R2_NOTIFICATION_READ_ONLY_UNKNOWN)
        }
        return collection
    }

    override suspend fun getConfiguration(target: R2WriteTarget, queueId: String): R2NotificationQueue {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2NotificationTarget(target)
        val queue = r2NotificationQueueId(queueId)
        return readQueue(validated, queue)
    }

    override suspend fun appendRules(
        target: R2WriteTarget,
        queueId: String,
        rules: List<R2NotificationRuleSpec>,
    ): ExecutionReceipt = write(R2_APPEND_NOTIFICATION_KEY, target, queueId) { validated, queue ->
        // The new-rule body is frozen and validated before the fresh read; an existing rule identity
        // is never sent and a caller's later mutation cannot change this intent.
        val snapshot = snapshotR2NotificationRuleSpecs(rules)
        val body = encodeR2NotificationAppendBody(snapshot)
        val before = readQueue(validated, queue)
        if (!before.interpretable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_NOTIFICATION_READ_ONLY_WRITE)
        }
        requireR2NotificationAppendBudget(before, snapshot)
        HttpBody.Text(body, JSON_CONTENT_TYPE) to before
    }

    override suspend fun deleteRules(
        target: R2WriteTarget,
        queueId: String,
        deletion: R2NotificationDeletion,
    ): ExecutionReceipt = write(R2_DELETE_NOTIFICATION_KEY, target, queueId) { validated, queue ->
        // Freeze the selected IDs (or the explicit clear) before the fresh read.
        val intent = encodeR2NotificationDeleteIntent(deletion)
        val before = readQueue(validated, queue)
        if (!before.interpretable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_NOTIFICATION_READ_ONLY_WRITE)
        }
        requireR2NotificationSelectionPresent(before, intent.selectedIds)
        (intent.body?.let { HttpBody.Text(it, JSON_CONTENT_TYPE) } ?: HttpBody.Empty) to before
    }

    private suspend fun write(
        operation: String,
        target: R2WriteTarget,
        queueId: String,
        prepare: suspend (R2WriteTarget, String) -> Pair<HttpBody, R2NotificationQueue>,
    ): ExecutionReceipt {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2NotificationTarget(target)
        val queue = r2NotificationQueueId(queueId)
        val registration = ActionPolicies.registry.registration(operation) ?: throw invalidInputFailure()
        val path = queueLocator(validated, queue)
        if (!registration.matches(registration.method, path)) throw invalidInputFailure()
        val (body, before) = prepare(validated, queue)
        val request = HttpRequest(registration.method, path, body, jurisdictionHeader(validated))
        beforeMutation(before)
        val response =
            try {
                gateway.send(operation, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                throw failure
            } catch (_: Exception) {
                throw CloudFailure(
                    CloudFailureCode.UNKNOWN_RESULT,
                    "事件通知配置是否已修改尚未确认，请先核对云端配置，不要重复提交",
                    uncertain = true,
                )
            }
        val root = ResponseDecoders.envelope(response, writing = true)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
        // Only the pinned 200 means completed and 202 means accepted. Any other undeclared 2xx is
        // not a completion claim and stays uncertain instead of being flattened to Completed.
        return when (response.status) {
            200 -> ExecutionReceipt.Completed()
            202 -> ExecutionReceipt.Accepted()
            else -> throw CloudFailure(
                CloudFailureCode.UNKNOWN_RESULT,
                "事件通知配置写入返回了未声明的成功状态，结果尚未确认，请先核对云端配置，不要重复提交",
                uncertain = true,
            )
        }
    }

    private suspend fun readQueue(target: R2WriteTarget, queueId: String): R2NotificationQueue {
        val root = readEnvelope(
            R2_GET_NOTIFICATION_KEY,
            queueLocator(target, queueId),
            jurisdictionHeader(target),
        )
        return decodeR2NotificationQueue(resultOf(root), expectedQueueId = queueId)
    }

    private suspend fun readEnvelope(key: String, path: String, headers: Map<String, String>): JsonObject {
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

    private fun resultOf(root: JsonObject): JsonObject = root["result"] as? JsonObject ?: throw invalidResponse(false)

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }
}

private fun jurisdictionHeader(target: R2WriteTarget): Map<String, String> =
    mapOf("cf-r2-jurisdiction" to target.jurisdiction)

private fun configurationLocator(target: R2WriteTarget): String =
    "/accounts/${encodePath(target.account.accountId)}/event_notifications/r2/${encodePath(target.bucket)}/configuration"

private fun queueLocator(target: R2WriteTarget, queueId: String): String =
    "${configurationLocator(target)}/queues/${encodePath(queueId)}"
