package com.flarego.ui.kv

import com.flarego.core.application.kv.*
import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import kotlin.test.*

internal open class KvTestSession : KvSession {
    override suspend fun listNamespaces(account:AccountScope,page:KvNamespacePageRequest)=CloudPage<KvNamespace>(emptyList(),null,null)
    override suspend fun getNamespace(namespace:KvNamespaceRef)=KvNamespace(namespace,"fixture",true)
    override suspend fun listKeys(namespace:KvNamespaceRef,page:KvKeyPageRequest)=CloudPage<KvKeyEntry>(emptyList(),null,null)
    override suspend fun readMetadata(target:KvKeyTarget):KvMetadata=error("unused")
    override suspend fun readValue(target:KvKeyTarget):KvDownload=error("unused")
    override suspend fun bulkRead(namespace:KvNamespaceRef,input:KvBulkReadInput):KvDownload=error("unused")
    override suspend fun createNamespace(account:AccountScope,title:String)=error("unguarded")
    override suspend fun renameNamespace(namespace:KvNamespaceRef,title:String):KvWriteReceipt=error("unguarded")
    override suspend fun deleteNamespace(namespace:KvNamespaceRef):KvWriteReceipt=error("unguarded")
    override suspend fun putValue(target:KvKeyTarget,input:KvValueInput):KvWriteReceipt=error("unguarded")
    override suspend fun deleteValue(target:KvKeyTarget):KvWriteReceipt=error("unguarded")
    override suspend fun bulkWrite(namespace:KvNamespaceRef,input:KvBulkWriteInput):KvWriteReceipt=error("unguarded")
    override suspend fun bulkDelete(namespace:KvNamespaceRef,keys:List<String>):KvWriteReceipt=error("unguarded")
}
@OptIn(ExperimentalCoroutinesApi::class)
class KvBrowserTest {
    private val c=Connection("local","fixture","a".repeat(32));private val account=AccountScope(c.id,c.accountId)
    private val ns=KvNamespaceRef(account,"b".repeat(32));private val key=KvKeyEntry("A/键",1000000,false)
    private inner class H(val scope:TestScope) {
        var context:KvBrowserContext?=KvBrowserContext(c,1,1);var serial=0;var writes=0;var fail=false;var lists=0
        var beforeList:suspend()->Unit={};var beforeKeys:suspend()->Unit={}
        var receipt=KvWriteReceipt(KvAcceptance.ACCEPTED)
        val keyRequests=mutableListOf<KvKeyPageRequest>();var next:PageToken?=PageToken.Cursor("opaque")
        val contents=KvOwnerMemoryStore();val port=KvOwnerSecurePort()
        val api=object:KvTestSession(){
            override suspend fun listNamespaces(account:AccountScope,page:KvNamespacePageRequest):CloudPage<KvNamespace>{lists++;beforeList();if(fail)throw CloudFailure(CloudFailureCode.UNAVAILABLE,"private-provider-body");return CloudPage(listOf(KvNamespace(KvNamespaceRef(account,ns.id),"fixture",true)),null,1)}
            override suspend fun listKeys(namespace:KvNamespaceRef,page:KvKeyPageRequest):CloudPage<KvKeyEntry>{keyRequests+=page;beforeKeys();if(fail)throw CloudFailure(CloudFailureCode.UNAVAILABLE,"private-provider-body");return CloudPage(if(page.cursor==null)listOf(key)else emptyList(),next,null)}
            override suspend fun readValue(target:KvKeyTarget)=KvDownload(contents.content("private-value".encodeToByteArray()),200,null)
        }
        val tasks=linkedMapOf<String,OperationTask>();val store=object:OperationStore{
            override fun get(id:String)=tasks[id];override fun list(connectionId:String)=tasks.values.toList()
            override fun put(task:OperationTask){tasks[task.id]=task};override fun removeConnection(connectionId:String)=Unit
            override fun putWithSummary(task:OperationTask,summary:Operation)=put(task)
        }
        val runtime:KvWriteRuntime=KvWriteRuntime(scope.backgroundScope,store,StandardTestDispatcher(scope.testScheduler),FingerprintPort{MessageDigest.getInstance("SHA-256").digest(it).joinToString(""){n->"%02x".format(n)}},OperationClock{1000},OperationIds{"id-${++serial}"},
            {context?.let{KvLiveSelection(it.connection,it.credentialRevision,browser.confirmationEpoch,browser.liveCommand())}},{listOf(c)},{1},
            {_,guard->object:KvSession by api{
                override suspend fun deleteValue(target:KvKeyTarget):KvWriteReceipt{guard();writes++;return KvWriteReceipt(KvAcceptance.ACCEPTED)}
                override suspend fun bulkWrite(namespace:KvNamespaceRef,input:KvBulkWriteInput):KvWriteReceipt{guard();writes++;return receipt}
            }})
        val browser:KvBrowser=KvBrowser(scope, {context},runtime,contents,{port},{1},{api})
        fun open(){browser.refreshContext();scope.runCurrent();browser.openNamespace(ns);scope.runCurrent()}
    }
    @Test fun directoryDetailAndCursorFailuresKeepLoadedRowsAndRetryTheSameCursor()=runTest{
        val h=H(this);h.open();assertEquals(ns,h.browser.state.value.namespace?.ref);assertEquals(listOf(key),h.browser.state.value.keys)
        h.fail=true;h.browser.refreshKeys(true);runCurrent()
        assertEquals(listOf(key),h.browser.state.value.keys);assertEquals(PageToken.Cursor("opaque"),h.browser.state.value.keyNext)
        assertFalse(h.browser.state.value.error.orEmpty().contains("private-provider-body"))
        h.fail=false;h.next=PageToken.Cursor("next");h.browser.refreshKeys(true);runCurrent()
        assertEquals("opaque",h.keyRequests.last().cursor);assertEquals(PageToken.Cursor("next"),h.browser.state.value.keyNext)
    }
    @Test fun lateOldAccountReadNeverPublishesIntoNewSelection()=runTest{
        val h=H(this);val gate=CompletableDeferred<Unit>();h.beforeList={withContext(NonCancellable){gate.await()}}
        h.browser.refreshContext();runCurrent();assertEquals(1,h.lists);h.context=null;h.browser.refreshContext();gate.complete(Unit);runCurrent()
        assertNull(h.browser.state.value.context);assertTrue(h.browser.state.value.namespaces.isEmpty())
    }
    @Test fun prefixChangeCancelsLateNamespaceKeyResultsAndPreservesVerbatimInput()=runTest{
        val h=H(this);h.open();val gate=CompletableDeferred<Unit>();h.beforeKeys={withContext(NonCancellable){gate.await()}}
        h.browser.refreshKeys();runCurrent();h.browser.filterPrefix(" A/键 ");gate.complete(Unit);runCurrent()
        assertEquals(" A/键 ",h.browser.state.value.prefix);assertEquals(" A/键 ",h.keyRequests.last().prefix)
    }
    @Test fun readValuesStayPrivateAndBackgroundRevokesReadyDraftAndOldConfirmation()=runTest{
        val h=H(this);h.open();h.browser.selectKey(key);h.browser.readValue();runCurrent()
        withTimeout(5000){h.browser.content.state.first{it.valueReady}};runCurrent()
        assertTrue(h.browser.content.state.value.valueReady);assertFalse(h.browser.state.value.toString().contains("private-value"))
        h.browser.prepareDeleteKey();runCurrent();val risk=h.browser.state.value.confirmation!!
        h.browser.acceptRisk(risk.handle);val typed=h.browser.state.value.confirmation!!
        h.browser.acceptTyped(typed.handle,ns.id);val final=h.browser.state.value.confirmation!!
        h.browser.background();h.browser.submit(final.handle);runCurrent()
        assertEquals(0,h.writes);assertFalse(h.browser.content.state.value.valueReady)
    }
    @Test fun wrongTypedNamespaceAndCancelledStagesSendNothingThenFreshFinalSendsOnce()=runTest{
        val h=H(this);h.open();h.browser.selectKey(key);h.browser.prepareDeleteKey();runCurrent()
        h.browser.acceptRisk(h.browser.state.value.confirmation!!.handle)
        val typed=h.browser.state.value.confirmation!!;h.browser.acceptTyped(typed.handle,"wrong");assertEquals(0,h.writes)
        h.browser.acceptTyped(typed.handle,ns.id);val final=h.browser.state.value.confirmation!!
        h.browser.submit(final.handle);runCurrent();assertEquals(1,h.writes)
        assertTrue(h.browser.state.value.notice!!.contains("传播"));assertEquals(1,h.browser.state.value.tasks.size)
    }
    @Test fun sequentialFieldEditsUseCurrentDraftAndExplicitOverwriteIsRequired()=runTest{
        val h=H(this);h.open();h.browser.begin(KvEditor.VALUE)
        h.browser.edit{it.copy(key="chosen")};h.browser.edit{it.copy(expiry=KvExpiryChoice.TTL)};h.browser.edit{it.copy(expirySeconds="60")}
        assertEquals("chosen",h.browser.state.value.form.key);assertEquals("60",h.browser.state.value.form.expirySeconds)
        h.browser.content.setValue(KvSensitiveInput.of(""));h.browser.prepare();runCurrent()
        assertNull(h.browser.state.value.confirmation);assertEquals(0,h.writes)
        h.browser.edit{it.copy(overwrite=true)};h.browser.prepare();runCurrent()
        assertEquals(listOf("chosen"),h.browser.state.value.confirmation!!.keys)
    }
    @Test fun terminalWriteOutcomesReleaseOriginalEditorContentAndRequireANewDraft()=runTest{
        for(outcome in listOf(KvAcceptance.ACCEPTED,KvAcceptance.PARTIAL,KvAcceptance.UNKNOWN)){
            val h=H(this);h.receipt=KvWriteReceipt(outcome,if(outcome==KvAcceptance.PARTIAL)0 else 1,if(outcome==KvAcceptance.PARTIAL)listOf("k")else emptyList())
            h.open();h.browser.begin(KvEditor.BULK_WRITE)
            h.browser.content.acceptValue(h.contents.content(byteArrayOf(-1,0,65)))
            h.browser.content.setMetadata(KvSensitiveInput.of("{\"private\":true}"))
            val body="""[{"key":"k","value":"private-value"}]""".encodeToByteArray()
            h.browser.importDocument(KvDocumentKind.IMPORT_BULK_WRITE,object:ByteSource{
                var at=0
                override fun read(buffer:ByteArray):Int{if(at==body.size)return -1;val n=minOf(buffer.size,body.size-at);body.copyInto(buffer,0,at,at+n);at+=n;return n}
                override fun close(){body.fill(0)}
            })
            h.browser.edit{it.copy(overwrite=true)};h.browser.prepare();runCurrent()
            withContext(Dispatchers.Default){withTimeout(5000){h.browser.state.first{it.confirmation!=null||it.error!=null}}};runCurrent()
            h.browser.acceptRisk(assertNotNull(h.browser.state.value.confirmation,h.browser.state.value.error).handle)
            h.browser.acceptTyped(h.browser.state.value.confirmation!!.handle,ns.id)
            val final=h.browser.state.value.confirmation!!.handle
            h.browser.submit(final);runCurrent();assertEquals(1,h.writes)
            assertNull(h.browser.state.value.editor);assertNull(h.browser.liveCommand())
            assertFalse(h.browser.content.state.value.secure);assertFalse(h.browser.content.state.value.valueReady)
            assertFalse(h.browser.content.state.value.metadataReady);assertNull(h.browser.content.state.value.bulkKind)
            assertTrue(h.contents.live.isEmpty(),"All original and frozen encrypted content must be released")
            assertEquals(h.receipt.failedKeys,h.browser.state.value.failedKeys)
            h.browser.submit(final);runCurrent();assertEquals(1,h.writes)
        }
    }

}
