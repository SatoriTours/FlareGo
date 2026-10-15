package com.flarego

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flarego.core.application.AppController
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.inventory.CloudflareDirectoryClient
import com.flarego.core.cloudflare.billing.CloudflareBillingClient
import com.flarego.core.cloudflare.identity.CloudflareCapabilityClient
import com.flarego.core.demo.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import com.flarego.core.cloudflare.CloudflareSession
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.ports.*
import com.flarego.core.model.*
import com.flarego.core.cloudflare.identity.CloudflareIdentityClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.updates.*
import com.flarego.platform.*
import com.flarego.ui.ConnectionDraft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FlareGoViewModel @JvmOverloads constructor(
    application: Application,
    private val operationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val transportForToken: (String) -> HttpTransport = { CloudflareTransport(it) },
) : AndroidViewModel(application) {
    val updater =
        UpdateController(
            viewModelScope,
            GitHubUpdateService(File(application.cacheDir, "updates")),
            AndroidUpdateStore(application),
            installedCode(application),
            application.packageManager
                .getPackageInfo(application.packageName, 0)
                .versionName
                .orEmpty(),
            System::currentTimeMillis,
        )
    var permissionRequestPending = false
    var pendingInstall: Pair<String, AppUpdate>? = null

    private fun installedCode(application: Application): Int {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else {
            @Suppress("DEPRECATION") info.versionCode
        }
    }

    /** One database owner for the whole app; both stores share it. */
    private val localDatabase = LocalDatabase.open(application)
    private val store = localDatabase.localStore

    /** Recoverable task metadata, consumed by the operation lifecycle work. */
    val operationStore = localDatabase.operationStore
    private val vault = TokenVault(application)
    val connectionDraft = ConnectionDraft()
    private val credentialRevisions = ConcurrentHashMap<String, Long>()
    private fun credentialRevision(id: String) = credentialRevisions[id] ?: 0L
    private lateinit var controllerRef: AppController
    private suspend fun gateway(connection: Connection): CloudflareGateway {
        val token = try { withContext(Dispatchers.IO) { vault.get(connection.id) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (token == null) throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED,"本机凭据无法解密，请断开后重新连接账号")
        return CloudflareGateway(transportForToken(token), nowMs = System::currentTimeMillis,
            parseHttpDate = CloudflareHttpDates::parse)
    }
    private val sessionFactory = SessionFactory { connection ->
        if (connection.demo) DemoSession(connection)
        else {
            val gateway = gateway(connection)
            // The legacy DNS adapter uses the same clock-aware gateway. Its Unit receipt remains unknown.
            CloudflareSession(connection, HttpTransport { error("Requests must use the gateway") }, gateway)
        }
    }
    private val operationRuntime = AppOperationRuntime(viewModelScope,operationStore,operationDispatcher,
        { controllerRef.state.value }, { controllerRef.operationEpoch }, ::credentialRevision,
        sessionFactory,AndroidFingerprint(),OperationClock(System::currentTimeMillis),
        OperationIds { UUID.randomUUID().toString() }, { controllerRef.publishTasks(it) })
    val controller = AppController(viewModelScope,sessionFactory,store,store.connections(),
        dnsOperations = operationRuntime,
        productFactory = ProductSessionFactory { connection ->
            if (connection.demo) {
                ProductServices(DemoDirectory(connection),object : ScopedBillingPort {
                    override suspend fun history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry> {
                        ScopeGuard.requireOwned(scope,connection)
                        if (scope !is AccountScope || request.token != null) throw CloudFailure(CloudFailureCode.INVALID_INPUT,"演示账单仅提供一页示例")
                        val entries = DemoSession(connection).billing().invoices.map { bill ->
                            BillingEntry(bill.id,scope,bill.date,bill.amount,bill.currency,"示例 · ${bill.description}",null,null,null)
                        }
                        return CloudPage(entries.take(request.limit),null,entries.size.toLong())
                    }
                },object : CapabilityPort {
                    override suspend fun status(scope: CloudScope, actionKey: String): CapabilityStatus {
                        ScopeGuard.requireOwned(scope,connection)
                        return CapabilityStatus(actionKey,scope,availability = CapabilityAvailability.UNKNOWN,reason = "显式演示连接")
                    }
                })
            } else {
                val gateway = gateway(connection)
                ProductServices(CloudflareDirectoryClient(connection,gateway),CloudflareBillingClient(connection,gateway),
                    CloudflareCapabilityClient(connection,gateway,System::currentTimeMillis),
                    CloudflareDirectoryClient(connection,gateway,WorkersDirectoryMode.LEGACY))
            }
        }, credentialRevisionQuery = ::credentialRevision, nowMs = System::currentTimeMillis,
    ).also { controllerRef = it }

    init { viewModelScope.launch { operationRuntime.recover() } }
    suspend fun reconcileTask(id: String) = operationRuntime.reconcile(id)

    private fun identity(token: String): CloudflareIdentityClient {
        val cleanToken = token.trim()
        if (cleanToken.isBlank() || cleanToken.any(Char::isWhitespace))
            throw CloudException("请检查 Token；Token 不能包含空格或换行")
        return CloudflareIdentityClient(CloudflareGateway(transportForToken(cleanToken),
            nowMs = System::currentTimeMillis, parseHttpDate = CloudflareHttpDates::parse))
    }

    suspend fun discoverAccounts(token: String, request: PageRequest): CloudPage<AccountSummary> =
        identity(token).discover(request)

    suspend fun verifyDraftToken(token: String, kind: TokenKind, accountId: String?): TokenVerification {
        val account = accountId?.trim()?.lowercase()
        if (kind == TokenKind.ACCOUNT_OWNED && account?.matches(Regex("[a-f0-9]{32}")) != true)
            throw CloudException("Account ID 必须为 32 位十六进制字符")
        return identity(token).verifyToken(kind, account)
    }

    suspend fun connect(name: String, accountId: String, token: String) {
        val cleanAccount = accountId.trim().lowercase()
        val cleanToken = token.trim()
        if (!cleanAccount.matches(Regex("[a-f0-9]{32}")))
            throw CloudException("Account ID 必须为 32 位十六进制字符")
        if (
            name.trim().length !in 1..80 ||
                cleanToken.isBlank() ||
                cleanToken.any(Char::isWhitespace)
        )
            throw CloudException("请检查账号名称和 Token；Token 不能包含空格或换行")
        val existing = store.connections().find { it.accountId == cleanAccount }
        val connection =
            Connection(existing?.id ?: UUID.randomUUID().toString(), name.trim(), cleanAccount)
        identity(cleanToken).verifyAccount(cleanAccount)
        credentialRevisions[connection.id] = credentialRevision(connection.id) + 1
        controller.invalidateSession(connection.id)
        withContext(Dispatchers.IO) { vault.put(connection.id, cleanToken) }
        store.putConnection(connection)
        controller.reloadConnections(store.connections())
        controller.selectConnection(connection)
    }

    override fun onCleared() {
        connectionDraft.clear()
        localDatabase.close()
        super.onCleared()
    }

    fun disconnect(connection: Connection) {
        credentialRevisions[connection.id] = credentialRevision(connection.id) + 1
        controller.invalidateSession(connection.id)
        // Main stays free while a metadata writer releases the shared latch after IO.
        viewModelScope.launch {
            try {
                operationRuntime.removeConnection(connection.id) {
                    vault.remove(connection.id)
                    operationStore.removeConnection(connection.id)
                    store.removeConnection(connection.id)
                }
                controller.reloadConnections(store.connections())
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { controller.reportDisconnectFailure() }
        }
    }
}
