package com.flarego.ui.d1

import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.kv.KvOwnerSecurePort
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.*

internal class D1UiMemoryStore : D1ContentStore {
    val live = mutableSetOf<D1Content>()
    var next = 0
    override fun writer(maxBytes: Long) = object : D1ContentWriter {
        val out = ByteArrayOutputStream()
        var closed = false
        override fun write(buffer: ByteArray, offset: Int, count: Int) { check(!closed && out.size().toLong() + count <= maxBytes); out.write(buffer, offset, count) }
        override fun close() = Unit
        override fun abort() { closed = true; out.reset() }
        override suspend fun seal(): D1Content { check(!closed); closed = true; return content(out.toByteArray()) }
    }
    fun content(bytes: ByteArray): D1Content = object : D1Content {
        var closed = false
        override val descriptor = D1ContentDescriptor("fixture-${++next}", bytes.size.toLong())
        override suspend fun openVerified(): ByteSource { check(!closed); return object : ByteSource {
            var at = 0
            override fun read(buffer: ByteArray): Int { check(!closed); if (at == bytes.size) return -1; val n = minOf(buffer.size, bytes.size-at); bytes.copyInto(buffer, 0, at, at+n); at += n; return n }
            override fun close() = Unit
        } }
        override fun close() { closed = true; live.remove(this) }
    }.also { live += it }
    override suspend fun stage(source: ByteSource, maxBytes: Long): D1Content {
        val w = writer(maxBytes); val b = ByteArray(8192)
        try { while (true) { val n = source.read(b); if (n == -1) break; check(n > 0); w.write(b, 0, n) }; return w.seal() }
        finally { source.close() }
    }
    override fun releaseAll() { live.toList().forEach { it.close() } }
}

