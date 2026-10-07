package com.flarego.platform

import com.flarego.core.updates.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadTest {
    @Test
    fun allowsOnlyExactHttpsReleaseOrigins() {
        listOf(
                "https://api.github.com/repos/a/b",
                "https://release-assets.githubusercontent.com/path",
            )
            .forEach { assertTrue(GitHubHttp.trusted(it)) }
        listOf(
                "http://api.github.com/x",
                "https://github.com.evil.test/x",
                "https://api.github.com:444/x",
                "https://user@api.github.com/x",
            )
            .forEach { assertFalse(GitHubHttp.trusted(it)) }
    }

    @Test
    fun rejectsCorruptOrTruncatedDownloadAndRemovesPartialFiles() = runBlocking {
        val directory = Files.createTempDirectory("updates").toFile()
        val bytes = "valid-apk-placeholder".toByteArray()
        val hash =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        var responseBytes = bytes
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(null, responseBytes))
                        .build()
                }
                .build()
        val service = GitHubUpdateService(directory, GitHubHttp(client))
        val update =
            AppUpdate(
                UpdateChannel.RELEASE,
                "0.1.0",
                10002,
                "a".repeat(40),
                "",
                ReleaseAsset(1, "flarego-0.1.0.apk", bytes.size.toLong()),
                hash,
            )
        try {
            responseBytes = "bad".toByteArray()
            assertFails { service.download(update) {} }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            responseBytes = ByteArray(bytes.size) { 0 }
            assertFails { service.download(update) {} }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            responseBytes = bytes
            val path = service.download(update) {}
            assertArrayEquals(bytes, java.io.File(path).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun redirectCannotEscapeTrustedOrigin() {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Redirect")
                        .header("Location", "https://evil.test/apk")
                        .body(ResponseBody.create(null, ""))
                        .build()
                }
                .build()
        try {
            GitHubHttp(client).open("https://api.github.com/x")
            fail("Untrusted redirect accepted")
        } catch (_: IllegalArgumentException) {}
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
            fail("Invalid download accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
