package com.flarego.core.application.dns

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** App-owned plans/coordinator/store are injected. The app must also hold its one global write lock. */
@OptIn(ExperimentalAtomicApi::class)
class DnsFileController(private val resolver: OperationContextResolver, private val plans: ChangePlans,
    private val coordinator: OperationCoordinator, private val clock: OperationClock, private val fingerprint: FingerprintPort,
    private val store: OperationStore, private val storeDispatcher: CoroutineDispatcher,
    private val files: DnsFileStore, private val fileDispatcher: CoroutineDispatcher,
    private val uncertaintyGate: DnsUncertaintyGate = DnsUncertaintyGate(store, storeDispatcher),
    private val session: suspend (ZoneScope) -> DnsFilesSession) {
    private data class Prepared(val plan: ChangePlan, val file: DnsFileRef, val proxied: Boolean,
        val live: LiveOperationContext, val before: DnsFileImportState, val preview: DnsImportPreview,
        val notSent: AtomicReference<CloudFailure?> = AtomicReference(null))
    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())
    fun requestFingerprint(scope: ZoneScope, file: DnsFileRef, proxied: Boolean): String {
        if (scope.zoneId.length > 32 || file.id.isBlank() || file.displayName.isBlank() || file.length !in 1..DNS_IMPORT_MAX_BYTES ||
            !Regex("[0-9a-fA-F]{64}").matches(file.sha256)) throw invalidInputFailure()
        return digest(buildJsonObject {
            put("connection", scope.connectionId); put("account", scope.account.accountId); put("zone", scope.zoneId)
            put("action", DNS_IMPORT_OPERATION); put("file_id", file.id); put("name", file.displayName)
            put("length", file.length); put("sha256", file.sha256); put("proxied", proxied)
        })
    }
    suspend fun prepareImport(scope: ZoneScope, file: DnsFileRef, proxied: Boolean): ChangePlan {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != scope || live.requestFingerprint != requestFingerprint(scope, file, proxied)) throw invalidInputFailure()
        sameLive(live)
        uncertaintyGate.requireClear(scope, DNS_IMPORT_OPERATION, ref(scope))
        val snapshot = readDnsImport(files.open(file), file.length, file.sha256, fingerprint, fileDispatcher)
        sameLive(live)
        val api = session(scope); sameLive(live)
        val before = checked(api.observe(scope), scope)
        writable(before); sameLive(live)
        val plan = plans.prepare(scope, DNS_IMPORT_OPERATION, ref(scope), stateHash(before), null,
            requestFingerprint(scope, file, proxied), live.credentialRevision, clock.nowMs())
        val p = Prepared(plan, file, proxied, live, before, DnsImportPreview(scope, before.owner.name, file, proxied, null, snapshot.hints()))
        while (true) { val table = prepared.load(); if (prepared.compareAndSet(table, table + (plan.id to p))) break }
        return plan
    }
    fun preview(planId: String) = entry(planId).preview.let { it.copy(warnings = it.warnings.toList()) }
    suspend fun submitImport(scope: ZoneScope, file: DnsFileRef, proxied: Boolean, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        bound(p, scope, file, proxied, confirmed)
        uncertaintyGate.requireClear(scope, DNS_IMPORT_OPERATION, ref(scope), p.plan.id)
        bound(p, scope, file, proxied, confirmed)
        val guard = { bound(p, scope, file, proxied, confirmed) }
        val executed = coordinator.executeGuarded(confirmed, guard) { composed ->
            var admittedSend = false
            try {
                val api = session(scope); guard()
                uncertaintyGate.requireClear(scope, DNS_IMPORT_OPERATION, ref(scope), p.plan.id)
                api.writeImport(scope, { files.open(p.file) }, p.file.length, p.file.sha256, p.proxied, { current ->
                    val actual = checked(current, scope)
                    if (stateHash(actual) != p.plan.beforeHash) throw CloudFailure(CloudFailureCode.CONFLICT, "Zone 或导入权限已变化，请重新预览确认")
                    writable(actual); composed()
                    // Typed session contract: quota/file/owner preflight is complete; next step is the one send.
                    admittedSend = true
                }, guard).receipt()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                // Only a typed pre-send rejection proves NotApplied. Generic faults, cancellation and
                // anything after admission retain uncertainty; HTTP failures cannot settle this latch.
                if (!admittedSend && failure is CloudFailure) p.notSent.store(failure)
                ExecutionReceipt.Unknown()
            }
        }
        p.notSent.load()?.let { rejected ->
            try { coordinator.reconcile(executed.id) { Reconciliation.NotApplied } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Fail closed if the original context can no longer settle metadata. */ }
            throw rejected
        }
        return withContext(storeDispatcher) { store.get(executed.id) } ?: executed
    }
    suspend fun exportRecords(scope: ZoneScope, target: DnsExportTarget): DnsExportResult {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != scope) throw invalidInputFailure()
        sameLive(live)
        val tx = files.beginExport(target); var committed = false
        try {
            sameLive(live); val api = session(scope); sameLive(live)
            val head = api.exportRecords(scope, tx.sink); sameLive(live)
            val file = withContext(fileDispatcher) { sameLive(live); tx.commit() }
            committed = true
            return DnsExportResult(scope, file, head)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: CloudFailure) { throw failure }
        catch (_: Exception) { throw dnsFileIoFailure() }
        finally { if (!committed) withContext(NonCancellable + fileDispatcher) { runCatching { tx.abort() } } }
    }
    /** Inventory counts are not semantic proof of an import. Never recover a file or confirmation. */
    suspend fun reconcile(taskId: String): OperationTask {
        val initial = withContext(storeDispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        val live = resolver.current() ?: throw invalidInputFailure()
        val scope = initial.scope as? ZoneScope ?: throw invalidInputFailure()
        if (initial.actionKey != DNS_IMPORT_OPERATION || live.scope != scope) throw invalidInputFailure()
        sameLive(live)
        if (initial.state == TaskState.SUCCEEDED) return initial
        suspend fun read(task: OperationTask) {
            if (task.resourceRef != ref(scope)) return
            try { val api = session(scope); sameLive(live); checked(api.observe(scope), scope); api.readback(scope) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Remains unknown/partial; no receipt or executable file is restored. */ }
            sameLive(live)
        }
        if (initial.state == TaskState.PARTIAL) {
            read(initial); sameLive(live)
            if (withContext(storeDispatcher) { store.get(taskId) } != initial) throw invalidInputFailure()
            sameLive(live); return initial
        }
        return coordinator.reconcile(taskId) { task -> read(task); sameLive(live); Reconciliation.StillUnknown }
    }
    private fun entry(id: String) = prepared.load()[id] ?: throw invalidInputFailure()
    private fun bound(p: Prepared, scope: ZoneScope, file: DnsFileRef, proxied: Boolean, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan || file != p.file || proxied != p.proxied || p.plan.scope != scope || p.plan.actionKey != DNS_IMPORT_OPERATION ||
            p.plan.resourceRef != ref(scope) || p.plan.beforeHash != stateHash(p.before) || p.plan.desiredHash != null ||
            p.plan.requestFingerprint != requestFingerprint(scope, file, proxied) || p.plan.credentialRevision != p.live.credentialRevision) throw invalidInputFailure()
        sameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }
    private fun sameLive(live: LiveOperationContext) {
        if (resolver.current() != live || !resolver.isLive(live.scope, live.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(live.scope, live.connection)
    }
    private fun checked(state: DnsFileImportState, scope: ZoneScope): DnsFileImportState {
        val raw = Json.parseToJsonElement(state.owner.raw.toString()).jsonObject
        val account = (raw["account"] as? JsonObject)?.get("id") as? JsonPrimitive
        val id = raw["id"] as? JsonPrimitive; val name = raw["name"] as? JsonPrimitive
        if (state.owner.ref != scope || account?.isString != true || account.content != scope.account.accountId || id?.isString != true ||
            id.content != scope.zoneId || name?.isString != true || name.content != state.owner.name) throw scopeMismatchFailure()
        return state.copy(owner = state.owner.copy(raw = raw, nameservers = state.owner.nameservers.toList()))
    }
    private fun writable(state: DnsFileImportState) {
        if (state.writeAllowed != true) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "DNS 导入写权限未知或不可用，请先验证 Token 的 Zone 权限")
    }
    private fun stateHash(state: DnsFileImportState) = digest(buildJsonObject { put("owner", state.owner.raw); put("write_allowed", state.writeAllowed?.let(::JsonPrimitive) ?: JsonNull) })
    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))
    private fun ref(scope: ZoneScope) = ResourceRef(scope, "dns_import", scope.zoneId)
}

