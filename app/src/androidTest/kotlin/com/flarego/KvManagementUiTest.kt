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
import com.flarego.core.application.kv.KvConfirmationStage
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.kv.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

/** Actual MainActivity, VM, vault/database, secure window and native controls; synthetic cloud only. */
@SdkSuppress(minSdkVersion=29)
class KvManagementUiTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val application get()=instrumentation.targetContext.applicationContext as Application
    private val account="e".repeat(32)
    private val ns="f".repeat(32)
    private val base="/accounts/$account/storage/kv/namespaces"
    private val path="$base/$ns"
    private var model:FlareGoViewModel?=null
    private var initial=emptySet<String>()
    private val requests=java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val bodies=java.util.Collections.synchronizedList(mutableListOf<String>())
    private var title="KV Synthetic"
    private var allowWrites=true
    private var partial=false
    private var unknown=false
    private val binary=byteArrayOf(0,-1,-128,65)
    private fun envelope(result:String)="""{"success":true,"errors":[],"messages":[],"result":$result}"""
    private fun transport()=HttpTransport {r->
        requests+=r
        if(r.path=="/accounts/$account/tokens/verify")return@HttpTransport HttpResponse(401,"fixture")
        val result=when {
            r.path=="/accounts/$account"->"""{"id":"$account","name":"KV Synthetic"}"""
            r.path=="/user/tokens/verify"->"""{"id":"self","status":"active"}"""
            r.path=="/user/tokens/self"->if(allowWrites)"""{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"kv-write"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}""" else """{"id":"self","status":"active","policies":[]}"""
            r.path=="/user/tokens/permission_groups"->"""[{"id":"kv-write","name":"Workers KV Storage Write","scopes":["com.cloudflare.api.account"]}]"""
            r.path.startsWith("$base?")->"""[{"id":"$ns","title":"$title","supports_url_encoding":true}]"""
            r.method=="POST"&&r.path==base->"""{"id":"${"1".repeat(32)}","title":"Created","supports_url_encoding":true}"""
            r.method=="GET"&&r.path==path->"""{"id":"$ns","title":"$title","supports_url_encoding":true}"""
            r.method=="PUT"&&r.path==path->{title="Renamed";"null"}
            r.path.startsWith("$path/keys?")->"""[{"name":"binary/key","expiration":2000000000,"metadata":{"private":true}}]"""
            r.path.contains("/metadata/")->"""{"synthetic":"metadata"}"""
            r.method=="PUT"&&r.path.contains("/values/")->{
                val payload=r.payload
                val stream=if(payload is HttpBody.Multipart)payload.parts.first{it.name=="value"}.body as HttpBody.Stream else payload as HttpBody.Stream
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                try{while(true){val n=stream.source.read(buffer);if(n<0)break;check(n>0);out.write(buffer,0,n)}}finally{stream.source.close()}
                bodies+=out.toByteArray().decodeToString()
                (payload as? HttpBody.Multipart)?.parts?.forEach{(it.body as? HttpBody.SensitiveJson)?.let{body->bodies+=body.consumeBytes().decodeToString()}}
                "null"
            }
            r.path=="$path/bulk/get"->error("Bulk read uses transfer port")
            r.path=="$path/bulk"||r.path=="$path/bulk/delete"->{
                when(val p=r.payload){
                    is HttpBody.Stream->{val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);try{while(true){val n=p.source.read(b);if(n<0)break;check(n>0);out.write(b,0,n)}}finally{p.source.close()};bodies+=out.toString("UTF-8")}
                    is HttpBody.SensitiveJson->bodies+=p.consumeBytes().decodeToString()
                    else->error("unexpected payload")
                }
                if(unknown)throw java.io.IOException("synthetic-unknown")
                if(partial)"""{"successful_key_count":11,"unsuccessful_keys":["key-11"]}"""else """{"successful_key_count":12,"unsuccessful_keys":[]}"""
            }
            r.method=="DELETE"->"null"
            else->"[]"
        }
        HttpResponse(200,envelope(result))
    }
    private val transfers=object:TransferPort {
        override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead {
            requests+=request
            val bytes=if(request.path.endsWith("/bulk/get"))envelope("""{"values":{"object":{"x":1},"bool":false,"null":null}}""").encodeToByteArray()else binary
            try{sink.write(bytes,0,bytes.size)}finally{sink.close()}
            return HttpResponseHead(200,mapOf("Content-Length" to listOf(bytes.size.toString())),bytes.size.toLong())
        }
    }
    private val callbacks=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(a:Activity,s:Bundle?){
            if(a !is MainActivity||model!=null)return
            val m=FlareGoViewModel(application,Dispatchers.IO,{transport()},{transfers})
            initial=m.controller.state.value.connections.map{it.id}.toSet();model=m
            a.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:com.flarego.FlareGoViewModel",m)
        }
        override fun onActivityCreated(a:Activity,s:Bundle?){}
        override fun onActivityStarted(a:Activity){}
        override fun onActivityResumed(a:Activity){}
        override fun onActivityPaused(a:Activity){}
        override fun onActivityStopped(a:Activity){}
        override fun onActivitySaveInstanceState(a:Activity,s:Bundle){}
        override fun onActivityDestroyed(a:Activity){}
    }
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){instrumentation.runOnMainSync{application.registerActivityLifecycleCallbacks(callbacks)}}
        override fun after(){instrumentation.runOnMainSync{application.unregisterActivityLifecycleCallbacks(callbacks)}}
    }
    @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
    private val browser get()=model!!.kvBrowser
    private fun scroll(tag:String):SemanticsNodeInteraction {
        if(compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            val container=if(compose.onAllNodesWithTag("kv-confirm-scroll").fetchSemanticsNodes().isNotEmpty())"kv-confirm-scroll"else"kv-content"
            compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        }
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun click(tag:String){scroll(tag).performClick()}
    private fun input(tag:String,value:String){scroll(tag).performTextReplacement(value)}
    private fun waitFor(condition:()->Boolean)=compose.waitUntil(15000,condition)
    private fun install(open:Boolean=true){
        compose.runOnIdle{model!!.viewModelScope.launch{model!!.connect("KV Synthetic",account,"synthetic-kv-token")}}
        waitFor{model!!.controller.state.value.selected?.accountId==account}
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("KV",useUnmergedTree=true).performClick()
        waitFor{browser.state.value.namespaces.isNotEmpty()}
        if(open)openNamespace()
    }
    private fun openNamespace(){click("kv-ns-$ns");waitFor{browser.state.value.keys.isNotEmpty()}}
    private fun prepare(){click("kv-prepare");waitFor{browser.state.value.confirmation!=null}}
    private fun hideKeyboard(){
        compose.runOnUiThread {
            val focused=android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull{it.hasWindowFocus()}
            if(android.os.Build.VERSION.SDK_INT>=30)focused?.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
            else focused?.let{compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0)}
        }
        compose.waitForIdle()
    }
    private fun capture(name:String,dialog:Boolean=true){
        hideKeyboard();compose.waitForIdle()
        val bitmap=(if(dialog)compose.onNode(isDialog())else compose.onNodeWithTag("kv-browser")).captureToImage().asAndroidBitmap()
        val scale=application.resources.configuration.fontScale
        val dir=File(application.filesDir,"kv-evidence/font-$scale").also{it.mkdirs()}
        File(dir,"$name.png").outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun scopeTop(name:String){
        compose.onNodeWithTag("kv-confirm-scroll").performScrollToIndex(0)
        capture(name)
    }
    private fun stages(name:String,keys:Boolean=false){
        waitFor{browser.state.value.confirmation!=null}
        if(browser.state.value.confirmation!!.handle.stage==KvConfirmationStage.RISK){
            scopeTop("$name-risk");if(keys)keyPages("$name-risk")
            compose.onNodeWithTag("kv-risk-next").performClick()
            waitFor{browser.state.value.confirmation?.handle?.stage==KvConfirmationStage.TYPED}
            input("kv-typed-id","wrong");compose.onNodeWithTag("kv-typed-next").assertIsNotEnabled()
            input("kv-typed-id",ns);hideKeyboard();capture("$name-typed")
            scopeTop("$name-typed-scope")
            compose.onNodeWithTag("kv-typed-next").performClick()
        }
        waitFor{browser.state.value.confirmation?.handle?.stage==KvConfirmationStage.FINAL}
        scopeTop("$name-final")
        if(keys)keyPages("$name-final")
        scroll("kv-final-scope-end");capture("$name-final-end")
        compose.onNodeWithTag("kv-confirm-title").assertIsDisplayed()
        compose.onNodeWithTag("kv-cancel-confirm").assertIsDisplayed()
        compose.onNodeWithTag("kv-final-send").assertIsDisplayed().performClick()
        waitFor{!browser.state.value.writing&&browser.state.value.tasks.isNotEmpty()&&browser.state.value.confirmation==null}
        // A state transition precedes Android removing the old Dialog Window. A real tap
        // on the Activity must wait for that Window to regain focus, not just Compose state.
        compose.onNode(isDialog()).assertDoesNotExist()
        waitFor{compose.activity.hasWindowFocus()}
        assertNull(browser.state.value.editor);assertNull(browser.liveCommand())
        assertFalse(browser.content.state.value.secure);assertFalse(browser.content.state.value.valueReady)
        assertFalse(browser.content.state.value.metadataReady);assertNull(browser.content.state.value.bulkKind)
        assertTrue(File(application.cacheDir,"kv-private").listFiles().orEmpty().none{it.name.startsWith("flarego-kv-")})
    }
    private fun keyPages(name:String){
        scroll("kv-confirm-keys-next").performClick()
        scroll("kv-confirm-key-10").assertTextContains("key-10",substring=true)
        compose.onNodeWithTag("kv-confirm-key-11").assertIsDisplayed().assertTextContains("key-11",substring=true)
        compose.onNodeWithTag("kv-confirm-keys-prev").assertIsEnabled()
        compose.onNodeWithTag("kv-confirm-keys-next").assertIsNotEnabled()
        capture("$name-keys-last-page")
    }
    private fun importBulk(delete:Boolean=false){
        click(if(delete)"kv-bulk-delete"else"kv-bulk-write")
        val text=(0..11).joinToString(",","[","]"){if(delete)"\"key-$it\""else"""{"key":"key-$it","value":"synthetic-value-$it","expiration_ttl":60}"""}
        compose.runOnIdle{model!!.viewModelScope.launch{browser.importDocument(if(delete)KvDocumentKind.IMPORT_BULK_DELETE else KvDocumentKind.IMPORT_BULK_WRITE,object:ByteSource{
            val bytes=text.encodeToByteArray();var at=0
            override fun read(buffer:ByteArray):Int{if(at==bytes.size)return -1;val n=minOf(buffer.size,bytes.size-at);bytes.copyInto(buffer,0,at,at+n);at+=n;return n}
            override fun close()=Unit
        })}}
        waitFor{browser.content.state.value.bulkKeys.size==12}
        if(!delete)click("kv-overwrite")
        prepare()
    }
    private val automation get()=instrumentation.uiAutomation
    private var ownedSource:String?=null
    private var ownedDirectory:String?=null
    private fun shell(command:String):String {
        val pipes=automation.executeShellCommandRw("sh")
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use{it.write((command+"\nexit\n").toByteArray())}
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use{it.readBytes().decodeToString()}
        } finally {pipes.forEach{runCatching{it.close()}}}
    }
    private fun node(root:android.view.accessibility.AccessibilityNodeInfo?,predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
        if(root==null)return null
        if(predicate(root))return root
        for(i in 0 until root.childCount)node(root.getChild(i),predicate)?.let{return it}
        return null
    }
    private fun systemClick(text:String):Boolean {
        var n=node(automation.rootInActiveWindow){it.text?.toString()?.equals(text,true)==true||it.contentDescription?.toString()?.equals(text,true)==true}?:return false
        while(!n.isClickable)n=n.parent?:return false
        return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun systemWait(message:String,condition:()->Boolean) {
        val deadline=System.nanoTime()+15_000_000_000L
        while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(100)}
        assertTrue(message,condition())
    }
    private fun pickerVisible()=automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true
    private fun cancelPicker(){automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);systemWait("Picker closes"){!pickerVisible()}}
    private fun importPickedFixture(){
        val name="flarego-kv-source-${java.util.UUID.randomUUID()}.bin";ownedSource=name
        shell("printf '\\000\\377\\200A' > /sdcard/Download/$name")
        click("kv-new-value");input("kv-key-input","file/key");click("kv-import-value")
        systemWait("Actual OpenDocument picker is visible",::pickerVisible)
        systemClick("Show roots");systemWait("Downloads root"){systemClick("Downloads")}
        systemWait("Select explicit owned source"){systemClick(name)}
        waitFor{browser.content.state.value.valueReady}
        assertEquals(4L,browser.content.state.value.valueBytes);assertFalse(browser.content.state.value.textEditable)
        assertTrue(requests.none{it.method!="GET"})
    }
    @After fun cleanup(){
        if(pickerVisible())cancelPicker()
        ownedSource?.let{shell("rm -f /sdcard/Download/$it")}
        ownedDirectory?.let{shell("rm -rf /sdcard/Documents/$it")}
        compose.runOnIdle{model?.let{m->m.controller.state.value.connections.filter{it.id !in initial}.forEach(m::disconnect)}}
        waitFor{model?.controller?.state?.value?.connections?.none{it.id !in initial}!=false}
    }
    @Test fun actualEntryNamespaceCreateRenameDeleteHaveIndependentConfirmations(){
        install(false);capture("directory",false)
        click("kv-create");input("kv-title-input","Created");prepare();stages("create")
        assertEquals(1,requests.count{it.method=="POST"&&it.path==base})
        openNamespace();click("kv-rename");input("kv-title-input","Renamed");prepare();stages("rename")
        assertEquals(1,requests.count{it.method=="PUT"&&it.path==path})
        click("kv-delete-namespace");stages("delete-namespace")
        assertEquals(1,requests.count{it.method=="DELETE"&&it.path==path})
        assertTrue(browser.state.value.tasks.all{it.state==TaskState.ACCEPTED})
    }
    @Test fun textMetadataTtlRemainFrozenAndSendOnlyAfterThreeStages(){
        install();click("kv-new-value");input("kv-key-input","new/key")
        input("kv-value-input","synthetic-private-value");input("kv-metadata-input","{\"fixture\":true}")
        click("kv-include-metadata");click("kv-expiry-TTL");input("kv-expiry-input","90");click("kv-overwrite")
        compose.runOnIdle{assertTrue(browser.content.state.value.secure);assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)}
        prepare();assertTrue(requests.none{it.method=="PUT"});stages("put-value")
        assertEquals(listOf("synthetic-private-value","{\"fixture\":true}"),bodies.toList())
        assertEquals(1,requests.count{it.method=="PUT"&&it.path.endsWith("/values/new%2Fkey?expiration_ttl=90")})
        assertFalse(browser.state.value.tasks.joinToString().contains("synthetic-private"))
    }
    @Test fun binaryReadAndMetadataArePrivateAndDeletionUsesCurrentSingleKey(){
        install();click("kv-key-binary/key");click("kv-read-metadata")
        waitFor{browser.content.state.value.metadataReady};capture("metadata-private",false)
        click("kv-cancel-editor");click("kv-read-value");waitFor{browser.content.state.value.valueReady}
        compose.onNodeWithTag("kv-binary-value").performScrollTo().assertIsDisplayed();capture("binary-private",false)
        click("kv-cancel-editor");click("kv-delete-key");stages("delete-key")
        assertEquals(1,requests.count{it.method=="DELETE"&&it.path.endsWith("/values/binary%2Fkey")})
    }
    @Test fun bulkWriteAllTwelveKeysAndPartialReceiptAreReachable(){
        partial=true;install();importBulk();stages("bulk-write",true)
        assertEquals(1,requests.count{it.method=="PUT"&&it.path=="$path/bulk"})
        assertEquals(listOf("key-11"),browser.state.value.failedKeys)
        assertEquals(TaskState.PARTIAL,browser.state.value.tasks.single().state)
        capture("partial-result-before-open",false)
        val failedButton=scroll("kv-failed-keys")
        val diagnostic=File(application.filesDir,"kv-evidence/font-${application.resources.configuration.fontScale}/partial-scroll.txt")
        diagnostic.writeText("visibleImmediately=${failedButton.isDisplayed()}\n"+compose.onNodeWithTag("kv-content").printToString(maxDepth=30)+"\nTARGET\n"+failedButton.printToString())
        // Android's Window/layout visibility can settle after the semantic scroll action.
        // Wait for the actual target to become visible; do not repeat the scroll or tap.
        waitFor{failedButton.isDisplayed()}
        diagnostic.appendText("\nvisibleAfterLayout=${failedButton.isDisplayed()}\n")
        failedButton.assertIsDisplayed().assertIsEnabled().performClick()
        waitFor{compose.onAllNodes(isDialog()).fetchSemanticsNodes().size==1}
        capture("partial-failed-keys-before-scroll");compose.onNodeWithText("1. key-11").performScrollTo().assertIsDisplayed();capture("partial-failed-keys")
    }
    @Test fun bulkDeleteCurrentPostAndUnknownResultBlockFurtherWrites(){
        unknown=true;install();importBulk(true);stages("bulk-delete",true)
        assertEquals(1,requests.count{it.method=="POST"&&it.path=="$path/bulk/delete"})
        assertEquals(TaskState.UNKNOWN,browser.state.value.tasks.single().state)
        compose.runOnIdle{browser.cancel();browser.prepareDeleteNamespace()}
        waitFor{browser.state.value.error!=null};assertNull(browser.state.value.confirmation)
        assertEquals(1,requests.count{it.method!="GET"})
        capture("unknown-gate",false)
    }
    @Test fun bulkReadJsonTypesUseStreamingResponseAndExplicitPrivatePreview(){
        install();click("kv-bulk-read");input("kv-bulk-read-keys","object\nbool\nnull");click("kv-bulk-type-json");click("kv-bulk-read-send")
        waitFor{browser.content.state.value.bulkKind==KvBulkContentKind.RESPONSE}
        click("kv-preview-bulk-bool");waitFor{browser.content.state.value.previewReady};click("kv-reveal-bulk")
        compose.onNodeWithTag("kv-bulk-preview").performScrollTo().assertTextEquals("false");capture("bulk-read-json",false)
        assertEquals(1,requests.count{it.method=="POST"&&it.path=="$path/bulk/get"})
        assertTrue(browser.state.value.tasks.isEmpty())
    }
    @Test fun deniedKvGrantRefusesRealEntryAfterFinalConfirmation(){
        allowWrites=false;install(false);click("kv-create");input("kv-title-input","Denied");prepare()
        compose.onNodeWithTag("kv-final-send").performClick()
        waitFor{!browser.state.value.writing&&browser.state.value.tasks.isNotEmpty()}
        assertEquals(TaskState.FAILED,browser.state.value.tasks.single().state)
        assertTrue(requests.none{it.method!="GET"});capture("grant-denied",false)
    }
    @Test fun activityBackgroundRevokesPrivateMaterialAndOldConfirmation(){
        install();click("kv-new-value");input("kv-key-input","key");input("kv-value-input","synthetic-private");click("kv-overwrite");prepare()
        compose.runOnIdle{model!!.onKvBackground()}
        waitFor{browser.state.value.confirmation==null}
        assertFalse(browser.content.state.value.valueReady);assertFalse(browser.content.state.value.secure)
        assertTrue(requests.none{it.method!="GET"})
    }
    @Test fun actualSafPickerImportsBinaryAndExportsCompleteNewDocumentInExplicitDirectory(){
        install();val grants=application.contentResolver.persistedUriPermissions.map{it.uri.toString()}.toSet()
        importPickedFixture();capture("saf-import-binary",false)
        val directory="flarego-kv-export-${java.util.UUID.randomUUID()}";ownedDirectory=directory
        shell("mkdir -p /sdcard/Documents/$directory")
        click("kv-export-value");systemWait("Actual tree picker is visible",::pickerVisible)
        systemWait("Explicit synthetic directory is selectable"){systemClick(directory)}
        systemWait("User explicitly authorizes this folder"){systemClick("Use this folder")}
        systemWait("Confirm system permission"){systemClick("Allow")}
        waitFor{browser.state.value.notice?.contains("完整文件已导出")==true}
        val names=shell("ls /sdcard/Documents/$directory").trim().lines().filter{it.isNotEmpty()}
        assertEquals(1,names.size);assertTrue(names.single().startsWith("flarego-kv-"));assertFalse(names.single().contains("pending"))
        assertEquals("4",shell("wc -c < /sdcard/Documents/$directory/${names.single()}").trim())
        val original=shell("sha256sum /sdcard/Download/$ownedSource").substringBefore(' ')
        assertEquals(original,shell("sha256sum /sdcard/Documents/$directory/${names.single()}").substringBefore(' '))
        assertEquals(grants,application.contentResolver.persistedUriPermissions.map{it.uri.toString()}.toSet())
        assertTrue(requests.none{it.method!="GET"});assertTrue(browser.state.value.tasks.isEmpty())
        scroll("kv-notice");capture("saf-export-complete",false)
    }
    @Test fun cancelledAndStaleSafResultsNeverRestorePrivateContentsOrSendCloudWrites(){
        install();click("kv-new-value");input("kv-key-input","stale");click("kv-import-value")
        systemWait("OpenDocument picker",::pickerVisible);cancelPicker()
        assertFalse(browser.content.state.value.valueReady)
        click("kv-new-value");click("kv-import-value");systemWait("OpenDocument can open again",::pickerVisible)
        compose.runOnUiThread{model!!.controller.navigate(Page.OVERVIEW)}
        cancelPicker();waitFor{browser.state.value.context==null}
        assertFalse(browser.content.state.value.valueReady);assertTrue(requests.none{it.method!="GET"})
    }

}
