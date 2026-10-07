package com.flarego.core

import com.flarego.core.updates.*
import kotlin.test.*
import kotlinx.coroutines.test.*

class UpdateTest {
    private val hash = "a".repeat(64)
    private val sha = "b".repeat(40)
    private val release =
        """{"tag_name":"v0.1.0","draft":false,"prerelease":false,"body":"changes","assets":[{"id":1,"name":"flarego-0.1.0.apk","size":123,"state":"uploaded"},{"id":2,"name":"build-metadata.json","size":100,"state":"uploaded"},{"id":3,"name":"SHA256SUMS","size":160,"state":"uploaded"}]}"""

    private fun metadata(code: Int = 10002) =
        """{"channel":"release","release_tag":"v0.1.0","version_name":"0.1.0","version_code":$code,"commit_sha":"$sha","apk_name":"flarego-0.1.0.apk"}"""

    private val sums
        get() = "$hash  flarego-0.1.0.apk\n${"c".repeat(64)}  build-metadata.json\n"

    @Test
    fun contractBindsTagChannelAssetsAndVersion() {
        val descriptor = ReleaseFormat.release(release, UpdateChannel.RELEASE)
        val update = ReleaseFormat.update(descriptor, metadata(), sums, UpdateChannel.RELEASE)
        assertEquals(10002, update.versionCode)
        assertEquals(hash, update.sha256)
        assertFails { ReleaseFormat.release(release, UpdateChannel.SNAPSHOT) }
        assertFails {
            ReleaseFormat.update(
                descriptor,
                metadata().replace("v0.1.0", "v0.2.0"),
                sums,
                UpdateChannel.RELEASE,
            )
        }
        assertFails { ReleaseFormat.update(descriptor, metadata(0), sums, UpdateChannel.RELEASE) }
        assertFails {
            ReleaseFormat.release(
                release.replace("\"draft\":false", "\"draft\":true"),
                UpdateChannel.RELEASE,
            )
        }
    }

    @Test
    fun checksumDuplicatesAndMissingAssetsRejected() {
        val descriptor = ReleaseFormat.release(release, UpdateChannel.RELEASE)
        assertFails {
            ReleaseFormat.update(
                descriptor,
                metadata(),
                sums + "$hash  flarego-0.1.0.apk\n",
                UpdateChannel.RELEASE,
            )
        }
        assertFails { ReleaseFormat.update(descriptor, metadata(), "", UpdateChannel.RELEASE) }
        assertFails {
            ReleaseFormat.release(release.replace("SHA256SUMS", "other"), UpdateChannel.RELEASE)
        }
        assertFails {
            ReleaseFormat.release(
                release.replace("\"size\":123", "\"size\":536870913"),
                UpdateChannel.RELEASE,
            )
        }
    }

    @Test
    fun automaticThrottlePersistsAndManualCheckBypasses() = runTest {
        val store = MemoryStore()
        val service = FakeService()
        var now = 1_000_000L
        val first = UpdateController(backgroundScope, service, store, 1, "local", { now }, false)
        first.check(automatic = true)
        runCurrent()
        assertEquals(1, service.checks)
        val second = UpdateController(backgroundScope, service, store, 1, "local", { now }, false)
        second.check(automatic = true)
        runCurrent()
        assertEquals(1, service.checks)
        second.check()
        runCurrent()
        assertEquals(2, service.checks)
        now += 6 * 60 * 60 * 1000
        second.check(automatic = true)
        runCurrent()
        assertEquals(3, service.checks)
    }

    @Test
    fun newerOnlyAndSingleInstallConsumption() = runTest {
        val service = FakeService()
        service.result =
            ReleaseFormat.update(
                ReleaseFormat.release(release, UpdateChannel.RELEASE),
                metadata(),
                sums,
                UpdateChannel.RELEASE,
            )
        val controller =
            UpdateController(backgroundScope, service, MemoryStore(), 10002, "0.1.0", { 1L }, false)
        controller.check()
        runCurrent()
        assertNull(controller.state.value.available)
        val old =
            UpdateController(backgroundScope, service, MemoryStore(), 1, "local", { 1L }, false)
        old.check()
        runCurrent()
        old.download()
        runCurrent()
        assertEquals("/cache/update.apk", old.takeInstallRequest()?.first)
        assertNull(old.takeInstallRequest())
        old.setChannel(UpdateChannel.SNAPSHOT)
        runCurrent()
        assertNull(old.state.value.downloadedPath)
    }

    @Test
    fun failureDoesNotKeepBusyOrInstall() = runTest {
        val service = FakeService().also { it.fail = true }
        val controller =
            UpdateController(backgroundScope, service, MemoryStore(), 1, "local", { 1L }, false)
        controller.check()
        runCurrent()
        assertFalse(controller.state.value.busy)
        assertNull(controller.takeInstallRequest())
        assertTrue(controller.state.value.message.isNotBlank())
    }

    private class MemoryStore : UpdateStore {
        var saved = UpdatePreferences()

        override fun read() = saved

        override fun write(preferences: UpdatePreferences) {
            saved = preferences
        }
    }

    private class FakeService : UpdateService {
        var checks = 0
        var result: AppUpdate? = null
        var fail = false

        override suspend fun check(channel: UpdateChannel): AppUpdate? {
            checks++
            if (fail) error("offline")
            return result?.takeIf { it.channel == channel }
        }

        override suspend fun download(update: AppUpdate, progress: (Float) -> Unit): String {
            progress(1f)
            return "/cache/update.apk"
        }
    }
}
