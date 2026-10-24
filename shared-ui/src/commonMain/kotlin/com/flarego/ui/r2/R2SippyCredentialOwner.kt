package com.flarego.ui.r2

import com.flarego.core.application.r2.R2SippyAzureSourceSecrets
import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippyGcsSourceSecrets
import com.flarego.core.application.r2.R2SippyS3SourceSecrets
import com.flarego.core.application.r2.R2SippySecretLease
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.application.r2.R2SippySourceSecrets
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2WriteTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One private credential value.
 *
 * It is deliberately **not** a data class and never renders itself: a typed secret-change event
 * built from it cannot leak the value through `toString`/log/test output. Only
 * [R2SippyCredentialOwner] reads it back, and only to build the one-shot memory-only draft lease.
 */
class R2SippySecretInput private constructor(private val raw: String) {
    internal fun reveal(): String = raw

    override fun toString(): String = "R2SippySecretInput(redacted)"

    companion object {
        fun of(value: String): R2SippySecretInput = R2SippySecretInput(value)
    }
}

/**
 * The private fields a Sippy draft can carry. Destination fields belong to the user's existing R2
 * access-key pair; source fields belong to the selected provider only.
 */
enum class R2SippySecretField {
    SOURCE_BUCKET,
    SOURCE_REGION,
    SOURCE_CONTAINER,
    SOURCE_BUCKET_URL,
    SOURCE_ACCESS_KEY_ID,
    SOURCE_SECRET_ACCESS_KEY,
    GCS_CLIENT_EMAIL,
    GCS_PRIVATE_KEY,
    AZURE_ACCOUNT_NAME,
    AZURE_ACCOUNT_KEY,
    AZURE_SAS_TOKEN,
    DESTINATION_ACCESS_KEY_ID,
    DESTINATION_SECRET_ACCESS_KEY,
}

/**
 * The client-side safety limit of one credential value (UTF-8 bytes).
 *
 * It is this app's editing bound, not a provider limit: an over-long entry is rejected (never
 * truncated and never echoed) so a value can never grow into an unbounded in-memory buffer.
 */
const val R2_SIPPY_MAX_INPUT_BYTES: Int = 8 * 1024

/**
 * The only Sippy credential state the UI/AppState may read.
 *
 * It carries provider and *presence* metadata plus a non-secret revision. There is no credential
 * value, length, prefix, mask or digest here: a masked value must never be treated as the real value
 * and nothing in this object can rebuild the material.
 */
data class R2SippyCredentialUiState(
    val provider: R2SippyProvider = R2SippyProvider.AWS,
    val providers: List<R2SippyProvider> = R2SippyProvider.entries.toList(),
    /** Fields that currently have a value; presence only, never the value. */
    val filled: Set<R2SippySecretField> = emptySet(),
    /** True when a complete, validated draft lease exists (still without any readable value). */
    val complete: Boolean = false,
    /** Non-secret monotonic revision: bumps on every edit and on every new binding. */
    val revision: Long = 0,
    /** True only while the real window protection for this draft is active. */
    val secureReady: Boolean = false,
    /** The window generation this draft's editing session is bound to. */
    val windowGeneration: Long? = null,
    /**
     * Opaque, non-secret monotone identity of the **editing session** behind this state.
     *
     * The composition captures it together with the mounted secret surface and reports that exact
     * value when the surface leaves. It is not an execution authority, not derived from any
     * credential and never leaves the UI/owner; an older value can never confirm a newer retirement.
     */
    val secureEpoch: Long? = null,
    /** Fixed app-owned message only; never provider or validation echo. */
    val error: String? = null,
)

/**
 * The app-owned, memory-only Sippy credential editor.
 *
 * Contract:
 * - values live only in this object's private map; the public [state] has presence metadata only;
 * - every edit retires the previous draft lease (its material is dropped) and produces either an
 *   incomplete draft or a **new random binding**, so an old preview can never be confirmed against
 *   edited credentials;
 * - nothing here is serializable: no draft, lease or value reaches a plan, a task, the database, a
 *   saved-state bundle or a log;
 * - secret input and the live write are refused unless the real window protection is active, and a
 *   lost/recreated window withdraws the private material (an already admitted task-owned transport
 *   is never touched here);
 * - JVM strings cannot be physically erased; releasing the references is best effort.
 */
