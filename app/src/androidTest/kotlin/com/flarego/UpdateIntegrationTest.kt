package com.flarego

import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.updates.*
import com.flarego.platform.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun preferencesPersistAcrossControllerRecreation() {
        val store = AndroidUpdateStore(context)
        val original = store.read()
        try {
            val expected = UpdatePreferences(UpdateChannel.SNAPSHOT, false, 1234567)
            store.write(expected)
            assertEquals(expected, AndroidUpdateStore(context).read())
        } finally {
            store.write(original)
        }
    }

    @Test
    fun installerRejectsOldVersionChangedBytesAndOutsideCache() = runBlocking {
        val root = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(root, "fixture.apk")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val source = File(context.applicationInfo.sourceDir)
        source.copyTo(apk, overwrite = true)
        val hash =
            apk.inputStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
                GitHubUpdateService.hex(digest.digest())
            }
        @Suppress("DEPRECATION")
        val code =
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else info.versionCode
        val update =
            AppUpdate(
                UpdateChannel.RELEASE,
                info.versionName.orEmpty(),
                code,
                "a".repeat(40),
                "",
                ReleaseAsset(1, "fixture.apk", apk.length()),
                hash,
            )
        val installer = AndroidUpdateInstaller(context)
        try {
            expectFailure("此版本已安装") { installer.prepare(apk.path, update) }
            expectFailure("安装包路径无效") { installer.prepare(source.path, update) }
            java.io.RandomAccessFile(apk, "rw").use { file ->
                file.seek(20)
                val previous = file.read()
                file.seek(20)
                file.write(previous xor 1)
            }
            expectFailure("安装包已发生变化") { installer.prepare(apk.path, update) }
        } finally {
            apk.delete()
        }
    }

    private suspend fun expectFailure(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Invalid installation accepted")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "Unexpected rejection: ${error.message}",
                error.message.orEmpty().contains(message),
            )
        }
    }
}
