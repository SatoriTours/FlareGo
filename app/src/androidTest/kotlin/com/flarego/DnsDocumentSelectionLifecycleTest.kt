package com.flarego

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.model.*
import com.flarego.platform.DnsDocumentFiles
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real DocumentsProvider and real tree grant fixture; no product MainActivity/VM/HTTP. */
class DnsDocumentSelectionLifecycleTest {
    companion object {
        private val saf=B2SafFixture()
        @JvmStatic @BeforeClass fun grant()=saf.prepare()
        @JvmStatic @AfterClass fun cleanup()=saf.close()
    }
    @Test fun unclaimedDiscardIsIdempotentAndDoesNotDeleteImportOrOtherDraft()=runBlocking<Unit> {
        val source=saf.document("source".toByteArray());val target=saf.document();val other=saf.document()
        DnsDocumentFiles(saf.context).use { files ->
            val ref=files.resolveImport(source);val a=files.resolveExportTarget(target);val b=files.resolveExportTarget(other)
            files.discardExportTarget(a);files.discardExportTarget(a)
            assertFalse(saf.exists(target));assertTrue(saf.exists(source));assertTrue(saf.exists(other))
            assertEquals(0,files.retryPendingCleanup().pending)
            val tx=files.beginExport(b);tx.sink.write(byteArrayOf(1),0,1);val committed=tx.commit()
            files.discardExportTarget(b);files.release(committed);files.release(ref)
            assertTrue(saf.exists(other));assertArrayEquals(byteArrayOf(1),saf.bytes(other))
        }
    }
    @Test fun claimedTargetRefusesDiscardAndRetryDoesNotAbortActiveTransaction()=runBlocking<Unit> {
        val target=saf.document()
        DnsDocumentFiles(saf.context).use { files ->
            val ref=files.resolveExportTarget(target);val tx=files.beginExport(ref)
            expectCloud { files.discardExportTarget(ref) }
            assertEquals(0,files.retryPendingCleanup().attempted)
            tx.sink.write(byteArrayOf(3),0,1);tx.commit();tx.abort()
            assertArrayEquals(byteArrayOf(3),saf.bytes(target))
        }
    }
    @Test fun externallyFilledUnclaimedTargetPermanentlySurvivesDiscardRetryAndClose()=runBlocking<Unit> {
        val target=saf.document();val marker="external marker must survive".toByteArray()
        val files=DnsDocumentFiles(saf.context);val ref=files.resolveExportTarget(target)
        saf.write(target,marker);files.discardExportTarget(ref);files.discardExportTarget(ref)
        files.retryPendingCleanup();files.close();files.close()
        assertTrue(saf.exists(target));assertArrayEquals(marker,saf.bytes(target))
    }
    @Test fun failedDiscardIsRetainedForRegrantCleanupAndDoesNotScanNormalTargets()=runBlocking<Unit> {
        val target=saf.document();val other=saf.document()
        DnsDocumentFiles(saf.context).use { files ->
            val a=files.resolveExportTarget(target);val b=files.resolveExportTarget(other)
            saf.revoke()
            try { assertEquals(CloudFailureCode.PERMISSION_REQUIRED,expectCloud { files.discardExportTarget(a) }.code) }
            finally { saf.grantAgain() }
            val result=files.retryPendingCleanup(1)
            assertEquals(1,result.attempted);assertEquals(0,result.pending)
            assertFalse(saf.exists(target));assertTrue(saf.exists(other))
            val tx=files.beginExport(b);tx.abort()
        }
    }
    @Test fun failedAbortRetryOnlyCleansAbortedTargetsAndKeepsCommittedDocument()=runBlocking<Unit> {
        val target=saf.document();val committed=saf.document();val normal=saf.document()
        DnsDocumentFiles(saf.context).use { files ->
            val done=files.beginExport(files.resolveExportTarget(committed));done.sink.write(byteArrayOf(9),0,1);done.commit()
            val tx=files.beginExport(files.resolveExportTarget(target));files.resolveExportTarget(normal)
            saf.revoke()
            try { expectCloud { tx.abort() } } finally { saf.grantAgain() }
            assertEquals(1,files.retryPendingCleanup().attempted)
            assertFalse(saf.exists(target));assertTrue(saf.exists(normal));assertArrayEquals(byteArrayOf(9),saf.bytes(committed))
        }
    }
    @Test fun cancelledResolveExportDeliveryDiscardsOnlyNewOwnedTarget()=runBlocking<Unit> {
        val target=saf.document();val other=saf.document();val before=saf.tempNames()
        DnsDocumentFiles(saf.context).use { files ->
            files.resolveExportTarget(other)
            cancelAfterIoReturn { files.resolveExportTarget(target);Unit }
            assertFalse("Cancelled resolve must not retain undelivered new target",saf.exists(target))
            assertTrue(saf.exists(other));assertEquals(before,saf.tempNames())
        }
    }
    @Test fun cancelledResolveImportDeliveryReleasesHiddenCapabilityAndClosesDescriptors()=runBlocking<Unit> {
        val source=saf.document("original".toByteArray());val count=saf.fdCount()
        DnsDocumentFiles(saf.context).use { files ->
            cancelAfterIoReturn { files.resolveImport(source);Unit }
            // Undelivered ref has no public capability; inspect count only, never URI/content.
            val field=files.javaClass.getDeclaredField("selected").apply { isAccessible=true }
            assertEquals(0,(field.get(files) as Map<*,*>).size)
            assertEquals(count,saf.fdCount());assertArrayEquals("original".toByteArray(),saf.bytes(source))
        }
    }
    @Test fun cleanupBatchLimitIsValidatedAndNeverConsumesNormalTarget()=runBlocking<Unit> {
        DnsDocumentFiles(saf.context).use { files ->
            val normal=saf.document();files.resolveExportTarget(normal)
            expectCloud { files.retryPendingCleanup(0) };expectCloud { files.retryPendingCleanup(65) }
            assertEquals(0,files.retryPendingCleanup(1).attempted);assertTrue(saf.exists(normal))
        }
    }
    private fun localSelection(kind:DnsDocumentKind,connection:Connection,document:Uri?=null,requestWrite:Boolean=false,forceSearch:Boolean=false):DnsDocumentSelection {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application)
        val stamp=owner.registerExistingCredential(connection)
        val request=DnsDocumentRequestStamp(ZoneScope(AccountScope(connection.id,connection.accountId),"local-zone"),1,stamp,2,3)
        val picker=DnsDocumentPicker(saf.context) { request }
        val result=saf.result(picker.begin(kind,request),document,requestWrite,forceSearch)
        return requireNotNull(picker.parse(kind,result.resultCode,result.data))
    }
    private fun localConnection(demo:Boolean=false)=Connection("lease-"+UUID.randomUUID(),"Local SAF fixture","account",demo=demo)
    @Test fun sourceDraftAndJobLeasesSurviveOtherBorrowerReleaseWithoutDeletingSource()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application)
        val source=saf.document("owned original".toByteArray());val a=owner.borrow();val b=owner.borrow()
        val selected=localSelection(DnsDocumentKind.IMPORT,localConnection(),source)
        val draft=owner.select(a,selected);saf.closeResultHosts()
        val shared=owner.retainDraft(b,draft);val job=owner.claimJob(a,draft)
        owner.releaseBorrower(a);assertEquals('o'.code,owner.files.open(job.file!!).useReadFirst())
        owner.release(shared);owner.releaseBorrower(b)
        assertEquals('o'.code,owner.files.open(job.file!!).useReadFirst())
        assertTrue(saf.resolver.persistedUriPermissions.any { it.uri==selected.uri && it.isReadPermission })
        owner.release(job);owner.release(job)
        assertFalse(saf.resolver.persistedUriPermissions.any { it.uri==selected.uri })
        expectCloud { owner.files.open(job.file!!) };assertArrayEquals("owned original".toByteArray(),saf.bytes(source))
    }
    @Test fun preexistingPersistedReadGrantIsNeverRevokedByLastLocalLease()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application);val borrower=owner.borrow()
        val source=saf.document("keep grant".toByteArray());val selection=localSelection(DnsDocumentKind.IMPORT,localConnection(),source)
        saf.resolver.takePersistableUriPermission(selection.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val lease=owner.select(borrower,selection);saf.closeResultHosts();owner.release(lease);owner.releaseBorrower(borrower)
            assertTrue(saf.resolver.persistedUriPermissions.any { it.uri==selection.uri && it.isReadPermission })
            assertTrue(saf.exists(source))
        } finally { saf.resolver.releasePersistableUriPermission(selection.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun demoExplicitLocalExportUsesStampAndJobLeaseWithoutProductionAcquisition()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application);val borrower=owner.borrow()
        val selection=localSelection(DnsDocumentKind.EXPORT,localConnection(demo=true))
        val draft=owner.select(borrower,selection);val job=owner.claimJob(borrower,draft)
        owner.release(draft);owner.releaseBorrower(borrower)
        val tx=owner.files.beginExport(job.target!!);val bytes="; explicit local demo BIND\n".toByteArray()
        tx.sink.write(bytes,0,bytes.size);val committed=tx.commit();tx.abort()
        assertEquals(bytes.size.toLong(),committed.length)
        owner.release(job);owner.retryCleanup();assertArrayEquals(bytes,saf.bytes(selection.uri))
        assertFalse(saf.resolver.persistedUriPermissions.any { it.uri==selection.uri })
    }
    @Test fun failedOwnedAbortRetainsGrantCapabilityForExplicitCleanupOnly()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application);val borrower=owner.borrow()
        val connection=localConnection();val selection=localSelection(DnsDocumentKind.EXPORT,connection)
        val draft=owner.select(borrower,selection);val job=owner.claimJob(borrower,draft);owner.release(draft)
        val tx=owner.files.beginExport(job.target!!);tx.sink.write(byteArrayOf(1),0,1);saf.closeResultHosts()
        saf.resolver.releasePersistableUriPermission(selection.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);saf.revoke()
        try { expectCloud { tx.abort() };owner.release(job);owner.releaseBorrower(borrower) }
        finally { saf.grantAgain() }
        // Actual new provider grant; retry performs cleanup only, never beginExport or an HTTP send.
        val again=localSelection(DnsDocumentKind.IMPORT,connection,selection.uri,requestWrite=true)
        saf.resolver.takePersistableUriPermission(again.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        saf.closeResultHosts()
        val cleanup=owner.retryCleanup();assertTrue(cleanup.attempted>0);assertEquals(0,cleanup.pending)
        assertFalse(saf.exists(selection.uri))
    }
    @Test fun selectedCapabilityCannotReplayOrAttachAfterCredentialTransition()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application);val borrower=owner.borrow();val c=localConnection()
        val source=saf.document("unchanged".toByteArray());val selected=localSelection(DnsDocumentKind.IMPORT,c,source)
        val lease=owner.select(borrower,selected);expectCloud { owner.select(borrower,selected) }
        val stale=localSelection(DnsDocumentKind.IMPORT,c,source);owner.beginCredentialChange(c)
        expectCloud { owner.select(borrower,stale) }
        owner.release(lease);owner.releaseBorrower(borrower);assertArrayEquals("unchanged".toByteArray(),saf.bytes(source))
    }
    @Test fun sameUriIndependentSelectionsDoNotReclassifyOwnedGrantAsPreexistingBaseline()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application)
        val borrower=owner.borrow();val source=saf.document("two independent selections".toByteArray());val c=localConnection()
        val first=localSelection(DnsDocumentKind.IMPORT,c,source);val a=owner.select(borrower,first)
        val second=localSelection(DnsDocumentKind.IMPORT,c,source);val b=owner.select(borrower,second);saf.closeResultHosts()
        owner.release(a)
        assertTrue(saf.resolver.persistedUriPermissions.any { it.uri==first.uri && it.isReadPermission })
        assertEquals('t'.code,owner.files.open(b.file!!).useReadFirst())
        owner.release(b);owner.releaseBorrower(borrower)
        assertFalse("Second selection must not turn our first lease's grant into a permanent baseline",saf.resolver.persistedUriPermissions.any { it.uri==first.uri })
        assertArrayEquals("two independent selections".toByteArray(),saf.bytes(source))
    }
    @Test fun issuedSelectionPredicateCanQueryOwnerFromAnotherThreadWithoutOwnerMonitor()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(saf.context.applicationContext as Application)
        val borrower=owner.borrow();val c=localConnection();val credential=owner.registerExistingCredential(c)
        val request=DnsDocumentRequestStamp(ZoneScope(AccountScope(c.id,c.accountId),"local-zone"),1,credential,2,3)
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        val probe=java.util.concurrent.atomic.AtomicBoolean(false)
        var queriedWithoutBlocking=false;var acquired:ManagedDocumentLease?=null
        try {
            worker.submit {}.get(2,TimeUnit.SECONDS) // Start the worker before the bounded monitor probe.
            val picker=DnsDocumentPicker(saf.context) {
                if(probe.compareAndSet(true,false)) {
                    val query=worker.submit<ManagementCredentialStamp?> { owner.currentCredentialStamp(c) }
                    queriedWithoutBlocking=try { query.get(1,TimeUnit.SECONDS)==credential }
                        catch(_:java.util.concurrent.TimeoutException) { false }
                }
                request
            }
            val source=saf.document("bounded real selection probe".toByteArray())
            val result=saf.result(picker.begin(DnsDocumentKind.IMPORT,request),source)
            val selected=requireNotNull(picker.parse(DnsDocumentKind.IMPORT,result.resultCode,result.data))
            probe.set(true)
            acquired=owner.select(borrower,selected)
            assertTrue("External selection callback must not execute under the Owner monitor",queriedWithoutBlocking)
        } finally {
            acquired?.let { owner.release(it) };owner.releaseBorrower(borrower)
            worker.shutdown();assertTrue("Bounded probe must leave no blocked worker",worker.awaitTermination(2,TimeUnit.SECONDS))
        }
    }
    @Test fun failedPretransactionDeleteRemainsPendingUntilRealRegrant()=runBlocking<Unit> {
        pretransactionFailure(externalMarker=false)
    }
    @Test fun failedPretransactionCleanupPreservesExternalFillBeforeRegrant()=runBlocking<Unit> {
        pretransactionFailure(externalMarker=true)
    }
    private suspend fun pretransactionFailure(externalMarker:Boolean) {
        val c=localConnection(demo=true);val selected=localSelection(DnsDocumentKind.EXPORT,c)
        val mode=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(mode,selected.flags and mode)
        saf.resolver.takePersistableUriPermission(selected.uri,mode)
        val cacheIsFile=java.io.File.createTempFile("b2a-cache-is-file-",".tmp",saf.context.cacheDir)
        val wrapper=object:android.content.ContextWrapper(saf.context) { override fun getCacheDir():java.io.File=cacheIsFile }
        val files=DnsDocumentFiles(wrapper)
        var revoked=false
        try {
            val target=files.resolveExportTarget(selected.uri);val normal=saf.document();files.resolveExportTarget(normal)
            saf.closeResultHosts();saf.resolver.releasePersistableUriPermission(selected.uri,mode);saf.revoke();revoked=true
            saf.assertDirectReadDenied(selected.uri)
            expectCloud { files.beginExport(target) } // File.createTempFile cannot create inside an actual ordinary file.
            val before=files.retryPendingCleanup(1)
            assertEquals("Failed pre-transaction delete must retain its issued target",1,before.pending)
            assertEquals(1,before.attempted)
            if(externalMarker) saf.fillExternally(selected.uri)
            saf.grantAgain();revoked=false
            val again=localSelection(DnsDocumentKind.IMPORT,c,selected.uri,requestWrite=true,forceSearch=true)
            assertEquals(mode,again.flags and mode)
            saf.resolver.takePersistableUriPermission(again.uri,mode);saf.closeResultHosts()
            val after=files.retryPendingCleanup(1)
            assertEquals(1,after.attempted);assertEquals(0,after.pending)
            assertTrue("Cleanup must not scan a normal unclaimed draft",saf.exists(normal))
            if(externalMarker) {
                assertArrayEquals("external-before-regrant".toByteArray(),saf.bytes(selected.uri))
                files.retryPendingCleanup();files.close()
                assertArrayEquals("external-before-regrant".toByteArray(),saf.bytes(selected.uri))
            } else assertFalse(saf.exists(selected.uri))
        } finally {
            if(revoked)saf.grantAgain()
            files.close();cacheIsFile.delete()
            runCatching { saf.resolver.releasePersistableUriPermission(selected.uri,mode) }
        }
    }
    private fun com.flarego.core.ports.ByteSource.useReadFirst():Int = try {
        val byte=ByteArray(1);assertEquals(1,read(byte));byte[0].toInt()
    } finally { close() }
    private class Queued:CoroutineDispatcher() { val queue=LinkedBlockingQueue<Runnable>();override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) { queue.add(block) } }
    private fun cancelAfterIoReturn(block:suspend()->Unit) {
        val q=Queued();val scope=CoroutineScope(SupervisorJob()+q)
        try {
            val job=scope.launch(start=CoroutineStart.UNDISPATCHED) { block() }
            val resume=q.queue.poll(5,TimeUnit.SECONDS) ?: error("Fixture IO did not finish")
            job.cancel();resume.run()
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!job.isCompleted && System.nanoTime()<end) q.queue.poll(50,TimeUnit.MILLISECONDS)?.run()
            assertTrue(job.isCompleted)
        } finally { scope.cancel() }
    }
}
internal suspend fun expectCloud(block:suspend()->Unit):CloudFailure {
    try { block() } catch(failure:CloudFailure) { return failure }
    throw AssertionError("Expected safe typed rejection")
}

