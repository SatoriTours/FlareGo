package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.decodeR2BucketJob
import com.flarego.core.application.r2.decodeR2JobPage
import com.flarego.core.application.r2.encodeR2JobCreateBody
import com.flarego.core.application.r2.queryPairs
import com.flarego.core.application.r2.r2JobLocatorOrNull
import com.flarego.core.application.r2.r2UpdateJobId
import com.flarego.core.application.r2.validateR2JobTarget
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.R2_CREATE_JOB_KEY
import com.flarego.core.cloudflare.protocol.R2_DATA_CATALOG_CHECK_HEADER
import com.flarego.core.cloudflare.protocol.R2_DATA_CATALOG_CHECK_VALUE
import com.flarego.core.cloudflare.protocol.R2_GET_JOB_KEY
import com.flarego.core.cloudflare.protocol.R2_LIST_JOBS_KEY
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2BucketJob
import com.flarego.core.model.R2JobCreateRequest
import com.flarego.core.model.R2JobListQuery
import com.flarego.core.model.R2JobPage
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.R2JobSubmission
import com.flarego.core.ports.R2JobsPort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R2 batch C bucket-job wire over the P1 gateway and the handwritten policy registry.
 *
 * Reads are projected through the safe allowlist and never retain raw provider JSON. The single
 * write:
 * 1. validates the target and encodes the explicit request body (every fallible local step);
 * 2. runs [beforeMutation], the trusted seam for the later proof-bound final guard;
 * 3. sends exactly once — a job POST is never retried, because each POST is a different job;
 * 4. decodes the receipt into a safe job and only mints a read-only locator for a trustworthy
 *    same-kind ID. A 200 response is never treated as the job having completed, so the later
 *    controller must still perform its own independent GET.
 */
class CloudflareR2JobsClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    // Trusted integration seam. Task 2's authorized session installs a proof-bound guard here.
    private val beforeMutation: () -> Unit = {},
) : R2JobsPort {
    override suspend fun listJobs(target: R2WriteTarget, query: R2JobListQuery): R2JobPage {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2JobTarget(target)
        val pairs = query.queryPairs()
        val path = jobsLocator(validated) + querySuffix(pairs)
        val root = readEnvelope(R2_LIST_JOBS_KEY, path, jurisdictionHeader(validated))
        return decodeR2JobPage(resultOf(root), query.jobType)
    }

    override suspend fun getJob(target: R2WriteTarget, jobId: String): R2BucketJob {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2JobTarget(target)
        val id = r2UpdateJobId(jobId)
        val root = readEnvelope(R2_GET_JOB_KEY, "${jobsLocator(validated)}/${encodePath(id)}", jurisdictionHeader(validated))
        return decodeR2BucketJob(resultOf(root), expectedId = id)
    }

    override suspend fun createJob(target: R2WriteTarget, request: R2JobCreateRequest): R2JobSubmission {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2JobTarget(target)
        val registration = ActionPolicies.registry.registration(R2_CREATE_JOB_KEY) ?: throw invalidInputFailure()
        val path = jobsLocator(validated)
        if (!registration.matches(registration.method, path)) throw invalidInputFailure()
        val body = HttpBody.Text(encodeR2JobCreateBody(request), JSON_CONTENT_TYPE)
        val headers = buildMap {
            putAll(jurisdictionHeader(validated))
            // The catalog protection header is fixed to `true` and belongs only to a prefix-delete
            // create; it is never a caller-supplied header map, and a migration never sends it.
            if (request is R2JobCreateRequest.PrefixDelete) put(R2_DATA_CATALOG_CHECK_HEADER, R2_DATA_CATALOG_CHECK_VALUE)
        }
        val httpRequest = HttpRequest(registration.method, path, body, headers)
        beforeMutation()
        val response =
            try {
                gateway.send(R2_CREATE_JOB_KEY, httpRequest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                // The job may already exist remotely: an uncertain write never becomes "not sent".
                throw failure
            } catch (_: Exception) {
                throw CloudFailure(
                    CloudFailureCode.UNKNOWN_RESULT,
                    "后台作业是否已创建尚未确认，请先核对云端作业列表，不要重复提交",
                    uncertain = true,
                )
            }
        val root = ResponseDecoders.envelope(response, writing = true)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
        // After a send, an unexplained result shape is uncertain, not a definite refusal.
        val result = root["result"] as? JsonObject ?: throw CloudFailure(
            CloudFailureCode.INVALID_RESPONSE,
            "后台作业是否已创建尚未确认，请先核对云端作业列表，不要重复提交",
            uncertain = true,
        )
        val job = decodeR2BucketJob(result, expectedRequest = request)
        val locator = r2JobLocatorOrNull(validated, request, job)
        return if (locator != null) {
            // A trustworthy same-kind/same-parameter ID is only an acceptance, even if the POST
            // already reports COMPLETED; the independent GET remains the completion proof.
            R2JobSubmission(
                receipt = ExecutionReceipt.Accepted(pollPath = "${jobsLocator(validated)}/${encodePath(locator.jobId)}"),
                job = job,
                pollLocator = locator,
            )
        } else {
            R2JobSubmission(receipt = ExecutionReceipt.Unknown(), job = job, pollLocator = null)
        }
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

private fun jobsLocator(target: R2WriteTarget): String =
    "/accounts/${encodePath(target.account.accountId)}/r2/buckets/${encodePath(target.bucket)}/jobs"

private fun querySuffix(pairs: List<Pair<String, String>>): String =
    if (pairs.isEmpty()) "" else pairs.joinToString("&", prefix = "?") { (name, value) -> "$name=${encodePath(value)}" }
