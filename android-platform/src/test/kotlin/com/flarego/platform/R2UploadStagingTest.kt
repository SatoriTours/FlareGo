package com.flarego.platform

import com.flarego.core.model.CloudFailure
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class R2UploadStagingTest {
    @Test fun stagingCopiesBytesAndHashesTheSamePassWithoutKeepingTheSourceOpen() = runBlocking {
        val directory = Files.createTempDirectory("r2-stage-test").toFile()
        var closed = false
        val data = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val source = object : ByteArrayInputStream(data) { override fun close() { closed = true; super.close() } }
        try {
            val staged = stageR2Upload(directory, { source })
            assertTrue(closed)
            assertEquals(data.size.toLong(), staged.length)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, staged.sha256)
            assertEquals(1, directory.listFiles()!!.size)
            staged.release(); staged.release()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun claimedSourceReadsOnlyThePrivateSnapshotOnceAndSurvivesUiRelease() = runBlocking {
        val directory = Files.createTempDirectory("r2-stage-test").toFile()
        try {
            val original = byteArrayOf(3, 4, 5)
            val staged = stageR2Upload(directory, { ByteArrayInputStream(original) })
            original.fill(9)
            val source = staged.openSource()
            staged.release()
            val bytes = ByteArray(8)
            assertEquals(3, source.read(bytes))
            assertContentEquals(byteArrayOf(3, 4, 5), bytes.take(3).toByteArray())
            assertEquals(-1, source.read(bytes))
            source.close(); source.close()
            assertFailsWith<CloudFailure> { staged.openSource() }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun releasedOrAlreadyClaimedSnapshotsCannotSupplyASecondUpload() = runBlocking {
        val directory = Files.createTempDirectory("r2-stage-test").toFile()
        try {
            val staged = stageR2Upload(directory, { ByteArrayInputStream(byteArrayOf(1)) })
            val first = staged.openSource()
            assertFailsWith<CloudFailure> { staged.openSource() }
            first.close(); staged.release()
            val other = stageR2Upload(directory, { ByteArrayInputStream(byteArrayOf(1)) })
            other.release()
            assertFailsWith<CloudFailure> { other.openSource() }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun oversizeOrReadFailureDeletesTemporaryCopyAndDoesNotEchoPrivateInput() = runBlocking {
        val directory = Files.createTempDirectory("r2-stage-test").toFile()
        try {
            assertFailsWith<CloudFailure> { stageR2Upload(directory, { ByteArrayInputStream(ByteArray(65)) }, maxBytes = 64) }
            assertTrue(directory.listFiles()!!.isEmpty())
            val error = assertFailsWith<CloudFailure> {
                stageR2Upload(directory, { object : InputStream() { override fun read(): Int = throw IOException("private-file-marker") } })
            }
            assertFalse(error.toString().contains("private-file-marker"))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellationAndNonProgressingInputLeaveNoTemporaryCopy() = runBlocking {
        val directory = Files.createTempDirectory("r2-stage-test").toFile()
        var closed = false
        try {
            assertFailsWith<CancellationException> {
                stageR2Upload(directory, { object : InputStream() {
                    override fun read(): Int = throw CancellationException("cancel")
                    override fun close() { closed = true }
                } })
            }
            assertTrue(closed)
            assertTrue(directory.listFiles()!!.isEmpty())
            assertFailsWith<CloudFailure> {
                stageR2Upload(directory, { object : InputStream() {
                    override fun read(): Int = 0
                    override fun read(b: ByteArray): Int = 0
                } })
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
