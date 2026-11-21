package com.flarego.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

private val reviewedM1Changes = setOf(
    "app/src/main/kotlin/com/flarego/FlareGoViewModel.kt",
    "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt",
    "shared-core/src/commonTest/kotlin/com/flarego/core/D1CoverageTest.kt",
    "shared-core/src/commonTest/kotlin/com/flarego/core/KvCoverageTest.kt",
    "shared-core/src/commonTest/kotlin/com/flarego/core/P2CoverageTest.kt",
    "shared-core/src/commonTest/kotlin/com/flarego/core/R2CoverageTest.kt",
    "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainConfigurationTest.kt",
    "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyTest.kt",
    "shared-ui/src/commonMain/kotlin/com/flarego/ui/FlareGoApp.kt",
)

/** Updates only current source claims. Existing validators still check every file and the exact path sets. */
internal fun m1CurrentSourceClaims(root: Path, base: JsonObject, expectedChanges: Int): JsonObject {
    val delta = Json.parseToJsonElement(
        Files.readString(root.resolve("docs/cloudflare-audit/workers-m1-current-source-sha256.json"))
    ).jsonObject
    assertEquals(reviewedM1Changes, delta.keys, "Only the reviewed M1 paths may be overlaid")
    val digest = Regex("[0-9a-f]{64}")
    val replacements = delta.mapValues { (path, entry) ->
        val change = entry.jsonObject
        assertEquals(setOf("previous", "current"), change.keys, path)
        val previous = change.getValue("previous").jsonPrimitive
        val current = change.getValue("current").jsonPrimitive
        assertTrue(previous.isString && digest.matches(previous.content), "Invalid previous digest: $path")
        assertTrue(current.isString && digest.matches(current.content), "Invalid current digest: $path")
        assertNotEquals(previous, current, "Unchanged digest is not an M1 delta: $path")
        base[path]?.let { assertEquals(previous, it, "M1 delta no longer matches its baseline: $path") }
        current
    }.filterKeys { it in base }
    assertEquals(expectedChanges, replacements.size, "Incomplete M1 delta for current source claims")
    return JsonObject(base + replacements)
}
