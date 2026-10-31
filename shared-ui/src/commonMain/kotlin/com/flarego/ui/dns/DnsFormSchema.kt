package com.flarego.ui.dns

import kotlinx.serialization.json.*

/** Presentation only. The trusted Task1 validator remains the authority for writes. */
internal data class DnsFormField(val path: String, val kind: String, val choices: List<String>)

internal fun dnsFieldLabel(path: String, type: String): String = when (path) {
    "content" -> if (type == "TXT") "TXT 内容" else "记录内容"
    "ttl" -> "TTL（秒）"
    "priority", "data.priority" -> "优先级"
    "data.port" -> "端口"
    "data.weight" -> "权重"
    "data.order" -> "排序序号"
    "data.preference" -> "优先顺序"
    "comment" -> "备注"
    else -> path
}

internal class DnsFormSchema(private val root: JsonObject) {
    private fun parts(node: JsonObject, depth: Int = 0): List<JsonObject> {
        require(depth < 32) { "无法展示此字段结构" }
        val ref = node["\$ref"] as? JsonPrimitive
        if (ref != null) {
            val prefix = "#/\$defs/"
            require(ref.content.startsWith(prefix))
            val key = ref.content.removePrefix(prefix).replace("~1", "/").replace("~0", "~")
            return parts(root.getValue("\$defs").jsonObject.getValue(key).jsonObject, depth + 1)
        }
        return listOf(node) + listOf("allOf", "anyOf", "oneOf").flatMap { key ->
            (node[key] as? JsonArray).orEmpty().flatMap { parts(it.jsonObject, depth + 1) }
        }
    }

    private fun properties(nodes: List<JsonObject>): Map<String, List<JsonObject>> {
        val result = linkedMapOf<String, MutableList<JsonObject>>()
        nodes.flatMap { parts(it) }.forEach { part ->
            (part["properties"] as? JsonObject)?.forEach { (key, value) ->
                result.getOrPut(key) { mutableListOf() }.add(value.jsonObject)
            }
        }
        return result
    }

    fun fields(): List<DnsFormField> {
        fun flatten(nodes: List<JsonObject>, prefix: String, depth: Int): List<DnsFormField> {
            require(depth < 8)
            return properties(nodes).flatMap { (key, definitions) ->
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                val expanded = definitions.flatMap { parts(it) }
                if (path in setOf("name", "type") || expanded.any { it["readOnly"] == JsonPrimitive(true) }) emptyList()
                else {
                    val kind = expanded.mapNotNull { it["type"]?.jsonPrimitive?.content }.firstOrNull()
                        ?: error("无法展示字段 $path")
                    if (kind == "object") flatten(definitions, path, depth + 1)
                    else listOf(DnsFormField(path, kind, expanded.flatMap {
                        (it["enum"] as? JsonArray).orEmpty().map { choice -> choice.jsonPrimitive.content }
                    }.distinct()))
                }
            }
        }
        return flatten(listOf(root), "", 0)
    }
}

internal fun JsonObject.at(path: String): JsonElement? = path.split('.').fold(this as JsonElement?) { value, key ->
    (value as? JsonObject)?.get(key)
}

private fun JsonObject.withField(path: List<String>, value: JsonElement?): JsonObject {
    val key = path.first()
    val entry = if (path.size == 1) value else {
        ((this[key] as? JsonObject) ?: JsonObject(emptyMap())).withField(path.drop(1), value)
            .takeIf { it.isNotEmpty() }
    }
    return JsonObject(if (entry == null) this - key else this + (key to entry))
}

internal fun DnsFormDraft.payload(specs: List<DnsFormField>, complete: Boolean): JsonObject {
    var result = fields
    // Full forms represent visible empty string inputs, including empty TXT and
    // NAPTR regex. PATCH never invents fields. Numbers never get a default value.
    if (complete) specs.filter {
        it.kind == "string" && (it.path == "content" || it.path.startsWith("data."))
    }.forEach { spec ->
        if (result.at(spec.path) == null) result = result.withField(spec.path.split('.'), JsonPrimitive(""))
    }
    val known = specs.associateBy { it.path }
    edits.forEach { (path, text) ->
        val spec = known[path] ?: error("包含不支持的字段，请重新选择记录类型")
        val value = when (spec.kind) {
            "string" -> JsonPrimitive(text) // TXT, quotes and newlines must remain exact.
            "number", "integer" -> if (text.isBlank()) null else {
                require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(text.trim())) {
                    "${dnsFieldLabel(path, type)}请输入有效数字；不会自动转换为 0"
                }
                val number = try { Json.parseToJsonElement(text) as? JsonPrimitive } catch (_: Exception) { null }
                require(number != null && !number.isString && number.doubleOrNull?.isFinite() == true) {
                    "${dnsFieldLabel(path, type)}请输入有效数字；不会自动转换为 0"
                }
                number
            }
            "boolean" -> if (text.isEmpty()) null else {
                require(text == "true" || text == "false") { "$path 请输入布尔值" }
                JsonPrimitive(text == "true")
            }
            "array" -> if (text.isBlank()) null else {
                val array = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null }
                require(array != null) { "$path 请输入 JSON 数组" }
                array
            }
            else -> error("字段 $path 暂不支持编辑")
        }
        result = result.withField(path.split('.'), value)
    }
    return result
}
