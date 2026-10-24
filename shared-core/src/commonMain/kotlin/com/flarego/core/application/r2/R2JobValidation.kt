package com.flarego.core.application.r2

import com.flarego.core.model.R2BucketJob
import com.flarego.core.model.R2JobCreateRequest
import com.flarego.core.model.R2JobDetails
import com.flarego.core.model.R2JobListQuery
import com.flarego.core.model.R2JobLocator
import com.flarego.core.model.R2JobPage
import com.flarego.core.model.R2JobStatus
import com.flarego.core.model.R2JobType
import com.flarego.core.model.R2PrefixDeleteDetails
import com.flarego.core.model.R2StorageClass
import com.flarego.core.model.R2StorageClassMigrationDetails
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_JOB_MAX_ID_CHARS
import com.flarego.core.model.R2_JOB_MAX_TOKEN_CHARS
import com.flarego.core.model.isTrustedR2JobId
import com.flarego.core.model.requireR2JobId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Local validation, the explicit create-job encoder and the safe GET projection of batch C jobs.
 *
 * Nothing here is loaded from a schema at runtime. The rules are:
 * - a non-empty prefix must end in `/`, an empty prefix is the explicit whole-bucket mode, and the
 *   value is never trimmed or guessed;
 * - a migration always sends both classes explicitly; there is no omitted-field default and the two
 *   classes must differ;
 * - a GET result is projected through an exact allowlist. Absent and present-but-invalid are
 *   different: an optional field may be omitted, but a present wrong-type/null/negative/overflowing
 *   value, a contradictory `isBucketClear`, both discriminators at once or an unaddressable ID keep
 *   the job display-only. The raw JSON is not retained.
 */

/** Fixed message for a display-only job this app cannot fully interpret. */
internal const val R2_JOB_READ_ONLY_UNKNOWN = "该后台作业包含应用尚未支持的字段或取值，仅可查看"

private val JOB_KEYS = setOf("id", "jobType", "status", "startTime", "endTime", "prefixDelete", "storageClassMigration")
private val PREFIX_DELETE_KEYS = setOf("prefix", "deletedObjects", "isBucketClear")
private val MIGRATION_KEYS = setOf(
    "sourceStorageClass",
    "destinationStorageClass",
    "migratedObjects",
    "migratedBytes",
    "migratedMetadataBytes",
    "skippedObjects",
    "skippedBytes",
    "failedObjects",
    "failedBytes",
)

/** Validates the bucket/jurisdiction of one C target; account ownership stays with the scope guard. */
internal fun validateR2JobTarget(target: R2WriteTarget): R2WriteTarget = validateR2ConfigurationTarget(target)

internal fun r2UpdateJobId(jobId: String): String = requireR2JobId(jobId)

/** The exact pinned create body of one request, with no omitted-field default. */
internal fun encodeR2JobCreateBody(request: R2JobCreateRequest): String =
    when (request) {
        is R2JobCreateRequest.PrefixDelete -> buildJsonObject {
            put("jobType", R2JobType.PREFIX_DELETE.wireValue)
            put("prefix", request.prefix)
        }.toString()
        is R2JobCreateRequest.StorageClassMigration -> buildJsonObject {
            put("jobType", R2JobType.STORAGE_CLASS_MIGRATION.wireValue)
            put("sourceStorageClass", request.source.wireValue)
            put("destinationStorageClass", request.destination.wireValue)
        }.toString()
    }

/** The query pairs of the reviewed list filter; a status filter always carries its jobType. */
internal fun R2JobListQuery.queryPairs(): List<Pair<String, String>> = buildList {
    val type = jobType
    if (type != null) add("jobType" to type.wireValue)
    val jobStatus = status
    if (jobStatus != null) add("status" to jobStatus.wireValue)
    val limit = maxKeys
    if (limit != null) add("maxKeys" to limit.toString())
    val token = continuationToken
    if (token != null) add("continuationToken" to token)
}

/**
 * Projects one job through the exact allowlist.
 *
 * [expectedType] / [expectedId] / [expectedRequest] bind the answer to what was asked. Any mismatch
 * or unexplained field never throws — the job stays display-only with `interpretable=false`, because
 * a read must remain safe to show.
 */
