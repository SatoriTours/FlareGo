package com.flarego.ui.dns

import kotlin.test.*
import kotlinx.serialization.json.*

class DnsFormExperienceTest {
    @Test fun numericSyntaxErrorsUseSameReadableLabelForPreviewAndInlineFeedback() {
        for ((path, label) in listOf("ttl" to "TTL（秒）", "data.port" to "端口", "data.priority" to "优先级")) {
            val draft = DnsFormDraft(type = "SRV", edits = mapOf(path to "invalid"))
            val failure = assertFailsWith<IllegalArgumentException> {
                draft.payload(listOf(DnsFormField(path, "number", emptyList())), complete = false)
            }
            assertEquals("${label}请输入有效数字；不会自动转换为 0", failure.message)
            assertEquals("invalid", draft.edits[path])
        }
    }
    @Test fun readableLabelsDoNotChangeNumericJsonPathsOrWhitespaceDrafts() {
        val specs = listOf(DnsFormField("data.port", "number", emptyList()))
        val draft = DnsFormDraft(type = "SRV", edits = mapOf("data.port" to "8443"))
        assertEquals(JsonPrimitive(8443), draft.payload(specs, false).at("data.port"))
        val empty = draft.copy(edits = mapOf("data.port" to " "))
        assertNull(empty.payload(specs, false).at("data.port"))
        assertEquals(" ", empty.edits["data.port"])
    }
}
