package com.flarego.core.application.r2

import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2SippySourceRef
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.invalidInputFailure
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

/** The public source metadata of one draft (no keys, no secrets). */
internal fun sourceRefOf(secrets: R2SippySourceSecrets): R2SippySourceRef =
    when (secrets) {
        is R2SippyAwsSourceSecrets -> R2SippySourceRef(R2SippyProvider.AWS, bucket = secrets.bucket, region = secrets.region)
        is R2SippyGcsSourceSecrets -> R2SippySourceRef(R2SippyProvider.GCS, bucket = secrets.bucket)
        is R2SippyS3SourceSecrets -> R2SippySourceRef(R2SippyProvider.S3, bucketUrl = secrets.bucketUrl)
        is R2SippyAzureSourceSecrets -> R2SippySourceRef(R2SippyProvider.AZURE, container = secrets.container)
    }

/**
 * The private source credential of one Sippy draft.
 *
 * These objects exist only inside [R2SippyPrivateSecrets]; they are not data classes, have no
 * serializable form, and every `toString` is redacted. Secret values are readable only inside this
 * module for the one-shot encoder.
 */
sealed interface R2SippySourceSecrets {
    val provider: R2SippyProvider
}

class R2SippyAwsSourceSecrets(
    internal val accessKeyId: String,
    internal val secretAccessKey: String,
    internal val bucket: String,
    internal val region: String,
) : R2SippySourceSecrets {
    override val provider: R2SippyProvider get() = R2SippyProvider.AWS

    override fun toString(): String = "R2SippyAwsSourceSecrets(redacted)"
}

class R2SippyGcsSourceSecrets(
    internal val clientEmail: String,
    internal val privateKey: String,
    internal val bucket: String,
) : R2SippySourceSecrets {
    override val provider: R2SippyProvider get() = R2SippyProvider.GCS

    override fun toString(): String = "R2SippyGcsSourceSecrets(redacted)"
}

class R2SippyS3SourceSecrets(
    internal val accessKeyId: String,
    internal val secretAccessKey: String,
    internal val bucketUrl: String,
) : R2SippySourceSecrets {
    override val provider: R2SippyProvider get() = R2SippyProvider.S3

    override fun toString(): String = "R2SippyS3SourceSecrets(redacted)"
}

/** Azure requires exactly one of [accountKey] and [sasToken]. */
class R2SippyAzureSourceSecrets(
    internal val accountName: String,
    internal val container: String,
    internal val accountKey: String? = null,
    internal val sasToken: String? = null,
) : R2SippySourceSecrets {
    override val provider: R2SippyProvider get() = R2SippyProvider.AZURE

    override fun toString(): String = "R2SippyAzureSourceSecrets(redacted)"
}

/** The existing R2 access-key pair the user supplies for Sippy's destination writes. */
class R2SippyDestinationSecrets(
    internal val accessKeyId: String,
    internal val secretAccessKey: String,
) {
    override fun toString(): String = "R2SippyDestinationSecrets(redacted)"
}

/** The complete private material of one draft; never serialized, logged or put in a data class. */
class R2SippyPrivateSecrets internal constructor(
    internal val source: R2SippySourceSecrets,
    internal val destination: R2SippyDestinationSecrets,
) {
    override fun toString(): String = "R2SippyPrivateSecrets(redacted)"
}

/**
 * The ownership state of one in-memory secret lease.
 *
 * [CLAIMED] exists only on the editor's draft: the material was transferred to the app-owned task
 * claim, so the draft can neither retire nor admit it any more.
 */
enum class R2SippyLeaseState { DRAFT, CLAIMED, SENDING, CONSUMED, RETIRED }