/** Small immutable snapshot of verified bytes; original stream is closed before this escapes. */
internal class DnsImportSnapshot(bytes: ByteArray, val sha256: String) {
    private val data = bytes.copyOf()
    val length: Long get() = data.size.toLong()
    fun source(): ByteSource = object : ByteSource {
        private var position = 0
        private var closed = false
        override fun read(buffer: ByteArray): Int {
            if (closed || position == data.size) return -1
            val count = minOf(buffer.size, data.size - position)
            data.copyInto(buffer, 0, position, position + count); position += count; return count
        }
        override fun close() { closed = true }
    }
    fun hints(): List<String> {
        val directives = Regex("(?m)^[ \\t]*\\$([A-Za-z_]+)(?:[ \\t\\r]|$)").findAll(data.decodeToString()).map { it.groupValues[1].uppercase() }.toSet()
        if ("INCLUDE" in directives) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "Cloudflare 不支持 BIND $" + "INCLUDE 指令，请自行处理后重新选择文件")
        return buildList {
            add("记录数未知：没有完整 BIND parser 或真实 dry-run API，不承诺逐记录预检")
            add("官方执行可能部分导入；回执/记录总数不能证明每条语义一致，不要自动重导")
            add("$" + "ORIGIN、$" + "TTL、$" + "GENERATE 和标签按原字节保留；$" + "INCLUDE 不受供应商支持")
            add("cf-proxied 标签会覆盖一般 proxied 选项，本应用不改写文件标签")
            if ((directives - setOf("ORIGIN", "TTL", "GENERATE")).isNotEmpty()) add("存在未知指令，未验证其语法或展开记录数")
            add("供应商无 CAS；读取与发送之间仍有外部并发竞争窗口")
        }
    }
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal class DnsOwnedSource(private val source: ByteSource) : ByteSource {
    private val closed = kotlin.concurrent.atomics.AtomicReference(false)
    override fun read(buffer: ByteArray) = source.read(buffer)
    override fun close() { if (closed.compareAndSet(false, true)) source.close() }
}

