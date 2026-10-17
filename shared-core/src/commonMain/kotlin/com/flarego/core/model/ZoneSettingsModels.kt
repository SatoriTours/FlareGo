package com.flarego.core.model

import kotlinx.serialization.json.*
import com.flarego.core.cloudflare.generated.ZoneSettingSchemas

enum class ZoneSettingKey(val route: String, val readOperation: String, val writeOperation: String, val responseId: String = route, val generic: Boolean = false) {
    AEGIS("aegis", "zone-cache-settings-get-aegis-setting", "zone-cache-settings-change-aegis-setting"),
    AUTOMATIC_PLATFORM_OPTIMIZATION("automatic_platform_optimization", "zone-settings-get-automatic-platform-optimization-setting", "zone-settings-change-automatic-platform-optimization-setting"),
    BINARY_AST("binary_ast", "zone-settings-get-binary-ast-setting", "zone-settings-change-binary-ast-setting"),
    FONTS("fonts", "zone-settings-get-fonts-setting", "zone-settings-change-fonts-setting"),
    H2_PRIORITIZATION("h2_prioritization", "zone-settings-get-h2-prioritization-setting", "zone-settings-change-h2-prioritization-setting"),
    IMAGE_RESIZING("image_resizing", "zone-settings-get-image-resizing-setting", "zone-settings-change-image-resizing-setting"),
    ORIGIN_H2_MAX_STREAMS("origin_h2_max_streams", "zone-cache-settings-get-origin-h2-max-streams-setting", "zone-cache-settings-change-origin-h2-max-streams-setting"),
    ORIGIN_MAX_HTTP_VERSION("origin_max_http_version", "zone-cache-settings-get-origin-max-http-version-setting", "zone-cache-settings-change-origin-max-http-version-setting"),
    SPEED_BRAIN("speed_brain", "zone-settings-get-speed-brain-setting", "zone-settings-change-speed-brain-setting"),
    TRANSFORMATIONS_ALLOWED_ORIGINS("transformations_allowed_origins", "zone-settings-get-transformations-allowed-origins-setting", "zone-settings-change-transformations-allowed-origins-setting", "image_resizing_allowed_origins"),
    TRANSFORMATIONS_C2PA("transformations_c2pa", "zone-settings-get-transformations-c2pa-setting", "zone-settings-change-transformations-c2pa-setting", "image_resizing_c2pa"),
    TRANSFORMATIONS_CONFIG("transformations_config", "zone-settings-get-transformations-config", "zone-settings-change-transformations-config"),
    SSL("ssl", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    ALWAYS_USE_HTTPS("always_use_https", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    MIN_TLS_VERSION("min_tls_version", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    TLS_1_3("tls_1_3", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    HTTP2("http2", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    HTTP3("http3", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    DEVELOPMENT_MODE("development_mode", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true),
    BROWSER_CACHE_TTL("browser_cache_ttl", "zone-settings-get-single-setting", "zone-settings-edit-single-setting", generic = true);
    companion object {
        fun fromId(id: String): ZoneSettingKey = entries.firstOrNull { it.route == id } ?: throw invalidInputFailure()
    }
}

data class ZoneSettingValue(val scope: ZoneScope, val key: ZoneSettingKey, val value: JsonElement, val editable: Boolean?, val raw: JsonObject)
data class UniversalSslSetting(val scope: ZoneScope, val enabled: Boolean, val editable: Boolean?, val raw: JsonObject)

enum class CachePurgeTier { FREE, PRO, BUSINESS, ENTERPRISE }
data class CachePurgeFile(val url: String, val headers: Map<String, String> = emptyMap())
sealed interface CachePurgeInput {
    data object All : CachePurgeInput
    data class Files(val files: List<CachePurgeFile>) : CachePurgeInput
    data class Hosts(val hosts: List<String>) : CachePurgeInput
    data class Tags(val tags: List<String>) : CachePurgeInput
    data class Prefixes(val prefixes: List<String>) : CachePurgeInput
}

/** Credential/session-bound verified provider evidence, never local user consent or read access. */
interface ZoneSettingsAuthority {
    /** True only with verified Cloudflare Token write permission for this operation/scope. */
    fun mayWrite(scope: ZoneScope, operationId: String): Boolean? = null
    /** Provider-verified entitlement when the target response does not expose editable. */
    fun eligible(scope: ZoneScope, operationId: String): Boolean? = null
    fun purgeTier(scope: ZoneScope): CachePurgeTier? = null
}

sealed interface ZoneSettingsTarget {
    data class Setting(val key: ZoneSettingKey) : ZoneSettingsTarget
    data object UniversalSsl : ZoneSettingsTarget
    data object Cache : ZoneSettingsTarget
}
sealed interface ZoneSettingsAction {
    val scope: ZoneScope
    data class Update(override val scope: ZoneScope, val key: ZoneSettingKey, val input: JsonObject) : ZoneSettingsAction
    data class UniversalSsl(override val scope: ZoneScope, val enabled: Boolean) : ZoneSettingsAction
    data class Purge(override val scope: ZoneScope, val input: CachePurgeInput) : ZoneSettingsAction
}

data class ZoneSettingsSnapshot(val owner: ZoneDetails, val setting: ZoneSettingValue? = null, val universal: UniversalSslSetting? = null,
    val writeAllowed: Boolean?, val purgeTier: CachePurgeTier? = null)
enum class ZoneSettingsRisk { SSL_DOWNGRADE, DISABLE_UNIVERSAL_SSL, PURGE_ALL }
class ZoneSettingsRiskAcknowledgement internal constructor(val planId: String, val risk: ZoneSettingsRisk)
data class ZoneSettingsPreview(val scope: ZoneScope, val domain: String, val target: ZoneSettingsTarget, val risk: ZoneSettingsRisk?, val impact: String, val concurrencyWarning: String)

/** Per-key fixed request schema; response enums and the whole generic union are never validators. */
fun validateZoneSettingInput(key: ZoneSettingKey, input: JsonObject) {
    val schema = ZoneSettingSchemas.input(key.route, key.generic) ?: throw CloudFailure(
        CloudFailureCode.UNSUPPORTED_CAPABILITY, "该设置输入定义尚需审核，当前只读")
    if (input.keys != setOf("value") || !zoneSettingConforms(schema, input)) throw invalidInputFailure()
    if ((input["value"] as? JsonObject)?.isEmpty() == true) throw invalidInputFailure()
    if (key == ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS) requireOrigins(input.getValue("value").jsonPrimitive.content)
    if (key == ZoneSettingKey.TRANSFORMATIONS_CONFIG) (input["value"] as? JsonObject)?.get("allowed_origins")?.let { requireOrigins(it.jsonPrimitive.content) }
}

/** Strict input allowlist validation. Unknown properties are never silently sent. */
internal fun zoneSettingConforms(schema: JsonObject, value: JsonElement, depth: Int = 0): Boolean {
    if (depth > 48) return false
    if (value == JsonNull) return schema["nullable"]?.jsonPrimitive?.booleanOrNull == true
    fun branches(key: String) = (schema[key] as? JsonArray)?.map { it.jsonObject }
    branches("oneOf")?.let { if (it.count { branch -> zoneSettingConforms(branch, value, depth + 1) } != 1) return false }
    branches("anyOf")?.let { if (it.none { branch -> zoneSettingConforms(branch, value, depth + 1) }) return false }
    branches("allOf")?.let { if (it.any { branch -> !zoneSettingConforms(branch, value, depth + 1) }) return false }
    (schema["enum"] as? JsonArray)?.let { if (value !in it) return false }
    val type = schema["type"]?.jsonPrimitive?.content
    val primitive = value as? JsonPrimitive
    if (type == "string" && primitive?.isString != true) return false
    if (type == "boolean" && (primitive?.isString != false || primitive.booleanOrNull == null)) return false
    if (type in listOf("integer", "number")) {
        if (primitive?.isString != false) return false
        val number = primitive.doubleOrNull?.takeIf { it.isFinite() } ?: return false
        if (type == "integer" && primitive.longOrNull == null) return false
        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number < it) return false }
        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number > it) return false }
    }
    if (type == "object" && value !is JsonObject || type == "array" && value !is JsonArray) return false
    if (value is JsonObject && (type == "object" || schema.containsKey("properties"))) {
        val props = (schema["properties"] as? JsonObject).orEmpty()
        val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        if (!value.keys.containsAll(required)) return false
        for ((key, element) in value) {
            val property = props[key] as? JsonObject ?: (schema["additionalProperties"] as? JsonObject) ?: return false
            if (!zoneSettingConforms(property, element, depth + 1)) return false
        }
    }
    if (value is JsonArray) {
        schema["minItems"]?.jsonPrimitive?.intOrNull?.let { if (value.size < it) return false }
        schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { if (value.size > it) return false }
        if (schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true && value.toSet().size != value.size) return false
        (schema["items"] as? JsonObject)?.let { item -> if (value.any { !zoneSettingConforms(item, it, depth + 1) }) return false }
    }
    if (primitive?.isString == true) {
        val content = primitive.content
        schema["minLength"]?.jsonPrimitive?.intOrNull?.let { if (content.length < it) return false }
        schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { if (content.length > it) return false }
        schema["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(content)) return false }
        if (schema["format"]?.jsonPrimitive?.content == "hostname" && !settingHostname(content)) return false
    }
    return true
}

/** Official 2026 purge limits: URL 100 (verified Enterprise 500); other modes 100. */
fun cachePurgeBody(input: CachePurgeInput, verifiedTier: CachePurgeTier?): JsonObject {
    val body = when (input) {
        CachePurgeInput.All -> buildJsonObject { put("purge_everything", true) }
        is CachePurgeInput.Files -> {
            requireCount(input.files.size, if (verifiedTier == CachePurgeTier.ENTERPRISE) 500 else 100)
            input.files.forEach { file ->
                purgeUrlHost(file.url)
                for ((name, value) in file.headers) {
                    if (!Regex("^[!#${'$'}%&'*+.^_`|~0-9A-Za-z-]+${'$'}").matches(name) ||
                        name.lowercase() in setOf("authorization", "proxy-authorization", "cookie", "x-auth-key", "x-auth-email") ||
                        value.any { it.code < 0x20 || it.code == 0x7f }) throw invalidInputFailure()
                }
            }
            val objects = input.files.any { it.headers.isNotEmpty() }
            buildJsonObject { put("files", JsonArray(input.files.map { file ->
                if (!objects) JsonPrimitive(file.url) else buildJsonObject {
                    put("url", file.url)
                    if (file.headers.isNotEmpty()) put("headers", JsonObject(file.headers.mapValues { JsonPrimitive(it.value) }))
                }
            })) }
        }
        is CachePurgeInput.Hosts -> {
            requireCount(input.hosts.size, 100)
            if (input.hosts.any { !settingHostname(it) }) throw invalidInputFailure()
            buildJsonObject { put("hosts", JsonArray(input.hosts.map(::JsonPrimitive))) }
        }
        is CachePurgeInput.Tags -> {
            requireCount(input.tags.size, 100)
            if (input.tags.any { tag -> tag.isEmpty() || tag.length > 1024 || tag.any { it.code !in 0x21..0x7e || it == ',' } }) throw invalidInputFailure()
            buildJsonObject { put("tags", JsonArray(input.tags.map(::JsonPrimitive))) }
        }
        is CachePurgeInput.Prefixes -> {
            requireCount(input.prefixes.size, 100)
            input.prefixes.forEach(::purgePrefixHost)
            buildJsonObject { put("prefixes", JsonArray(input.prefixes.map(::JsonPrimitive))) }
        }
    }
    if (!zoneSettingConforms(ZoneSettingSchemas.purgeInput, body)) throw invalidInputFailure()
    return body
}

private fun requireCount(count: Int, maximum: Int) { if (count !in 1..maximum) throw invalidInputFailure() }
private fun requireOrigins(value: String) {
    if (value != "*" && value.split(',').any { !settingHostname(it) }) throw invalidInputFailure()
}
internal fun settingHostname(value: String): Boolean = value.isNotEmpty() && value.length <= 253 && value.split('.').all { label ->
    label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
}
internal fun purgeUrlHost(url: String): String {
    if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '\\' || it == '#' || it == '*' } ||
        !Regex("(?i)^https?://").containsMatchIn(url)) throw invalidInputFailure()
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?')
    if ('@' in authority) throw invalidInputFailure()
    val host = authority.substringBefore(':')
    if (!settingHostname(host)) throw invalidInputFailure()
    if (':' in authority && authority.substringAfter(':').toIntOrNull() !in 1..65535) throw invalidInputFailure()
    // Malformed percent escapes fail locally; URL bytes are payload only, never an HTTP target.
    var i = 0
    while (i < url.length) {
        if (url[i] == '%') { if (i + 2 >= url.length || url.substring(i + 1, i + 3).any { it !in "0123456789abcdefABCDEF" }) throw invalidInputFailure(); i += 3 }
        else i++
    }
    return host
}
internal fun purgePrefixHost(prefix: String): String {
    if ('?' in prefix || '#' in prefix || ':' in prefix || '*' in prefix || prefix.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '\\' } ||
        prefix.count { it == '/' } > 31 || !settingHostname(prefix.substringBefore('/'))) throw invalidInputFailure()
    return prefix.substringBefore('/')
}

