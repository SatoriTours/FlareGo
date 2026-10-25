package com.flarego.ui.kv

import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.r2.*
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.*

internal class KvOwnerMemoryStore : KvContentStore {
    private var next = 0
    val live = mutableSetOf<KvContent>()
    override fun writer(maxBytes: Long): KvContentWriter = object : KvContentWriter {
        val out = ByteArrayOutputStream(); var aborted = false
        override fun write(buffer: ByteArray, offset: Int, count: Int) { check(out.size()+count<=maxBytes); out.write(buffer,offset,count) }
        override fun close() = Unit
        override fun abort() { aborted = true; out.reset() }
        override suspend fun seal(): KvContent { check(!aborted); return content(out.toByteArray()) }
    }
    fun content(bytes: ByteArray): KvContent = object : KvContent {
        var closed = false
        override val descriptor = KvContentDescriptor("handle-${++next}", bytes.size.toLong())
        override suspend fun openVerified(): ByteSource { check(!closed); return object : ByteSource {
            var at = 0
            override fun read(buffer: ByteArray): Int { check(!closed); if(at==bytes.size)return -1; val n=minOf(buffer.size,bytes.size-at);bytes.copyInto(buffer,0,at,at+n);at+=n;return n }
            override fun close() = Unit
        } }
        override fun close() { closed = true; live.remove(this) }
    }.also { live += it }
    override suspend fun stage(source: ByteSource,maxBytes: Long): KvContent {
        val w=writer(maxBytes);val b=ByteArray(8192)
        try { while(true) { val n=source.read(b);if(n<0)break;w.write(b,0,n) };return w.seal() }finally { source.close() }
    }
    override fun releaseAll() { live.toList().forEach { it.close() } }
}
internal class KvOwnerSecurePort(override val generation: Long = 1) : SecureWindowPort {
    var active = true; val held = mutableSetOf<SecureWindowSession>(); val armed = mutableSetOf<SecureWindowSession>(); var removed = 0
    override fun request(purpose: SecurePurpose): SecureWindowSession? = if (!active) null else object : SecureWindowSession {
        override val purpose = purpose;override val generation = this@KvOwnerSecurePort.generation
        override fun isProtected() = active && this in held
    }.also { held += it }
    override fun release(session: SecureWindowSession) { held -= session }
    override fun armRetirement(session: SecureWindowSession) = (session in held).also { if(it)armed+=session }
    override fun contentRemoved(session: SecureWindowSession) { if(armed.remove(session)){removed++;held-=session} }
}
class KvContentOwnerTest {
    @Test fun missingWindowNeverStoresOrExposesSensitiveContent() = runTest {
        val store=KvOwnerMemoryStore();val owner=KvContentOwner(store,{null},{})
        assertFalse(owner.begin());assertFalse(owner.setValue(KvSensitiveInput.of("private-marker")))
        assertNull(owner.valueForDisplay());assertFailsWith<CloudFailure>{owner.freezeValue(KvExpiry.Never,false)}
        assertFailsWith<CloudFailure>{owner.acceptValue(store.content(byteArrayOf(1)))}
        assertTrue(store.live.isEmpty())
    }
    @Test fun emptyTextIsReadyAndPublicStateContainsOnlySafeDescriptors() = runTest {
        val store=KvOwnerMemoryStore();val port=KvOwnerSecurePort();var edits=0
        val owner=KvContentOwner(store,{port},{edits++});assertTrue(owner.begin())
        assertTrue(owner.setValue(KvSensitiveInput.of("")));assertTrue(owner.state.value.valueReady)
        val empty=owner.freezeValue(KvExpiry.Never,false);assertEquals(0,empty.content.descriptor.length);empty.content.close()
        owner.setValue(KvSensitiveInput.of("private-value"));owner.setMetadata(KvSensitiveInput.of("\"private-meta\""))
        assertFalse(owner.state.value.revealed);assertFalse(owner.state.value.toString().contains("private-"))
        assertFalse(KvSensitiveInput.of("private-value").toString().contains("private-value"))
        assertTrue(edits>=3);assertEquals("private-value",owner.valueForDisplay()!!.raw())
        owner.withdraw();assertNull(owner.valueForDisplay());assertFalse(owner.state.value.valueReady)
    }
    @Test fun invalidUtf8AndLargeValuesRemainCompleteFilesWithoutTruncatedText() = runTest {
        val store=KvOwnerMemoryStore();val owner=KvContentOwner(store,{KvOwnerSecurePort()},{})
        // Keep the same real window identity for the complete session.
        val port=KvOwnerSecurePort();val fixed=KvContentOwner(store,{port},{})
        assertTrue(fixed.begin());fixed.acceptValue(store.content(byteArrayOf(-1,0,1)))
        assertFalse(fixed.state.value.textEditable);assertNull(fixed.valueForDisplay())
        val binary=fixed.freezeValue(KvExpiry.Never,false);assertEquals(3,binary.content.descriptor.length)
        binary.content.close();fixed.acceptValue(store.content(ByteArray(65537){65}))
        assertFalse(fixed.state.value.textEditable);assertEquals(65537,fixed.state.value.valueBytes)
        val copy=fixed.freezeExport(KvExportContent.VALUE);assertEquals(65537,copy.descriptor.length)
        fixed.withdraw();assertEquals(1,store.live.size);copy.close();assertTrue(store.live.isEmpty())
        assertFalse(owner.secureNow())
    }
    @Test fun editorRejectsOversizedInputWithoutTruncatingExistingValue() = runTest {
        val p=KvOwnerSecurePort();val owner=KvContentOwner(KvOwnerMemoryStore(),{p},{})
        owner.begin();owner.setValue(KvSensitiveInput.of("retained"))
        assertFalse(owner.setValue(KvSensitiveInput.of("a".repeat(65537))))
        assertEquals("retained",owner.valueForDisplay()!!.raw())
    }
    @Test fun windowReplacementRevokesMaterialAndRetiresOnlyAfterSurfaceRemoval() = runTest {
        var port=KvOwnerSecurePort();var changed=0
        val owner=KvContentOwner(KvOwnerMemoryStore(),{port},{changed++})
        owner.begin();owner.setValue(KvSensitiveInput.of("private-marker"));val old=port
        val epoch=owner.state.value.surface;owner.withdraw()
        assertNull(owner.valueForDisplay());assertTrue(old.held.isNotEmpty());assertEquals(0,old.removed)
        owner.surfaceRemoved(epoch);assertEquals(1,old.removed)
        owner.begin();owner.setValue(KvSensitiveInput.of("other"));port=KvOwnerSecurePort(2);owner.onWindowChanged()
        assertFalse(owner.state.value.secure);assertFalse(owner.state.value.valueReady);assertTrue(changed>0)
    }
    @Test fun bulkDeleteAndWriteFreezeFullKeysButNeverPublishValues() = runTest {
        val store=KvOwnerMemoryStore();val p=KvOwnerSecurePort();val owner=KvContentOwner(store,{p},{})
        owner.begin()
        owner.importBulk(store.content("""[{"key":"A","value":"private-bulk"},{"key":"B","value":""}]""".encodeToByteArray()).openVerified(),KvBulkContentKind.WRITE,1000)
        assertEquals(listOf("A","B"),owner.state.value.bulkKeys);assertFalse(owner.state.value.toString().contains("private-bulk"))
        val frozen=owner.freezeBulkWrite(1000);assertEquals(listOf("A","B"),frozen.keys);frozen.content.close()
        owner.importBulk(store.content("""["A","B"]""".encodeToByteArray()).openVerified(),KvBulkContentKind.DELETE,1000)
        assertEquals(listOf("A","B"),owner.bulkDeleteKeys())
    }
}
