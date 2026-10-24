package com.flarego.core.application.r2

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.r2ConfigurationWriteKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Read-only projection of one provider configuration document for the settings UI.
 *
 * The UI never decodes provider JSON itself: it receives these already validated typed
 * rules (or an empty projection with the same read-only reason the controller saw), plus
 * the original text for display. The projection grants no write permission.
 */
data class R2ConfigurationProjection(
    val editable: Boolean,
    val readOnlyReason: String?,
    val rawText: String,
    val cors: List<R2CorsRule> = emptyList(),
    val lifecycle: List<R2LifecycleRule> = emptyList(),
    val localUploadsEnabled: Boolean? = null,
    val lock: List<R2LockRule> = emptyList(),
)

/** Result of the single local validation facade the UI may call; it never sends anything. */
data class R2ConfigurationDraftValidation(val valid: Boolean, val message: String? = null)

/**
 * The one public bridge between the reviewed batch B1 parsers/encoders and the UI.
 *
 * Everything here reuses the same `internal` decoders and encoders as the wire client, so
 * a screen can display or validate a draft but can never grow a second, divergent parser
 * or turn an arbitrary JSON object into a write body.
 */
object R2ConfigurationEditing {
    fun project(document: R2ConfigurationDocument): R2ConfigurationProjection {
        val rawText = document.raw.toString()
        if (!document.editable) {
            return R2ConfigurationProjection(false, document.readOnlyReason, rawText)
        }
        return try {
            when (document.kind) {
                R2ConfigurationKind.CORS ->
                    R2ConfigurationProjection(true, null, rawText, cors = decodeRules(document.raw, ::decodeR2CorsRule))
                R2ConfigurationKind.LIFECYCLE ->
                    R2ConfigurationProjection(true, null, rawText, lifecycle = decodeRules(document.raw, ::decodeR2LifecycleRule))
                R2ConfigurationKind.LOCK ->
                    R2ConfigurationProjection(true, null, rawText, lock = decodeRules(document.raw, ::decodeR2LockRule))
                R2ConfigurationKind.LOCAL_UPLOADS ->
                    R2ConfigurationProjection(
                        true,
                        null,
                        rawText,
                        localUploadsEnabled = (document.raw["enabled"] as? JsonPrimitive)?.booleanOrNull,
                    )
            }
        } catch (_: CloudFailure) {
            R2ConfigurationProjection(false, document.readOnlyReason, rawText)
        }
    }

    /** Local validation only: target, jurisdiction support, rules and size bounds. */
    fun validate(write: R2ConfigurationWrite): R2ConfigurationDraftValidation =
        try {
            validateR2ConfigurationTarget(write.target)
            requireR2ConfigurationJurisdictionSupported(write.target, r2ConfigurationWriteKind(write))
            r2ConfigurationWriteBody(write)
            R2ConfigurationDraftValidation(true)
        } catch (failure: CloudFailure) {
            R2ConfigurationDraftValidation(false, failure.publicMessage)
        }

    /**
     * True for the writes that the reviewed registration classifies as destructive or that
     * can delete/abort objects; the UI adds its own explicit acknowledgement for these.
     */
    fun requiresDestructiveAcknowledgement(write: R2ConfigurationWrite): Boolean =
        when (write) {
            is R2ConfigurationWrite.PutCors -> false
            is R2ConfigurationWrite.DeleteCors -> true
            is R2ConfigurationWrite.PutLifecycle -> true
            is R2ConfigurationWrite.PutLocalUploads -> false
            is R2ConfigurationWrite.PutLock, is R2ConfigurationWrite.DeleteLock -> true
        }

    /** A `PUT` replaces the complete rule set, so preview always asks for that acknowledgement. */
    fun requiresFullReplacementAcknowledgement(write: R2ConfigurationWrite): Boolean =
        write !is R2ConfigurationWrite.DeleteCors && write !is R2ConfigurationWrite.DeleteLock

    private fun <T> decodeRules(raw: JsonObject, decode: (JsonObject) -> T): List<T> {
        val rules = raw["rules"] as? JsonArray ?: throw readOnlyFailure()
        return rules.map { element -> decode(element as? JsonObject ?: throw readOnlyFailure()) }
    }

    private fun readOnlyFailure() =
        CloudFailure(
            com.flarego.core.model.CloudFailureCode.UNSUPPORTED_CAPABILITY,
            R2_CONFIGURATION_READ_ONLY_WRITE,
        )
}
