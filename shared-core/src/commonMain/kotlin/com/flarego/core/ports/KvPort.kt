package com.flarego.core.ports

import com.flarego.core.model.*

interface KvSession {
    suspend fun listNamespaces(account: AccountScope, page: KvNamespacePageRequest = KvNamespacePageRequest()): CloudPage<KvNamespace>
    suspend fun createNamespace(account: AccountScope, title: String): KvNamespace
    suspend fun getNamespace(namespace: KvNamespaceRef): KvNamespace
    suspend fun renameNamespace(namespace: KvNamespaceRef, title: String): KvWriteReceipt
    suspend fun deleteNamespace(namespace: KvNamespaceRef): KvWriteReceipt
    suspend fun listKeys(namespace: KvNamespaceRef, page: KvKeyPageRequest = KvKeyPageRequest()): CloudPage<KvKeyEntry>
    suspend fun readMetadata(target: KvKeyTarget): KvMetadata
    suspend fun readValue(target: KvKeyTarget): KvDownload
    suspend fun putValue(target: KvKeyTarget, input: KvValueInput): KvWriteReceipt
    suspend fun deleteValue(target: KvKeyTarget): KvWriteReceipt
    suspend fun bulkRead(namespace: KvNamespaceRef, input: KvBulkReadInput): KvDownload
    suspend fun bulkWrite(namespace: KvNamespaceRef, input: KvBulkWriteInput): KvWriteReceipt
    suspend fun bulkDelete(namespace: KvNamespaceRef, keys: List<String>): KvWriteReceipt
}
