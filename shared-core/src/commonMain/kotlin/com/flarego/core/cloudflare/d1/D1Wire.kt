package com.flarego.core.cloudflare.d1

import com.flarego.core.application.d1.D1Validation
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.model.*
import kotlinx.serialization.json.*

internal object D1Wire {
    fun database(value: JsonElement?, account: AccountScope, writing: Boolean): D1Database {
        val row = value as? JsonObject ?: throw invalidResponse(writing)
        val id = string(row["uuid"], writing)
        val name = string(row["name"], writing)
        try { D1Validation.databaseId(id); D1Validation.name(name) } catch (_: Exception) { throw invalidResponse(writing) }
        val size = row["file_size"]?.let {
            if (it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull?.takeIf { n -> n >= 0 } ?: throw invalidResponse(writing)
        }
        val replication = row["read_replication"]?.takeUnless { it == JsonNull }?.let {
            val mode = string((it as? JsonObject)?.get("mode"), writing)
            D1Replication.entries.find { item -> item.name == mode } ?: throw invalidResponse(writing)
        }
        return D1Database(D1DatabaseRef(account, id), name, size, replication, row)
    }

    fun fileReceipt(value: JsonElement?, importing: Boolean, database: D1DatabaseRef): D1FileReceipt {
        val row = value as? JsonObject ?: throw invalidResponse(true)
        val success = (row["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw invalidResponse(true)
        if (string(row["type"], true) != if (importing) "import" else "export") throw invalidResponse(true)
        val status = row["status"]?.let { string(it, true) }
        if (status != null && status !in setOf("complete", "error", "active") || !success && status != "error") throw invalidResponse(true)
        val bookmark = row["at_bookmark"]?.let { bookmark(it, true) }
        val filename = row["filename"]?.let { string(it, true) }
        val url = row["upload_url"]?.let { string(it, true) }
        if ((filename == null) != (url == null) || !importing && url != null) throw invalidResponse(true)
        val upload = url?.let { D1UploadLocation(filename!!, it, database) }
        val result = row["result"]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: throw invalidResponse(true) }
        if (status == "complete") {
            if (!success || row["error"]?.let { it != JsonNull } == true) throw invalidResponse(true)
            if (importing) {
                val completed = result ?: throw invalidResponse(true)
                bookmark(completed["final_bookmark"], true)
                completed["meta"]?.let { if (it !is JsonObject) throw invalidResponse(true) }
                completed["num_queries"]?.let {
                    val count = (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull
                    if (count == null || count < 0) throw invalidResponse(true)
                }
            }
        }
        val download = if (!importing && status == "complete") {
            val location = result ?: throw invalidResponse(true)
            D1DownloadLocation(string(location["filename"], true), string(location["signed_url"], true), database)
        } else null
        // A direct-start init response must retain the polling bookmark. Never infer an upload.
        val state = when {
            status == "error" -> D1FileStatus.ERROR
            status == "complete" -> D1FileStatus.COMPLETE
            upload != null -> D1FileStatus.UPLOAD
            bookmark != null -> D1FileStatus.PROCESSING
            else -> throw invalidResponse(true)
        }
        if (upload != null && status != null) throw invalidResponse(true)
        return D1FileReceipt(state, bookmark, upload, download, row, database)
    }

    fun bookmark(value: JsonElement?, writing: Boolean): String {
        val text = string(value, writing)
        try { D1Validation.bookmark(text) } catch (_: Exception) { throw invalidResponse(writing) }
        return text
    }
    private fun string(value: JsonElement?, writing: Boolean) =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: throw invalidResponse(writing)
}