class R2SippyCredentialOwner(
    private val secureWindow: () -> SecureWindowPort? = { null },
    /** Called after every edit that invalidates a preview (never with a value). */
    private val onDraftChanged: () -> Unit = {},
    /**
     * Called when the real window protection of this editing session is gone (window replaced,
     * detached or flag cleared), after readiness and the private material are already withdrawn. It
     * lets the app revoke an issued preview immediately instead of waiting for the next intent.
     */
    private val onSecureStateLost: () -> Unit = {},
) {
    private val mutable = MutableStateFlow(R2SippyCredentialUiState())
    val state: StateFlow<R2SippyCredentialUiState> = mutable.asStateFlow()

    private val values = mutableMapOf<R2SippySecretField, String>()
    private var lease: R2SippySecretLease? = null
    private var provider = R2SippyProvider.AWS
    private var revision = 0L
    private var session: SecureWindowSession? = null
    private var sessionPort: SecureWindowPort? = null
    /** Monotone session identity; issued only by [beginSecureEditing]. */
    private var sessionEpoch = 0L
    private var epochSeq = 0L

    /**
     * One armed retirement: the exact window handle and the session epoch that armed it.
     *
     * The map (not a single slot) is what makes a re-entered editor keep every outstanding handle
     * and what makes a late/duplicate report of an older surface unable to confirm a newer one.
     */
    private class Retirement(
        val port: SecureWindowPort,
        val session: SecureWindowSession,
        val epoch: Long,
    )

    private val retirements = LinkedHashMap<Long, Retirement>()
    private var lastError: String? = null

    /** Deterministic, secret-free view used by tests and by the surface. */
    val publicState: R2SippyCredentialUiState get() = mutable.value

    val isComplete: Boolean get() = lease != null

    /**
     * True only while this session was issued by the *currently attached* window and that window
     * still carries the protection. The identity/generation check against the live port is what
     * makes a replaced (or not yet destroyed) window lose readiness immediately, instead of the old
     * session object quietly staying "protected" on a window that is no longer the app's window.
     */
    fun isSecureNow(): Boolean {
        val current = session ?: return false
        val issuing = sessionPort
        val live = secureWindow()
        val ok =
            issuing != null && live === issuing && current.generation == issuing.generation &&
                current.isProtected()
        if (!ok) revokeSession()
        return ok
    }

    /**
     * The application attached a window generation.
     *
     * The attach is what revokes a previous readiness: when the new generation is not the one this
     * session was issued for, the secret eligibility and the private material are dropped here, so a
     * caller does not have to wait for the next `isSecureNow()` nor for the old Activity's
     * `onDestroy`. The new window must acquire its own protection before anything is ready again.
     */
    fun onWindowAttached(generation: Long) {
        val current = session ?: return
        val live = secureWindow()
        if (current.generation == generation && live === sessionPort && live?.generation == generation) return
        revokeSession()
    }

    /**
     * Requests the real window protection for this editor.
     *
     * Returns true only when the flag is already applied for this generation, so the caller may
     * render secret inputs. A missing bridge returns false and keeps every secret input disabled.
     */
    fun beginSecureEditing(): Boolean {
        if (isSecureNow()) return true
        // A new session gets its own epoch and its own handle. Outstanding retirements are *kept*:
        // their windows still hold their own protection until their clean frame is observed.
        val port = secureWindow() ?: run { publish(); return false }
        val next = port.request(SecurePurpose.SIPPY_CREDENTIALS) ?: run { publish(); return false }
        epochSeq++
        sessionEpoch = epochSeq
        session = next
        sessionPort = port
        val ready = next.isProtected()
        if (!ready) {
            port.release(next)
            session = null
            sessionPort = null
            sessionEpoch = 0
            dropPrivateMaterial()
        }
        publish()
        return ready
    }

    /** Page leave / explicit end: revoke eligibility and material, then release the purpose. */
    fun endSecureEditing() {
        if (session == null) {
            if (values.isNotEmpty() || lease != null) dropPrivateMaterial()
            publish()
            return
        }
        revokeSession()
    }

    /**
     * The Activity/window was destroyed: only the matching generation loses its material, so a stale
     * owner can never affect a new window or a new editing session.
     */
    fun onWindowDetached(generation: Long) {
        if (session?.generation != generation) return
        revokeSession()
    }

    /** Clears every private value and the draft lease (route/target change, explicit cancel). */
    fun withdraw() {
        dropPrivateMaterial()
        revision++
        lastError = null
        publish()
    }

    fun selectProvider(next: R2SippyProvider): Boolean {
        if (!isSecureNow()) return false
        if (next == provider) return true
        // A provider switch never carries any previous private value across.
        dropPrivateMaterial()
        provider = next
        revision++
        lastError = null
        publish()
        onDraftChanged()
        return true
    }

    /**
     * Stores one private value. Refused without the real window protection, and refused for a field
     * that does not belong to the selected provider.
     */
    fun setField(field: R2SippySecretField, input: R2SippySecretInput): Boolean {
        if (!isSecureNow()) return false
        if (!fieldBelongsTo(field, provider)) return false
        val value = input.reveal()
        if (value.encodeToByteArray().size > R2_SIPPY_MAX_INPUT_BYTES) {
            // Rejected, never truncated and never echoed back. A refused *edit* is still an edit: the
            // previous draft material is retired and the issued preview/acknowledgements are revoked,
            // so a stale draft can never be submitted with the old credentials.
            lastError = R2_SIPPY_INPUT_TOO_LONG
            withdrawMaterial()
            revision++
            publish()
            onDraftChanged()
            return false
        }
        if (value.isEmpty()) {
            values.remove(field)
        } else {
            values[field] = value
        }
        // Any edit invalidates the previous binding and therefore any issued preview.
        withdrawMaterial()
        revision++
        lastError = null
        rebuild()
        publish()
        onDraftChanged()
        return true
    }

    /**
     * The actual editor command for the current draft, or null when it is incomplete or not
     * protected. It is resolved from this live draft, never from a prepared plan or command.
     */
    fun liveWrite(target: R2WriteTarget): R2SippyWrite.PutSippy? {
        if (!isSecureNow()) return null
        val current = lease ?: return null
        return R2SippyWrite.PutSippy(target, current)
    }

    /**
     * The minimal private getter for the control that is drawing [field] right now.
     *
     * It is only for the value of an already-protected, already-valid control: it is never part of the
     * public state, never logged and never stored by this owner beyond the draft map. The caller
     * (the control being drawn) and the UI framework hold unavoidable transient copies; JVM string
     * erasure is best effort and that limit is documented rather than claimed away. Returns null when
     * the window is not protected or the field does not belong to the current provider.
     */
    fun currentValue(field: R2SippySecretField): R2SippySecretInput? {
        if (!isSecureNow()) return null
        if (!fieldBelongsTo(field, provider)) return null
        val value = values[field] ?: return null
        return R2SippySecretInput.of(value)
    }

    /**
     * The mounted secret surface of exactly [epoch] reported that it left the composition.
     *
     * It is only a composition fact: the window itself decides when a frame without that surface has
     * been committed, and only then releases the retired handle. If it cannot observe such a frame the
     * handle stays protected (fail-closed).
     *
     * [epoch] is the value the surface captured while it drew ([R2SippyCredentialUiState.secureEpoch]).
     * Only the retirement still pending for **exactly** that epoch is confirmed: a late or duplicate
     * report of an older, still-pending retirement confirms *that* older handle (it is the surface that
     * drew it, so this is the correct target) and can never confirm a newer one; an unknown, foreign,
     * already-confirmed or null epoch is a no-op. No report ever releases a handle it does not name.
     */
    fun onSecretSurfaceRemoved(epoch: Long?) {
        if (epoch == null) return
        // Confirmation only: an outstanding retirement of *that* epoch is confirmed. A report for a
        // live session, an older session, a foreign window or an already confirmed epoch is a no-op, so
        // a spurious/late/duplicate report can neither revoke a live session nor confirm another one.
        val pending = retirements.remove(epoch) ?: return
        pending.port.contentRemoved(pending.session)
    }

    /**
     * Opaque per-mount identity of the composed panel that can draw the secret surface.
     *
     * It carries nothing: not a value, not a digest, not an execution authority. It only lets the owner
     * tell "the panel that hosts the surface was really disposed" apart from "a subtree of it was
     * re-created by a recomposition".
     */
    class SurfaceMount internal constructor()

    private var liveMount: SurfaceMount? = null

    /** Called once per composition mount of the panel (from `remember`), never per recomposition. */
    internal fun newSurfaceMount(): SurfaceMount {
        val mount = SurfaceMount()
        liveMount = mount
        return mount
    }

    /**
     * The panel that hosts the sensitive surface left the composition entirely (route change, tab
     * switch, screen close) — the one case no explicit intent reports.
     *
     * Only the *current* mount may do this: a superseded mount (a subtree re-creation of the same
     * panel) is ignored, so a recomposition never drops a live editing session. The session is revoked
     * here (eligibility/material first) and, because the surface is already gone, the exact handle is
     * handed to the window for its clean-frame barrier immediately. No timeout, no forced release: a
     * window that cannot observe the commit keeps protecting.
     */
    internal fun onSurfaceMountDisposed(mount: SurfaceMount) {
        if (liveMount !== mount) return
        liveMount = null
        if (session != null) revokeSession()
        // Every handle this screen already retired is gone as well — including one whose epoch never
        // got a mounted secret surface (begin+end before any recomposition), which therefore has no
        // branch report of its own. Confirming them here hands each exact handle to its own window's
        // clean-frame barrier; nothing is released and a window that cannot observe the commit keeps
        // protecting.
        if (retirements.isEmpty()) return
        val pending = retirements.values.toList()
        retirements.clear()
        for (retirement in pending) retirement.port.contentRemoved(retirement.session)
    }

    /** The binding revision of the live draft, for display/logging without any credential. */
    val bindingRevision: Long get() = revision

    fun dispose() = endSecureEditing()

    /**
     * The single revocation path: eligibility and material go first, the window protection last.
     *
     * Publishing the not-ready state before releasing the purpose means the secret surface can never
     * be left (or captured) with the flag already cleared while this owner still reports readiness.
     * The purpose is only released while the issuing window is still the bound window: an outgoing
     * window keeps its own flag until its owner `close()`, so a leaving surface is never uncovered
     * prematurely. An already admitted app-owned transport is never touched here.
     */
    private fun revokeSession() {
        val current = session
        val issuing = sessionPort
        val epoch = sessionEpoch
        session = null
        sessionPort = null
        sessionEpoch = 0
        dropPrivateMaterial()
        publish()
        if (current != null && issuing != null && epoch != 0L && secureWindow() === issuing) {
            // Two-phase retirement: arm it and keep the protection. The bound window releases this
            // exact handle only after the composition reported that the surface is gone **and** a
            // clean frame was committed. If it cannot observe that commit the handle stays protected:
            // fail-closed means keeping the protection, never releasing it early.
            if (issuing.armRetirement(current)) {
                retirements[epoch] = Retirement(issuing, current, epoch)
            }
        }
        onSecureStateLost()
    }

    /** Drops only the draft lease: the values are still being edited. */
    private fun withdrawMaterial() {
        lease?.retire()
        lease = null
    }

    /**
     * Drops the draft lease *and* every private value (lost window, page leave, route change,
     * provider switch). Only the references are released; JVM strings are best effort.
     */
    private fun dropPrivateMaterial() {
        withdrawMaterial()
        values.clear()
    }

    private fun rebuild() {
        val source = sourceSecrets(provider, values) ?: return
        val destination = destinationSecrets(values) ?: return
        lease =
            try {
                R2SippySecrets.draft(source, destination)
            } catch (_: Exception) {
                // The local validation is app-owned and returns a fixed message; the value is never
                // echoed back into the error.
                lastError = R2_SIPPY_INVALID_INPUT
                null
            }
    }

    private fun publish() {
        mutable.value =
            R2SippyCredentialUiState(
                provider = provider,
                filled = values.keys.toSet(),
                complete = lease != null,
                revision = revision,
                secureReady = session?.isProtected() == true,
                windowGeneration = session?.generation,
                secureEpoch = if (session != null) sessionEpoch else null,
                error = lastError,
            )
    }

    private fun fieldBelongsTo(field: R2SippySecretField, provider: R2SippyProvider): Boolean =
        when (field) {
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID, R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY -> true
            R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretField.SOURCE_SECRET_ACCESS_KEY ->
                provider == R2SippyProvider.AWS || provider == R2SippyProvider.S3
            R2SippySecretField.SOURCE_BUCKET ->
                provider == R2SippyProvider.AWS || provider == R2SippyProvider.GCS
            R2SippySecretField.SOURCE_REGION -> provider == R2SippyProvider.AWS
            R2SippySecretField.SOURCE_BUCKET_URL -> provider == R2SippyProvider.S3
            R2SippySecretField.SOURCE_CONTAINER -> provider == R2SippyProvider.AZURE
            R2SippySecretField.GCS_CLIENT_EMAIL, R2SippySecretField.GCS_PRIVATE_KEY -> provider == R2SippyProvider.GCS
            R2SippySecretField.AZURE_ACCOUNT_NAME,
            R2SippySecretField.AZURE_ACCOUNT_KEY,
            R2SippySecretField.AZURE_SAS_TOKEN,
            -> provider == R2SippyProvider.AZURE
        }

    private fun sourceSecrets(
        provider: R2SippyProvider,
        values: Map<R2SippySecretField, String>,
    ): R2SippySourceSecrets? =
        when (provider) {
            R2SippyProvider.AWS -> {
                val bucket = values[R2SippySecretField.SOURCE_BUCKET]
                val region = values[R2SippySecretField.SOURCE_REGION]
                val keyId = values[R2SippySecretField.SOURCE_ACCESS_KEY_ID]
                val secret = values[R2SippySecretField.SOURCE_SECRET_ACCESS_KEY]
                if (bucket == null || region == null || keyId == null || secret == null) null
                else R2SippyAwsSourceSecrets(keyId, secret, bucket, region)
            }
            R2SippyProvider.GCS -> {
                val bucket = values[R2SippySecretField.SOURCE_BUCKET]
                val email = values[R2SippySecretField.GCS_CLIENT_EMAIL]
                val key = values[R2SippySecretField.GCS_PRIVATE_KEY]
                if (bucket == null || email == null || key == null) null
                else R2SippyGcsSourceSecrets(email, key, bucket)
            }
            R2SippyProvider.S3 -> {
                val url = values[R2SippySecretField.SOURCE_BUCKET_URL]
                val keyId = values[R2SippySecretField.SOURCE_ACCESS_KEY_ID]
                val secret = values[R2SippySecretField.SOURCE_SECRET_ACCESS_KEY]
                if (url == null || keyId == null || secret == null) null
                else R2SippyS3SourceSecrets(keyId, secret, url)
            }
            R2SippyProvider.AZURE -> {
                val account = values[R2SippySecretField.AZURE_ACCOUNT_NAME]
                val container = values[R2SippySecretField.SOURCE_CONTAINER]
                val key = values[R2SippySecretField.AZURE_ACCOUNT_KEY]
                val sas = values[R2SippySecretField.AZURE_SAS_TOKEN]
                if (account == null || container == null) null
                else if ((key == null) == (sas == null)) null
                else R2SippyAzureSourceSecrets(account, container, key, sas)
            }
        }

    private fun destinationSecrets(values: Map<R2SippySecretField, String>): R2SippyDestinationSecrets? {
        val keyId = values[R2SippySecretField.DESTINATION_ACCESS_KEY_ID] ?: return null
        val secret = values[R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY] ?: return null
        return R2SippyDestinationSecrets(keyId, secret)
    }

    private companion object {
        const val R2_SIPPY_INVALID_INPUT = "凭据未通过本地字段校验，请检查后重试（不会保留或回显已输入内容）"
        const val R2_SIPPY_INPUT_TOO_LONG = "单项凭据超过应用编辑上限（$R2_SIPPY_MAX_INPUT_BYTES 字节）；超限内容已被拒绝且不会保留"
    }
}