/** Validates exact bytes on IO, bounded to 262144; closes even when already cancelled. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal suspend fun readDnsImport(source: ByteSource, length: Long, expected: String, hash: FingerprintPort,
    dispatcher: CoroutineDispatcher): DnsImportSnapshot {
    val owned = DnsOwnedSource(source)
    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
    val cancelled = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) runCatching { owned.close() }
    }
    var failed = false
    try {
        return withContext(dispatcher) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (length !in 1..DNS_IMPORT_MAX_BYTES || !Regex("[0-9a-fA-F]{64}").matches(expected)) throw invalidInputFailure()
            val data = ByteArray(DNS_IMPORT_MAX_BYTES.toInt() + 1); val buffer = ByteArray(8192); var count = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val n = owned.read(buffer)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (n == -1) break
                if (n <= 0 || n > buffer.size || count + n > DNS_IMPORT_MAX_BYTES) throw invalidInputFailure()
                buffer.copyInto(data, count, 0, n); count += n
            }
            val bytes = data.copyOf(count); val actual = hash.sha256(bytes)
            if (count.toLong() != length || actual != expected) throw CloudFailure(CloudFailureCode.CONFLICT, "文件长度或指纹已变化，请重新选择文件并预览确认")
            DnsImportSnapshot(bytes, actual).also { it.hints() }
        }
    } catch (cancel: CancellationException) { failed = true; throw cancel }
    catch (failure: CloudFailure) { failed = true; throw failure }
    catch (_: Exception) { failed = true; throw dnsFileIoFailure() }
    finally {
        cancelled?.dispose()
        try { withContext(kotlinx.coroutines.NonCancellable + dispatcher) { owned.close() } }
        catch (_: Exception) { if (!failed) throw dnsFileIoFailure() }
    }
}
internal fun dnsFileIoFailure() = CloudFailure(CloudFailureCode.UNAVAILABLE, "文件读写失败，请检查权限和存储空间后重新选择文件")
