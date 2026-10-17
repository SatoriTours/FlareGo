package com.flarego

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.model.*
import com.flarego.core.ports.ByteSource
import com.flarego.platform.AndroidFingerprint
import com.flarego.platform.DnsDocumentFiles
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Real SAF picker grant + ExternalStorage DocumentsProvider + ContentResolver; no network or app VM. */
class DnsFileIntegrationTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val context get() = instrumentation.targetContext
        private val resolver get() = context.contentResolver
        private val folder = "flarego-task6-" + UUID.randomUUID().toString()
        private const val authority = "com.android.externalstorage.documents"
        private lateinit var root: Uri
        private lateinit var tree: Uri
        private var host: ActivityScenario<ComponentActivity>? = null
        private val fixtures = mutableListOf<Uri>()
        private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.equals(text, true) == true) return node
            for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
            return null
        }
        private fun clickText(text: String): Boolean {
            val node = findText(instrumentation.uiAutomation.rootInActiveWindow, text) ?: return false
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        @JvmStatic @BeforeClass fun grantActualSafTree() {
            // Only a newly created test folder. The picker grants app access; shell does not grant URI permission.
            instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/$folder").use { fd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
            }
            tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Download/$folder")
            val initial = DocumentsContract.buildDocumentUri(authority, "primary:Download/$folder")
            root = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Download/$folder")
            host = ActivityScenario.launch(ComponentActivity::class.java)
            host!!.onActivity { activity ->
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 6006)
            }
            val deadline = System.nanoTime() + 15_000_000_000L
            var granted = false
            while (System.nanoTime() < deadline) {
                clickText("Use this folder"); clickText("Allow")
                try {
                    resolver.query(root, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        granted = it.moveToFirst() && it.getString(0) == folder
                    }
                } catch (_: SecurityException) { }
                if (granted) break
                Thread.sleep(150)
            }
            assertTrue("Real SAF picker tree grant must succeed before behavior RED can count", granted)
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            host!!.close(); host = null // Persistent grant, rather than the picker Activity, now authorizes IO.
        }
        @JvmStatic @AfterClass fun removeOnlyOwnedSafFixtures() {
            fixtures.reversed().forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            if (::root.isInitialized) runCatching { DocumentsContract.deleteDocument(resolver, root) }
            if (::tree.isInitialized) runCatching { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            host?.close()
        }
        private fun document(bytes: ByteArray = byteArrayOf()): Uri {
            val uri = checkNotNull(DocumentsContract.createDocument(resolver, root, "text/plain", "fixture-${UUID.randomUUID()}.bind"))
            fixtures += uri
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            return uri
        }
        private fun contents(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
        private fun exists(uri: Uri): Boolean = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { it.moveToFirst() } == true
        } catch (_: Exception) { false }
        private val original = "\$ORIGIN example.com.\r\n\$TTL 300\r\n@ IN TXT \"raw\" ; cf_tags=raw cf-proxied:false\r\n\$GENERATE 1-3 x\$ A 192.0.2.1\r\n".toByteArray()
        private fun read(source: ByteSource): ByteArray {
            try {
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(97)
                while (true) { val n = source.read(buffer); if (n < 0) break; bytes.write(buffer, 0, n) }
                return bytes.toByteArray()
            } finally { source.close() }
        }
        private suspend fun expectFailure(block: suspend () -> Unit): CloudFailure {
            try { block() } catch (error: CloudFailure) { return error }
            throw AssertionError("Expected a typed file failure")
        }
    }
    @Test fun opaqueImportRefUsesActualBytesAndReopensOriginalWithoutDeletingIt() = runBlocking<Unit> {
        val uri = document(original)
        DnsDocumentFiles(context).use { files ->
            val ref = files.resolveImport(uri)
            assertEquals(original.size.toLong(), ref.length)
            assertEquals(AndroidFingerprint().sha256(original), ref.sha256)
            assertFalse(ref.id.contains("content:")); assertFalse(ref.toString().contains(uri.toString()))
            assertArrayEquals(original, read(files.open(ref)))
            val changed = "changed original".toByteArray()
            resolver.openOutputStream(uri, "wt")!!.use { it.write(changed) }
            assertArrayEquals(changed, read(files.open(ref))) // Core independently rehashes against frozen metadata.
            expectFailure { files.open(ref.copy(length = ref.length + 1)) }
            files.release(ref); expectFailure { files.open(ref) }
            assertArrayEquals(changed, contents(uri))
        }
        assertTrue(exists(uri))
    }
    @Test fun realSafImportAccepts262144AndRejects262145OrEmpty() = runBlocking<Unit> {
        DnsDocumentFiles(context).use { files ->
            val exact = ByteArray(262144) { ';'.code.toByte() }
            val ref = files.resolveImport(document(exact))
            assertEquals(262144L, ref.length); assertArrayEquals(exact, read(files.open(ref)))
            expectFailure { files.resolveImport(document(ByteArray(262145))) }
            expectFailure { files.resolveImport(document()) }
        }
    }
    @Test fun exportStagesLocallyThenCommitsExactBytesAndAbortAfterCommitKeepsDocument() = runBlocking<Unit> {
        val uri = document()
        DnsDocumentFiles(context).use { files ->
            val tx = files.beginExport(files.resolveExportTarget(uri))
            tx.sink.write(original, 0, original.size); tx.sink.close(); tx.sink.close()
            assertArrayEquals(byteArrayOf(), contents(uri))
            val ref = tx.commit()
            assertArrayEquals(original, contents(uri)); assertEquals(AndroidFingerprint().sha256(original), ref.sha256)
            assertArrayEquals(original, read(files.open(ref)))
            tx.abort(); tx.abort(); assertTrue(exists(uri))
        }
        assertArrayEquals(original, contents(uri))
    }
    @Test fun abortedExportRemovesOnlyOwnedNewDocumentAndTempNotImportOriginal() = runBlocking<Unit> {
        val source = document(original); val target = document()
        val tempBefore = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        DnsDocumentFiles(context).use { files ->
            val ref = files.resolveImport(source)
            val tx = files.beginExport(files.resolveExportTarget(target))
            tx.sink.write(original, 0, original.size); tx.abort(); tx.abort()
            assertFalse(exists(target)); assertArrayEquals(original, read(files.open(ref)))
            assertEquals(tempBefore, context.cacheDir.listFiles()!!.map { it.name }.toSet())
        }
        assertTrue(exists(source))
    }
    @Test fun nonemptySourceForgedRefsAndRepeatedTargetAreRejectedWithoutDeletingUserFile() = runBlocking<Unit> {
        val source = document(original); val emptySource = document()
        DnsDocumentFiles(context).use { files ->
            expectFailure { files.resolveExportTarget(source) }
            // Even an empty URI already offered as an import must never become an owned export target.
            expectFailure { files.resolveImport(emptySource) }
            expectFailure { files.open(DnsFileRef("not-issued", "zone.bind", 1, "0".repeat(64))) }
            val uri = document(); val target = files.resolveExportTarget(uri)
            expectFailure { files.resolveExportTarget(uri) }
            val tx = files.beginExport(target); expectFailure { files.beginExport(target) }; tx.abort()
        }
        assertArrayEquals(original, contents(source)); assertTrue(exists(emptySource))
    }
    @Test fun unknownUriPermissionFailureIsTypedAndDoesNotExposeUri() = runBlocking<Unit> {
        val denied = DocumentsContract.buildDocumentUri(authority, "primary:Android/data/other-app/private-zone.bind")
        DnsDocumentFiles(context).use { files ->
            val error = expectFailure { files.resolveImport(denied) }
            assertEquals(CloudFailureCode.PERMISSION_REQUIRED, error.code)
            assertFalse(error.toString().contains("other-app")); assertFalse(error.toString().contains("content://"))
            assertNull(error.cause)
        }
    }
    @Test fun cancelledCommitCanAbortAndLeavesNoOwnedDocumentOrTemp() = runBlocking<Unit> {
        val source = document(original); val target = document()
        val tempBefore = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        DnsDocumentFiles(context).use { files ->
            val ref = files.resolveImport(source)
            val tx = files.beginExport(files.resolveExportTarget(target))
            tx.sink.write(original, 0, original.size); tx.sink.close()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                tx.commit()
            }
            job.join(); assertTrue(job.isCancelled)
            tx.abort(); assertFalse(exists(target)); assertArrayEquals(original, read(files.open(ref)))
            assertEquals(tempBefore, context.cacheDir.listFiles()!!.map { it.name }.toSet())
        }
    }
    @Test fun closingServiceAlsoClosesPreviouslyOpenedRealSafSource() = runBlocking<Unit> {
        val uri = document(original); val files = DnsDocumentFiles(context)
        val source = files.open(files.resolveImport(uri))
        files.close()
        expectFailure { source.read(ByteArray(8)) }
        source.close(); assertArrayEquals(original, contents(uri))
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
    }
    private fun cancelAfterIoReturn(block: suspend () -> Unit, acquired: () -> Unit) {
        val queued = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queued)
        try {
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
            val firstResume = queued.queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                ?: throw AssertionError("IO did not finish acquiring the real resource")
            acquired(); job.cancel(); firstResume.run()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!job.isCompleted && System.nanoTime() < deadline) {
                queued.queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)?.run()
            }
            assertTrue("Cancelled acquisition must finish cleanup", job.isCompleted)
        } finally { scope.cancel() }
    }
    private fun ownedSafDescriptorCount(): Int = java.io.File("/proc/self/fd").listFiles()!!.count {
        try { android.system.Os.readlink(it.path).contains(folder) } catch (_: Exception) { false }
    }
    @Test fun cancellationBetweenOpenAndDeliveryClosesRealSafDescriptor() = runBlocking<Unit> {
        val uri = document(original)
        DnsDocumentFiles(context).use { files ->
            val ref = files.resolveImport(uri); val before = ownedSafDescriptorCount()
            cancelAfterIoReturn({ files.open(ref); Unit }, { assertTrue(ownedSafDescriptorCount() > before) })
            assertEquals("Discarded open result must not leak a descriptor", before, ownedSafDescriptorCount())
        }
        assertArrayEquals(original, contents(uri))
    }
    @Test fun cancellationBetweenBeginAndDeliveryRemovesOwnedStagingAndTarget() = runBlocking<Unit> {
        val uri = document(); val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        DnsDocumentFiles(context).use { files ->
            val target = files.resolveExportTarget(uri)
            cancelAfterIoReturn({ files.beginExport(target); Unit }, {
                assertTrue(context.cacheDir.listFiles()!!.any { it.name !in before })
            })
            assertFalse("Cancelled undelivered transaction must remove its new target", exists(uri))
            assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
        }
    }
    @Test fun externallyPopulatedTargetSurvivesRejectedCommitRepeatedAbortAndServiceClose() = runBlocking<Unit> {
        val target = document(); val marker = "external user content\r\nDo not remove".toByteArray()
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        val descriptorsBefore = ownedSafDescriptorCount()
        val files = DnsDocumentFiles(context)
        try {
            val tx = files.beginExport(files.resolveExportTarget(target))
            tx.sink.write(original, 0, original.size); tx.sink.close()
            resolver.openOutputStream(target, "wt")!!.use { it.write(marker) }
            expectFailure { tx.commit() }
            assertTrue("Rejected commit must preserve a target populated by the user", exists(target))
            assertArrayEquals(marker, contents(target))
            tx.abort(); tx.abort(); files.close(); files.close()
            assertTrue(exists(target)); assertArrayEquals(marker, contents(target))
            assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
            assertEquals(descriptorsBefore, ownedSafDescriptorCount())
        } finally { files.close() }
    }
    @Test fun externallyPopulatedTargetSurvivesDirectAbortBeforePublication() = runBlocking<Unit> {
        preserveExternalFillOnCleanup(false)
    }
    @Test fun externallyPopulatedTargetSurvivesServiceCloseBeforePublication() = runBlocking<Unit> {
        preserveExternalFillOnCleanup(true)
    }
    private suspend fun preserveExternalFillOnCleanup(closeService: Boolean) {
        val target = document(); val marker = "user wrote before cleanup".toByteArray()
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        val descriptorsBefore = ownedSafDescriptorCount()
        val files = DnsDocumentFiles(context)
        try {
            val tx = files.beginExport(files.resolveExportTarget(target))
            tx.sink.write(original, 0, original.size)
            resolver.openOutputStream(target, "wt")!!.use { it.write(marker) }
            if (closeService) files.close() else { tx.abort(); tx.abort(); files.close() }
            assertTrue("Pre-publication cleanup must preserve user-written content", exists(target))
            assertArrayEquals(marker, contents(target))
            assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
            assertEquals(descriptorsBefore, ownedSafDescriptorCount())
        } finally { files.close() }
    }
    @Test fun expiredSafGrantRejectsOpenAndCommitThenAllowsCleanupAfterRegrant() = runBlocking<Unit> {
        val originalUri = document(original); val targetUri = document()
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        DnsDocumentFiles(context).use { files ->
            val ref = files.resolveImport(originalUri)
            val tx = files.beginExport(files.resolveExportTarget(targetUri))
            tx.sink.write(original, 0, original.size); tx.sink.close()
            resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                val openError = expectFailure { files.open(ref) }
                assertEquals(CloudFailureCode.PERMISSION_REQUIRED, openError.code)
                val commitError = expectFailure { tx.commit() }
                assertEquals(CloudFailureCode.PERMISSION_REQUIRED, commitError.code)
                assertFalse(commitError.toString().contains("content://"))
                assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
            } finally { grantActualSafTree() }
            tx.abort(); tx.abort()
            assertFalse("Regrant must let idempotent abort finish owned target cleanup", exists(targetUri))
            assertArrayEquals(original, contents(originalUri))
        }
    }
    @Test fun closingPlatformServiceCleansPendingExportButKeepsSourceAndCommittedExport() = runBlocking<Unit> {
        val source = document(original); val pending = document(); val committed = document()
        val tempBefore = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        val files = DnsDocumentFiles(context); val sourceRef = files.resolveImport(source)
        val tx = files.beginExport(files.resolveExportTarget(committed)); tx.sink.write(original, 0, original.size); tx.sink.close(); tx.commit()
        val pendingTx = files.beginExport(files.resolveExportTarget(pending)); pendingTx.sink.write(original, 0, 5)
        files.close(); files.close()
        assertFalse(exists(pending)); assertArrayEquals(original, contents(committed)); assertArrayEquals(original, contents(source))
        expectFailure { files.open(sourceRef) }
        assertEquals(tempBefore, context.cacheDir.listFiles()!!.map { it.name }.toSet())
    }
}
