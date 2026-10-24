package com.flarego.ui.r2

import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2ConfigurationEditing
import com.flarego.core.application.r2.R2PreparedPreview
import com.flarego.core.application.r2.R2ConfigurationProjection
import com.flarego.core.application.r2.R2DomainConfigurationEditing
import com.flarego.core.application.r2.R2DomainProjection
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.ports.R2SippySession
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LifecycleStorageTransition
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_JURISDICTIONS
import com.flarego.core.ports.R2ConfigurationSession
import com.flarego.core.ports.R2DomainConfigurationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the real typed configuration drafts, the exact target (bucket + jurisdiction),
 * the lazily loaded read-only document and the risk acknowledgements of the bucket
 * settings surface.
 *
 * It never sends a request on its own and never keeps an executable plan: reads use the
 * injected session port, and a preview/confirm is only *requested* from the single
 * [R2Browser] route, which owns the issuer-backed command and the shared confirmation
 * epoch. Switching the bucket, jurisdiction, panel, account or credential revokes the
 * previous preview before anything else happens.
 */
class R2ConfigurationBrowser(
    private val scope: CoroutineScope,
    private val actual: () -> R2BrowserContext?,
    private val session: (suspend (R2BrowserContext) -> R2ConfigurationSession)?,
    private val domainSession: (suspend (R2BrowserContext) -> R2DomainConfigurationSession)? = null,
    private val requestPreview: (R2ConfigurationWrite) -> Unit = {},
    private val requestDomainPreview: (R2DomainConfigurationWrite) -> Unit = {},
    private val requestConfirm: () -> Unit = {},
    private val requestCancel: () -> Unit = {},
    // Batch B3 Sippy: optional so every existing construction keeps working unchanged.
    private val sippySession: (suspend (R2BrowserContext) -> R2SippySession)? = null,
    private val requestSippyPreview: (R2SippyWrite) -> Unit = {},
    /** The one secure-window port supplier; absent means "no protection available". */
    private val secureWindow: () -> SecureWindowPort? = { null },
) {
    /**
     * The app-owned, memory-only Sippy credential editor. Task 6 drives it; this stage proves the
     * ownership/secrecy/ready-gate contract and the root write path.
     */
    val sippyCredentials =
        R2SippyCredentialOwner(
            secureWindow,
            onDraftChanged = { onSippyDraftChanged() },
            onSecureStateLost = { onSippySecureStateLost() },
        )

    private val mutable = MutableStateFlow(
        R2ConfigurationUiState(writesAvailable = session != null, domainWritesAvailable = domainSession != null),
    )
    val state: StateFlow<R2ConfigurationUiState> = mutable.asStateFlow()

    private var bound: R2BrowserContext? = null
    private var epoch = 0L
    private var job: Job? = null
    private var closed = false
    private var loaded: R2ConfigurationProjection? = null

    fun onContextChanged(context: R2BrowserContext?) {
        if (closed || context == bound) return
        bound = context
        epoch++
        job?.cancel()
        loaded = null
        // A route/account/credential change never carries the previous private material forward.
        sippyCredentials.withdraw()
        mutable.value =
            R2ConfigurationUiState(
                writesAvailable = session != null,
                domainWritesAvailable = domainSession != null,
                notice = if (session == null) CONFIGURATION_NOT_WIRED else null,
                sippy = sippyBaseState(),
            )
    }

    fun close() {
        if (closed) return
        closed = true
        epoch++
        job?.cancel()
        bound = null
        loaded = null
        sippyCredentials.endSecureEditing()
        mutable.value = R2ConfigurationUiState()
    }

    /** Root route revoked the live command: no configuration preview may survive. */
    internal fun onRootInvalidated() {
        mutable.value = mutable.value.copy(preview = null, writing = false,
            sippy = mutable.value.sippy.copy(writing = false))
    }

    /** The route owns the live command, so the display projection uses that exact write. */
    internal fun onRootPreview(preview: R2PreparedPreview, write: R2ConfigurationWrite) {
        val target = write.target
        mutable.value =
            mutable.value.copy(
                preview =
                    R2ConfigurationPreviewUi(
                        target = "${target.jurisdiction}/${target.bucket}",
                        impact = preview.detail.impact,
                        concurrencyWarning = preview.detail.concurrencyWarning,
                        requiresTypedTargetName = R2ConfigurationEditing.requiresDestructiveAcknowledgement(write),
                    ),
                writing = false,
            )
    }

    /** The route owns the live domain command, so the display projection uses that exact write. */
    internal fun onRootDomainPreview(preview: R2PreparedPreview, write: R2DomainConfigurationWrite) {
        val target = write.target
        mutable.value =
            mutable.value.copy(
                preview =
                    R2ConfigurationPreviewUi(
                        target = "${target.jurisdiction}/${target.bucket}" + (write.domain?.let { "/$it" } ?: ""),
                        impact = preview.detail.impact,
                        concurrencyWarning = preview.detail.concurrencyWarning,
                        requiresTypedTargetName = true,
                        requiresTypedDomainName = write !is R2DomainConfigurationWrite.PutManagedDomain,
                    ),
                writing = false,
            )
    }

    /**
     * The route owns the live Sippy command, so the one shared preview card (and the one
     * confirm/cancel path) shows exactly that write.
     */
    internal fun onRootSippyPreview(preview: R2PreparedPreview, write: R2SippyWrite) {
        val target = write.target
        mutable.value =
            mutable.value.copy(
                preview =
                    R2ConfigurationPreviewUi(
                        target = "${target.jurisdiction}/${target.bucket}/SIPPY",
                        impact = preview.detail.impact,
                        concurrencyWarning = preview.detail.concurrencyWarning,
                        requiresTypedTargetName = false,
                    ),
                writing = false,
                sippy = mutable.value.sippy.copy(writing = false),
            )
    }

    internal fun onRootWriting(writing: Boolean) {
        mutable.value = mutable.value.copy(writing = writing)
    }

    internal fun onRootSettled(notice: String?, reload: Boolean = false) {
        mutable.value = mutable.value.copy(preview = null, writing = false, notice = notice,
            sippy = mutable.value.sippy.copy(writing = false,
                // The acknowledgements are one-shot: a settled write needs them again next time.
                disclosureAcknowledged = false,
                credentialsAcknowledged = false,
                disableAcknowledged = false,
                disableRequested = false,
                notice = if (mutable.value.panel == R2ConfigurationPanel.SIPPY) notice else mutable.value.sippy.notice))
        // A verified write changed the document; show the read-back state, never the old snapshot.
        // The result notice survives that reload: it is the operation's settlement, not a draft hint.
        if (reload && mutable.value.open) reload(keepNotice = true)
    }

    private fun valid(context: R2BrowserContext, generation: Long) =
        !closed && context == bound && actual() == context && generation == epoch

    private fun target(): R2WriteTarget? {
        val context = bound ?: return null
        val bucket = mutable.value.target.bucket.trim()
        if (bucket.isEmpty()) return null
        return R2WriteTarget(context.account, bucket, mutable.value.target.jurisdiction)
    }

    private suspend fun read(context: R2BrowserContext, target: R2WriteTarget, kind: R2ConfigurationKind): R2ConfigurationDocument {
        val port = session?.invoke(context) ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, CONFIGURATION_NOT_WIRED)
        return when (kind) {
            R2ConfigurationKind.CORS -> port.getCors(target)
            R2ConfigurationKind.LIFECYCLE -> port.getLifecycle(target)
            R2ConfigurationKind.LOCAL_UPLOADS -> port.getLocalUploads(target)
            R2ConfigurationKind.LOCK -> port.getLock(target)
        }
    }

    fun onIntent(intent: R2ConfigurationIntent) {
        if (closed) return
        when (intent) {
            is R2ConfigurationIntent.Open -> open(intent.bucket)
            R2ConfigurationIntent.Close -> {
                epoch++
                job?.cancel()
                loaded = null
                sippyCredentials.endSecureEditing()
                mutable.value = R2ConfigurationUiState(writesAvailable = session != null,
                    notice = if (session == null) CONFIGURATION_NOT_WIRED else null)
            }
            is R2ConfigurationIntent.BucketChanged -> {
                mutable.value = mutable.value.copy(
                    target = mutable.value.target.copy(bucket = intent.value),
                    validationMessage = null,
                )
                // The loaded document belongs to the previous bucket; never keep it as the
                // basis of a write for the newly typed name.
                invalidateDocument()
            }
            is R2ConfigurationIntent.JurisdictionChanged -> {
                if (intent.value !in R2_JURISDICTIONS) return
                mutable.value = mutable.value.copy(
                    target = mutable.value.target.copy(jurisdiction = intent.value),
                    validationMessage = null,
                )
                reload()
            }
            is R2ConfigurationIntent.PanelSelected -> {
                if (intent.panel == mutable.value.panel) return
                // Leaving the Sippy surface releases its window purpose and its private material.
                if (mutable.value.panel == R2ConfigurationPanel.SIPPY) sippyCredentials.endSecureEditing()
                mutable.value = mutable.value.copy(
                    panel = intent.panel,
                    validationMessage = null,
                    notice = null,
                    selectedDomain = null,
                    customDomainEdit = R2CustomDomainEditDraft(),
                    domainDeleteRequested = false,
                    typedDomainName = "",
                )
                reload()
            }
            R2ConfigurationIntent.Refresh, R2ConfigurationIntent.RestoreDocument -> reload()
            is R2ConfigurationIntent.RisksChanged -> {
                mutable.value = mutable.value.copy(risks = intent.risks)
                refresh()
            }
            is R2ConfigurationIntent.TypedTargetNameChanged -> {
                mutable.value = mutable.value.copy(typedTargetName = intent.value)
                refresh()
            }
            R2ConfigurationIntent.AddCorsRule -> updateCors { it + R2CorsDraft() }
            is R2ConfigurationIntent.RemoveCorsRule -> updateCors { it.removeAtOrNull(intent.index) }
            is R2ConfigurationIntent.CorsIdChanged -> updateCors { it.replaceAt(intent.index) { row -> row.copy(id = intent.value) } }
            is R2ConfigurationIntent.CorsMethodsChanged ->
                updateCors { it.replaceAt(intent.index) { row -> row.copy(methods = intent.value) } }
            is R2ConfigurationIntent.CorsOriginsChanged ->
                updateCors { it.replaceAt(intent.index) { row -> row.copy(origins = intent.value) } }
            is R2ConfigurationIntent.CorsAllowedHeadersChanged ->
                updateCors { it.replaceAt(intent.index) { row -> row.copy(allowedHeaders = intent.value) } }
            is R2ConfigurationIntent.CorsExposeHeadersChanged ->
                updateCors { it.replaceAt(intent.index) { row -> row.copy(exposeHeaders = intent.value) } }
            is R2ConfigurationIntent.CorsMaxAgeChanged ->
                updateCors { it.replaceAt(intent.index) { row -> row.copy(maxAgeSeconds = intent.value) } }
            R2ConfigurationIntent.AddLifecycleRule -> updateLifecycle { it + R2LifecycleDraft() }
            is R2ConfigurationIntent.RemoveLifecycleRule -> updateLifecycle { it.removeAtOrNull(intent.index) }
            is R2ConfigurationIntent.LifecycleIdChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(id = intent.value) } }
            is R2ConfigurationIntent.LifecycleEnabledChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(enabled = intent.value) } }
            is R2ConfigurationIntent.LifecyclePrefixChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(prefix = intent.value) } }
            is R2ConfigurationIntent.LifecycleDeleteModeChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(deleteMode = intent.value) } }
            is R2ConfigurationIntent.LifecycleDeleteDateChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(deleteDate = intent.value) } }
            is R2ConfigurationIntent.LifecycleTransitionTypeChanged -> updateLifecycle {
                it.replaceAt(intent.index) { row ->
                    row.copy(
                        transitions =
                            row.transitions.replaceAt(intent.transitionIndex) { transition ->
                                transition.copy(conditionType = intent.value)
                            },
                    )
                }
            }
            is R2ConfigurationIntent.LifecycleTransitionDateChanged -> updateLifecycle {
                it.replaceAt(intent.index) { row ->
                    row.copy(
                        transitions =
                            row.transitions.replaceAt(intent.transitionIndex) { transition ->
                                transition.copy(utcDate = intent.value)
                            },
                    )
                }
            }
            is R2ConfigurationIntent.LifecycleDeleteAgeChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(deleteAgeSeconds = intent.value) } }
            is R2ConfigurationIntent.LifecycleAbortAgeChanged ->
                updateLifecycle { it.replaceAt(intent.index) { row -> row.copy(abortAgeSeconds = intent.value) } }
            is R2ConfigurationIntent.AddLifecycleTransition -> updateLifecycle {
                it.replaceAt(intent.index) { row -> row.copy(transitions = row.transitions + R2LifecycleTransitionDraft()) }
            }
            is R2ConfigurationIntent.RemoveLifecycleTransition -> updateLifecycle {
                it.replaceAt(intent.index) { row -> row.copy(transitions = row.transitions.removeAtOrNull(intent.transitionIndex)) }
            }
            is R2ConfigurationIntent.LifecycleTransitionAgeChanged -> updateLifecycle {
                it.replaceAt(intent.index) { row ->
                    row.copy(
                        transitions =
                            row.transitions.replaceAt(intent.transitionIndex) { transition ->
                                transition.copy(ageSeconds = intent.value)
                            },
                    )
                }
            }
            is R2ConfigurationIntent.LocalUploadsEnabledChanged -> {
                mutable.value = mutable.value.copy(localUploadsEnabled = intent.value)
                refresh()
            }
            R2ConfigurationIntent.AddLockRule -> updateLock { it + R2LockDraft() }
            is R2ConfigurationIntent.RemoveLockRule -> updateLock { it.removeAtOrNull(intent.index) }
            is R2ConfigurationIntent.LockIdChanged -> updateLock { it.replaceAt(intent.index) { row -> row.copy(id = intent.value) } }
            is R2ConfigurationIntent.LockEnabledChanged ->
                updateLock { it.replaceAt(intent.index) { row -> row.copy(enabled = intent.value) } }
            is R2ConfigurationIntent.LockPrefixChanged ->
                updateLock { it.replaceAt(intent.index) { row -> row.copy(prefix = intent.value) } }
            is R2ConfigurationIntent.LockConditionTypeChanged -> updateLock {
                it.replaceAt(intent.index) { row -> row.copy(conditionType = intent.value) }
            }
            is R2ConfigurationIntent.LockAgeSecondsChanged ->
                updateLock { it.replaceAt(intent.index) { row -> row.copy(ageSeconds = intent.value) } }
            is R2ConfigurationIntent.LockDateChanged ->
                updateLock { it.replaceAt(intent.index) { row -> row.copy(date = intent.value) } }
            is R2ConfigurationIntent.ManagedDomainEnabledChanged -> {
                mutable.value = mutable.value.copy(managedDomainEnabled = intent.value)
                refresh()
            }
            is R2ConfigurationIntent.CustomDomainCreateDomainChanged -> updateCreate { it.copy(domain = intent.value) }
            is R2ConfigurationIntent.CustomDomainCreateZoneIdChanged -> updateCreate { it.copy(zoneId = intent.value) }
            is R2ConfigurationIntent.CustomDomainCreateEnabledChanged -> updateCreate { it.copy(enabled = intent.value) }
            is R2ConfigurationIntent.CustomDomainCreateMinTlsChanged -> updateCreate { it.copy(minTls = intent.value) }
            is R2ConfigurationIntent.SelectCustomDomain -> {
                mutable.value = mutable.value.copy(
                    selectedDomain = intent.domain,
                    customDomainEdit = R2CustomDomainEditDraft(),
                    domainDeleteRequested = false,
                    typedDomainName = "",
                    validationMessage = null,
                )
                reload()
            }
            is R2ConfigurationIntent.CustomDomainEditEnabledChanged -> updateEdit { it.copy(enabled = intent.value) }
            is R2ConfigurationIntent.CustomDomainEditMinTlsChanged -> updateEdit { it.copy(minTls = intent.value) }
            is R2ConfigurationIntent.TypedDomainNameChanged -> {
                mutable.value = mutable.value.copy(typedDomainName = intent.value)
                refresh()
            }
            R2ConfigurationIntent.BeginSippySecureEditing -> {
                // Protection is requested before any secret input may be rendered; the state then
                // reports the real flag, not an optimistic boolean.
                sippyCredentials.beginSecureEditing()
                syncSippy()
                refreshSippy()
            }
            R2ConfigurationIntent.EndSippySecureEditing -> {
                sippyCredentials.endSecureEditing()
                syncSippy()
                refreshSippy()
            }
            is R2ConfigurationIntent.SippyProviderSelected -> {
                // A provider switch drops every previous private value and any issued preview.
                if (sippyCredentials.selectProvider(intent.provider)) {
                    mutable.value = mutable.value.copy(
                        sippy = mutable.value.sippy.copy(
                            typedBucketName = mutable.value.sippy.typedBucketName,
                        ),
                    )
                }
                syncSippy()
                if (mutable.value.panel == R2ConfigurationPanel.SIPPY) refreshSippy()
            }
            is R2ConfigurationIntent.SippyFieldChanged -> {
                // The owner rejects without storing when the window is not protected, the field does
                // not belong to the provider, or the value exceeds the editing limit.
                sippyCredentials.setField(intent.field, intent.input)
                syncSippy()
                if (mutable.value.panel == R2ConfigurationPanel.SIPPY) refreshSippy()
            }
            is R2ConfigurationIntent.SippyTypedBucketChanged -> {
                mutable.value = mutable.value.copy(sippy = mutable.value.sippy.copy(typedBucketName = intent.value))
                refreshSippy()
            }
            is R2ConfigurationIntent.SippyDisclosureAcknowledgedChanged -> {
                mutable.value =
                    mutable.value.copy(sippy = mutable.value.sippy.copy(disclosureAcknowledged = intent.acknowledged))
                refreshSippy()
            }
            is R2ConfigurationIntent.SippyCredentialsAcknowledgedChanged -> {
                mutable.value =
                    mutable.value.copy(sippy = mutable.value.sippy.copy(credentialsAcknowledged = intent.acknowledged))
                refreshSippy()
            }
            is R2ConfigurationIntent.SippyDisableAcknowledgedChanged -> {
                mutable.value =
                    mutable.value.copy(sippy = mutable.value.sippy.copy(disableAcknowledged = intent.acknowledged))
                refreshSippy()
            }
            R2ConfigurationIntent.RequestDisableSippy -> {
                if (mutable.value.panel == R2ConfigurationPanel.SIPPY) {
                    mutable.value = mutable.value.copy(sippy = mutable.value.sippy.copy(disableRequested = true))
                    refreshSippy()
                }
            }
            R2ConfigurationIntent.CancelDisableSippy -> {
                if (mutable.value.sippy.disableRequested) {
                    mutable.value = mutable.value.copy(sippy = mutable.value.sippy.copy(disableRequested = false))
                    refreshSippy()
                }
            }
            R2ConfigurationIntent.RequestDeleteCustomDomain -> {
                if (mutable.value.panel == R2ConfigurationPanel.CUSTOM_DOMAINS && mutable.value.selectedDomain != null) {
                    mutable.value = mutable.value.copy(
                        domainDeleteRequested = true,
                        customDomainEdit = R2CustomDomainEditDraft(),
                        typedDomainName = "",
                    )
                    refresh()
                }
            }
            R2ConfigurationIntent.CancelDeleteCustomDomain -> {
                if (mutable.value.domainDeleteRequested) {
                    mutable.value = mutable.value.copy(domainDeleteRequested = false)
                    refresh()
                }
            }
            R2ConfigurationIntent.RequestDeleteConfiguration -> {
                // A whole-configuration DELETE exists only for the two reviewed families.
                if (mutable.value.panel == R2ConfigurationPanel.CORS ||
                    mutable.value.panel == R2ConfigurationPanel.LOCK
                ) {
                    mutable.value = mutable.value.copy(deleteRequested = true)
                    refresh()
                }
            }
            R2ConfigurationIntent.CancelDeleteConfiguration -> {
                if (mutable.value.deleteRequested) {
                    mutable.value = mutable.value.copy(deleteRequested = false)
                    refresh()
                }
            }
            R2ConfigurationIntent.Preview -> preview()
            R2ConfigurationIntent.Confirm -> if (mutable.value.preview != null) requestConfirm()
            R2ConfigurationIntent.CancelPreview -> {
                mutable.value = mutable.value.copy(preview = null)
                requestCancel()
            }
        }
    }

    private fun open(bucket: String) {
        val context = bound ?: return
        if (session == null && domainSession == null && sippySession == null) {
            mutable.value = mutable.value.copy(open = true, error = CONFIGURATION_NOT_WIRED)
            return
        }
        val clean = bucket.trim()
        if (clean.isEmpty()) return
        mutable.value =
            R2ConfigurationUiState(
                open = true,
                panel = mutable.value.panel,
                target = R2ConfigurationDraftTarget(bucket = clean),
                writesAvailable = session != null,
                domainWritesAvailable = domainSession != null,
                sippy = sippyBaseState(),
            )
        reload()
    }

    /** Drops the loaded document so no draft of another bucket can be written by mistake. */
    private fun invalidateDocument() {
        epoch++
        job?.cancel()
        loaded = null
        // A different bucket is a different Sippy target: the private draft never carries over.
        sippyCredentials.withdraw()
        mutable.value =
            mutable.value.copy(
                loading = false,
                editable = false,
                readOnlyReason = null,
                rawDocumentText = null,
                cors = emptyList(),
                lifecycle = emptyList(),
                lock = emptyList(),
                localUploadsEnabled = false,
                deleteRequested = false,
                managedDomainHost = null,
                managedDomainEnabled = false,
                customDomains = emptyList(),
                selectedDomain = null,
                customDomainCreate = R2CustomDomainCreateDraft(),
                customDomainEdit = R2CustomDomainEditDraft(),
                domainDeleteRequested = false,
                typedDomainName = "",
                preview = null,
                canPreview = false,
                validationMessage = "目标桶已变化，请刷新加载该桶的配置后再编辑",
                sippy = mutable.value.sippy.copy(
                    snapshot = null,
                    snapshotError = null,
                    disableRequested = false,
                    secureReady = false,
                    secureEpoch = null,
                    draftComplete = false,
                    credentialRevision = sippyCredentials.bindingRevision,
                ),
            )
    }

    private fun reload(keepNotice: Boolean = false) {
        val context = bound ?: return
        val target = target() ?: run {
            mutable.value = mutable.value.copy(
                open = mutable.value.open,
                loading = false,
                editable = false,
                error = null,
                rawDocumentText = null,
                preview = null,
                canPreview = false,
                validationMessage = "请输入完整的目标桶名",
            )
            return
        }
        epoch++
        val generation = epoch
        job?.cancel()
        loaded = null
        mutable.value = mutable.value.copy(
            loading = true,
            error = null,
            notice = if (keepNotice) mutable.value.notice else null,
            editable = false,
            readOnlyReason = null,
            rawDocumentText = null,
            cors = emptyList(),
            lifecycle = emptyList(),
            lock = emptyList(),
            localUploadsEnabled = false,
            deleteRequested = false,
            managedDomainHost = null,
            managedDomainEnabled = false,
            customDomains = emptyList(),
            customDomainCreate = R2CustomDomainCreateDraft(),
            customDomainEdit = R2CustomDomainEditDraft(),
            domainDeleteRequested = false,
            typedDomainName = "",
            preview = null,
            canPreview = false,
            validationMessage = null,
        )
        if (mutable.value.panel == R2ConfigurationPanel.SIPPY) {
            reloadSippy(context, target, generation)
            return
        }
        if (mutable.value.panel.domainKind != null) {
            reloadDomain(context, target, generation)
            return
        }
        job = scope.launch {
            try {
                val document = read(context, target, mutable.value.panel.kind ?: return@launch)
                if (!valid(context, generation)) return@launch
                val projection = R2ConfigurationEditing.project(document)
                loaded = projection
                mutable.value = seeded(projection).copy(loading = false, error = null)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (valid(context, generation)) {
                    mutable.value = mutable.value.copy(
                        loading = false,
                        editable = false,
                        error = safe(failure),
                        canPreview = false,
                    )
                }
            }
        }
    }

    /**
     * Loads the domain document of the active panel. The collection panel additionally loads the
     * selected single domain, so its edit draft is always based on that exact domain.
     */
    private fun reloadDomain(context: R2BrowserContext, target: R2WriteTarget, generation: Long) {
        job = scope.launch {
            try {
                val port = domainSession?.invoke(context)
                    ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, DOMAIN_NOT_WIRED)
                when (mutable.value.panel) {
                    R2ConfigurationPanel.MANAGED_DOMAIN -> {
                        val document = port.getManagedDomain(target)
                        if (!valid(context, generation)) return@launch
                        mutable.value = seededManaged(R2DomainConfigurationEditing.project(document))
                            .copy(loading = false, error = null)
                    }
                    R2ConfigurationPanel.CUSTOM_DOMAINS -> {
                        val list = port.listCustomDomains(target)
                        if (!valid(context, generation)) return@launch
                        val projection = R2DomainConfigurationEditing.project(list)
                        var next = seededCustomDomains(projection)
                        val selected = next.selectedDomain
                        if (selected != null) {
                            val single = port.getCustomDomain(R2DomainTarget(target, selected))
                            if (!valid(context, generation)) return@launch
                            next = next.copy(
                                editable = projection.editable && single.editable,
                                readOnlyReason = projection.readOnlyReason ?: single.readOnlyReason,
                                customDomainEdit = R2CustomDomainEditDraft(),
                                domainDeleteRequested = false,
                            )
                        }
                        mutable.value = next.copy(loading = false, error = null)
                    }
                    else -> return@launch
                }
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (valid(context, generation)) {
                    mutable.value = mutable.value.copy(
                        loading = false,
                        editable = false,
                        error = safe(failure),
                        canPreview = false,
                    )
                }
            }
        }
    }

    private fun seededManaged(projection: R2DomainProjection): R2ConfigurationUiState {
        val base = mutable.value
        return base.copy(
            editable = projection.editable,
            readOnlyReason = projection.readOnlyReason,
            rawDocumentText = projection.rawText,
            managedDomainHost = projection.managed?.domain,
            managedDomainEnabled = projection.managed?.enabled ?: false,
        )
    }

    private fun seededCustomDomains(projection: R2DomainProjection): R2ConfigurationUiState {
        val base = mutable.value
        val known = projection.customDomains.map { domain ->
            R2CustomDomainDraft(
                domain = domain.domain,
                enabled = domain.enabled,
                minTls = domain.minTls,
                ciphers = domain.ciphers,
                zoneId = domain.zoneId,
                zoneName = domain.zoneName,
                ownership = domain.status?.ownership,
                ssl = domain.status?.ssl,
            )
        }
        return base.copy(
            editable = projection.editable,
            readOnlyReason = projection.readOnlyReason,
            rawDocumentText = projection.rawText,
            customDomains = known,
            selectedDomain = base.selectedDomain?.takeIf { name -> known.any { it.domain == name } },
        )
    }

    private fun updateCreate(transform: (R2CustomDomainCreateDraft) -> R2CustomDomainCreateDraft) {
        mutable.value = mutable.value.copy(customDomainCreate = transform(mutable.value.customDomainCreate))
        refresh()
    }

    private fun updateEdit(transform: (R2CustomDomainEditDraft) -> R2CustomDomainEditDraft) {
        mutable.value = mutable.value.copy(customDomainEdit = transform(mutable.value.customDomainEdit))
        refresh()
    }

    private fun seeded(projection: R2ConfigurationProjection): R2ConfigurationUiState {
        val base = mutable.value
        return when (base.panel) {
            R2ConfigurationPanel.CORS ->
                base.copy(editable = projection.editable, readOnlyReason = projection.readOnlyReason,
                    rawDocumentText = projection.rawText, cors = projection.cors.map(::draftOf))
            R2ConfigurationPanel.LIFECYCLE ->
                base.copy(editable = projection.editable, readOnlyReason = projection.readOnlyReason,
                    rawDocumentText = projection.rawText, lifecycle = projection.lifecycle.map(::draftOf))
            R2ConfigurationPanel.LOCAL_UPLOADS ->
                base.copy(editable = projection.editable, readOnlyReason = projection.readOnlyReason,
                    rawDocumentText = projection.rawText, localUploadsEnabled = projection.localUploadsEnabled ?: false)
            R2ConfigurationPanel.LOCK ->
                base.copy(editable = projection.editable, readOnlyReason = projection.readOnlyReason,
                    rawDocumentText = projection.rawText, lock = projection.lock.map(::draftOf))
            R2ConfigurationPanel.MANAGED_DOMAIN, R2ConfigurationPanel.CUSTOM_DOMAINS -> base
            // The Sippy surface keeps its own safe snapshot state; no rules[] projection applies.
            R2ConfigurationPanel.SIPPY -> base
        }
    }

    /** Recomputes the local preview permission without sending anything. */
    private fun refresh() {
        if (mutable.value.panel == R2ConfigurationPanel.SIPPY) {
            refreshSippy()
            return
        }
        if (mutable.value.panel.domainKind != null) {
            val write = domainDraftWrite()
            val issue = fieldIssue()
            if (write == null || issue != null) {
                mutable.value =
                    mutable.value.copy(
                        canPreview = false,
                        validationMessage = issue ?: "请先补全域名配置输入",
                    )
                return
            }
            val message = R2DomainConfigurationEditing.validate(write).message
            val gate = domainGateMessage(write)
            mutable.value =
                mutable.value.copy(
                    canPreview = message == null && gate == null,
                    validationMessage = message ?: gate,
                )
            return
        }
        val write = draftWrite()
        val issue = fieldIssue()
        if (write == null || issue != null) {
            mutable.value =
                mutable.value.copy(
                    canPreview = false,
                    validationMessage = issue ?: "请先补全配置输入",
                )
            return
        }
        val message = R2ConfigurationEditing.validate(write).message
        val gate = gateMessage(write)
        mutable.value =
            mutable.value.copy(
                canPreview = message == null && gate == null,
                validationMessage = message ?: gate,
            )
    }

    /**
     * A blank optional age is "absent"; a non-blank value that is not an integer, a blank
     * date of an explicitly selected Date condition, or a missing transition input is
     * reported instead of being silently dropped or converted to another condition shape.
     */
    private fun fieldIssue(): String? {
        if (mutable.value.deleteRequested) return null
        // The Sippy surface reports its own issue from the credential draft and the safe snapshot.
        if (mutable.value.panel == R2ConfigurationPanel.SIPPY) return null
        return when (mutable.value.panel) {
            R2ConfigurationPanel.CORS ->
                mutable.value.cors.indexOfFirst { row ->
                    row.maxAgeSeconds.trim().let { it.isNotEmpty() && it.toLongOrNull() == null }
                }.takeIf { it >= 0 }?.let { "第 ${it + 1} 条 CORS 规则的 maxAgeSeconds 必须是整数秒（留空表示不发送）" }
            R2ConfigurationPanel.LIFECYCLE ->
                mutable.value.lifecycle.withIndex().firstNotNullOfOrNull { (index, row) ->
                    val prefix = "第 ${index + 1} 条生命周期规则"
                    when (row.deleteMode) {
                        R2LifecycleDeleteMode.NONE -> null
                        R2LifecycleDeleteMode.AGE ->
                            if (row.deleteAgeSeconds.trim().toLongOrNull() == null) "${prefix}的删除条件年龄必须是整数秒" else null
                        R2LifecycleDeleteMode.DATE ->
                            if (row.deleteDate.isBlank()) "${prefix}的删除条件需要 UTC 日期（RFC3339，带 Z）" else null
                    } ?: if (row.abortAgeSeconds.trim().let { it.isNotEmpty() && it.toLongOrNull() == null }) {
                        "${prefix}的终止分片上传年龄必须是整数秒"
                    } else {
                        null
                    } ?: row.transitions.withIndex().firstNotNullOfOrNull { (transitionIndex, transition) ->
                        val label = "${prefix}的第 ${transitionIndex + 1} 个存储等级迁移"
                        when (transition.conditionType) {
                            R2LifecycleConditionType.AGE ->
                                if (transition.ageSeconds.trim().toLongOrNull() == null) "${label}年龄必须是整数秒" else null
                            R2LifecycleConditionType.DATE ->
                                if (transition.utcDate.isBlank()) "${label}需要 UTC 日期（RFC3339，带 Z）" else null
                        }
                    }
                }
            R2ConfigurationPanel.LOCK ->
                mutable.value.lock.withIndex().firstOrNull { (_, row) ->
                    when (row.conditionType) {
                        R2LockConditionType.AGE_SECONDS -> row.ageSeconds.trim().toLongOrNull() == null
                        R2LockConditionType.DATE -> row.date.isBlank()
                        R2LockConditionType.INDEFINITE -> false
                    }
                }?.let {
                    if (mutable.value.lock[it.index].conditionType == R2LockConditionType.DATE) {
                        "第 ${it.index + 1} 条锁规则需要 UTC 日期（RFC3339，带 Z）"
                    } else {
                        "第 ${it.index + 1} 条锁规则的年龄必须是整数秒"
                    }
                }
            R2ConfigurationPanel.LOCAL_UPLOADS -> null
            R2ConfigurationPanel.MANAGED_DOMAIN -> null
            R2ConfigurationPanel.CUSTOM_DOMAINS -> customDomainFieldIssue()
            // Unreachable: the Sippy surface returns above, but the branch keeps the `when` explicit.
            R2ConfigurationPanel.SIPPY -> null
        }
    }

    private fun customDomainFieldIssue(): String? {
        val current = mutable.value
        if (current.domainDeleteRequested) {
            return if (current.selectedDomain == null) "请先选择要删除的自定义域" else null
        }
        if (current.selectedDomain != null) {
            val edit = current.customDomainEdit
            if (edit.enabled == null && edit.minTls == null) return "请至少修改 enabled 或 minTLS 中一项后再预览"
            if (edit.minTls != null && !R2DomainConfigurationEditing.isValidMinTls(edit.minTls)) {
                return "minTLS 仅支持 1.0 / 1.1 / 1.2 / 1.3"
            }
            return null
        }
        val create = current.customDomainCreate
        if (!R2DomainConfigurationEditing.isValidDomainName(create.domain)) {
            return "请输入合法的小写自定义域名（至少两级标签，不含协议、路径或通配符）"
        }
        if (!R2DomainConfigurationEditing.isValidZoneId(create.zoneId)) {
            return "Zone ID 必须是 16–32 位小写十六进制"
        }
        if (!R2DomainConfigurationEditing.isValidMinTls(create.minTls)) {
            return "minTLS 仅支持 1.0 / 1.1 / 1.2 / 1.3"
        }
        return null
    }

    private fun gateMessage(write: R2ConfigurationWrite): String? {
        val current = mutable.value
        if (!current.open) return "请先打开桶设置"
        if (session == null) return CONFIGURATION_NOT_WIRED
        if (current.loading) return "配置仍在加载，请稍候"
        if (!current.editable) return current.readOnlyReason ?: "当前配置不可编辑"
        if (write.target.jurisdiction != current.target.jurisdiction || write.target.bucket != current.target.bucket.trim()) {
            return "目标已变化，请重新加载该桶的配置"
        }
        if (R2ConfigurationEditing.requiresFullReplacementAcknowledgement(write) &&
            !current.risks.fullReplacementAcknowledged
        ) {
            return "整组替换会覆盖该桶当前的完整规则集，请先确认影响"
        }
        if (R2ConfigurationEditing.requiresDestructiveAcknowledgement(write)) {
            if (!current.risks.destructiveImpactAcknowledged) return "该操作可能影响现有对象，请先确认破坏性影响"
            if (current.typedTargetName != write.target.bucket) return "请输入与目标完全一致的桶名后再预览"
        }
        return null
    }

    private fun preview() {
        if (mutable.value.panel == R2ConfigurationPanel.SIPPY) {
            previewSippy()
            return
        }
        val issue = fieldIssue()
        if (issue != null) {
            mutable.value = mutable.value.copy(validationMessage = issue, canPreview = false)
            return
        }
        if (mutable.value.panel.domainKind != null) {
            previewDomain()
            return
        }
        val write = draftWrite() ?: run {
            mutable.value = mutable.value.copy(validationMessage = "请先补全配置输入")
            return
        }
        val validation = R2ConfigurationEditing.validate(write)
        if (!validation.valid) {
            mutable.value = mutable.value.copy(validationMessage = validation.message)
            return
        }
        val gate = gateMessage(write)
        if (gate != null) {
            mutable.value = mutable.value.copy(validationMessage = gate, canPreview = false)
            return
        }
        mutable.value = mutable.value.copy(writing = true, preview = null, notice = null)
        requestPreview(write)
    }

    /** Builds the typed write of the current panel, or null when the drafts are incomplete. */
    private fun draftWrite(): R2ConfigurationWrite? {
        val target = target() ?: return null
        if (mutable.value.deleteRequested) {
            return when (mutable.value.panel) {
                R2ConfigurationPanel.CORS -> R2ConfigurationWrite.DeleteCors(target)
                R2ConfigurationPanel.LOCK -> R2ConfigurationWrite.DeleteLock(target)
                else -> null
            }
        }
        return when (mutable.value.panel) {
            R2ConfigurationPanel.CORS -> {
                val rules = mutable.value.cors.map { row ->
                    R2CorsRule(
                        id = row.id.ifEmpty { null },
                        allowed =
                            R2CorsAllowed(
                                methods = splitList(row.methods),
                                origins = splitList(row.origins),
                                headers = splitList(row.allowedHeaders),
                            ),
                        exposeHeaders = splitList(row.exposeHeaders),
                        maxAgeSeconds = row.maxAgeSeconds.trim().ifEmpty { null }?.toLongOrNull(),
                    )
                }
                R2ConfigurationWrite.PutCors(target, rules)
            }
            R2ConfigurationPanel.LIFECYCLE -> {
                val rules = mutable.value.lifecycle.map { row ->
                    R2LifecycleRule(
                        id = row.id,
                        enabled = row.enabled,
                        conditions = R2LifecycleConditions(row.prefix),
                        deleteObjectsTransition =
                            when (row.deleteMode) {
                                R2LifecycleDeleteMode.NONE -> null
                                R2LifecycleDeleteMode.AGE -> R2LifecycleCondition.Age(row.deleteAgeSeconds.trim().toLongOrNull() ?: 0L)
                                R2LifecycleDeleteMode.DATE -> R2LifecycleCondition.Date(row.deleteDate)
                            },
                        abortMultipartUploadsTransition = row.abortAgeSeconds.trim().ifEmpty { null }?.toLongOrNull()
                            ?.let(R2LifecycleCondition::Age),
                        storageClassTransitions =
                            row.transitions.map { transition ->
                                val condition =
                                    when (transition.conditionType) {
                                        R2LifecycleConditionType.AGE ->
                                            R2LifecycleCondition.Age(transition.ageSeconds.trim().toLongOrNull() ?: 0L)
                                        R2LifecycleConditionType.DATE -> R2LifecycleCondition.Date(transition.utcDate)
                                    }
                                R2LifecycleStorageTransition(condition, transition.storageClass)
                            },
                    )
                }
                R2ConfigurationWrite.PutLifecycle(target, rules)
            }
            R2ConfigurationPanel.LOCAL_UPLOADS ->
                R2ConfigurationWrite.PutLocalUploads(target, mutable.value.localUploadsEnabled)
            R2ConfigurationPanel.MANAGED_DOMAIN, R2ConfigurationPanel.CUSTOM_DOMAINS -> null
            // Sippy has no B1 rules document and no B1 write path.
            R2ConfigurationPanel.SIPPY -> null
            R2ConfigurationPanel.LOCK -> {
                val rules = mutable.value.lock.map { row ->
                    val condition =
                        when (row.conditionType) {
                            R2LockConditionType.AGE_SECONDS ->
                                R2LockCondition.Age(row.ageSeconds.trim().toLongOrNull() ?: 0L)
                            R2LockConditionType.DATE -> R2LockCondition.Date(row.date)
                            R2LockConditionType.INDEFINITE -> R2LockCondition.Indefinite
                        }
                    R2LockRule(id = row.id, enabled = row.enabled, prefix = row.prefix, condition = condition)
                }
                R2ConfigurationWrite.PutLock(target, rules)
            }
        }
    }

    private fun previewDomain() {
        val write = domainDraftWrite() ?: run {
            mutable.value = mutable.value.copy(validationMessage = "请先补全域名配置输入")
            return
        }
        val validation = R2DomainConfigurationEditing.validate(write)
        if (!validation.valid) {
            mutable.value = mutable.value.copy(validationMessage = validation.message)
            return
        }
        val gate = domainGateMessage(write)
        if (gate != null) {
            mutable.value = mutable.value.copy(validationMessage = gate, canPreview = false)
            return
        }
        mutable.value = mutable.value.copy(writing = true, preview = null, notice = null)
        requestDomainPreview(write)
    }

    /** Builds the typed domain write of the active panel, or null when the draft is incomplete. */
    private fun domainDraftWrite(): R2DomainConfigurationWrite? {
        val target = target() ?: return null
        return when (mutable.value.panel) {
            R2ConfigurationPanel.MANAGED_DOMAIN ->
                R2DomainConfigurationWrite.PutManagedDomain(target, mutable.value.managedDomainEnabled)
            R2ConfigurationPanel.CUSTOM_DOMAINS -> {
                val selected = mutable.value.selectedDomain
                when {
                    mutable.value.domainDeleteRequested ->
                        selected?.let { R2DomainConfigurationWrite.DeleteCustomDomain(target, it) }
                    selected != null -> {
                        val edit = mutable.value.customDomainEdit
                        if (edit.enabled == null && edit.minTls == null) {
                            null
                        } else {
                            R2DomainConfigurationWrite.EditCustomDomain(
                                target,
                                selected,
                                R2CustomDomainEditInput(enabled = edit.enabled, minTls = edit.minTls),
                            )
                        }
                    }
                    else -> {
                        val create = mutable.value.customDomainCreate
                        R2DomainConfigurationWrite.AddCustomDomain(
                            target,
                            R2CustomDomainCreateInput(
                                domain = create.domain,
                                zoneId = create.zoneId,
                                enabled = create.enabled,
                                minTls = create.minTls,
                            ),
                        )
                    }
                }
            }
            else -> null
        }
    }

    /**
     * Domain gates: public exposure, exact target names, a separate TLS-downgrade acknowledgement and
     * the destructive delete confirmation. A static registration risk is never lowered here.
     */
    private fun domainGateMessage(write: R2DomainConfigurationWrite): String? {
        val current = mutable.value
        if (!current.open) return "请先打开桶设置"
        if (domainSession == null) return DOMAIN_NOT_WIRED
        if (current.loading) return "域名配置仍在加载，请稍候"
        if (!current.editable) return current.readOnlyReason ?: "当前域名配置不可编辑"
        if (write.target.jurisdiction != current.target.jurisdiction ||
            write.target.bucket != current.target.bucket.trim()
        ) {
            return "目标已变化，请重新加载该桶的域名配置"
        }
        val publicImpact =
            when (write) {
                is R2DomainConfigurationWrite.PutManagedDomain -> true
                is R2DomainConfigurationWrite.AddCustomDomain -> write.input.enabled
                is R2DomainConfigurationWrite.EditCustomDomain -> write.edit.enabled == true
                is R2DomainConfigurationWrite.DeleteCustomDomain -> false
            }
        if (publicImpact && !current.risks.publicExposureAcknowledged) {
            return "公网入口变化会改变对外可见性，请先单独确认公开访问影响"
        }
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain -> {
                if (current.typedTargetName != write.target.bucket) return "请输入与目标完全一致的桶名后再预览"
            }
            is R2DomainConfigurationWrite.AddCustomDomain -> {
                if (current.typedTargetName != write.target.bucket) return "请输入与目标完全一致的桶名后再预览"
                if (current.typedDomainName != write.input.domain) return "请输入与目标完全一致的域名后再预览"
                if (R2DomainConfigurationEditing.isTlsDowngrade("1.2", write.input.minTls) &&
                    !current.risks.tlsDowngradeAcknowledged
                ) {
                    return "降低最低 TLS 版本属于安全降级，请单独确认"
                }
            }
            is R2DomainConfigurationWrite.EditCustomDomain -> {
                if (current.typedTargetName != write.target.bucket) return "请输入与目标完全一致的桶名后再预览"
                if (current.typedDomainName != write.domain) return "请输入与目标完全一致的域名后再预览"
                val row = current.customDomains.singleOrNull { it.domain == write.domain }
                val target = write.edit.minTls
                if (target != null && R2DomainConfigurationEditing.isTlsDowngrade(row?.minTls, target) &&
                    !current.risks.tlsDowngradeAcknowledged
                ) {
                    return "降低最低 TLS 版本属于安全降级，请单独确认"
                }
            }
            is R2DomainConfigurationWrite.DeleteCustomDomain -> {
                if (!current.risks.destructiveImpactAcknowledged) return "删除自定义域是破坏性操作，请先确认影响"
                if (current.typedTargetName != write.target.bucket) return "请输入与目标完全一致的桶名后再预览"
                if (current.typedDomainName != write.domain) return "请输入与目标完全一致的域名后再预览"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ batch B3 Sippy surface

    private fun sippyBaseState(): R2SippySurfaceUi {
        val credential = sippyCredentials.publicState
        return R2SippySurfaceUi(
            available = sippySession != null,
            provider = credential.provider,
            secureReady = credential.secureReady,
            secureEpoch = credential.secureEpoch,
            credentialRevision = credential.revision,
            draftComplete = credential.complete,
            error = if (sippySession == null) SIPPY_NOT_WIRED else credential.error,
        )
    }

    /** Mirrors the safe credential metadata into the surface state; never a credential value. */
    private fun syncSippy() {
        val credential = sippyCredentials.publicState
        mutable.value =
            mutable.value.copy(
                sippy = mutable.value.sippy.copy(
                    available = sippySession != null,
                    provider = credential.provider,
                    secureReady = credential.secureReady,
                    secureEpoch = credential.secureEpoch,
                    credentialRevision = credential.revision,
                    draftComplete = credential.complete,
                    error = if (sippySession == null) SIPPY_NOT_WIRED else credential.error,
                ),
            )
    }

    /**
     * Any credential or provider change revokes the issued preview first; nothing is sent and a
     * stale confirmation can never be replayed against the edited draft.
     */
    private fun onSippyDraftChanged() {
        // Any credential/provider change revokes the issued preview and the acknowledgements that
        // belonged to it; the typed bucket name is a non-secret convenience and may stay.
        mutable.value = mutable.value.copy(
            preview = null,
            sippy = mutable.value.sippy.copy(
                notice = null,
                disclosureAcknowledged = false,
                credentialsAcknowledged = false,
            ),
        )
        requestCancel()
        syncSippy()
        if (mutable.value.panel == R2ConfigurationPanel.SIPPY) refreshSippy()
    }

    /**
     * The window protection of this editing session went away: no issued preview may survive it and
     * no input may stay editable. The owner already withdrew its material before calling this.
     */
    private fun onSippySecureStateLost() {
        if (closed) return
        mutable.value = mutable.value.copy(
            preview = null,
            sippy = mutable.value.sippy.copy(
                writing = false,
                // The acknowledgements and the disable request belonged to the lost session.
                disclosureAcknowledged = false,
                credentialsAcknowledged = false,
                disableAcknowledged = false,
                disableRequested = false,
            ),
        )
        requestCancel()
        syncSippy()
        if (mutable.value.panel == R2ConfigurationPanel.SIPPY) refreshSippy()
    }

    /**
     * The actual editor command of the live Sippy route, resolved from the current private draft and
     * the current window protection. A prepared plan id, a cached command or a masked value is never
     * used as input here.
     */
    internal fun liveSippyCommand(): R2Command? {
        if (closed || !mutable.value.open) return null
        if (mutable.value.panel != R2ConfigurationPanel.SIPPY) return null
        val context = bound ?: return null
        if (context != actual()) return null
        return sippyWrite()?.let { R2Command.Sippy(it) }
    }

    private fun sippyWrite(): R2SippyWrite? {
        if (mutable.value.panel != R2ConfigurationPanel.SIPPY) return null
        val target = target() ?: return null
        if (mutable.value.sippy.disableRequested) return R2SippyWrite.DeleteSippy(target)
        return sippyCredentials.liveWrite(target)
    }

    private fun refreshSippy() {
        val write = sippyWrite()
        val issue = sippyIssue(write)
        mutable.value =
            mutable.value.copy(
                canPreview = write != null && issue == null,
                validationMessage = issue,
                sippy = mutable.value.sippy.copy(writing = false),
            )
        syncSippy()
    }

    private fun sippyIssue(write: R2SippyWrite?): String? {
        val current = mutable.value
        if (!current.open) return "请先打开桶设置"
        if (sippySession == null) return SIPPY_NOT_WIRED
        if (current.loading) return "Sippy 配置仍在加载，请稍候"
        if (!sippyCredentials.isSecureNow()) return SIPPY_SECURE_REQUIRED
        val snapshot = current.sippy.snapshot ?: return "请先刷新当前 Sippy 配置"
        if (!snapshot.editable) return snapshot.readOnlyReason ?: SIPPY_READ_ONLY
        if (write == null) {
            return if (current.sippy.disableRequested) "当前没有可停用的 Sippy 配置" else SIPPY_DRAFT_INCOMPLETE
        }
        // Non-visual gates: an exact target name and the explicit disclosure are required for both
        // operations; a PUT additionally needs the destination access-key acknowledgement.
        if (current.sippy.typedBucketName.trim() != current.target.bucket.trim()) {
            return "请输入完整的目标桶名以确认（用于防止写错桶）"
        }
        return when (write) {
            is R2SippyWrite.PutSippy -> {
                if (!current.sippy.disclosureAcknowledged) {
                    return "请确认：本次会把源与目标凭据随请求提交给 Cloudflare 一次（不保存、不回显、不可从公开配置恢复）"
                }
                if (current.sippy.credentialsAcknowledged) null
                else "请确认目标为本桶已有的 R2 Access Key，且本次只提交一次"
            }
            is R2SippyWrite.DeleteSippy ->
                if (current.sippy.disableAcknowledged) null
                else "请确认：停用只影响新的按需迁移，已复制对象保留，且不会删除任何对象"
        }
    }

    private fun previewSippy() {
        val write = sippyWrite()
        val issue = sippyIssue(write)
        if (write == null || issue != null) {
            mutable.value = mutable.value.copy(validationMessage = issue, canPreview = false)
            return
        }
        mutable.value =
            mutable.value.copy(
                writing = true,
                preview = null,
                notice = null,
                sippy = mutable.value.sippy.copy(notice = null),
            )
        requestSippyPreview(write)
    }

    private fun reloadSippy(context: R2BrowserContext, target: R2WriteTarget, generation: Long) {
        val loader = sippySession
        if (loader == null) {
            mutable.value =
                mutable.value.copy(
                    loading = false,
                    canPreview = false,
                    sippy = mutable.value.sippy.copy(available = false, error = SIPPY_NOT_WIRED),
                )
            return
        }
        job =
            scope.launch {
                try {
                    val snapshot = loader(context).getSippy(target)
                    if (!valid(context, generation)) return@launch
                    mutable.value =
                        mutable.value.copy(
                            loading = false,
                            error = null,
                            sippy =
                                mutable.value.sippy.copy(
                                    available = true,
                                    snapshot = snapshotUi(snapshot),
                                    snapshotError = null,
                                ),
                        )
                    refresh()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    if (valid(context, generation)) {
                        // A 404/refused/failed read is never "no configuration": only the error is
                        // shown, the previous safe snapshot is dropped and no preview is offered.
                        mutable.value =
                            mutable.value.copy(
                                loading = false,
                                canPreview = false,
                                editable = false,
                                sippy =
                                    mutable.value.sippy.copy(
                                        available = true,
                                        snapshot = null,
                                        snapshotError = safe(failure),
                                    ),
                            )
                    }
                }
            }
    }

    private fun snapshotUi(snapshot: R2SippySnapshot): R2SippySnapshotUi =
        R2SippySnapshotUi(
            enabled = snapshot.enabled,
            editable = snapshot.editable,
            readOnlyReason = snapshot.readOnlyReason,
            provider = snapshot.source?.provider?.wireValue,
            sourceBucket = snapshot.source?.bucket,
            sourceRegion = snapshot.source?.region,
            sourceContainer = snapshot.source?.container,
            sourceBucketUrl = snapshot.source?.bucketUrl,
            destinationBucket = snapshot.destination?.bucket,
            destinationAccount = snapshot.destination?.account,
            detailsProvided = snapshot.detailsProvided,
        )

    private fun updateCors(transform: (List<R2CorsDraft>) -> List<R2CorsDraft>) {
        mutable.value = mutable.value.copy(cors = transform(mutable.value.cors))
        refresh()
    }

    private fun updateLifecycle(transform: (List<R2LifecycleDraft>) -> List<R2LifecycleDraft>) {
        mutable.value = mutable.value.copy(lifecycle = transform(mutable.value.lifecycle))
        refresh()
    }

    private fun updateLock(transform: (List<R2LockDraft>) -> List<R2LockDraft>) {
        mutable.value = mutable.value.copy(lock = transform(mutable.value.lock))
        refresh()
    }

    private fun safe(failure: Exception): String =
        (failure as? CloudFailure)?.publicMessage ?: "无法读取该桶的配置，请检查权限与地区后重试"

    private fun draftOf(rule: R2CorsRule) =
        R2CorsDraft(
            id = rule.id.orEmpty(),
            methods = rule.allowed.methods.joinToString(", "),
            origins = rule.allowed.origins.joinToString(", "),
            allowedHeaders = rule.allowed.headers.joinToString(", "),
            exposeHeaders = rule.exposeHeaders.joinToString(", "),
            maxAgeSeconds = rule.maxAgeSeconds?.toString().orEmpty(),
        )

    private fun draftOf(rule: R2LifecycleRule): R2LifecycleDraft {
        val delete = rule.deleteObjectsTransition
        return R2LifecycleDraft(
            id = rule.id,
            enabled = rule.enabled,
            prefix = rule.conditions.prefix,
            deleteMode =
                when (delete) {
                    null -> R2LifecycleDeleteMode.NONE
                    is R2LifecycleCondition.Age -> R2LifecycleDeleteMode.AGE
                    is R2LifecycleCondition.Date -> R2LifecycleDeleteMode.DATE
                },
            deleteAgeSeconds = (delete as? R2LifecycleCondition.Age)?.maxAge?.toString().orEmpty(),
            // The provider's exact text (including 1..9 fractional digits) is the draft value.
            deleteDate = (delete as? R2LifecycleCondition.Date)?.utcDate.orEmpty(),
            abortAgeSeconds = rule.abortMultipartUploadsTransition?.maxAge?.toString().orEmpty(),
            transitions =
                rule.storageClassTransitions.map { transition ->
                    R2LifecycleTransitionDraft(
                        conditionType =
                            when (transition.condition) {
                                is R2LifecycleCondition.Date -> R2LifecycleConditionType.DATE
                                is R2LifecycleCondition.Age -> R2LifecycleConditionType.AGE
                            },
                        ageSeconds = (transition.condition as? R2LifecycleCondition.Age)?.maxAge?.toString().orEmpty(),
                        utcDate = (transition.condition as? R2LifecycleCondition.Date)?.utcDate.orEmpty(),
                        storageClass = transition.storageClass,
                    )
                },
        )
    }

    private fun draftOf(rule: R2LockRule) =
        R2LockDraft(
            id = rule.id,
            enabled = rule.enabled,
            prefix = rule.prefix,
            conditionType =
                when (rule.condition) {
                    is R2LockCondition.Age -> R2LockConditionType.AGE_SECONDS
                    is R2LockCondition.Date -> R2LockConditionType.DATE
                    R2LockCondition.Indefinite -> R2LockConditionType.INDEFINITE
                },
            ageSeconds = (rule.condition as? R2LockCondition.Age)?.maxAgeSeconds?.toString().orEmpty(),
            date = (rule.condition as? R2LockCondition.Date)?.utcDate.orEmpty(),
        )

    companion object {
        const val DOMAIN_NOT_WIRED =
            "域名配置写入尚未接通；本页只读，不会发送域名配置写入（写权限必须另行验证）"

        const val CONFIGURATION_NOT_WIRED =
            "桶设置写入尚未接通；本页只读，不会发送任何配置写入（写权限必须另行验证）"

        const val SIPPY_NOT_WIRED =
            "当前应用未接通 Sippy 配置写入；本页只读，不会发送 Sippy 配置写入"

        const val SIPPY_SECURE_REQUIRED =
            "需要先建立受保护的窗口，才能输入或提交 Sippy 凭据"

        const val SIPPY_READ_ONLY = "该 Sippy 配置当前无法解释，仅可查看"

        const val SIPPY_DRAFT_INCOMPLETE = "请填写完整的源与目标凭据后再预览"

        private fun splitList(value: String): List<String> =
            value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

private fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
    if (index !in indices) this else mapIndexed { position, value -> if (position == index) transform(value) else value }

private fun <T> List<T>.removeAtOrNull(index: Int): List<T> =
    if (index !in indices) this else filterIndexed { position, _ -> position != index }