internal fun decodeR2BucketJob(
    raw: JsonObject,
    expectedType: R2JobType? = null,
    expectedId: String? = null,
    expectedRequest: R2JobCreateRequest? = null,
): R2BucketJob {
    var valid = raw.keys.all { it in JOB_KEYS }

    fun stringField(key: String, required: Boolean): String? {
        if (!raw.containsKey(key)) {
            if (required) valid = false
            return null
        }
        val value = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (value == null || value.isEmpty()) {
            valid = false
            return null
        }
        return value
    }

    fun detailsOf(key: String, required: Boolean, allowed: Set<String>): JsonObject? {
        if (!raw.containsKey(key)) {
            if (required) valid = false
            return null
        }
        val details = raw[key] as? JsonObject
        if (details == null || !details.keys.all { it in allowed }) {
            valid = false
            return null
        }
        return details
    }

    fun counter(container: JsonObject, key: String, required: Boolean): Long? {
        if (!container.containsKey(key)) {
            if (required) valid = false
            return null
        }
        val primitive = container[key] as? JsonPrimitive
        val value = primitive?.takeIf { !it.isString }?.longOrNull
        if (value == null || value < 0) {
            valid = false
            return null
        }
        return value
    }

    fun optionalBoolean(container: JsonObject, key: String): Boolean? {
        if (!container.containsKey(key)) return null
        val primitive = container[key] as? JsonPrimitive
        val value = primitive?.takeIf { !it.isString }?.booleanOrNull
        if (value == null) valid = false
        return value
    }

    val idRaw = stringField("id", required = true)
    val id = idRaw?.takeIf(::isTrustedR2JobId)
    if (idRaw != null && id == null) valid = false
    val type = raw.stringOrNull("jobType")?.let(R2JobType::fromWire)
    val status = raw.stringOrNull("status")?.let(R2JobStatus::fromWire)
    val startTime = stringField("startTime", required = true)
    val endTime = stringField("endTime", required = false)
    if (raw.containsKey("jobType") && type == null) valid = false
    // `status` is required by the pinned descriptor: absent, wrong-typed and unknown all stay
    // display-only, not only a present-but-unknown value.
    if (status == null) valid = false
    if (expectedType != null && type != expectedType) valid = false
    if (expectedId != null && id != expectedId) valid = false

    val hasPrefixBranch = raw.containsKey("prefixDelete")
    val hasMigrationBranch = raw.containsKey("storageClassMigration")
    if (hasPrefixBranch && hasMigrationBranch) valid = false

    val details =
        when (type) {
            R2JobType.PREFIX_DELETE -> detailsOf("prefixDelete", required = true, PREFIX_DELETE_KEYS)?.let { details ->
                val prefix = (details["prefix"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (prefix == null) valid = false
                val deleted = counter(details, "deletedObjects", required = true)
                val isBucketClear = optionalBoolean(details, "isBucketClear")
                if (prefix != null && isBucketClear != null && isBucketClear != prefix.isEmpty()) {
                    // `isBucketClear` is true only for the whole-bucket clear (empty prefix).
                    valid = false
                }
                if (prefix != null) {
                    if ((expectedRequest as? R2JobCreateRequest.PrefixDelete)?.prefix?.let { it != prefix } == true) {
                        valid = false
                    }
                    R2JobDetails.PrefixDelete(R2PrefixDeleteDetails(prefix, isBucketClear, deleted))
                } else {
                    null
                }
            }
            R2JobType.STORAGE_CLASS_MIGRATION -> detailsOf("storageClassMigration", required = true, MIGRATION_KEYS)?.let { details ->
                val source = (details["sourceStorageClass"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let(R2StorageClass::fromWire)
                val destination = (details["destinationStorageClass"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let(R2StorageClass::fromWire)
                if (source == null || destination == null) valid = false
                if (source != null && destination != null && source == destination) valid = false
                val migratedObjects = counter(details, "migratedObjects", required = true)
                val migratedBytes = counter(details, "migratedBytes", required = false)
                val migratedMetadataBytes = counter(details, "migratedMetadataBytes", required = false)
                val skippedObjects = counter(details, "skippedObjects", required = false)
                val skippedBytes = counter(details, "skippedBytes", required = false)
                val failedObjects = counter(details, "failedObjects", required = false)
                val failedBytes = counter(details, "failedBytes", required = false)
                if (expectedRequest is R2JobCreateRequest.StorageClassMigration &&
                    (source != expectedRequest.source || destination != expectedRequest.destination)
                ) {
                    valid = false
                }
                R2JobDetails.StorageClassMigration(
                    R2StorageClassMigrationDetails(
                        sourceStorageClass = source,
                        destinationStorageClass = destination,
                        migratedObjects = migratedObjects,
                        migratedBytes = migratedBytes,
                        migratedMetadataBytes = migratedMetadataBytes,
                        skippedObjects = skippedObjects,
                        skippedBytes = skippedBytes,
                        failedObjects = failedObjects,
                        failedBytes = failedBytes,
                    ),
                )
            }
            null -> null
        }
    if (type == null) valid = false
    if (type != null && details == null) valid = false
    if (hasPrefixBranch != (type == R2JobType.PREFIX_DELETE) && hasMigrationBranch != (type == R2JobType.STORAGE_CLASS_MIGRATION)) {
        // The branch present must be the one the discriminator names.
        valid = false
    }
    if (expectedRequest != null && type != expectedTypeFor(expectedRequest)) valid = false
    return R2BucketJob(
        id = id,
        jobType = type,
        status = status,
        startTime = startTime,
        endTime = endTime,
        details = details,
        interpretable = valid,
        readOnlyReason = if (valid) null else R2_JOB_READ_ONLY_UNKNOWN,
    )
}

private fun expectedTypeFor(request: R2JobCreateRequest): R2JobType =
    when (request) {
        is R2JobCreateRequest.PrefixDelete -> R2JobType.PREFIX_DELETE
        is R2JobCreateRequest.StorageClassMigration -> R2JobType.STORAGE_CLASS_MIGRATION
    }

/** Projects one list page; an unknown job entry never removes or rewrites the other entries. */
internal fun decodeR2JobPage(raw: JsonObject, expectedType: R2JobType?): R2JobPage {
    var interpretable = raw.keys.all { it == "jobs" || it == "nextContinuationToken" }
    val jobsRaw = raw["jobs"] as? JsonArray
    if (jobsRaw == null) interpretable = false
    val tokenRaw = raw["nextContinuationToken"]
    val token = (tokenRaw as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (raw.containsKey("nextContinuationToken")) {
        if (token == null || token.isEmpty() || token.length > R2_JOB_MAX_TOKEN_CHARS || token.any { it.code !in 0x21..0x7E }) {
            interpretable = false
        }
    }
    val jobs = jobsRaw.orEmpty().map { element ->
        val entry = element as? JsonObject
        if (entry == null) {
            interpretable = false
            R2BucketJob(null, null, null, null, null, null, false, R2_JOB_READ_ONLY_UNKNOWN)
        } else {
            decodeR2BucketJob(entry, expectedType = expectedType).also { if (!it.interpretable) interpretable = false }
        }
    }
    return R2JobPage(
        jobs = jobs,
        nextContinuationToken = token,
        interpretable = interpretable,
        readOnlyReason = if (interpretable) null else R2_JOB_READ_ONLY_UNKNOWN,
    )
}

/**
 * A poll locator for a trustworthy same-kind job, or null.
 *
 * The ID must be provider identity, the job must be interpretable and its kind/details must match
 * the submitted request. A trusted receipt is still only a read-only locator; it is never a command.
 */
internal fun r2JobLocatorOrNull(target: R2WriteTarget, request: R2JobCreateRequest, job: R2BucketJob?): R2JobLocator? {
    if (job == null || !job.interpretable) return null
    if (job.jobType != expectedTypeFor(request)) return null
    val id = job.id ?: return null
    if (!isTrustedR2JobId(id)) return null
    return R2JobLocator(target, id)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