class D1ContentOwnerTest {
    @Test fun draftPresenceIncludesBrowseFiltersButNotConfirmationTextOrCompletedSql() = runTest {
        val p = KvOwnerSecurePort(); val o = D1ContentOwner(D1UiMemoryStore(), { p }, {})
        assertFalse(o.hasDraftInput()); assertTrue(o.begin()); assertFalse(o.hasDraftInput())
        o.set(D1Field.TYPED, "confirmed-name"); assertFalse(o.hasDraftInput())
        o.set(D1Field.PREVIOUS_BOOKMARK, "provider-issued-bookmark"); assertFalse(o.hasDraftInput())
        o.set(D1Field.FILTER_VALUE, " "); assertTrue(o.hasDraftInput())
        o.set(D1Field.FILTER_VALUE, ""); assertFalse(o.hasDraftInput())
        o.set(D1Field.SQL, "SELECT 1;"); o.set(D1Field.PARAMS, "[]"); assertTrue(o.hasDraftInput())
        o.clearInput(); assertFalse(o.hasDraftInput())
    }
    @Test fun importedDraftIsPendingUntilClearedButResultAloneIsNotDraft() = runTest {
        val store = D1UiMemoryStore(); val p = KvOwnerSecurePort(); val o = D1ContentOwner(store, { p }, {})
        o.begin(); val source = store.content("SELECT 1;".encodeToByteArray())
        o.importInput(source.openVerified(), D1InputKind.SQL_TEXT); source.close()
        assertTrue(o.hasDraftInput())
        o.acceptResult(store.content("private-result".encodeToByteArray()))
        o.clearInput(); assertFalse(o.hasDraftInput()); assertTrue(o.state.value.resultReady)
        o.withdraw(); assertTrue(store.live.isEmpty())
    }
    @Test fun revokedWindowCannotReportOrRetainPrivateDrafts() = runTest {
        var p = KvOwnerSecurePort(); val o = D1ContentOwner(D1UiMemoryStore(), { p }, {})
        o.begin(); o.set(D1Field.TABLE, "private-table"); assertTrue(o.hasDraftInput())
        p = KvOwnerSecurePort(2)
        assertFalse(o.hasDraftInput()); assertFalse(o.state.value.secure)
        assertNull(o.field(D1Field.TABLE))
    }
    @Test fun missingWindowRejectsInputAndClosesUnclaimedResult() = runTest {
        val store = D1UiMemoryStore(); val owner = D1ContentOwner(store, { null }, {})
        assertFalse(owner.begin()); assertFalse(owner.set(D1Field.SQL, "secret-sql"))
        assertNull(owner.field(D1Field.SQL)); assertFails { owner.freezeSql(false) }
        assertFalse(owner.acceptResult(store.content("secret-result".encodeToByteArray())))
        assertTrue(store.live.isEmpty())
    }
    @Test fun privateFieldsNeverEnterPublicStateAndEditorDoesNotTruncate() = runTest {
        val p = KvOwnerSecurePort(); val o = D1ContentOwner(D1UiMemoryStore(), { p }, {})
        assertTrue(o.begin()); assertTrue(o.set(D1Field.SQL, "secret-sql")); assertTrue(o.set(D1Field.PARAMS, "[\"secret-param\"]"))
        assertFalse(o.state.value.revealed); assertFalse(o.state.value.toString().contains("secret-"))
        assertFalse(o.set(D1Field.SQL, "x".repeat(65537))); assertEquals("secret-sql", o.field(D1Field.SQL))
        val frozen = o.freezeSql(false); assertEquals(1, frozen.queryCount); frozen.content.close()
    }
    @Test fun fullFileAndIndependentResultSurviveInputTerminalAndExportCopySurvivesClose() = runTest {
        val store = D1UiMemoryStore(); val p = KvOwnerSecurePort(); val o = D1ContentOwner(store, { p }, {})
        o.begin(); val text = "SELECT '" + "x".repeat(70000) + "';"
        val input = store.content(text.encodeToByteArray())
        o.importInput(input.openVerified(), D1InputKind.SQL_TEXT); input.close()
        val frozen = o.freezeSql(false); assertTrue(frozen.content.descriptor.length > 70000); frozen.content.close()
        assertTrue(o.acceptResult(store.content(("[" + "r".repeat(9000) + "]").encodeToByteArray())))
        o.clearInput(); o.pageResult(false); assertEquals(2048, o.resultPage()!!.text.length)
        assertTrue(o.state.value.resultBytes!! > 9000); val copy = o.freezeExport()
        o.withdraw(); assertNull(o.resultPage()); assertEquals(1, store.live.size)
        assertEquals(9002, copy.descriptor.length); copy.close(); assertTrue(store.live.isEmpty())
    }
    @Test fun revokedLeaseCannotAcceptLateResultIntoANewPrivateSession() = runTest {
        val store = D1UiMemoryStore(); val p = KvOwnerSecurePort(); val o = D1ContentOwner(store, { p }, {})
        o.begin(); val lease = o.lease(); o.withdraw(); o.begin()
        assertFalse(o.acceptResult(store.content("late".encodeToByteArray()), lease))
        assertFalse(o.state.value.resultReady); assertTrue(store.live.isEmpty())
    }
    @Test fun replacementWindowRevokesAllFieldsAndRetiresOldSurfaceAfterRemoval() = runTest {
        val store = D1UiMemoryStore(); var p = KvOwnerSecurePort(); val old = p
        val o = D1ContentOwner(store, { p }, {}); o.begin(); o.set(D1Field.BOOKMARK, "private-bookmark")
        val surface = o.state.value.surface; p = KvOwnerSecurePort(2); o.onWindowChanged()
        assertNull(o.field(D1Field.BOOKMARK)); assertFalse(o.state.value.secure); assertEquals(0, old.removed)
        o.surfaceRemoved(surface); assertEquals(1, old.removed)
    }
    @Test fun resultPaginationRetainsCompleteUtf8AndClosesReaders() = runTest {
        val store = D1UiMemoryStore(); val p = KvOwnerSecurePort(); val o = D1ContentOwner(store, { p }, {})
        o.begin(); val text = "界".repeat(3000); o.acceptResult(store.content(text.encodeToByteArray()))
        val rebuilt = StringBuilder()
        do { o.pageResult(false); val page = o.resultPage()!!; rebuilt.append(page.text) } while (page.hasMore)
        assertEquals(text, rebuilt.toString()); o.pageResult(true); assertTrue(o.resultPage()!!.hasMore)
    }
    @Test fun explicitlyClosingResultReleasesOnlyItsOwnerAndKeepsApprovedExportCopy() = runTest {
        val store=D1UiMemoryStore();val p=KvOwnerSecurePort();val o=D1ContentOwner(store,{p},{})
        o.begin();o.set(D1Field.SQL,"SELECT 'input';");o.acceptResult(store.content("private-result".encodeToByteArray()))
        o.pageResult(false);val copy=o.freezeExport();o.closeResult()
        assertFalse(o.state.value.resultReady);assertNull(o.resultPage());assertEquals("SELECT 'input';",o.field(D1Field.SQL))
        assertEquals(1,store.live.size);copy.close();assertTrue(store.live.isEmpty())
    }
}
