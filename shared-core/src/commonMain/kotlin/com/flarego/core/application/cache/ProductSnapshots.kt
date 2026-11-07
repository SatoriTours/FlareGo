package com.flarego.core.application.cache

import com.flarego.core.application.inventory.*
import com.flarego.core.model.*
import com.flarego.core.ports.WorkersDirectoryMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Explicit metadata allowlist, not serialization of API response bodies. Billing is excluded. */
class ProductSnapshots(private val store: ReadSnapshotStore) {
    private fun key(product: ProductKey, mode: WorkersDirectoryMode) = "directory-v1/${product.name}/${if(product == ProductKey.WORKERS) mode.name else "default"}"
    suspend fun restore(account: AccountScope, product: ProductKey, mode: WorkersDirectoryMode, revision: Long, now: Long): ProductLoadState? = safely {
        if (product == ProductKey.BILLING) return@safely null
        val snapshot = store.read(account, key(product, mode))?.takeIf { it.usable(now) } ?: return@safely null
        val json = Json.parseToJsonElement(snapshot.payload).jsonObject
        val rows = json.getValue("items").jsonArray.map { it.jsonObject }
        val content = if (product == ProductKey.ZONES) ProductContent.Zones(rows.map {
            Domain(it.text("id"), it.text("name"), it.text("status"), account.connectionId)
        }) else ProductContent.Resources(rows.map {
            CloudResource(it.text("id"), it.text("name"), product.kind(), it.text("detail"),
                it.optional("usage"), it.optional("source"), it["legacy"]?.jsonPrimitive?.boolean ?: false,
                ResourceRef(account, it.text("refKind"), it.text("externalId")), it.optional("deployedOn"))
        })
        // Continuations are session-local. Revalidate the first page before requesting more.
        ProductLoadState(content, null, json["total"]?.jsonPrimitive?.longOrNull, false, null, account,
            revision, snapshot.observedAtMs, false, fromCache = true)
    }
    suspend fun save(state: ProductLoadState, product: ProductKey, mode: WorkersDirectoryMode, isCurrent: () -> Boolean = { true }) {
        if (product == ProductKey.BILLING || state.observedAtMs == null) return
        safely {
            val old = if(state.allPagesLoaded) null else restore(state.scope, product, mode, state.credentialRevision, state.observedAtMs)?.content
            val items = when(val content = state.content) {
                is ProductContent.Zones -> merge((old as? ProductContent.Zones)?.items.orEmpty(), content.items) { it.id }.map { d -> buildJsonObject {
                    put("id", d.id); put("name", d.name); put("status", d.status)
                } }
                is ProductContent.Resources -> merge((old as? ProductContent.Resources)?.items.orEmpty(), content.items) { it.id }.map { r ->
                    val ref = r.resourceRef ?: return@safely null
                    if(ref.scope != state.scope) return@safely null
                    buildJsonObject {
                        put("id", r.id); put("name", r.name); put("detail", r.detail); put("usage", r.usage)
                        put("source", r.source); put("legacy", r.legacy); put("deployedOn", r.deployedOn)
                        put("refKind", ref.kind); put("externalId", ref.externalId)
                    }
                }
                is ProductContent.Billing -> return@safely null
            }
            store.write(state.scope, key(product, mode), ReadSnapshot(buildJsonObject {
                put("items", JsonArray(items)); put("total", state.total)
            }.toString(), state.observedAtMs),isCurrent)
        }
    }
    suspend fun removeConnection(id: String) { safely { store.removeConnection(id) } }
}
internal suspend fun <T> safely(block: suspend () -> T): T? = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optional(key: String) = get(key)?.jsonPrimitive?.contentOrNull