internal const val UNIVERSAL_SSL_READ = "universal-ssl-settings-for-a-zone-universal-ssl-settings-details"
internal const val UNIVERSAL_SSL_WRITE = "universal-ssl-settings-for-a-zone-edit-universal-ssl-settings"
internal fun settingsTarget(action: ZoneSettingsAction): ZoneSettingsTarget = when (action) {
    is ZoneSettingsAction.Update -> ZoneSettingsTarget.Setting(action.key)
    is ZoneSettingsAction.UniversalSsl -> ZoneSettingsTarget.UniversalSsl
    is ZoneSettingsAction.Purge -> ZoneSettingsTarget.Cache
}
internal fun settingsOperation(action: ZoneSettingsAction): String = when (action) {
    is ZoneSettingsAction.Update -> action.key.writeOperation
    is ZoneSettingsAction.UniversalSsl -> UNIVERSAL_SSL_WRITE
    is ZoneSettingsAction.Purge -> "zone-purge"
}
internal fun settingsReference(scope: ZoneScope, target: ZoneSettingsTarget): ResourceRef = when (target) {
    is ZoneSettingsTarget.Setting -> ResourceRef(scope, "zone_setting", target.key.name)
    ZoneSettingsTarget.UniversalSsl -> ResourceRef(scope, "universal_ssl", "universal_ssl")
    ZoneSettingsTarget.Cache -> ResourceRef(scope, "cache_purge", scope.zoneId)
}
internal fun freezeSettingsAction(action: ZoneSettingsAction): ZoneSettingsAction = when (action) {
    is ZoneSettingsAction.Update -> action.copy(input = Json.parseToJsonElement(action.input.toString()).jsonObject).also { validateZoneSettingInput(it.key, it.input) }
    is ZoneSettingsAction.UniversalSsl -> action.copy()
    is ZoneSettingsAction.Purge -> action.copy(input = when (val p = action.input) {
        CachePurgeInput.All -> p
        is CachePurgeInput.Files -> p.copy(files = p.files.map { it.copy(headers = it.headers.toMap()) })
        is CachePurgeInput.Hosts -> p.copy(hosts = p.hosts.toList())
        is CachePurgeInput.Tags -> p.copy(tags = p.tags.toList())
        is CachePurgeInput.Prefixes -> p.copy(prefixes = p.prefixes.toList())
    }).also { cachePurgeBody(it.input, CachePurgeTier.ENTERPRISE) } // Structural maximum only; verified tier is checked again before send.
}
internal fun settingsInput(action: ZoneSettingsAction): JsonObject = when (action) {
    is ZoneSettingsAction.Update -> action.input
    is ZoneSettingsAction.UniversalSsl -> buildJsonObject { put("enabled", action.enabled) }
    is ZoneSettingsAction.Purge -> cachePurgeBody(action.input, CachePurgeTier.ENTERPRISE)
}

