package com.flarego.core.application.dns

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.round

data class FieldViolation(val path: String, val message: String)

/** An unsupported schema is a violation, even in an unused/nullable branch. */
fun validateDnsFields(schema: JsonObject, value: JsonObject): List<FieldViolation> =
    try {
        LocalDnsSchema(schema).validate(value)
    } catch (error: IllegalArgumentException) {
        listOf(FieldViolation("$", "Unsupported DNS schema: ${error.message}"))
    }

/** Small OpenAPI 3.0 schema interpreter; never defaults unknown keywords to success. */
internal class LocalDnsSchema(private val root: JsonObject) {
    private val annotations = setOf("description", "title", "example", "default", "x-auditable", "x-stainless-collection-type")
    private val keywords = setOf(
        "${'$'}ref", "${'$'}defs", "type", "enum", "required", "properties", "items", "allOf", "anyOf", "oneOf",
        "nullable", "readOnly", "writeOnly", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "multipleOf", "minLength", "maxLength", "pattern", "format", "minItems", "maxItems", "uniqueItems",
        "minProperties", "maxProperties", "additionalProperties",
    )
    private var visited = 0

    init { check(root, emptySet(), 0) }

    private fun objectValue(value: JsonElement?, key: String): JsonObject =
        value as? JsonObject ?: throw IllegalArgumentException("$key must be a schema object")

    private fun arrayValue(value: JsonElement?, key: String): JsonArray =
        value as? JsonArray ?: throw IllegalArgumentException("$key must be an array")

    private fun text(value: JsonElement?, key: String): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$key must be a string")