/** Fresh owned folder and actual system grant. Parser flags cases remain separate from product launcher proof. */
internal class B2SafFixture:AutoCloseable {
    val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    val context get()=instrumentation.targetContext
    val resolver get()=context.contentResolver
    val folder="flarego-b2a-"+UUID.randomUUID()
    private val authority="com.android.externalstorage.documents"
    lateinit var tree:Uri;lateinit var root:Uri
    private val documents=mutableListOf<Uri>()
    private val resultHosts=mutableListOf<ActivityScenario<ComponentActivity>>()
    fun closeResultHosts() { resultHosts.toList().forEach { it.close() };resultHosts.clear() }
    fun result(launch:DnsDocumentLaunch,existing:Uri?=null,requestWrite:Boolean=false,forceSearch:Boolean=false):ActivityResult {
        val host=ActivityScenario.launch(ComponentActivity::class.java);resultHosts+=host
        val queue=LinkedBlockingQueue<ActivityResult>()
        val name=existing?.let { uri -> resolver.query(withinTree(uri),arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)!!.use { it.moveToFirst();it.getString(0) } }
        host.onActivity { activity ->
            val launcher=activity.activityResultRegistry.register("b2a-"+UUID.randomUUID(),ActivityResultContracts.StartActivityForResult()) { queue.add(it) }
            launcher.launch(launch.intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,root).apply { if(requestWrite)addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) })
        }
        val started=System.nanoTime();val end=started+TimeUnit.SECONDS.toNanos(20)
        var searchOpened=false;var searched=false
        while(System.nanoTime()<end) {
            if(name!=null) {
                val clicked=if(forceSearch && !searched) false else click(name)
                // A large fixture folder virtualizes off-screen rows. Use the real picker search,
                // never synthesize a result/grant or assume the desired random filename is visible.
                if(!clicked && System.nanoTime()-started>TimeUnit.SECONDS.toNanos(1)) {
                    if(!searchOpened) searchOpened=click("Search")
                    if(searchOpened && !searched) editor(instrumentation.uiAutomation.rootInActiveWindow)?.let { field ->
                        val args=android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name) }
                        if(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);searched=true
                        }
                    }
                }
            } else click("Save")
            queue.poll(100,TimeUnit.MILLISECONDS)?.let { result ->
                assertEquals("Real system picker must return OK",Activity.RESULT_OK,result.resultCode)
                val uri=requireNotNull(result.data?.data)
                if(launch.kind==DnsDocumentKind.EXPORT)documents+=uri
                return result
            }
        }
        val matches=mutableListOf<String>()
        fun inspect(node:AccessibilityNodeInfo?) {
            if(node==null)return
            if(node.text?.toString()==name)matches+="class=${node.className},editable=${node.isEditable},clickable=${node.isClickable}"
            for(i in 0 until node.childCount)inspect(node.getChild(i))
        }
        inspect(instrumentation.uiAutomation.rootInActiveWindow)
        throw AssertionError("Real picker result not delivered; desired matches="+matches.joinToString(";"))
    }
    private fun find(node:AccessibilityNodeInfo?,text:String):AccessibilityNodeInfo? {
        if(node==null)return null
        if(!node.isEditable && (node.text?.toString()?.equals(text,true)==true || node.contentDescription?.toString()?.equals(text,true)==true))return node
        for(i in 0 until node.childCount) find(node.getChild(i),text)?.let { return it }
        return null
    }
    private fun click(text:String):Boolean {
        var node=find(instrumentation.uiAutomation.rootInActiveWindow,text)
        while(node!=null && !node.isClickable)node=node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
    }
    private fun editor(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.isEditable)return node
        for(i in 0 until node.childCount)editor(node.getChild(i))?.let { return it }
        return null
    }
    fun prepare() {
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/$folder").use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } }
        tree=DocumentsContract.buildTreeDocumentUri(authority,"primary:Download/$folder")
        root=DocumentsContract.buildDocumentUriUsingTree(tree,"primary:Download/$folder")
        grantAgain()
    }
    fun grantAgain() {
        val host=ActivityScenario.launch(ComponentActivity::class.java)
        try {
            host.onActivity { activity -> activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri(authority,"primary:Download/$folder"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),6067) }
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);var granted=false
            while(System.nanoTime()<end) {
                click("Use this folder");click("Allow")
                granted=runCatching { resolver.query(root,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { it.moveToFirst() && it.getString(0)==folder }==true }.getOrDefault(false)
                if(granted)break;Thread.sleep(100)
            }
            assertTrue("Actual SAF grant is required before behavior evidence",granted)
            resolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } finally { host.close() }
    }
    fun revoke()=resolver.releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    fun document(bytes:ByteArray=byteArrayOf()):Uri {
        val uri=checkNotNull(DocumentsContract.createDocument(resolver,root,"text/plain","fixture-${UUID.randomUUID()}.bind"));documents+=uri;write(uri,bytes);return uri
    }
    private fun withinTree(uri:Uri)=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getDocumentId(uri))
    fun write(uri:Uri,bytes:ByteArray) { resolver.openOutputStream(withinTree(uri),"wt")!!.use { it.write(bytes) } }
    fun bytes(uri:Uri)=resolver.openInputStream(withinTree(uri))!!.use { it.readBytes() }
    fun exists(uri:Uri)=runCatching { resolver.query(withinTree(uri),arrayOf(OpenableColumns.SIZE),null,null,null)?.use { it.moveToFirst() }==true }.getOrDefault(false)
    fun assertDirectReadDenied(uri:Uri) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        var denied=false
        while(System.nanoTime()<end) {
            try { resolver.openInputStream(uri)?.use { it.read() } }
            catch(_:SecurityException) { denied=true;break }
            Thread.sleep(25)
        }
        assertTrue("Real direct access must be revoked before testing permission-failed cleanup",denied)
    }
    fun fillExternally(uri:Uri) {
        val id=DocumentsContract.getDocumentId(uri)
        require(id.startsWith("primary:Download/$folder/"))
        fun quote(value:String)="'"+value.replace("'","'\"'\"'")+"'"
        val path="/sdcard/"+id.removePrefix("primary:")
        // Start an actual shell and feed its stdin; verify exact external bytes before regrant.
        val pipes=instrumentation.uiAutomation.executeShellCommandRw("sh")
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input ->
                input.write(("printf '%s' 'external-before-regrant' > "+quote(path)+"\ncat "+quote(path)+"\nexit\n").toByteArray())
            }
            val actual=android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            assertArrayEquals("Shell writer must fill only the fixture before app regrant","external-before-regrant".toByteArray(),actual)
        } finally { pipes.forEach { runCatching { it.close() } } }
    }
    fun tempNames()=context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("flarego-dns-export-") }.map { it.name }.toSet()
    fun fdCount()=java.io.File("/proc/self/fd").listFiles().orEmpty().count { runCatching { android.system.Os.readlink(it.path).contains(folder) }.getOrDefault(false) }
    override fun close() {
        closeResultHosts()
        documents.asReversed().forEach { runCatching { DocumentsContract.deleteDocument(resolver,withinTree(it)) } }
        if(::root.isInitialized)runCatching { DocumentsContract.deleteDocument(resolver,root) }
        if(::tree.isInitialized)runCatching { revoke() }
    }
}
