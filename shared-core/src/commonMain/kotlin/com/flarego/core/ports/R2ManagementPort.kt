package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudPage
import com.flarego.core.model.PageRequest
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.ports.ByteSink

/**
 * Typed port for the reviewed R2 bucket control plane.
 *
 * Every method takes the account scope explicitly; the implementation proves ownership
 * with [com.flarego.core.model.ScopeGuard] before it builds a request. Reads keep the
 * provider document so unknown fields survive.
 */
interface R2ManagementPort {
    suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument>

    suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument

    /** `POST` variant: the JSON body carries the optional location hint and storage class. */
    suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument

    /** `PUT` variant: no body, bucket name from the path, optional storage class in a reviewed header. */
    suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument

    /** `PATCH` variant: no body; the required storage class travels in a reviewed header. */
    suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument

    suspend fun deleteBucket(account: AccountScope, name: String)

    suspend fun listObjects(
        account: AccountScope,
        bucket: String,
        filter: R2ObjectFilter = R2ObjectFilter(),
        page: PageRequest,
    ): CloudPage<R2ObjectEntry>

    /**
     * Streams one object into [sink]. The body never enters memory; on any failure the
     * caller discards its temporary target instead of publishing a partial file.
     */
    suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead
}

/**
 * Credential-bound typed adapter. The observed document is read before [beforeSend] and
 * is passed to it, so a confirmation can compare the frozen target with fresh provider
 * evidence. A create has no existing bucket and passes null. A suspension or session
 * acquisition must not happen between the guard and the single typed send.
 */
interface R2ManagementSession : R2ManagementPort {
    suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt
}