    private fun number(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.takeUnless { it.isString || it == JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun resolve(reference: String): JsonObject {
        require(reference.startsWith("#/${'$'}defs/")) { "only local definitions resolve" }
        val tokens = reference.removePrefix("#/").split('/').map {
            require(!Regex("~(?![01])").containsMatchIn(it)) { "invalid JSON pointer escape" }
            it.replace("~1", "/").replace("~0", "~")
        }
        var current: JsonElement = root
        for (token in tokens) current = (current as? JsonObject)?.get(token)
            ?: throw IllegalArgumentException("unresolved local reference")
        return objectValue(current, "reference target")
    }

    private fun check(node: JsonObject, refs: Set<String>, depth: Int) {
        require(depth <= 64 && ++visited <= 20000) { "schema recursion/size limit" }
        require((node.keys - keywords - annotations).isEmpty()) { "unknown keyword(s): ${node.keys - keywords - annotations}" }
        node["type"]?.let { require(text(it, "type") in setOf("object", "array", "string", "number", "integer", "boolean")) { "unsupported type" } }
        for (key in listOf("nullable", "readOnly", "writeOnly", "uniqueItems", "exclusiveMinimum", "exclusiveMaximum")) {
            node[key]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$key must be boolean" } }
        }
        for (key in listOf("minimum", "maximum", "multipleOf")) node[key]?.let {
            val n = number(it)
            require(n != null && (key != "multipleOf" || n > 0)) { "$key must be a finite number (multipleOf positive)" }
        }
        for (key in listOf("minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties")) node[key]?.let {
            require(it is JsonPrimitive && !it.isString && it.intOrNull != null && it.int >= 0) { "$key must be a nonnegative integer" }
        }
        node["enum"]?.let { require(arrayValue(it, "enum").isNotEmpty()) { "empty enum" } }
        node["required"]?.let {
            val names = arrayValue(it, "required").map { name -> text(name, "required member") }
            require(names.distinct().size == names.size) { "duplicate required member" }
        }
        node["pattern"]?.let { Regex(text(it, "pattern")) }
        node["format"]?.let { require(text(it, "format") in setOf("ipv4", "ipv6", "hostname")) { "unsupported format" } }
        node["${'$'}ref"]?.let {
            require((node.keys - setOf("${'$'}ref", "${'$'}defs")).isEmpty()) { "unsupported reference siblings" }
            val ref = text(it, "reference")
            require(ref !in refs) { "cyclic reference" }
            check(resolve(ref), refs + ref, depth + 1)
        }
        for (key in listOf("properties", "${'$'}defs")) node[key]?.let {
            objectValue(it, key).values.forEach { child -> check(objectValue(child, key), refs, depth + 1) }
        }
        for (key in listOf("allOf", "anyOf", "oneOf")) node[key]?.let {
            val children = arrayValue(it, key)
            require(children.isNotEmpty()) { "empty $key" }
            children.forEach { child -> check(objectValue(child, key), refs, depth + 1) }
        }
        node["items"]?.let { check(objectValue(it, "items"), refs, depth + 1) }
        node["additionalProperties"]?.let {
            if (it is JsonObject) check(it, refs, depth + 1)
            else require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "invalid additionalProperties" }
        }
    }

    fun validate(value: JsonObject): List<FieldViolation> = evaluate(root, value, "$")

    private fun evaluate(node: JsonObject, value: JsonElement, path: String): List<FieldViolation> {
        val errors = mutableListOf<FieldViolation>()
        fun reject(ok: Boolean, message: String) { if (!ok) errors += FieldViolation(path, message) }
        if (node["readOnly"] == JsonPrimitive(true)) return listOf(FieldViolation(path, "readOnly field cannot be written"))
        node["${'$'}ref"]?.let { errors += evaluate(resolve(it.jsonPrimitive.content), value, path) }
        if (value == JsonNull) {
            // OpenAPI nullable modifies this node's explicit type, not arbitrary refs/unions.
            if (node["type"] != null && node["nullable"] != JsonPrimitive(true)) reject(false, "null is not allowed")
        } else node["type"]?.let {
            val ok = when (it.jsonPrimitive.content) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "number" -> number(value) != null
                "integer" -> number(value)?.let { n -> n % 1.0 == 0.0 } == true
                else -> false
            }
            reject(ok, "expected ${it.jsonPrimitive.content}")
        }
        node["enum"]?.let { reject(it.jsonArray.any { member -> sameJson(member, value) }, "value is outside enum") }
        for (key in listOf("allOf", "anyOf", "oneOf")) node[key]?.let {
            val results = it.jsonArray.map { branch -> evaluate(branch.jsonObject, value, path) }
            when (key) {
                "allOf" -> results.forEach { branch -> errors += branch }
                "anyOf" -> reject(results.any { branch -> branch.isEmpty() }, "no anyOf branch matches")
                "oneOf" -> reject(results.count { branch -> branch.isEmpty() } == 1, "exactly one oneOf branch must match")
            }
        }
        if (value is JsonObject) {
            node["required"]?.jsonArray?.forEach { reject(it.jsonPrimitive.content in value, "missing required field ${it.jsonPrimitive.content}") }
            node["minProperties"]?.let { reject(value.size >= it.jsonPrimitive.int, "too few properties") }
            node["maxProperties"]?.let { reject(value.size <= it.jsonPrimitive.int, "too many properties") }
            val properties = node["properties"]?.jsonObject ?: JsonObject(emptyMap())
            for ((name, entry) in value) {
                val property = properties[name]
                if (property != null) errors += evaluate(property.jsonObject, entry, "$path.$name")
                else when (val additional = node["additionalProperties"]) {
                    is JsonObject -> errors += evaluate(additional, entry, "$path.$name")
                    JsonPrimitive(false) -> errors += FieldViolation("$path.$name", "additional property is forbidden")
                    else -> Unit // JSON Schema default is open; DNS writes apply a separate allowlist.
                }
            }
        }
        if (value is JsonArray) {
            node["minItems"]?.let { reject(value.size >= it.jsonPrimitive.int, "too few items") }
            node["maxItems"]?.let { reject(value.size <= it.jsonPrimitive.int, "too many items") }
            if (node["uniqueItems"] == JsonPrimitive(true)) reject(value.indices.all { i -> (0 until i).none { j -> sameJson(value[i], value[j]) } }, "duplicate items")
            node["items"]?.let { item -> value.forEachIndexed { i, entry -> errors += evaluate(item.jsonObject, entry, "$path[$i]") } }
        }
        if (value is JsonPrimitive && value.isString) {
            val content = value.content
            val length = content.indices.count { i -> !content[i].isLowSurrogate() || i == 0 || !content[i - 1].isHighSurrogate() }
            node["minLength"]?.let { reject(length >= it.jsonPrimitive.int, "string is too short") }
            node["maxLength"]?.let { reject(length <= it.jsonPrimitive.int, "string is too long") }
            node["pattern"]?.let { reject(Regex(it.jsonPrimitive.content).containsMatchIn(content), "pattern does not match") }
            node["format"]?.let {
                reject(when (it.jsonPrimitive.content) {
                    "ipv4" -> isDnsIpv4(content)
                    "ipv6" -> isDnsIpv6(content)
                    "hostname" -> isDnsHostname(content, allowRoot = true)
                    else -> false
                }, "invalid ${it.jsonPrimitive.content}")
            }
        }
        number(value)?.let { n ->
            node["minimum"]?.let { reject(if (node["exclusiveMinimum"] == JsonPrimitive(true)) n > it.jsonPrimitive.double else n >= it.jsonPrimitive.double, "below minimum") }
            node["maximum"]?.let { reject(if (node["exclusiveMaximum"] == JsonPrimitive(true)) n < it.jsonPrimitive.double else n <= it.jsonPrimitive.double, "above maximum") }
            node["multipleOf"]?.let { val quotient = n / it.jsonPrimitive.double; reject(abs(quotient - round(quotient)) < 1e-9, "not a multiple") }
        }
        return errors
    }

    private fun sameJson(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, v) -> sameJson(v, b.getValue(key)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        number(a) != null && number(b) != null -> number(a) == number(b)
        else -> a == b
    }

    /** Union of declared fields, including inherited allOf properties. */
    fun properties(node: JsonObject = root): Map<String, List<JsonObject>> {
        val result = mutableMapOf<String, MutableList<JsonObject>>()
        fun add(part: JsonObject) {
            part["${'$'}ref"]?.let { add(resolve(it.jsonPrimitive.content)) }
            part["properties"]?.jsonObject?.forEach { (name, schema) -> result.getOrPut(name) { mutableListOf() }.add(schema.jsonObject) }
            for (key in listOf("allOf", "anyOf", "oneOf")) part[key]?.jsonArray?.forEach { add(it.jsonObject) }
        }
        add(node)
        return result
    }
}
