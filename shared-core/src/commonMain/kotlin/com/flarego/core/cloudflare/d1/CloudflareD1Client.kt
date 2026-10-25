package com.flarego.core.cloudflare.d1

import com.flarego.core.application.d1.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Routing adapter. Production access is mediated by the separate authority issuer. */
class CloudflareD1Client(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val transfers: TransferPort,
    private val contents: D1ContentStore,
    private val beforeMutation: (String, AccountScope, String?) -> Unit,
) : D1Session {
    override suspend fun listDatabases(account: AccountScope, page: D1PageRequest): CloudPage<D1Database> {
        val base = collection(account)
        D1Validation.page(page)
        val paging = PaginationStrategy.Number(page.page, page.perPage)
        val query = Pagination.query(paging) + (page.name?.let { "&name=${encodePath(it)}" } ?: "")
        val root = read("d1-list-databases", "$base?$query")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { database(it, account, false) }
        if (items.map { it.ref.id.lowercase() }.distinct().size != items.size) throw invalidResponse(false)
        val metadata = Pagination.decode(root, paging)
        return CloudPage(items, metadata.next, metadata.total)
    }

    override suspend fun getDatabase(database: D1DatabaseRef): D1Database =
        database(read("d1-get-database", locator(database))["result"], database.account, false, database.id)

    override suspend fun createDatabase(account: AccountScope, input: D1CreateInput): D1Database {
        val path = collection(account)
        D1Validation.create(input)
        val body = buildJsonObject {
            put("name", input.name)
            input.jurisdiction?.let { put("jurisdiction", it.name) }
            input.location?.let { put("primary_location_hint", it.name) }
            input.replication?.let { put("read_replication", replication(it)) }
        }
        return database(write("d1-create-database", "POST", path, body, account, null)["result"], account, true)
    }

    override suspend fun updateDatabase(database: D1DatabaseRef, replication: D1Replication): D1Database =
        update(database, "d1-update-database", "PUT", replication)

    override suspend fun patchDatabase(database: D1DatabaseRef, replication: D1Replication?): D1Database =
        update(database, "d1-update-partial-database", "PATCH", replication)

    private suspend fun update(ref: D1DatabaseRef, op: String, method: String, mode: D1Replication?): D1Database {
        val path = locator(ref)
        val body = buildJsonObject { mode?.let { put("read_replication", replication(it)) } }
        return database(write(op, method, path, body, ref.account, ref.id)["result"], ref.account, true, ref.id)
    }

    override suspend fun deleteDatabase(database: D1DatabaseRef) {
        write("d1-delete-database", "DELETE", locator(database), null, database.account, database.id)
    }

    override suspend fun undeleteDatabase(database: D1DatabaseRef, newName: String?): D1Database {
        val path = locator(database)
        newName?.let(D1Validation::name)
        val body = buildJsonObject { newName?.let { put("new_name", it) } }
        return database(write("d1-undelete-database", "POST", "$path/undelete", body, database.account, database.id)["result"], database.account, true, database.id)
    }

    override suspend fun query(database: D1DatabaseRef, input: D1SqlInput): D1ResultDocument =
        sql(database, input, raw = false, writing = true)

    override suspend fun raw(database: D1DatabaseRef, input: D1SqlInput): D1ResultDocument =
        sql(database, input, raw = true, writing = true)

    override suspend fun browse(database: D1DatabaseRef, request: D1BrowseRequest): D1ResultDocument {
        locator(database)
        val input = D1SqlJson.fromStatements(listOf(request.statement), contents, batch = false)
        try { return sql(database, input, raw = false, writing = false) }
        finally { input.content.close() }
    }

    private suspend fun sql(ref: D1DatabaseRef, input: D1SqlInput, raw: Boolean, writing: Boolean): D1ResultDocument {
        val op = if (raw) "d1-raw-database-query" else "d1-query-database"
        val path = "${locator(ref)}/${if (raw) "raw" else "query"}"
        // Validate the same sealed file that will be sent, including every query/parameter.
        D1SqlJson.prepareJson(input.content)
        val source = input.content.openVerified()
        val writer = contents.writer(Long.MAX_VALUE)
        var sealed: D1Content? = null
        try {
            val request = HttpRequest("POST", path, HttpBody.Stream(source, input.content.descriptor.length, "application/json"))
            if (ActionPolicies.registry.registration(op)?.matches(request.method, request.path) != true) throw invalidInputFailure()
            if (writing) beforeMutation(op, ref.account, ref.id)
            // One attempt only. No SQL retry, including controlled read-only browsing.
            val head = withTimeout(30_000) { transfers.download(request, writer) }
            if (head.status != 200) {
                ResponseDecoders.json(HttpResponse(head.status, "null"), writing)
                throw invalidResponse(writing)
            }
            val content = writer.seal().also { sealed = it }
            if (content.descriptor.length != head.transferredBytes) throw invalidResponse(writing)
            val summary = try { D1ResultJson.validate(content, raw) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { throw invalidResponse(writing) }
            return D1ResultDocument(content, summary).also { sealed = null }
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: CloudFailure) { throw failure
        } catch (_: Exception) { throw unavailable(writing)
        } finally { source.close(); writer.abort(); sealed?.close() }
    }

    override suspend fun importDatabase(database: D1DatabaseRef, request: D1ImportRequest): D1FileReceipt {
        val body = buildJsonObject {
            when (request) {
                is D1ImportRequest.Init -> { put("action", "init"); put("etag", D1Validation.md5(request.etag)) }
                is D1ImportRequest.Ingest -> {
                    put("action", "ingest"); put("etag", D1Validation.md5(request.etag))
                    put("filename", D1Validation.identifier(request.filename))
                }
                is D1ImportRequest.Poll -> { put("action", "poll"); put("current_bookmark", D1Validation.bookmark(request.bookmark)) }
            }
        }
        val root = write("d1-import-database", "POST", "${locator(database)}/import", body, database.account, database.id)
        return D1Wire.fileReceipt(root["result"], importing = true, database = database)
    }

    override suspend fun exportDatabase(database: D1DatabaseRef, options: D1ExportOptions): D1FileReceipt {
        D1Validation.exportOptions(options)
        val body = buildJsonObject {
            put("output_format", "polling")
            put("dump_options", buildJsonObject {
                put("no_data", options.noData); put("no_schema", options.noSchema)
                put("tables", JsonArray(options.tables.map(::JsonPrimitive)))
            })
        }
        return export(database, body)
    }

    override suspend fun pollExport(database: D1DatabaseRef, bookmark: String): D1FileReceipt = export(database, buildJsonObject {
        put("output_format", "polling"); put("current_bookmark", D1Validation.bookmark(bookmark))
    })

    private suspend fun export(ref: D1DatabaseRef, body: JsonObject) = D1Wire.fileReceipt(
        write("d1-export-database", "POST", "${locator(ref)}/export", body, ref.account, ref.id)["result"], importing = false, database = ref,
    )

    override suspend fun getBookmark(database: D1DatabaseRef, timestamp: String?): String {
        val suffix = timestamp?.let { "?timestamp=${encodePath(D1Validation.timestamp(it))}" }.orEmpty()
        val root = read("d1-time-travel-get-bookmark", "${locator(database)}/time_travel/bookmark$suffix")
        val result = root["result"] as? JsonObject ?: throw invalidResponse(false)
        return D1Wire.bookmark(result["bookmark"], false)
    }

    override suspend fun restore(database: D1DatabaseRef, point: D1RestorePoint): D1RestoreReceipt {
        val query = when (point) {
            is D1RestorePoint.Bookmark -> "bookmark=${encodePath(D1Validation.bookmark(point.value))}"
            is D1RestorePoint.Timestamp -> "timestamp=${encodePath(D1Validation.timestamp(point.value))}"
        }
        val root = write("d1-time-travel-restore", "POST", "${locator(database)}/time_travel/restore?$query", null, database.account, database.id)
        val row = root["result"] as? JsonObject ?: throw invalidResponse(true)
        return D1RestoreReceipt(D1Wire.bookmark(row["bookmark"], true), row["previous_bookmark"]?.let { D1Wire.bookmark(it, true) })
    }

    private fun collection(account: AccountScope): String {
        ScopeGuard.requireOwned(account, connection)
        return "/accounts/${encodePath(account.accountId)}/d1/database"
    }
    private fun locator(ref: D1DatabaseRef) = "${collection(ref.account)}/${D1Validation.databaseId(ref.id)}"
    private fun replication(mode: D1Replication) = buildJsonObject { put("mode", mode.name) }
    private fun database(value: JsonElement?, account: AccountScope, writing: Boolean, expected: String? = null) =
        D1Wire.database(value, account, writing).also { if (expected != null && !it.ref.id.equals(expected, ignoreCase = true)) throw invalidResponse(writing) }

    private suspend fun read(op: String, path: String): JsonObject = exchange(op, HttpRequest("GET", path), false)
    private suspend fun write(op: String, method: String, path: String, body: JsonObject?, account: AccountScope, id: String?): JsonObject {
        val payload = body?.let { HttpBody.SensitiveJson(it.toString(), "application/json") } ?: HttpBody.Empty
        try { beforeMutation(op, account, id); return exchange(op, HttpRequest(method, path, payload), true) }
        finally { (payload as? HttpBody.SensitiveJson)?.discard() }
    }
    private suspend fun exchange(op: String, request: HttpRequest, writing: Boolean): JsonObject {
        try {
            val response = gateway.send(op, request)
            val root = ResponseDecoders.envelope(response, writing)
            val fileAction = op == "d1-import-database" || op == "d1-export-database"
            if ((response.status != 200 && !(fileAction && response.status == 202)) ||
                (root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(writing)
            return root
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: CloudFailure) { throw failure
        } catch (_: Exception) { throw unavailable(writing) }
    }
    private fun unavailable(writing: Boolean) = CloudFailure(CloudFailureCode.UNAVAILABLE, if (writing) "操作结果未知，请核对后处理" else "无法读取，请稍后重试", uncertain = writing)
}
