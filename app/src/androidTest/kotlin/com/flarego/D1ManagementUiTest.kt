package com.flarego

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.d1.*
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.d1.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File
import java.security.MessageDigest

/** Real Main, VM, Window, encrypted cache and SAF; every cloud/transfer adapter is synthetic. */
@SdkSuppress(minSdkVersion = 29)
class D1ManagementUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val account = "d".repeat(32)
    private val uuid = "12345678-1234-4321-abcd-123456789abc"
    private val base = "/accounts/$account/d1/database"
    private val path = "$base/$uuid"
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var replication = "disabled"
    private var unknown = false
    private var partial = false
    private var productionAuthority = false
    private var afterQuery: (suspend () -> Unit)? = null
    private val exportSql = "-- complete synthetic export\n" + "INSERT INTO t VALUES ('synthetic');\n".repeat(3000)
    private fun envelope(result: String) = """{"success":true,"errors":[],"messages":[],"result":$result}"""
    private fun row(id: String = uuid) = """{"uuid":"$id","name":"D1-Synthetic","file_size":123456,"read_replication":{"mode":"$replication"},"unknown_detail":"private-detail"}"""
    private fun body(request: HttpRequest): String = when (val p = request.payload) {
        is HttpBody.SensitiveJson -> p.consumeBytes().decodeToString()
        is HttpBody.Stream -> {
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
            try { while (true) { val n = p.source.read(b); if (n == -1) break; check(n > 0); out.write(b,0,n) } } finally { p.source.close() }
            out.toString("UTF-8")
        }
        else -> ""
    }
    private fun transport() = HttpTransport { r ->
        requests += r
        if (r.path == "/accounts/$account/tokens/verify") return@HttpTransport HttpResponse(401,"fixture")
        val result = when {
            r.path == "/accounts/$account" -> """{"id":"$account","name":"D1 Synthetic"}"""
            r.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
            r.path == "/user/tokens/self" -> """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"d1-write"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
            r.path == "/user/tokens/permission_groups" -> """[{"id":"d1-write","name":"D1 Write","scopes":["com.cloudflare.api.account"]}]"""
            r.method == "GET" && r.path.startsWith("$base?") -> "[${row()}]"
            r.method == "GET" && r.path.contains("/time_travel/bookmark") -> """{"bookmark":"synthetic-frozen-bookmark"}"""
            r.method == "GET" && r.path == path -> row()
            r.method == "POST" && r.path == base -> { bodies += body(r); row("11111111-2222-3333-4444-555555555555") }
            r.method in setOf("PUT","PATCH") && r.path == path -> { val b = body(r); bodies += b; if (b.contains("auto")) replication = "auto"; row() }
            r.method == "DELETE" && r.path == path -> "null"
            r.path == "$path/undelete" -> { bodies += body(r); row() }
            r.path.startsWith("$path/time_travel/restore?") -> """{"bookmark":"synthetic-after","previous_bookmark":"synthetic-before"}"""
            r.path == "$path/import" -> { bodies += body(r); """{"success":true,"type":"import","status":"complete","at_bookmark":"synthetic-import","result":{"final_bookmark":"synthetic-import-final","num_queries":2,"meta":{"changes":1}}}""" }
            r.path == "$path/export" -> { bodies += body(r); """{"success":true,"type":"export","status":"complete","result":{"filename":"synthetic.sql","signed_url":"https://fixture.example.invalid/private/export.sql"}}""" }
            else -> "[]"
        }
        HttpResponse(200,envelope(result))
    }
    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request; val text = body(request); bodies += text; afterQuery?.invoke()
            if (unknown) { sink.close(); throw java.io.IOException("synthetic unknown") }
            val raw = request.path.endsWith("/raw")
            val results = if (raw) """{"columns":["private-column","number","nil","flag"],"rows":[["private-result",9223372036854775807,null,true]]}"""
                else """[{"private-column":"${"private-result-".repeat(800)}","number":9223372036854775807,"nil":null,"flag":true}]"""
            val response = envelope("""[{"success":true,"meta":{"changes":1},"results":$results}${if(partial) ",{\"success\":false,\"error\":\"private-error\"}" else ""}]""").encodeToByteArray()
            try { sink.write(response,0,response.size) } finally { sink.close() }
            return HttpResponseHead(200,emptyMap(),response.size.toLong())
        }
    }
    private val signed = object : D1SignedTransferPort {
        override suspend fun upload(location: D1UploadLocation, content: D1Content, md5: String) = error("direct init must not upload")
        override suspend fun download(location: D1DownloadLocation, sink: ByteSink): Long {
            assertTrue(location.withUrl { it.startsWith("https://fixture.example.invalid/") })
            val bytes = exportSql.encodeToByteArray(); try { sink.write(bytes,0,bytes.size) } finally { sink.close() }; return bytes.size.toLong()
        }
    }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(a: Activity,s: Bundle?) {
            if (a !is MainActivity || model != null) return
            val adapters = D1SyntheticAdapters({ connection,content,guard ->
                val gateway = CloudflareGateway(transport())
                if (productionAuthority) AuthorizedD1Session(connection,gateway,transfers,content,
                    D1TokenAuthorityIssuer(connection,1,{1},OperationClock(System::currentTimeMillis)),guard)
                else CloudflareD1Client(connection,gateway,transfers,content) { _,_,_ -> guard() }
            },signed)
            val m = FlareGoViewModel.forD1SyntheticTest(application,Dispatchers.IO,{ transport() },{ transfers },adapters)
            initial = m.controller.state.value.connections.map { it.id }.toSet(); model = m
            a.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:com.flarego.FlareGoViewModel",m)
        }
        override fun onActivityCreated(a: Activity,s: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity,s: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() { instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) } }
        override fun after() { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = model!!.d1Browser
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000,condition)
    private fun scroll(tag: String): SemanticsNodeInteraction {
        val container = if (compose.onAllNodesWithTag("d1-confirm-scroll").fetchSemanticsNodes().isNotEmpty()) "d1-confirm-scroll" else "d1-content"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun click(tag: String) { scroll(tag).assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) } }
    private fun input(tag: String,value: String) { scroll(tag).performTextReplacement(value) }
    private fun install(open: Boolean = true) {
        compose.runOnIdle { model!!.viewModelScope.launch { model!!.connect("D1 Synthetic",account,"synthetic-d1-token") } }
        waitFor { model!!.controller.state.value.selected?.accountId == account }
        compose.onNodeWithTag("nav-resources").performClick(); compose.onNodeWithText("数据库",useUnmergedTree = true).performClick()
        waitFor { browser.state.value.databases.isNotEmpty() }
        if (open) { click("d1-db-$uuid"); waitFor { browser.state.value.database != null } }
    }
    @Test fun systemBackCancelsPrivateEditorBeforeReturningToDatabaseDirectory() {
        install()
        click("d1-open-SQL")
        hideKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        waitFor { browser.state.value.editor == null }
        assertNotNull(browser.state.value.database)
        assertFalse(browser.content.state.value.secure)
        androidx.test.espresso.Espresso.pressBack()
        waitFor { browser.state.value.database == null }
        scroll("d1-db-$uuid").assertIsDisplayed()
        assertTrue(requests.none { it.method in setOf("PUT", "POST", "PATCH", "DELETE") })
    }
    private fun hideKeyboard() {
        compose.runOnUiThread {
            android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }?.let { view ->
                if (android.os.Build.VERSION.SDK_INT >= 30) view.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
                else compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
            }
        }; compose.waitForIdle()
    }
    private fun capture(name: String,dialog: Boolean = true) {
        hideKeyboard(); compose.waitForIdle()
        val bitmap = (if(dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("d1-content")).captureToImage().asAndroidBitmap()
        val directory = File(application.filesDir,"d1-evidence/font-${application.resources.configuration.fontScale}").also { it.mkdirs() }
        File(directory,"$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }
    private fun prepare() {
        try {
            hideKeyboard(); scroll("d1-prepare").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }; waitFor { browser.content.state.value.confirmation != null || browser.state.value.error != null }; assertNotNull(browser.content.state.value.confirmation)
        } catch (failure: Throwable) {
            val dir=File(application.filesDir,"d1-evidence/font-${application.resources.configuration.fontScale}").also{it.mkdirs()}
            File(dir,"prepare-failure-${browser.state.value.editor}-${System.nanoTime()}.txt").writeText(browser.state.value.toString()+"\n"+browser.content.state.value+"\n"+compose.onRoot().printToString(60))
            runCatching{capture("prepare-failure",false)};throw failure
        }
    }
    private fun stages(name: String,typed: String = uuid,tables: Boolean = false) {
        assertEquals(D1ConfirmationStage.RISK,browser.content.state.value.confirmation?.stage)
        compose.onNodeWithTag("d1-confirm-scroll").performScrollToIndex(0); capture("$name-risk")
        compose.onNodeWithTag("d1-risk-next").performClick(); waitFor { browser.content.state.value.confirmation?.stage == D1ConfirmationStage.TYPED }
        input("d1-typed-id","wrong"); compose.onNodeWithTag("d1-typed-next").assertIsNotEnabled()
        input("d1-typed-id",typed); hideKeyboard(); capture("$name-typed")
        compose.onNodeWithTag("d1-typed-next").performClick(); waitFor { browser.content.state.value.confirmation?.stage == D1ConfirmationStage.FINAL }
        val connection = browser.state.value.context!!.connection
        compose.onNodeWithText("连接：${connection.name}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("连接 ID：${connection.id}").assertIsDisplayed()
        capture("$name-final-connection")
        compose.onNodeWithTag("d1-confirm-scroll").performScrollToIndex(0); capture("$name-final")
        if (tables) {
            click("d1-confirm-reveal"); click("d1-tables-next"); click("d1-tables-next")
            scroll("d1-confirm-table-10").assertTextContains("table-10",substring = true)
            scroll("d1-confirm-table-11").assertTextContains("table-11",substring = true)
            capture("$name-final-last-tables")
        }
        if (name.startsWith("restore-")) {
            click("d1-confirm-reveal")
            val frozen = browser.content.confirmation()!!
            frozen.restoreTimestamp?.let { value ->
                compose.onNodeWithText("请求 UTC：$value").performScrollTo().assertIsDisplayed(); capture("$name-final-timestamp")
            }
            frozen.restoreBookmark?.let { value ->
                compose.onNodeWithText("冻结 bookmark：$value").performScrollTo().assertIsDisplayed(); capture("$name-final-bookmark")
            }
        }
        scroll("d1-final-end"); capture("$name-final-end")
        compose.onNodeWithTag("d1-confirm-title").assertIsDisplayed(); compose.onNodeWithTag("d1-cancel-confirm").assertIsDisplayed()
        compose.onNodeWithTag("d1-final-send").assertIsDisplayed().performClick()
        waitFor { !browser.state.value.writing && browser.content.state.value.confirmation == null && browser.state.value.tasks.isNotEmpty() }
        compose.onNode(isDialog()).assertDoesNotExist(); waitFor { compose.activity.hasWindowFocus() }
        assertNull(browser.liveCommand()); assertNull(browser.state.value.editor)
        assertEquals("",browser.content.field(D1Field.SQL)); assertNull(browser.content.state.value.inputBytes)
    }
    private val automation get() = instrumentation.uiAutomation
    private var ownedSource: String? = null
    private var ownedDirectory: String? = null
    private fun shell(command: String): String {
        val pipes = automation.executeShellCommandRw("sh")
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command+"\nexit\n").toByteArray()) }
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes().decodeToString() }
        } finally { pipes.forEach { runCatching { it.close() } } }
    }
    private fun node(root: android.view.accessibility.AccessibilityNodeInfo?, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null) return null; if (predicate(root)) return root
        for (i in 0 until root.childCount) node(root.getChild(i),predicate)?.let { return it }; return null
    }
    private fun systemClick(text: String): Boolean {
        var n = node(automation.rootInActiveWindow) { it.text?.toString()?.equals(text,true) == true || it.contentDescription?.toString()?.equals(text,true) == true } ?: return false
        while (!n.isClickable) n = n.parent ?: return false
        return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun systemWait(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime()+15_000_000_000L
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(100) }; assertTrue(message,condition())
    }
    private fun pickerVisible() = automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
    private fun cancelPicker() { automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); systemWait("Picker closes") { !pickerVisible() } }
    @After fun cleanup() {
        if (pickerVisible()) cancelPicker()
        ownedSource?.let { shell("rm -f /sdcard/Download/$it") }
        ownedDirectory?.let { shell("rm -rf /sdcard/Documents/$it") }
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect) } }
        waitFor { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
    }
    @Test fun directoryCreateDetailsReplicationAndDeleteUseRealEntry() {
        install(false); capture("directory",false); click("d1-create"); input("d1-name","Created"); prepare(); stages("create",account)
        assertEquals(1,requests.count { it.method == "POST" && it.path == base })
        click("d1-db-$uuid"); waitFor { browser.state.value.database != null }; click("d1-details"); waitFor { browser.content.state.value.resultReady }
        click("d1-open-PUT"); compose.runOnIdle { browser.edit { it.copy(replication = D1Replication.auto) } }; prepare(); stages("put")
        click("d1-open-PATCH"); compose.runOnIdle { browser.edit { it.copy(replication = null) } }; prepare(); stages("patch")
        assertEquals("{}",bodies.last()); click("d1-open-DELETE"); prepare(); stages("delete")
        assertEquals(1,requests.count { it.method == "DELETE" && it.path == path }); assertTrue(browser.state.value.tasks.all { it.state == TaskState.SUCCEEDED })
    }
    @Test fun queryPreservesProgramAndParamsWithPrivateCompletePagedResults() {
        install(); click("d1-open-SQL"); input("d1-sql","/* ; */ SELECT 'private-sql;'; PRAGMA user_version=2;"); input("d1-params","[\"private-param\"]")
        capture("sql-masked",false); compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        prepare(); assertTrue(requests.none { it.method == "POST" && it.path.endsWith("/query") })
        click("d1-confirm-reveal"); scroll("d1-confirm-private-page")
        waitFor { compose.onAllNodes(hasTestTag("d1-confirm-private-page") and hasText("private-param",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("d1-confirm-private-page").assertTextContains("private-param",substring = true); capture("query-full-frozen-input")
        stages("query")
        assertTrue(bodies.single().contains("private-sql;")); assertTrue(bodies.single().contains("private-param"))
        assertTrue(browser.content.state.value.resultReady); click("d1-result-next"); capture("result-masked",false)
        click("d1-reveal"); scroll("d1-result-page").assertTextContains("private-result-",substring = true); capture("result-revealed",false)
        click("d1-result-next"); capture("result-next",false)
        assertFalse(browser.state.value.toString().contains("private-")); assertFalse(browser.state.value.tasks.toString().contains("private-"))
        androidx.test.espresso.Espresso.pressBack()
        waitFor { !browser.content.state.value.resultReady }
        compose.runOnIdle { assertFalse(browser.content.state.value.secure) }
        assertNotNull(browser.state.value.database)
        androidx.test.espresso.Espresso.pressBack()
        waitFor { browser.state.value.database == null }
        assertEquals(1, requests.count { it.path.endsWith("/query") })
    }
    @Test fun systemBackClosesResultButKeepsActiveSqlDraftProtected() {
        install(); click("d1-details"); waitFor { browser.content.state.value.resultReady }
        click("d1-open-SQL"); input("d1-sql", "SELECT 'draft-kept';")
        hideKeyboard()
        compose.waitUntil(5000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        }
        androidx.test.espresso.Espresso.pressBack()
        waitFor { !browser.content.state.value.resultReady }
        compose.runOnIdle {
            assertTrue(browser.content.state.value.secure)
            assertEquals(D1Editor.SQL, browser.state.value.editor)
            assertEquals("SELECT 'draft-kept';", browser.content.field(D1Field.SQL))
        }
        assertTrue(requests.none { it.method in setOf("POST", "PUT", "PATCH", "DELETE") })
    }

    @Test fun systemBackFromBrowseResultRetainsPrivateFiltersWithoutWriting() {
        install(); click("d1-private-begin"); input("d1-table", "private-table")
        input("d1-filter-column", "id"); input("d1-filter-value", "keep-filter")
        click("d1-browse-ROWS"); waitFor { browser.content.state.value.resultReady }
        hideKeyboard()
        compose.waitUntil(5000) {
            !compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime())
        }
        androidx.test.espresso.Espresso.pressBack()
        waitFor { !browser.content.state.value.resultReady }
        compose.runOnIdle {
            assertTrue(browser.content.state.value.secure)
            assertNull(browser.state.value.editor)
            assertEquals("private-table", browser.content.field(D1Field.TABLE))
            assertEquals("keep-filter", browser.content.field(D1Field.FILTER_VALUE))
            assertNotNull(browser.state.value.database)
            assertTrue(browser.state.value.tasks.isEmpty())
        }
        // Controlled ROWS browsing legitimately uses a read-only POST /query.
        assertEquals(1, requests.count { it.method == "POST" && it.path.endsWith("/query") })
        assertTrue(requests.none { it.method in setOf("PUT", "PATCH", "DELETE") ||
            (it.method == "POST" && !it.path.endsWith("/query")) })
    }

    @Test fun systemBackFromDetailsRetainsPendingExportAndDoesNotExportAgain() {
        install(); click("d1-open-EXPORT"); prepare(); stages("export-pending-back")
        compose.runOnIdle {
            assertTrue(browser.content.state.value.exportReady)
            assertFalse(browser.content.hasDraftInput())
            assertNull(browser.state.value.editor)
        }
        click("d1-details"); waitFor { browser.content.state.value.resultReady }
        androidx.test.espresso.Espresso.pressBack()
        waitFor { !browser.content.state.value.resultReady }
        compose.runOnIdle {
            assertTrue(browser.content.state.value.secure)
            assertTrue(browser.content.state.value.exportReady)
            assertNotNull(browser.state.value.database)
        }
        assertEquals(1, requests.count { it.method == "POST" && it.path == "$path/export" })
        click("d1-download-export"); waitFor { browser.content.state.value.resultReady }
        assertEquals(exportSql.encodeToByteArray().size.toLong(), browser.content.state.value.resultBytes)
        assertEquals(1, requests.count { it.method == "POST" && it.path == "$path/export" })
    }

    @Test fun controlledBrowseAndRawBatchKeepTypesAndDoNotTurnCustomSqlIntoRead() {
        install(); click("d1-private-begin"); input("d1-table","private-table"); input("d1-filter-column","id"); input("d1-filter-value","private-filter"); input("d1-sort-column","id")
        for (kind in D1BrowseKind.entries) { val before = requests.size; click("d1-browse-${kind.name}"); waitFor { requests.size > before && !browser.state.value.loading }; assertTrue(browser.state.value.tasks.isEmpty()) }
        click("d1-open-RAW"); click("d1-json-mode"); input("d1-json","""{"batch":[{"sql":"SELECT 'private-batch;';","params":[]},{"sql":"PRAGMA user_version=3;","params":[]}]}""")
        prepare(); stages("raw-batch"); assertEquals(1,requests.count { it.path.endsWith("/raw") })
        click("d1-result-next"); click("d1-reveal"); scroll("d1-result-page").assertTextContains("9223372036854775807",substring = true); capture("raw-types",false)
    }
    @Test fun completeImportDirectInitAndExportFullTableRangeHaveSeparateDownload() {
        install(); compose.runOnIdle { model!!.viewModelScope.launch { browser.importDocument(D1DocumentKind.IMPORT_SQL,source("CREATE TABLE t(x); INSERT INTO t VALUES ('private-file');")) } }
        waitFor { browser.content.state.value.inputBytes != null }; prepare(); stages("import-direct")
        assertEquals(1,requests.count { it.path == "$path/import" }); assertTrue(bodies.single().contains("init")); assertFalse(bodies.single().contains("ingest"))
        assertTrue(browser.content.state.value.resultReady); click("d1-result-next"); click("d1-reveal")
        scroll("d1-result-page").assertTextContains("synthetic-import-final",substring = true); capture("import-full-receipt",false)
        click("d1-close-result"); assertFalse(browser.content.state.value.resultReady)
        click("d1-open-EXPORT"); input("d1-tables",(0..11).joinToString("\n") { "table-$it" }); prepare(); stages("export-tables",tables = true)
        assertTrue(browser.content.state.value.exportReady); assertFalse(browser.content.state.value.resultReady)
        click("d1-download-export"); waitFor { browser.content.state.value.resultReady }; assertEquals(exportSql.encodeToByteArray().size.toLong(),browser.content.state.value.resultBytes)
        click("d1-result-next"); click("d1-reveal"); scroll("d1-result-page").assertTextContains("complete synthetic export",substring = true); capture("export-downloaded",false)
    }
    @Test fun bookmarkTimestampRestoreAndPreviousRequireNewConfirmation() {
        install(); click("d1-open-RESTORE"); input("d1-timestamp","2026-10-25T10:00:00Z"); click("d1-read-bookmark")
        waitFor { !browser.state.value.loading && browser.content.field(D1Field.BOOKMARK)?.isNotEmpty() == true }
        click("d1-restore-time"); prepare(); stages("restore-time")
        assertEquals(1,requests.count { it.path.startsWith("$path/time_travel/restore?") }); assertTrue(requests.last { it.path.startsWith("$path/time_travel/restore?") }.path.contains("bookmark=synthetic-frozen-bookmark"))
        compose.runOnIdle { browser.restorePrevious() }; prepare(); stages("restore-previous")
        assertEquals(2,requests.count { it.path.startsWith("$path/time_travel/restore?") }); assertTrue(requests.last { it.path.startsWith("$path/time_travel/restore?") }.path.contains("bookmark=synthetic-before"))
    }
    @Test fun undeleteExactUuidAndProductionMissingEvidenceRemainClosed() {
        install(false); click("d1-undelete"); input("d1-uuid",uuid); input("d1-name","Restored"); prepare(); stages("undelete")
        assertTrue(bodies.single().contains("new_name")); assertEquals(1,requests.count { it.path.endsWith("/undelete") })
        click("d1-db-$uuid"); waitFor { browser.state.value.database != null }; productionAuthority = true
        click("d1-open-EXPORT"); prepare(); stages("production-export-closed")
        assertEquals(TaskState.FAILED,browser.state.value.tasks.first { it.actionKey == "d1-export-database" }.state)
        assertTrue(requests.none { it.path == "$path/export" }); capture("production-evidence-closed",false)
    }
    @Test fun partialAndUnknownKeepDurableDatabaseGateWithoutReplay() {
        partial = true; install(); click("d1-open-SQL"); input("d1-sql","SELECT 1; INSERT INTO t VALUES(2);"); prepare(); stages("partial")
        assertEquals(TaskState.PARTIAL,browser.state.value.tasks.single().state); assertTrue(browser.content.state.value.resultReady)
        click("d1-open-DELETE"); prepareExpectBlocked(); assertEquals(1,requests.count { it.path.endsWith("/query") }); assertTrue(requests.none { it.method == "DELETE" })
        compose.runOnIdle { browser.closeTask(browser.state.value.tasks.single().id,uuid,true) }; waitFor { browser.state.value.tasks.single().state == TaskState.ACTION_REQUIRED }
        partial = false; unknown = true; click("d1-open-SQL"); input("d1-sql","INSERT INTO t VALUES(3);"); prepare(); stages("unknown")
        assertTrue(browser.state.value.tasks.any { it.state == TaskState.UNKNOWN }); click("d1-open-PUT"); prepareExpectBlocked(); capture("unknown-gate",false)
        assertEquals(2,requests.count { it.path.endsWith("/query") }); assertTrue(requests.none { it.method == "PUT" })
    }
    private fun prepareExpectBlocked() { click("d1-prepare"); waitFor { browser.state.value.error != null }; assertNull(browser.content.state.value.confirmation) }
    @Test fun backgroundRejectsOldConfirmationAndLateResult() {
        install(); click("d1-open-SQL"); input("d1-sql","SELECT 'private-late'"); prepare()
        compose.runOnIdle { model!!.onD1Background() }; assertFalse(browser.content.state.value.secure); assertNull(browser.content.state.value.confirmation)
        assertTrue(requests.none { it.path.endsWith("/query") })
        click("d1-open-SQL"); input("d1-sql","SELECT 'private-late'"); prepare()
        compose.onNodeWithTag("d1-risk-next").performClick(); input("d1-typed-id",uuid); hideKeyboard(); compose.onNodeWithTag("d1-typed-next").performClick()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); afterQuery = { started.complete(Unit); release.await() }
        compose.onNodeWithTag("d1-final-send").performClick(); waitFor { started.isCompleted }
        compose.runOnIdle { model!!.onD1Background() }; release.complete(Unit)
        waitFor { model!!.operationStore.list(browser.state.value.context!!.connection.id).any { it.state != TaskState.RUNNING } }
        assertFalse(browser.content.state.value.resultReady); assertFalse(browser.content.state.value.secure); assertEquals(1,requests.count { it.path.endsWith("/query") })
    }
    @Test fun actualSafImportsFullSqlAndPublishesCompleteIndependentResultCopy() {
        install(); val baseline = application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val name = "flarego-d1-source-${java.util.UUID.randomUUID()}.sql"; ownedSource = name
        shell("printf 'SELECT 1;\\n' > /sdcard/Download/$name")
        click("d1-open-IMPORT"); click("d1-import-sql"); systemWait("Actual OpenDocument",::pickerVisible)
        systemClick("Show roots"); systemWait("Downloads root") { systemClick("Downloads") }; systemWait("Explicit synthetic SQL source") { systemClick(name) }
        waitFor { browser.content.state.value.inputBytes != null }; capture("saf-import",false); prepare(); stages("saf-import")
        click("d1-open-EXPORT"); prepare(); stages("saf-export-all"); click("d1-download-export")
        try { waitFor { browser.content.state.value.resultReady } } catch (failure: Throwable) {
            val dir=File(application.filesDir,"d1-evidence/font-${application.resources.configuration.fontScale}").also{it.mkdirs()}
            File(dir,"saf-download-failure.txt").writeText(browser.state.value.toString()+"\n"+browser.content.state.value+"\n"+compose.onRoot().printToString(60))
            runCatching{capture("saf-download-failure",false)};throw failure
        }
        val directory = "flarego-d1-export-${java.util.UUID.randomUUID()}"; ownedDirectory = directory; shell("mkdir -p /sdcard/Documents/$directory")
        click("d1-export-result"); systemWait("Actual tree picker",::pickerVisible); systemWait("Explicit owned directory") { systemClick(directory) }
        systemWait("Choose folder") { systemClick("Use this folder") }; systemWait("Authorize folder") { systemClick("Allow") }
        waitFor { browser.state.value.notice?.contains("完整文件已保存") == true }
        val names = shell("ls /sdcard/Documents/$directory").trim().lines().filter { it.isNotBlank() }; assertEquals(1,names.size)
        assertTrue(names.single().startsWith("flarego-d1-")); assertFalse(names.single().contains("pending")); assertTrue(names.single().endsWith(".sql"))
        assertEquals(exportSql.encodeToByteArray().size.toString(),shell("wc -c < /sdcard/Documents/$directory/${names.single()}").trim())
        val hash = MessageDigest.getInstance("SHA-256").digest(exportSql.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(hash,shell("sha256sum /sdcard/Documents/$directory/${names.single()}").substringBefore(' ')); assertEquals(baseline,application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet())
        scroll("d1-notice"); capture("saf-export-complete",false)
    }
    @Test fun cancelledAndStaleSafResultsCannotRepopulatePrivateSession() {
        install(); click("d1-open-IMPORT"); click("d1-import-sql"); systemWait("OpenDocument",::pickerVisible); cancelPicker()
        assertNull(browser.content.state.value.inputBytes)
        click("d1-open-SQL"); click("d1-import-query"); systemWait("OpenDocument again",::pickerVisible)
        compose.runOnUiThread { model!!.controller.navigate(Page.OVERVIEW) }; cancelPicker(); waitFor { browser.state.value.context == null }
        assertFalse(browser.content.state.value.secure); assertNull(browser.content.state.value.inputBytes); assertTrue(requests.none { it.method != "GET" })
    }
    private fun source(text: String) = object : ByteSource {
        val bytes = text.encodeToByteArray(); var at = 0
        override fun read(buffer: ByteArray): Int { if (at == bytes.size) return -1; val n = minOf(buffer.size,bytes.size-at); bytes.copyInto(buffer,0,at,at+n); at += n; return n }
        override fun close() { bytes.fill(0) }
    }
}