/**
 * One memory-only Sippy credential lease.
 *
 * - A draft can be retired by the editor; retiring never closes an admitted transport.
 * - The final guard admits the draft exactly once (DRAFT -> SENDING) and hands the material to the
 *   one-shot encoder; a second admission/consumption is refused, so a repeated confirmation cannot
 *   produce a second HTTP request.
 * - After the transport ends, [complete] moves SENDING -> CONSUMED. The material is then unreachable.
 * - [bindingId] is an opaque random handle, never a hash of the secret, so an old preview can be
 *   rejected when the draft changes without persisting a comparable secret digest.
 *
 * JVM string immutability means physical erasure is best effort; this type does not promise more.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2SippySecretLease internal constructor(
    val bindingId: String,
    val provider: R2SippyProvider,
    val safeSource: R2SippySourceRef,
    material: R2SippyPrivateSecrets,
) {
    private val state = AtomicReference(R2SippyLeaseState.DRAFT)

    /**
     * The one material reference. It is taken out of this holder on retirement, admission or
     * transport completion, so a retained retired/consumed lease no longer keeps the secret
     * strings reachable. The owner after admission is the transport's one-shot encoded payload.
     */
    private val materialRef = AtomicReference<R2SippyPrivateSecrets?>(material)

    val leaseState: R2SippyLeaseState get() = state.load()

    val isDraft: Boolean get() = leaseState == R2SippyLeaseState.DRAFT

    /** True once the lease has been admitted by the final guard (or is already sending). */
    val isAdmitted: Boolean get() = leaseState == R2SippyLeaseState.SENDING

    /** Deterministic internal ownership observation for tests; never depends on GC timing. */
    internal val retainsMaterial: Boolean get() = materialRef.load() != null

    /** Takes (and thereby releases) the holder's only material reference exactly once. */
    private fun takeMaterial(): R2SippyPrivateSecrets? {
        while (true) {
            val current = materialRef.load() ?: return null
            if (materialRef.compareAndSet(current, null)) return current
        }
    }

    /** Draft cancellation/retirement; an admitted transport is never closed by the editor. */
    fun retire(): Boolean {
        if (!state.compareAndSet(R2SippyLeaseState.DRAFT, R2SippyLeaseState.RETIRED)) return false
        takeMaterial()
        return true
    }

    /**
     * Read-only borrow for the one-shot encoder. Only a draft can be encoded; the lease keeps
     * ownership until [admitForTransport] takes it.
     */
    internal fun borrowMaterialForEncoding(): R2SippyPrivateSecrets? =
        if (state.load() == R2SippyLeaseState.DRAFT) materialRef.load() else null

    /**
     * The app-owned task claim: the material moves to a new lease instance that shares the same
     * binding id. The draft itself becomes [R2SippyLeaseState.CLAIMED], so an editor retirement
     * (or a stale UI) can never release what the admitted execution owns. Returns null when the
     * draft is no longer claimable (retired, already claimed or already used).
     */
    internal fun claimForTask(): R2SippySecretLease? {
        if (!state.compareAndSet(R2SippyLeaseState.DRAFT, R2SippyLeaseState.CLAIMED)) return null
        // The material is *moved*: the draft keeps no reference, so retire/admit through the old
        // editor object can never reach what the task owns. The binding id and public source are
        // copied so an already issued plan fingerprint stays valid.
        val moved = takeMaterial() ?: return null
        return R2SippySecretLease(bindingId, provider, safeSource, moved)
    }

    /** The final-guard admission: exactly one draft becomes the transport's owned material. */
    internal fun admitForTransport(): R2SippyPrivateSecrets? {
        if (!state.compareAndSet(R2SippyLeaseState.DRAFT, R2SippyLeaseState.SENDING)) return null
        return takeMaterial()
    }

    /** The transport ended (success, refusal or uncertainty); the lease is never reusable. */
    internal fun completeTransport() {
        if (state.compareAndSet(R2SippyLeaseState.SENDING, R2SippyLeaseState.CONSUMED)) takeMaterial()
    }

    override fun toString(): String =
        "R2SippySecretLease($bindingId, $provider, ${leaseState.name.lowercase()}, redacted)"
}

/** One reviewed Sippy write. A `PUT` enables the configuration; a `DELETE` disables it. */
sealed interface R2SippyWrite {
    val target: R2WriteTarget

    data class PutSippy(override val target: R2WriteTarget, val lease: R2SippySecretLease) : R2SippyWrite

    data class DeleteSippy(override val target: R2WriteTarget) : R2SippyWrite
}

internal fun r2SippyWriteKey(write: R2SippyWrite): String =
    when (write) {
        is R2SippyWrite.PutSippy -> "r2-put-bucket-sippy-config"
        is R2SippyWrite.DeleteSippy -> "r2-delete-bucket-sippy-config"
    }

internal const val R2_SIPPY_READ_KEY: String = "r2-get-bucket-sippy-config"

/** The one-way draft factory; every draft gets a fresh opaque binding id. */
object R2SippySecrets {
    /** The production entry: a new random binding id per draft, not derived from the secrets. */
    fun draft(
        source: R2SippySourceSecrets,
        destination: R2SippyDestinationSecrets,
    ): R2SippySecretLease = create(source, destination, randomSippyBindingId())

    /** Shared construction; the id source is private so production callers cannot reuse an id. */
    internal fun create(
        source: R2SippySourceSecrets,
        destination: R2SippyDestinationSecrets,
        bindingId: String,
    ): R2SippySecretLease {
        validateR2SippySource(source)
        validateR2SippyDestination(destination)
        if (bindingId.isBlank() || bindingId.length > 128) throw invalidInputFailure()
        return R2SippySecretLease(
            bindingId = bindingId,
            provider = source.provider,
            safeSource = sourceRefOf(source),
            material = R2SippyPrivateSecrets(source, destination),
        )
    }
}

/** Internal test-only seam; production code must use the random factory above. */
internal fun draftForTest(
    source: R2SippySourceSecrets,
    destination: R2SippyDestinationSecrets,
    bindingId: () -> String,
): R2SippySecretLease = R2SippySecrets.create(source, destination, bindingId())

/** Random opaque binding id; not a secret digest and never persisted. */
internal fun randomSippyBindingId(): String {
    val bytes = Random.nextBytes(24)
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
