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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import com.flarego.core.cloudflare.identity.AuthorizedManagementSessions
import com.flarego.core.cloudflare.identity.AuthorizedDnsFilesDependencies
import com.flarego.core.application.management.ManagementPlatformPorts
import com.flarego.ui.management.ManagementPanelContext
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

class FlareGoViewModel constructor(
    application: Application,
    private val operationDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val transportForToken: (String) -> HttpTransport,
    private val transfersForToken: (String) -> TransferPort,
) : AndroidViewModel(application) {
    // Keep the original three-parameter/default/trailing-lambda constructor intact.
    // The explicit four-parameter constructor adds only the streaming test seam.
    @JvmOverloads constructor(application:Application,
        operationDispatcher:kotlinx.coroutines.CoroutineDispatcher=Dispatchers.IO,
        transportForToken:(String)->HttpTransport={CloudflareTransport(it)}):
        this(application,operationDispatcher,transportForToken,{CloudflareTransfers(it)})
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
    private val managementPlatformOwner = ManagementPlatformOwner.forApplication(application)
    private val managementBorrower = managementPlatformOwner.borrow()
    // Register persisted credentials once. Existing tombstones/transitions must remain unavailable.
    private val initialConnections=store.connections().also { connections ->
        connections.forEach { runCatching { managementPlatformOwner.registerExistingCredential(it) } }
    }
    private fun credentialRevision(id:String)=managementPlatformOwner.credentialRevision(id)
    private lateinit var controllerRef: AppController
    private suspend fun credentialBundle(connection:Connection):ManagementCredentialBundle {
        val bundle=managementPlatformOwner.acquireCredential(connection,
            loadToken={ withContext(operationDispatcher) { vault.get(connection.id) } },
            jsonForToken=transportForToken,transfersForToken=transfersForToken)
        managementPlatformOwner.requireCurrent(bundle)
        return bundle
    }
    private suspend fun gateway(connection:Connection)=credentialBundle(connection).gateway
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
        OperationIds { UUID.randomUUID().toString() }, { controllerRef.publishTasks(it) },
        managementFactory = ManagementSessionFactory { connection ->
            val bundle=credentialBundle(connection)
            AuthorizedManagementSessions(connection,bundle.gateway,bundle.issuer,AndroidFingerprint(),
                AuthorizedDnsFilesDependencies(bundle.transfers,managementPlatformOwner.importLimiter,operationDispatcher)).services()
        },managementPlatform=ManagementPlatformPorts(managementPlatformOwner.files,operationDispatcher))
    val management get()=operationRuntime.management
    private fun actualManagementContext():ExtendedAppContext? {
        if(!::controllerRef.isInitialized)return null
        val state=controllerRef.state.value
        val connection=state.selected?.takeIf { it in state.connections } ?: return null
        val stamp=managementPlatformOwner.currentCredentialStamp(connection) ?: return null
        val zone=management.zones.state.value.selected ?: return null
        val scope=zone.ref
        if(scope.connectionId!=connection.id || scope.account.accountId!=connection.accountId ||
            management.zones.state.value.account!=scope.account || zone.raw["id"]?.jsonPrimitive?.contentOrNull!=scope.zoneId ||
            (zone.raw["account"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull!=connection.accountId)return null
        return ExtendedAppContext(ManagementPanelContext(scope,controllerRef.operationEpoch),connection,stamp)
    }
    private val extendedAdapter=ExtendedManagementAppAdapter(viewModelScope,operationRuntime,::actualManagementContext,
        { managementPlatformOwner },{ managementBorrower },::selectDocument,::invalidateDocumentRequest)
    val extendedManagement:com.flarego.core.application.management.ExtendedManagementActions get()=operationRuntime
    val panelPorts get()=extendedAdapter.ports
    private val panelContextState=MutableStateFlow<ManagementPanelContext?>(null)
    val panelContexts:StateFlow<ManagementPanelContext?> = panelContextState.asStateFlow()
    val panelContext:ManagementPanelContext? get()=panelContextState.value
    private val documentPicker by lazy { DnsDocumentPicker(application) { extendedAdapter.documentRequestStamp() } }
    private val documentLaunchState=MutableStateFlow<DnsDocumentLaunch?>(null)
    val documentLaunches:StateFlow<DnsDocumentLaunch?> = documentLaunchState.asStateFlow()
    private var pendingDocumentStamp:DnsDocumentRequestStamp?=null
    private var pendingDocumentKind:DnsDocumentKind?=null
    private fun invalidateDocumentRequest() {
        documentPicker.invalidate()
        // An unlaunched request has no Activity result to drain. A launched flight remains single-flight until returned.
        documentLaunchState.value?.let { event ->
            documentPicker.parse(event.kind,android.app.Activity.RESULT_CANCELED,null)
            documentLaunchState.value=null;pendingDocumentStamp=null;pendingDocumentKind=null
        }
    }
    private fun selectDocument(kind:DnsDocumentKind) {
        val stamp=extendedAdapter.documentRequestStamp() ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT,"管理输入或确认已失效，请重新选择并预览")
        val launch=documentPicker.begin(kind,stamp)
        pendingDocumentStamp=stamp;pendingDocumentKind=kind
        documentLaunchState.value=launch
    }
    fun takeDocumentLaunch(requestId:String):DnsDocumentLaunch? {
        val event=documentLaunchState.value?.takeIf { it.requestId==requestId } ?: return null
        documentLaunchState.value=null
        return event
    }
    fun onDocumentResult(kind:DnsDocumentKind,resultCode:Int,data:android.content.Intent?) {
        val captured=pendingDocumentStamp?.takeIf { pendingDocumentKind==kind } ?: return
        pendingDocumentStamp=null;pendingDocumentKind=null
        try {
            val selected=documentPicker.parse(kind,resultCode,data)
            if(selected==null) {
                if(extendedAdapter.documentRequestStamp()==captured)extendedAdapter.documentCancelled(kind)
                return
            }
            // Result processing belongs to the VM, not an Activity/composition waiter.
            viewModelScope.launch {
                try {
                    val lease=managementPlatformOwner.select(managementBorrower,selected)
                    extendedAdapter.documentSelected(lease,selected.stamp)
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { if(extendedAdapter.documentRequestStamp()==captured)extendedAdapter.documentFailed(kind,error) }
            }
        } catch(error:Exception) { if(extendedAdapter.documentRequestStamp()==captured)extendedAdapter.documentFailed(kind,error) }
    }
    private fun registerDemoSelection() {
        controllerRef.state.value.selected?.takeIf { it.demo && it in controllerRef.state.value.connections }?.let {
            runCatching { managementPlatformOwner.registerExistingCredential(it) }
        }
    }
    fun onManagementResume() {
        registerDemoSelection()
        extendedAdapter.refreshActual()
        panelContextState.value=actualManagementContext()?.panel
    }

    val controller = AppController(viewModelScope,sessionFactory,store,initialConnections,
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
    ).also { controllerRef = it; it.bindManagement(operationRuntime.management) }

    init {
        viewModelScope.launch { operationRuntime.recover() }
        viewModelScope.launch {
            combine(controller.state,management.zones.state) { _,_ -> Unit }
                .collect { registerDemoSelection();extendedAdapter.refreshActual();panelContextState.value=actualManagementContext()?.panel }
        }
    }
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
        val change=managementPlatformOwner.beginCredentialChange(connection)
        // Synchronous transition admission retires old issuer before the first vault write suspension.
        extendedAdapter.invalidateConnection(connection.id)
        controller.invalidateSession(connection.id)
        panelContextState.value=null
        managementPlatformOwner.finishCredentialChange(change) {
            withContext(operationDispatcher) { vault.put(connection.id,cleanToken);store.putConnection(connection) }
        }
        controller.reloadConnections(store.connections())
        controller.selectConnection(connection)
    }

    override fun onCleared() {
        extendedAdapter.close()
        connectionDraft.clear()
        // Application file service remains shared. Claimed jobs release their own capability in finally.
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            managementPlatformOwner.releaseBorrower(managementBorrower)
            localDatabase.close()
        }
        super.onCleared()
    }

    fun disconnect(connection:Connection) {
        extendedAdapter.invalidateConnection(connection.id)
        controller.invalidateSession(connection.id)
        panelContextState.value=null
        // UNDISPATCHED enters retireCredential's synchronous admission before returning to the caller.
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                managementPlatformOwner.retireCredential(connection) {
                    operationRuntime.removeConnection(connection.id) {
                        vault.remove(connection.id)
                        operationStore.removeConnection(connection.id)
                        store.removeConnection(connection.id)
                    }
                }
                controller.reloadConnections(store.connections())
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { controller.reportDisconnectFailure() }
        }
    }
}
