package com.flarego

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The build is Token-only. These guards fail if an OAuth entry, callback handler, dedicated build
 * configuration or App Link registration comes back into the source tree.
 */
class TokenOnlySourceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(file: String) = File(root, file).readText()

    @Test fun buildAndManifestCarryNoDedicatedOAuthConfiguration() {
        val gradle = source("app/build.gradle.kts")
        for (token in listOf("oauthClientId", "oauthRedirectUri", "CLOUDFLARE_OAUTH", "manifestPlaceholders")) {
            assertFalse("app/build.gradle.kts must not contain $token", gradle.contains(token))
        }
        val manifest = source("app/src/main/AndroidManifest.xml")
        for (token in listOf("oauthCallbackHost", "oauthCallbackPath", "autoVerify")) {
            assertFalse("AndroidManifest.xml must not contain $token", manifest.contains(token))
        }
    }

    @Test fun noSourceKeepsAnOAuthEntryCallbackOrScopeProfile() {
        val forbidden = listOf(
            "OAuthConnection", "AndroidOAuthConnection", "OAuthCredentials", "OAuthTokens",
            "OAuthTokenEndpoint", "OAuthFlow", "OAuthScopeFailure", "cloudflareOAuthConfig",
            "acceptOAuthCallback", "consumeOAuthIntent", "CloudflareOAuthReadProfile",
        )
        val directories = listOf(
            "app/src/main/kotlin", "shared-ui/src/commonMain/kotlin",
            "android-platform/src/main/kotlin", "shared-core/src/commonMain/kotlin",
        )
        for (directory in directories) {
            File(root, directory).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                for (token in forbidden) {
                    assertFalse("${file.path} still references $token", text.contains(token))
                }
            }
        }
    }

    @Test fun theSetupHelpPointsAtApiTokenDocumentation() {
        val setup = source("shared-ui/src/commonMain/kotlin/com/flarego/ui/ServiceSetup.kt")
        assertFalse(setup.contains("developers.cloudflare.com/fundamentals/oauth/"))
        assertTrue(setup.contains("api/get-started/create-token") || setup.contains("api-tokens"))
    }
}
