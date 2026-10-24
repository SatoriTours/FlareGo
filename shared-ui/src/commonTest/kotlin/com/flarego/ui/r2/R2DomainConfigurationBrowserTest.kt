package com.flarego.ui.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudPage
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.PageRequest
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2BulkDeleteOutcome
import com.flarego.core.model.R2CustomDomain
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2CustomDomainStatus
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2ManagedDomain
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2DomainConfigurationSession
import com.flarego.core.ports.R2Session
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Non-visual batch B2 settings surface: domain drafts, exact target names, public-exposure and TLS
 * downgrade acknowledgements, the single activity route (no plan id or raw client in the state), and
 * the invalidation of a domain preview on any input/target/route/credential change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2DomainConfigurationBrowserTest {
    private val connection = Connection("one", "fixture", "account-one")
    private var context = R2BrowserContext(connection, 1, 1)
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val zoneId = "0123456789abcdef0123456789abcdef"
    private val domain = "cdn.example.com"

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.toList()
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) { rows.clear() }
    }

    private data class Row(
        val domain: String,
        val enabled: Boolean,
        val minTls: String?,
        val ciphers: List<String>?,
        val zoneId: String? = "0123456789abcdef0123456789abcdef",
        val zoneName: String? = "example.com",
        val ownership: String? = "pending",
        val ssl: String? = "initializing",
    )

    private inner class DomainSession : R2DomainConfigurationSession {
        var editable = true
        var readOnlyReason: String? = null
        var managedHost = "pub-abc.r2.dev"
        var managedEnabled = true
        val rows = mutableListOf(Row(domain, enabled = true, minTls = "1.3", ciphers = listOf("ECDHE-ECDSA-AES128-GCM-SHA256")))
        val writes = mutableListOf<R2DomainConfigurationWrite>()

        private fun rawManaged(): JsonObject = buildJsonObject {
            put("domain", managedHost); put("enabled", managedEnabled); put("bucketId", "bucket-id")
        }

        private fun rawRow(row: Row): JsonObject = buildJsonObject {
            put("domain", row.domain)
            put("enabled", row.enabled)
            row.minTls?.let { put("minTLS", it) }
            row.ciphers?.let { list -> put("ciphers", kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
            row.zoneId?.let { put("zoneId", it) }
            row.zoneName?.let { put("zoneName", it) }
            put("status", buildJsonObject { put("ownership", row.ownership ?: "pending"); put("ssl", row.ssl ?: "initializing") })
        }

        private fun typed(row: Row): R2CustomDomain = R2CustomDomain(
            domain = row.domain,
            enabled = row.enabled,
            minTls = row.minTls,
            ciphers = row.ciphers,
            zoneId = row.zoneId,
            zoneName = row.zoneName,
            status = R2CustomDomainStatus(row.ownership ?: "pending", row.ssl ?: "initializing"),
            raw = rawRow(row),
        )

        private fun document(target: R2WriteTarget, kind: R2DomainKind, domainName: String?): R2DomainDocument =
            when (kind) {
                R2DomainKind.MANAGED_DOMAIN ->
                    R2DomainDocument(
                        target, kind, rawManaged(), editable, if (editable) null else readOnlyReason,
                        managed = R2ManagedDomain(managedHost, managedEnabled, "bucket-id"),
                    )
                R2DomainKind.CUSTOM_DOMAINS ->
                    R2DomainDocument(
                        target, kind, buildJsonObject { put("domains", kotlinx.serialization.json.JsonArray(rows.map(::rawRow))) },
                        editable, if (editable) null else readOnlyReason,
                        customDomains = rows.map(::typed),
                    )
                R2DomainKind.CUSTOM_DOMAIN -> {
                    val row = rows.firstOrNull { it.domain == domainName }
                        ?: throw CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源")
                    R2DomainDocument(
                        target, kind, rawRow(row), editable, if (editable) null else readOnlyReason,
                        customDomain = typed(row),
                    )
                }
            }

        override suspend fun getManagedDomain(target: R2WriteTarget) = document(target, R2DomainKind.MANAGED_DOMAIN, null)
        override suspend fun listCustomDomains(target: R2WriteTarget) = document(target, R2DomainKind.CUSTOM_DOMAINS, null)
        override suspend fun getCustomDomain(target: R2DomainTarget) =
            document(target.target, R2DomainKind.CUSTOM_DOMAIN, target.domain)

        override suspend fun putManagedDomain(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
            writeDomainConfiguration(R2DomainConfigurationWrite.PutManagedDomain(target, enabled)) {}
        override suspend fun addCustomDomain(target: R2WriteTarget, input: R2CustomDomainCreateInput): ExecutionReceipt =
            writeDomainConfiguration(R2DomainConfigurationWrite.AddCustomDomain(target, input)) {}
        override suspend fun editCustomDomain(target: R2WriteTarget, domain: String, edit: R2CustomDomainEditInput): ExecutionReceipt =
            writeDomainConfiguration(R2DomainConfigurationWrite.EditCustomDomain(target, domain, edit)) {}
        override suspend fun deleteCustomDomain(target: R2WriteTarget, domain: String): ExecutionReceipt =
            writeDomainConfiguration(R2DomainConfigurationWrite.DeleteCustomDomain(target, domain)) {}

        override suspend fun writeDomainConfiguration(
            write: R2DomainConfigurationWrite,
            beforeSend: (R2DomainDocument?) -> Unit,
        ): ExecutionReceipt {
            writes += write
            when (write) {
                is R2DomainConfigurationWrite.PutManagedDomain ->
                    beforeSend(document(write.target, R2DomainKind.MANAGED_DOMAIN, null))
                is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain ->
                    beforeSend(document(write.target, R2DomainKind.CUSTOM_DOMAINS, null))
                is R2DomainConfigurationWrite.EditCustomDomain ->
                    beforeSend(document(write.target, R2DomainKind.CUSTOM_DOMAIN, write.domain))
            }
            when (write) {
                is R2DomainConfigurationWrite.PutManagedDomain -> managedEnabled = write.enabled
                is R2DomainConfigurationWrite.AddCustomDomain ->
                    rows += Row(write.input.domain, write.input.enabled, write.input.minTls, null)
                is R2DomainConfigurationWrite.EditCustomDomain -> {
                    val index = rows.indexOfFirst { it.domain == write.domain }
                    val row = rows[index]
                    rows[index] = row.copy(enabled = write.edit.enabled ?: row.enabled, minTls = write.edit.minTls ?: row.minTls)
                }
                is R2DomainConfigurationWrite.DeleteCustomDomain ->
                    rows.removeAll { it.domain == write.domain }
            }
            return ExecutionReceipt.Completed()
        }
    }

    private inner class BucketSession : R2Session {
        override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> =
            CloudPage(listOf(R2BucketDocument("my-bucket", account, buildJsonObject { put("name", "my-bucket") })), null, 1)
        override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument = TODO()
        override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument = TODO()
        override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument = TODO()
        override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument = TODO()
        override suspend fun deleteBucket(account: AccountScope, name: String) = TODO()
        override suspend fun listObjects(account: AccountScope, bucket: String, filter: R2ObjectFilter, page: PageRequest): CloudPage<R2ObjectEntry> = TODO()
        override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead = TODO()
        override suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?, contentFingerprint: String): R2ObjectEntry = TODO()
        override suspend fun deleteObject(target: R2ObjectTarget) = TODO()
        override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome = TODO()
        override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt = TODO()
        override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt = TODO()
    }

    private inner class Harness(private val testScope: TestScope) {
        var ids = 0
        var revision = 1L
        val store = Store()
        val domains = DomainSession()
        val buckets = BucketSession()
        val runtime: R2WriteRuntime = R2WriteRuntime(
            testScope.backgroundScope, store, StandardTestDispatcher(testScope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "ui-dom-${++ids}" },
            { R2LiveSelection(connection, revision, browser.confirmationEpoch, browser.liveCommand()) },
            { listOf(connection) }, { revision }, { buckets },
            domainSession = { domains },
        )
        val browser: R2Browser = R2Browser(testScope.backgroundScope, { context }, runtime, null, { domains }) { buckets }

        fun open(panel: R2ConfigurationPanel) {
            browser.refreshContext()
            browser.onConfigurationIntent(R2ConfigurationIntent.Open("my-bucket"))
            browser.onConfigurationIntent(R2ConfigurationIntent.PanelSelected(panel))
        }

        fun acknowledge(
            publicExposure: Boolean = false,
            tlsDowngrade: Boolean = false,
            destructive: Boolean = false,
            bucketName: String = "",
            domainName: String = "",
        ) {
            browser.onConfigurationIntent(
                R2ConfigurationIntent.RisksChanged(
                    R2ConfigurationRisks(
                        destructiveImpactAcknowledged = destructive,
                        publicExposureAcknowledged = publicExposure,
                        tlsDowngradeAcknowledged = tlsDowngrade,
                    ),
                ),
            )
            browser.onConfigurationIntent(R2ConfigurationIntent.TypedTargetNameChanged(bucketName))
            browser.onConfigurationIntent(R2ConfigurationIntent.TypedDomainNameChanged(domainName))
        }

        fun preview() {
            browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            testScope.runCurrent()
        }

        fun confirm() {
            browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
            testScope.runCurrent()
        }
    }

    @Test
    fun aManagedDomainToggleNeedsPublicRiskAndTypedBucketAndSendsExplicitFalse() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.MANAGED_DOMAIN)
        runCurrent()
        assertTrue(h.browser.configuration.state.value.editable)
        assertEquals("pub-abc.r2.dev", h.browser.configuration.state.value.managedDomainHost)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.ManagedDomainEnabledChanged(false))
        h.preview()
        assertNull(h.browser.state.value.preview, "no public-exposure acknowledgement, no preview")
        h.acknowledge(publicExposure = false, bucketName = "my-bucket")
        h.preview()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(publicExposure = true, bucketName = "wrong")
        h.preview()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(publicExposure = true, bucketName = "my-bucket")
        h.preview()
        assertNotNull(h.browser.state.value.preview)
        assertTrue(h.domains.writes.isEmpty(), "a preview never writes")
        h.confirm()
        val write = h.domains.writes.single()
        assertEquals(false, (write as R2DomainConfigurationWrite.PutManagedDomain).enabled)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertFalse(h.domains.managedEnabled)
    }

    @Test
    fun creatingADomainNeedsTypedDomainAndPublicRiskAndNeverSendsCiphers() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.CUSTOM_DOMAINS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateDomainChanged("new.example.com"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateZoneIdChanged(zoneId))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateEnabledChanged(true))
        h.preview()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(publicExposure = true, bucketName = "my-bucket")
        h.preview()
        assertNull(h.browser.state.value.preview, "the exact domain name is required too")
        h.acknowledge(publicExposure = true, bucketName = "my-bucket", domainName = "new.example.com")
        h.preview()
        assertNotNull(h.browser.state.value.preview)
        h.confirm()
        val write = h.domains.writes.single() as R2DomainConfigurationWrite.AddCustomDomain
        assertNull(write.input.ciphers, "this stage never sends a new cipher list")
        assertEquals(true, write.input.enabled)
        assertEquals("1.2", write.input.minTls)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertTrue(h.domains.rows.any { it.domain == "new.example.com" })
    }

    @Test
    fun editingUsesOnlyTheExplicitChangeSetAndNeedsATlsDowngradeAcknowledgement() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.CUSTOM_DOMAINS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.SelectCustomDomain(domain))
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainEditEnabledChanged(false))
        h.acknowledge(bucketName = "my-bucket", domainName = domain)
        h.preview()
        assertNotNull(h.browser.state.value.preview)
        h.confirm()
        val disable = h.domains.writes.last() as R2DomainConfigurationWrite.EditCustomDomain
        assertEquals(false, disable.edit.enabled)
        assertNull(disable.edit.minTls, "an unchanged minTLS is omitted, never guessed")
        assertEquals("1.3", h.domains.rows.single { it.domain == domain }.minTls)

        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainEditEnabledChanged(null))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainEditMinTlsChanged("1.0"))
        h.acknowledge(bucketName = "my-bucket", domainName = domain)
        h.preview()
        assertNull(h.browser.state.value.preview, "a TLS downgrade needs its own acknowledgement")
        h.acknowledge(tlsDowngrade = true, bucketName = "my-bucket", domainName = domain)
        h.preview()
        assertNotNull(h.browser.state.value.preview)
        h.confirm()
        val downgrade = h.domains.writes.last() as R2DomainConfigurationWrite.EditCustomDomain
        assertNull(downgrade.edit.enabled)
        assertEquals("1.0", downgrade.edit.minTls)
        assertEquals("1.0", h.domains.rows.single { it.domain == domain }.minTls)
    }

    @Test
    fun deletingADomainIsDestructiveWithTypedNamesAndCancelOrRetargetRevokes() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.CUSTOM_DOMAINS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.SelectCustomDomain(domain))
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDeleteCustomDomain)
        assertTrue(h.browser.configuration.state.value.domainDeleteRequested)
        h.acknowledge(bucketName = "my-bucket", domainName = domain)
        h.preview()
        assertNull(h.browser.state.value.preview, "a destructive delete needs its own acknowledgement")
        h.acknowledge(destructive = true, bucketName = "my-bucket", domainName = "wrong")
        h.preview()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(destructive = true, bucketName = "my-bucket", domainName = domain)
        h.preview()
        assertNotNull(h.browser.state.value.preview)
        assertTrue(h.domains.writes.isEmpty())
        val epoch = h.browser.confirmationEpoch
        h.browser.onConfigurationIntent(R2ConfigurationIntent.SelectCustomDomain(null))
        runCurrent()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.browser.confirmationEpoch > epoch)
        h.confirm()
        assertTrue(h.domains.writes.isEmpty())

        h.browser.onConfigurationIntent(R2ConfigurationIntent.SelectCustomDomain(domain))
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDeleteCustomDomain)
        h.acknowledge(destructive = true, bucketName = "my-bucket", domainName = domain)
        h.preview()
        h.confirm()
        assertTrue(h.domains.writes.single() is R2DomainConfigurationWrite.DeleteCustomDomain)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertTrue(h.domains.rows.none { it.domain == domain })
    }

    @Test
    fun changingTheZoneInputOrTheCredentialRevokesADomainPreview() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.CUSTOM_DOMAINS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateDomainChanged("new.example.com"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateZoneIdChanged(zoneId))
        h.acknowledge(bucketName = "my-bucket", domainName = "new.example.com")
        h.preview()
        assertNotNull(h.browser.state.value.preview)
        val epoch = h.browser.confirmationEpoch
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateZoneIdChanged("fedcba9876543210fedcba9876543210"))
        assertNull(h.browser.state.value.preview)
        assertTrue(h.browser.confirmationEpoch > epoch)
        h.preview()
        assertNotNull(h.browser.state.value.preview)

        context = context.copy(credentialRevision = 2)
        h.revision = 2
        h.browser.refreshContext()
        runCurrent()
        assertFalse(h.browser.configuration.state.value.open)
        assertNull(h.browser.state.value.preview)
        h.confirm()
        assertTrue(h.domains.writes.isEmpty())
    }

    @Test
    fun aReadOnlyDomainDocumentNeverPublishesAPreview() = runTest {
        val h = Harness(this)
        h.domains.editable = false
        h.domains.readOnlyReason = "该域名配置包含应用尚未支持的字段或取值"
        h.open(R2ConfigurationPanel.MANAGED_DOMAIN)
        runCurrent()
        assertFalse(h.browser.configuration.state.value.editable)
        h.acknowledge(publicExposure = true, bucketName = "my-bucket")
        h.preview()
        assertNull(h.browser.state.value.preview)
        assertFalse(h.browser.configuration.state.value.canPreview)
        assertNotNull(h.browser.configuration.state.value.rawDocumentText)
        assertTrue(h.domains.writes.isEmpty())
    }

    @Test
    fun theDomainSettingsStateExposesOnlyADisplayPreviewAndCipherValuesAreReadOnly() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.CUSTOM_DOMAINS)
        runCurrent()
        val row = h.browser.configuration.state.value.customDomains.single()
        assertEquals(listOf("ECDHE-ECDSA-AES128-GCM-SHA256"), row.ciphers, "the current cipher list is displayed")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.SelectCustomDomain(domain))
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainEditMinTlsChanged("1.2"))
        h.acknowledge(bucketName = "my-bucket", domainName = domain)
        h.preview()
        assertNull(h.browser.state.value.preview, "lowering 1.3 → 1.2 is a TLS downgrade and needs its own acknowledgement")
        h.acknowledge(tlsDowngrade = true, bucketName = "my-bucket", domainName = domain)
        h.preview()
        val shown = assertNotNull(h.browser.configuration.state.value.preview)
        assertTrue(shown.impact.isNotBlank())
        assertTrue(shown.requiresTypedDomainName)
        // The route owns the plan; the settings state only carries display text.
        assertNotNull(h.browser.state.value.preview?.planId)
        h.confirm()
        val edit = h.domains.writes.single() as R2DomainConfigurationWrite.EditCustomDomain
        assertNull(edit.edit.ciphers, "no cipher write is offered by the settings surface")
    }

    @Test
    fun localOrDomainErrorsAreReportedWithoutSending() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.CUSTOM_DOMAINS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateDomainChanged("Bad_Domain"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainCreateZoneIdChanged("short"))
        h.preview()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.browser.configuration.state.value.validationMessage.orEmpty().isNotBlank())
        assertTrue(h.domains.writes.isEmpty())
    }
}
