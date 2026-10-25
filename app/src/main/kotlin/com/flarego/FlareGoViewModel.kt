package com.flarego

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flarego.core.application.AppController
import com.flarego.core.application.operations.*
import com.flarego.core.application.r2.*
import com.flarego.core.application.kv.*
import com.flarego.ui.kv.*
import com.flarego.core.application.d1.*
import com.flarego.ui.d1.*
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

/** Internal immutable dependency injection for synthetic device tests; no product/config switch. */
internal class D1SyntheticAdapters(
    val session: suspend (Connection,D1ContentStore,() -> Unit) -> D1Session,
    val transfers: D1SignedTransferPort,
)

class FlareGoViewModel private constructor(
    application: Application,
    private val operationDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val transportForToken: (String) -> HttpTransport,
    private val transfersForToken: (String) -> TransferPort,
    private val d1Synthetic: D1SyntheticAdapters?,
) : AndroidViewModel(application) {
    constructor(application: Application, operationDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        transportForToken: (String) -> HttpTransport, transfersForToken: (String) -> TransferPort):
        this(application,operationDispatcher,transportForToken,transfersForToken,null)
    companion object {
        internal fun forD1SyntheticTest(application: Application,dispatcher: kotlinx.coroutines.CoroutineDispatcher,
            transport: (String) -> HttpTransport,transfers: (String) -> TransferPort,synthetic: D1SyntheticAdapters) =
            FlareGoViewModel(application,dispatcher,transport,transfers,synthetic)
    }
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
    private val oauthConfig = cloudflareOAuthConfig()
    private val credentials = OAuthCredentialOwner.get(application, oauthConfig)
    internal val oauthConnection = AndroidOAuthConnection(viewModelScope, oauthConfig, ::identity, ::saveOAuthConnection)
    fun acceptOAuthCallback(value: String) = oauthConnection.acceptCallback(value)
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
        val expectedRevision = credentialRevision(connection.id)
        val resolveToken: suspend () -> String = {
            withContext(operationDispatcher) {
                if (credentialRevision(connection.id) != expectedRevision) throw CloudException("连接凭据已变化，请刷新")
                val token = credentials.accessToken(connection.id) ?: throw CloudException("请重新连接 Cloudflare")
                if (credentialRevision(connection.id) != expectedRevision) throw CloudException("连接凭据已变化，请刷新")
                token
            }
        }
        val bundle=managementPlatformOwner.acquireCredential(connection,
            loadToken=resolveToken,
            jsonForToken={ RenewingHttpTransport(resolveToken, transportForToken) },
            transfersForToken={ RenewingTransferPort(resolveToken, transfersForToken) })
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
    private fun actualR2Context(): com.flarego.ui.r2.R2BrowserContext? {
        if (!::controllerRef.isInitialized) return null
        val state = controllerRef.state.value
        if (state.page != Page.RESOURCES || state.resourceKind != ResourceKind.R2) return null
        val selected = state.selected?.takeIf { it in state.connections } ?: return null
        val stamp = managementPlatformOwner.currentCredentialStamp(selected) ?: return null
        return com.flarego.ui.r2.R2BrowserContext(selected, stamp.revision, controllerRef.operationEpoch)
    }
    private suspend fun r2SessionFor(connection: Connection, expectedRevision: Long? = null): R2Session {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 R2 深度管理尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision)
            throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "R2 凭据已变化，请刷新")
        return com.flarego.core.cloudflare.identity.AuthorizedR2Session(connection, bundle.gateway, bundle.transfers, bundle.r2Issuer)
    }
    /**
     * The batch B1 configuration session of the one app-owned credential bundle: reads reuse
     * the reviewed wire client, every write obtains a fresh proof from the same stable
     * `R2TokenAuthorityIssuer` as batch A.
     */
    private suspend fun r2ConfigurationSessionFor(
        connection: Connection,
        expectedRevision: Long? = null,
    ): com.flarego.core.ports.R2ConfigurationSession {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 R2 配置管理尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision)
            throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "R2 凭据已变化，请刷新")
        return com.flarego.core.cloudflare.identity.AuthorizedR2ConfigurationSession(
            connection, bundle.gateway, bundle.r2Issuer)
    }
    /**
     * The batch B2 domain session of the one app-owned credential bundle. Zone ownership reuses
     * the reviewed `ZoneManagementPort` read (`CloudflareZoneClient`), never a DNS write.
     */
    private suspend fun r2DomainSessionFor(
        connection: Connection,
        expectedRevision: Long? = null,
    ): com.flarego.core.ports.R2DomainConfigurationSession {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 R2 域名配置管理尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision)
            throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "R2 凭据已变化，请刷新")
        val zones = com.flarego.core.cloudflare.zones.CloudflareZoneClient(connection, bundle.gateway)
        return com.flarego.core.cloudflare.identity.AuthorizedR2DomainConfigurationSession(
            connection, bundle.gateway, bundle.r2Issuer, zones)
    }
    /**
     * The batch B3 Sippy session of the one app-owned credential bundle: reads reuse the reviewed
     * wire client, every write obtains a fresh proof from the same stable `R2TokenAuthorityIssuer`.
     */
    private suspend fun r2SippySessionFor(
        connection: Connection,
        expectedRevision: Long? = null,
    ): com.flarego.core.ports.R2SippySession {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 Sippy 配置管理尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision)
            throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "R2 凭据已变化，请刷新")
        return com.flarego.core.cloudflare.identity.AuthorizedR2SippySession(
            connection, bundle.gateway, bundle.r2Issuer)
    }

    /**
     * The batch C bucket-job session of the one app-owned credential bundle: reads reuse the reviewed
     * wire client, the single create obtains a fresh proof from the same stable `R2TokenAuthorityIssuer`.
     */
    private suspend fun r2JobsSessionFor(
        connection: Connection,
        expectedRevision: Long? = null,
    ): com.flarego.core.ports.R2JobSession {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 R2 后台作业尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision)
            throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "R2 凭据已变化，请刷新")
        return com.flarego.core.cloudflare.identity.AuthorizedR2JobsSession(connection, bundle.gateway, bundle.r2Issuer)
    }

    /**
     * The batch C event-notification session of the one app-owned credential bundle. Its writes install
     * the proof-bound final guard on the client's own fresh pre-write document.
     */
    private suspend fun r2NotificationSessionFor(
        connection: Connection,
        expectedRevision: Long? = null,
    ): com.flarego.core.ports.R2NotificationSession {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 R2 事件通知尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision)
            throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "R2 凭据已变化，请刷新")
        return com.flarego.core.cloudflare.identity.AuthorizedR2NotificationSession(
            connection, bundle.gateway, bundle.r2Issuer)
    }

    /** The batch C account-metrics read of the one credential bundle (account-level only). */
    private suspend fun r2MetricsFor(connection: Connection): com.flarego.core.ports.R2MetricsPort {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前 R2 账号指标尚无演示适配器；不会把示例当作真实云结果")
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        return com.flarego.core.cloudflare.r2.CloudflareR2MetricsClient(connection, bundle.gateway)
    }

    private fun actualKvContext(): KvBrowserContext? {
        if (!::controllerRef.isInitialized) return null
        val state=controllerRef.state.value
        if(state.page!=Page.RESOURCES || state.resourceKind!=ResourceKind.KV)return null
        val selected=state.selected?.takeIf{it in state.connections}?:return null
        val stamp=managementPlatformOwner.currentCredentialStamp(selected)?:return null
        return KvBrowserContext(selected,stamp.revision,controllerRef.operationEpoch)
    }
    private val kvContents by lazy { KvEncryptedContentStore(File(getApplication<Application>().cacheDir,"kv-private")) }
    private suspend fun kvSessionFor(connection:Connection,expectedRevision:Long?=null,beforeSend:()->Unit={}):KvSession {
        if(connection.demo)throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"KV 深度管理尚无演示适配器，请使用本地合成验收环境")
        val bundle=credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if(expectedRevision!=null&&bundle.stamp.revision!=expectedRevision)throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED,"KV 凭据已变化，请刷新")
        return com.flarego.core.cloudflare.identity.AuthorizedKvSession(connection,bundle.gateway,bundle.transfers,
            kvContents,{System.currentTimeMillis()/1000},bundle.kvIssuer,beforeSend)
    }
    private val kvWrites:KvWriteRuntime by lazy {
        KvWriteRuntime(viewModelScope,operationStore,operationDispatcher,AndroidFingerprint(),
            OperationClock(System::currentTimeMillis),OperationIds{UUID.randomUUID().toString()},
            actual={actualKvContext()?.let{KvLiveSelection(it.connection,it.credentialRevision,kvBrowser.confirmationEpoch,kvBrowser.liveCommand())}},
            connections={if(::controllerRef.isInitialized)controllerRef.state.value.connections else emptyList()},
            revision=::credentialRevision,
            session={account,guard->
                val connection=controllerRef.state.value.connections.singleOrNull{it.id==account.connectionId&&it.accountId==account.accountId}
                    ?:throw CloudFailure(CloudFailureCode.INVALID_INPUT,"KV 账号已失效")
                kvSessionFor(connection,beforeSend=guard)
            })
    }
    private val kvBrowserLazy=lazy {
        KvBrowser(viewModelScope,::actualKvContext,kvWrites,kvContents,{secureWindowPort},
            {System.currentTimeMillis()/1000},{ctx->kvSessionFor(ctx.connection,ctx.credentialRevision)})
    }
    val kvBrowser:KvBrowser get()=kvBrowserLazy.value
    private val kvDocumentsLazy=lazy{KvDocumentBridge(getApplication(),viewModelScope,kvBrowser,::actualKvContext)}
    private val kvDocuments get()=kvDocumentsLazy.value
    suspend fun takeKvDocumentLaunch(request:KvDocumentRequest)=kvDocuments.takeLaunch(request)
    fun onKvDocumentLaunchFailed()=kvDocuments.launchFailed()
    fun onKvDocumentResult(export:Boolean,code:Int,data:android.content.Intent?)=kvDocuments.result(export,code,data)
    fun onKvBackground(){if(kvBrowserLazy.isInitialized())kvBrowser.background()}

    private fun actualD1Context(): D1BrowserContext? {
        if (!::controllerRef.isInitialized) return null
        val state = controllerRef.state.value
        if (state.page != Page.RESOURCES || state.resourceKind != ResourceKind.D1) return null
        val selected = state.selected?.takeIf { it in state.connections } ?: return null
        val stamp = managementPlatformOwner.currentCredentialStamp(selected) ?: return null
        return D1BrowserContext(selected,stamp.revision,controllerRef.operationEpoch)
    }
    private val d1Contents by lazy { D1EncryptedContentStore(File(getApplication<Application>().cacheDir,"d1-private")) }
    private val d1Transfers by lazy { d1Synthetic?.transfers ?: d1SignedTransfers() }
    private suspend fun d1SessionFor(connection: Connection,expectedRevision: Long? = null,beforeSend: () -> Unit = {}): D1Session {
        if (connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"D1 深度管理尚无演示适配器，请使用本地合成验收环境")
        if (expectedRevision != null && credentialRevision(connection.id) != expectedRevision) throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED,"D1 凭据已变化")
        d1Synthetic?.let { return it.session(connection,d1Contents,beforeSend) }
        val bundle = credentialBundle(connection)
        managementPlatformOwner.requireCurrent(bundle)
        if (expectedRevision != null && bundle.stamp.revision != expectedRevision) throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED,"D1 凭据已变化")
        return com.flarego.core.cloudflare.identity.AuthorizedD1Session(connection,bundle.gateway,bundle.transfers,d1Contents,bundle.d1Issuer,beforeSend)
    }
    private val d1Writes: D1WriteRuntime by lazy {
        D1WriteRuntime(viewModelScope,operationStore,operationDispatcher,AndroidFingerprint(),OperationClock(System::currentTimeMillis),OperationIds { UUID.randomUUID().toString() },
            actual = { actualD1Context()?.let { D1LiveSelection(it.connection,it.credentialRevision,d1Browser.confirmationEpoch,d1Browser.liveCommand()) } },
            connections = { if (::controllerRef.isInitialized) controllerRef.state.value.connections else emptyList() },revision = ::credentialRevision,
            session = { account,guard ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.CONFLICT,"D1 账号已失效")
                d1SessionFor(connection,beforeSend = guard)
            },pipeline = { _,api,onLocator -> D1FilePipeline(api,d1Transfers,d1Contents,System::currentTimeMillis,onLocator = onLocator) },
            onResult = { live,result -> d1Browser.claimResult(live,result) },onExportReady = { live,result -> d1Browser.claimExport(live,result) })
    }
    private val d1BrowserLazy = lazy {
        D1Browser(viewModelScope,::actualD1Context,d1Writes,d1Contents,{ secureWindowPort },D1Md5Fingerprint,
            { ctx -> d1SessionFor(ctx.connection,ctx.credentialRevision) },{ ctx,outcome ->
                if (actualD1Context() != ctx) throw CloudFailure(CloudFailureCode.CONFLICT,"D1 范围已变化")
                D1FilePipeline(d1SessionFor(ctx.connection,ctx.credentialRevision),d1Transfers,d1Contents,System::currentTimeMillis).downloadExport(outcome)
            })
    }
    val d1Browser: D1Browser get() = d1BrowserLazy.value
    private val d1DocumentsLazy = lazy { D1DocumentBridge(getApplication(),viewModelScope,d1Browser,::actualD1Context) }
    private val d1Documents get() = d1DocumentsLazy.value
    suspend fun takeD1DocumentLaunch(request: D1DocumentRequest) = d1Documents.takeLaunch(request)
    fun onD1DocumentLaunchFailed() = d1Documents.launchFailed()
    fun onD1DocumentResult(export: Boolean,code: Int,data: android.content.Intent?) = d1Documents.result(export,code,data)
    fun onD1Background() { if (d1BrowserLazy.isInitialized()) d1Browser.background() }

    private val r2Writes: R2WriteRuntime by lazy {
        R2WriteRuntime(viewModelScope, operationStore, operationDispatcher, AndroidFingerprint(),
            OperationClock(System::currentTimeMillis), OperationIds { UUID.randomUUID().toString() },
            actual = { actualR2Context()?.let { R2LiveSelection(it.connection, it.credentialRevision,
                r2Browser.confirmationEpoch, r2Browser.liveCommand()) } },
            connections = { if (::controllerRef.isInitialized) controllerRef.state.value.connections else emptyList() },
            revision = ::credentialRevision,
            session = { account ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 账号已失效")
                r2SessionFor(connection)
            }, publish = { controllerRef.publishTasks(it) },
            configSession = { account ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 账号已失效")
                r2ConfigurationSessionFor(connection)
            },
            domainSession = { account ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 账号已失效")
                r2DomainSessionFor(connection)
            },
            sippySession = { account ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 账号已失效")
                r2SippySessionFor(connection)
            },
            jobSession = { account ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 账号已失效")
                r2JobsSessionFor(connection)
            },
            notificationSession = { account ->
                val connection = controllerRef.state.value.connections.singleOrNull { it.id == account.connectionId && it.accountId == account.accountId }
                    ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 账号已失效")
                r2NotificationSessionFor(connection)
            })
    }
    /**
     * The one secure-window port of the *current* Activity/window.
     *
     * It is replaced on Activity recreation and cleared on detach, so a stale owner can never change
     * a new window and the credential editor loses its readiness (and its material) instead of
     * pretending to be protected.
     */
    @Volatile
    private var secureWindowPort: com.flarego.ui.r2.SecureWindowPort? = null

    fun attachSecureWindow(port: com.flarego.ui.r2.SecureWindowPort) {
        secureWindowPort = port
        // Attaching a *different* window revokes the previous readiness and drops the private Sippy
        // material now, instead of waiting for the old Activity's onDestroy or a later getter.
        if (r2BrowserLazy.isInitialized()) r2Browser.sippyCredentials.onWindowAttached(port.generation)
        if(kvBrowserLazy.isInitialized())kvBrowser.content.onWindowChanged()
        if(d1BrowserLazy.isInitialized())d1Browser.content.onWindowChanged()
    }

    /** Test observation only: the port the current Activity attached (never a credential). */
    internal fun attachedSecureWindowForTest(): com.flarego.ui.r2.SecureWindowPort? = secureWindowPort

    /** The Activity/window is going away: drop readiness and the private Sippy material eagerly. */
    fun onSecureWindowClosed(port: com.flarego.ui.r2.SecureWindowPort) {
        if (secureWindowPort === port) secureWindowPort = null
        if (r2BrowserLazy.isInitialized()) r2Browser.sippyCredentials.onWindowDetached(port.generation)
        if(kvBrowserLazy.isInitialized())kvBrowser.content.onWindowChanged()
        if(d1BrowserLazy.isInitialized())d1Browser.content.onWindowChanged()
    }

    private val r2BrowserLazy = lazy {
        com.flarego.ui.r2.R2Browser(
            viewModelScope,
            ::actualR2Context,
            r2Writes,
            { context -> r2ConfigurationSessionFor(context.connection, context.credentialRevision) },
            { context -> r2DomainSessionFor(context.connection, context.credentialRevision) },
            { context -> r2SippySessionFor(context.connection, context.credentialRevision) },
            { secureWindowPort },
            jobsSession = { context -> r2JobsSessionFor(context.connection, context.credentialRevision) },
            notificationSession = { context -> r2NotificationSessionFor(context.connection, context.credentialRevision) },
            metricsSession = { context -> r2MetricsFor(context.connection) },
            session = { context -> r2SessionFor(context.connection, context.credentialRevision) },
        )
    }
    val r2Browser: com.flarego.ui.r2.R2Browser get() = r2BrowserLazy.value
    private val r2Documents by lazy { R2DocumentBridge(getApplication(), viewModelScope, r2Browser) }
    fun takeR2DocumentLaunch(id: Long) = r2Documents.takeLaunch(id)
    fun onR2DocumentLaunchFailed(kind: com.flarego.ui.r2.R2DocumentKind) = r2Documents.launchFailed(kind)
    fun onR2DocumentResult(kind: com.flarego.ui.r2.R2DocumentKind, code: Int, data: android.content.Intent?) = r2Documents.result(kind, code, data)
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
        viewModelScope.launch { operationRuntime.recover(); r2Writes.refreshTasks(); kvWrites.refreshTasks() }
        viewModelScope.launch {
            combine(controller.state,management.zones.state) { _,_ -> Unit }
                .collect { registerDemoSelection();extendedAdapter.refreshActual();panelContextState.value=actualManagementContext()?.panel;r2Browser.refreshContext();kvBrowser.refreshContext();d1Browser.refreshContext() }
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
        identity(cleanToken).verifyAccount(cleanAccount)
        saveConnection(name.trim(), cleanAccount) { id -> credentials.putApiToken(id, cleanToken) }
    }

    private suspend fun saveOAuthConnection(name: String, accountId: String, tokens: OAuthTokens) {
        saveConnection(name, accountId) { id -> credentials.putOAuth(id, tokens) }
    }

    private suspend fun saveConnection(name: String, accountId: String, persist: suspend (String) -> Unit) {
        val existing = store.connections().find { it.accountId == accountId }
        val connection = Connection(existing?.id ?: UUID.randomUUID().toString(), name, accountId)
        val change=managementPlatformOwner.beginCredentialChange(connection)
        // Synchronous transition admission retires old issuer before the first vault write suspension.
        extendedAdapter.invalidateConnection(connection.id)
        controller.invalidateSession(connection.id)
        panelContextState.value=null
        managementPlatformOwner.finishCredentialChange(change) {
            withContext(operationDispatcher) { persist(connection.id);store.putConnection(connection) }
        }
        controller.reloadConnections(store.connections())
        controller.selectConnection(connection)
    }

    override fun onCleared() {
        extendedAdapter.close()
        r2Browser.close()
        r2Documents.close()
        if(kvDocumentsLazy.isInitialized())kvDocuments.close()
        if(kvBrowserLazy.isInitialized())kvBrowser.close()
        kvContents.releaseAll()
        if(d1DocumentsLazy.isInitialized())d1Documents.close()
        if(d1BrowserLazy.isInitialized())d1Browser.close()
        d1Contents.releaseAll()
        connectionDraft.clear()
        oauthConnection.cancel()
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
                    credentials.remove(connection.id)
                    operationRuntime.removeConnection(connection.id) {
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
