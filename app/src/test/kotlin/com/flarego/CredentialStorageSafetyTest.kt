package com.flarego

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static guards for the API Token connection path.
 *
 * The credential must never leave the device: no cloud backup, no device transfer, no log line
 * and no saved state. These are build-level guarantees, so they are asserted against the real
 * committed files rather than a mock.
 */
class CredentialStorageSafetyTest {
    private val module: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test fun credentialBearingDataIsExcludedFromCloudBackupAndDeviceTransfer() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))

        val rules = File(module, "src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue("$section must exclude domain=$domain", body.contains("domain=\"$domain\""))
            }
        }
    }

    @Test fun theTokenContractNeverLogsOrPersistsThePastedValue() {
        val sources = listOf(
            "src/main/kotlin/com/flarego/AndroidTokenConnection.kt",
            "../shared-core/src/commonMain/kotlin/com/flarego/core/application/connection/TokenConnectionController.kt",
            "../shared-ui/src/commonMain/kotlin/com/flarego/ui/TokenConnection.kt",
        )
        // Concrete API usage only: documentation may legitimately name these concepts.
        val forbidden = listOf("Log.", "println(", "Analytics", "rememberSaveable", "SavedStateRegistry", "savedInstanceState")
        for (path in sources) {
            val text = File(module, path).readText()
            for (token in forbidden) assertFalse("$path must not use $token", text.contains(token))
        }
    }
}
