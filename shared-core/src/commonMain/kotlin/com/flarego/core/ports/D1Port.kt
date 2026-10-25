package com.flarego.core.ports

import com.flarego.core.application.d1.D1SqlInput
import com.flarego.core.model.*

interface D1Session {
    suspend fun listDatabases(account: AccountScope, page: D1PageRequest = D1PageRequest()): CloudPage<D1Database>
    suspend fun getDatabase(database: D1DatabaseRef): D1Database
    suspend fun createDatabase(account: AccountScope, input: D1CreateInput): D1Database
    suspend fun updateDatabase(database: D1DatabaseRef, replication: D1Replication): D1Database
    suspend fun patchDatabase(database: D1DatabaseRef, replication: D1Replication?): D1Database
    suspend fun deleteDatabase(database: D1DatabaseRef)
    suspend fun undeleteDatabase(database: D1DatabaseRef, newName: String? = null): D1Database
    suspend fun query(database: D1DatabaseRef, input: D1SqlInput): D1ResultDocument
    suspend fun raw(database: D1DatabaseRef, input: D1SqlInput): D1ResultDocument
    suspend fun browse(database: D1DatabaseRef, request: D1BrowseRequest): D1ResultDocument
    suspend fun importDatabase(database: D1DatabaseRef, request: D1ImportRequest): D1FileReceipt
    suspend fun exportDatabase(database: D1DatabaseRef, options: D1ExportOptions): D1FileReceipt
    suspend fun pollExport(database: D1DatabaseRef, bookmark: String): D1FileReceipt
    suspend fun getBookmark(database: D1DatabaseRef, timestamp: String? = null): String
    suspend fun restore(database: D1DatabaseRef, point: D1RestorePoint): D1RestoreReceipt
}
