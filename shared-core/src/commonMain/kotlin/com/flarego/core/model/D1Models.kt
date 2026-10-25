package com.flarego.core.model

import com.flarego.core.application.d1.D1Validation
import com.flarego.core.application.d1.D1ResultSummary
import com.flarego.core.ports.D1Content
import kotlinx.serialization.json.JsonObject

const val D1_MAX_IMPORT_BYTES = 5_368_709_120L
const val D1_EDITOR_BYTES = 65_536
const val D1_BROWSE_PAGE_SIZE = 50

data class D1DatabaseRef(val account: AccountScope, val id: String)
enum class D1Jurisdiction { eu, fedramp, us }
enum class D1Location { wnam, enam, weur, eeur, apac, oc }
enum class D1Replication { auto, disabled }
data class D1CreateInput(
    val name: String,
    val jurisdiction: D1Jurisdiction? = null,
    val location: D1Location? = null,
    val replication: D1Replication? = null,
)
data class D1PageRequest(val page: Int = 1, val perPage: Int = 50, val name: String? = null)

/** Details stay in the private foreground owner; unknown provider fields are retained. */
class D1Database internal constructor(
    val ref: D1DatabaseRef,
    val name: String,
    val fileSize: Long?,
    val replication: D1Replication?,
    internal val details: JsonObject,
) {
    fun <T> withPrivateDetails(use: (String) -> T): T = use(details.toString())
    override fun toString() = "D1Database(ref=$ref)"
}

class D1ResultDocument(val content: D1Content, val summary: D1ResultSummary) : AutoCloseable {
    override fun close() = content.close()
    override fun toString() = "D1ResultDocument(summary=$summary)"
}

sealed interface D1ImportRequest {
    class Init(internal val etag: String) : D1ImportRequest { override fun toString() = "D1ImportInit(redacted)" }
    class Ingest(internal val etag: String, internal val filename: String) : D1ImportRequest { override fun toString() = "D1ImportIngest(redacted)" }
    class Poll(internal val bookmark: String) : D1ImportRequest { override fun toString() = "D1ImportPoll(redacted)" }
}

enum class D1FileStatus { PROCESSING, COMPLETE, ERROR, UPLOAD }
class D1UploadLocation internal constructor(internal val filename: String, private val url: String, val database: D1DatabaseRef) {
    fun <T> withUrl(use: (String) -> T): T = use(url)
    override fun toString() = "D1UploadLocation(redacted)"
}
class D1DownloadLocation internal constructor(internal val filename: String, private val url: String, val database: D1DatabaseRef) {
    fun <T> withUrl(use: (String) -> T): T = use(url)
    override fun toString() = "D1DownloadLocation(redacted)"
}
/** Only locators from a decoded response can enter the file pipeline. */
class D1FileReceipt internal constructor(
    val status: D1FileStatus,
    internal val bookmark: String?,
    val upload: D1UploadLocation? = null,
    val download: D1DownloadLocation? = null,
    internal val details: JsonObject,
    val database: D1DatabaseRef,
) {
    override fun toString() = "D1FileReceipt(status=$status)"
}

sealed interface D1RestorePoint {
    class Bookmark(internal val value: String) : D1RestorePoint { override fun toString() = "D1RestoreBookmark(redacted)" }
    class Timestamp(internal val value: String) : D1RestorePoint { override fun toString() = "D1RestoreTimestamp(redacted)" }
}
class D1RestoreReceipt internal constructor(val bookmark: String, val previousBookmark: String?) {
    override fun toString() = "D1RestoreReceipt(redacted)"
}

/** SQL and parameters are owner-only, absent from public state and task summaries. */
class D1Statement(sql: String, params: List<String> = emptyList()) {
    internal val sql = D1Validation.sql(sql)
    internal val params = D1Validation.params(params)
    override fun toString() = "D1Statement(redacted)"
}

class D1ExportOptions(tables: List<String> = emptyList(), val noData: Boolean = false, val noSchema: Boolean = false) {
    internal val tables = tables.toList()
    val allTables get() = tables.isEmpty()
    override fun toString() = "D1ExportOptions(redacted)"
}

/** Closed factories, never a constructor accepting a caller's SQL or expression. */
class D1BrowseRequest private constructor(internal val statement: D1Statement) {
    override fun toString() = "D1BrowseRequest(redacted)"
    companion object {
        fun schema(page: Int = 0) = D1BrowseRequest(D1Statement(
            "SELECT type,name,tbl_name,sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' AND name != '_cf_KV' ORDER BY type,name LIMIT 50 OFFSET ${offset(page)}",
        ))
        fun columns(table: String) = D1BrowseRequest(D1Statement("SELECT * FROM pragma_table_info(?)", listOf(D1Validation.identifier(table))))
        fun indexes(table: String) = D1BrowseRequest(D1Statement("SELECT * FROM pragma_index_list(?)", listOf(D1Validation.identifier(table))))
        fun rows(table: String, page: Int = 0, filterColumn: String? = null, filterValue: String? = null, sortColumn: String? = null, descending: Boolean = false): D1BrowseRequest {
            if ((filterColumn == null) != (filterValue == null)) throw invalidInputFailure()
            val where = filterColumn?.let { " WHERE ${quote(it)} = ?" }.orEmpty()
            val order = sortColumn?.let { " ORDER BY ${quote(it)} ${if (descending) "DESC" else "ASC"}" }.orEmpty()
            return D1BrowseRequest(D1Statement("SELECT * FROM ${quote(table)}$where$order LIMIT 50 OFFSET ${offset(page)}", filterValue?.let { listOf(it) }.orEmpty()))
        }
        private fun offset(page: Int): Long {
            if (page < 0) throw invalidInputFailure()
            return page.toLong() * D1_BROWSE_PAGE_SIZE
        }
        private fun quote(identifier: String) = "\"${D1Validation.identifier(identifier).replace("\"", "\"\"")}\""
    }
}