/** The fixed request explicitly promises nested omitted fields stay unchanged only for this route. */
internal fun settingPartialObjectPatch(key: ZoneSettingKey) = ZoneSettingSchemas.definition(key.route, key.generic)
    ?.get("partialObjectPatch")?.jsonPrimitive?.booleanOrNull == true
internal fun requireSettingReplacementSafe(key: ZoneSettingKey, before: JsonElement, desired: JsonElement) {
    if (before is JsonObject && desired is JsonObject && !settingPartialObjectPatch(key) && !desired.keys.containsAll(before.keys)) {
        throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "无法证明嵌套更新会保留现有字段，请刷新或使用官方控制台")
    }
}
internal fun settingProjectionMatches(actual: JsonElement, desired: JsonElement, partial: Boolean): Boolean =
    if (partial && actual is JsonObject && desired is JsonObject) desired.all { (key, value) -> actual[key] == value } else actual == desired

internal fun requireSettingsWritable(action: ZoneSettingsAction, snapshot: ZoneSettingsSnapshot) {
    if (snapshot.writeAllowed != true) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,
        if (snapshot.writeAllowed == null) "尚未验证当前 Token 写权限或设置可写资格，当前只读" else "当前 Token 权限或设置资格不允许此写入")
    when (action) {
        is ZoneSettingsAction.Update -> {
            validateZoneSettingInput(action.key, action.input)
            val current = snapshot.setting ?: throw invalidInputFailure()
            if (current.scope != action.scope || current.key != action.key) throw scopeMismatchFailure()
            val desired = action.input.getValue("value")
            requireSettingReplacementSafe(action.key, current.value, desired)
            if (settingProjectionMatches(current.value, desired, settingPartialObjectPatch(action.key))) throw CloudFailure(CloudFailureCode.INVALID_INPUT, "设置值没有变化，请修改输入后再预览")
        }
        is ZoneSettingsAction.UniversalSsl -> {
            val current = snapshot.universal ?: throw invalidInputFailure()
            if (current.scope != action.scope) throw scopeMismatchFailure()
            if (current.enabled == action.enabled) throw CloudFailure(CloudFailureCode.INVALID_INPUT, "Universal SSL 配置没有变化")
        }
        is ZoneSettingsAction.Purge -> {
            cachePurgeBody(action.input, snapshot.purgeTier)
            val hosts = when (val p = action.input) {
                is CachePurgeInput.Files -> p.files.map { purgeUrlHost(it.url) }
                is CachePurgeInput.Hosts -> p.hosts
                is CachePurgeInput.Prefixes -> p.prefixes.map(::purgePrefixHost)
                else -> emptyList()
            }
            if (hosts.any { !it.equals(snapshot.owner.name, ignoreCase = true) && !it.endsWith("." + snapshot.owner.name, ignoreCase = true) }) throw scopeMismatchFailure()
        }
    }
}
