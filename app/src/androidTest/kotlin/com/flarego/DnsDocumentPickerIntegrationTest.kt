package com.flarego

import androidx.compose.ui.test.*
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import java.util.UUID
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.zones.ZoneIntent
import com.flarego.core.model.*
import com.flarego.ui.dns.*
import com.flarego.ui.management.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual MainActivity registry and OS picker. No test/global transport replacement. */
class DnsDocumentPickerIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun modelAndScope():Pair<FlareGoViewModel,ZoneScope> {
        lateinit var m:FlareGoViewModel
        compose.runOnIdle {
            m=ViewModelProvider(compose.activity)[FlareGoViewModel::class.java]
            m.controller.reloadConnections(m.controller.state.value.connections+com.flarego.core.demo.demoConnections)
            m.controller.selectConnection(m.controller.state.value.connections.first { it.id=="demo-production" })
        }
        val c=m.controller.state.value.selected!!;val z=ZoneScope(AccountScope(c.id,c.accountId),"zone-demo")
        compose.runOnIdle { m.management.zones.onIntent(ZoneIntent.Load(z.account)) }
        compose.waitUntil(10000) { !m.management.zones.state.value.loading }
        compose.runOnIdle { m.management.zones.onIntent(ZoneIntent.Open(z)) }
        compose.waitUntil(10000) { m.management.zones.state.value.selected?.ref==z }
        return m to z
    }
    @Test fun realMainActivityOpenDocumentIsLaunchedOnceAndCancellationReturnsToSameVm() {
        val (m,z)=modelAndScope();val port=m.panelPorts.files!!
        lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(port.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { port.state.value.state.snapshot!=null && !port.state.value.state.loading }
        compose.runOnIdle { port.onIntent(lease,DnsFilesPanelIntent.SelectImportFile(z)) }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<deadline && automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true) Thread.sleep(50)
        assertTrue("actual system OpenDocument must be visible",automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(10000) { port.state.value.state.selection==DnsFilesSelection.CANCELLED }
        compose.runOnIdle { assertSame(m,ViewModelProvider(compose.activity)[FlareGoViewModel::class.java]);assertNull(port.state.value.state.file) }
    }
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private fun node(root:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        if(root==null)return null
        if(predicate(root))return root
        for(i in 0 until root.childCount)node(root.getChild(i),predicate)?.let { return it }
        return null
    }
    private fun clickSystem(label:String):Boolean {
        var n=node(automation.rootInActiveWindow) { it.text?.toString()?.equals(label,true)==true || it.contentDescription?.toString()?.equals(label,true)==true } ?: return false
        while(!n.isClickable)n=n.parent ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun waitSystem() {
        val deadline=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<deadline && automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true)Thread.sleep(50)
        assertTrue("Actual OS document activity must be shown",automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)
    }
    private fun downloads() {
        val toolbar=node(automation.rootInActiveWindow) { it.viewIdResourceName?.endsWith("/toolbar")==true }
        if(node(toolbar) { it.text?.toString()=="Downloads" }!=null)return
        clickSystem("Show roots")
        val deadline=System.currentTimeMillis()+5000
        while(System.currentTimeMillis()<deadline) { if(clickSystem("Downloads"))return;Thread.sleep(50) }
        fail("Actual system Downloads location is required")
    }
    private fun saveSystem(name:String) {
        downloads()
        val edit=node(automation.rootInActiveWindow) { it.isEditable && it.className?.toString()=="android.widget.EditText" }
        assertNotNull("System CreateDocument filename field",edit)
        assertTrue(edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name) }))
        assertTrue("User explicitly saves new document",clickSystem("Save"))
    }
    private fun shell(command:String):ByteArray {
        val pipes=automation.executeShellCommandRw("sh")
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command+"\nexit\n").toByteArray()) }
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { pipes.forEach { runCatching { it.close() } } }
    }
    private fun actualGrant(displayName:String):android.content.UriPermission {
        val resolver=compose.activity.contentResolver
        return resolver.persistedUriPermissions.single { permission ->
            runCatching { resolver.query(permission.uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                it.moveToFirst() && it.getString(0)==displayName
            }==true }.getOrDefault(false)
        }
    }
    private fun quote(value:String)="'"+value.replace("'","'\"'\"'")+"'"
    private fun actualFilesModule():Pair<FlareGoViewModel,ZoneScope> {
        val (m,z)=modelAndScope()
        compose.runOnIdle { m.controller.navigate(Page.DOMAINS) }
        compose.waitUntil(10000) { m.management.zones.state.value.account==z.account && !m.management.zones.state.value.loading }
        compose.runOnIdle { m.management.zones.onIntent(ZoneIntent.Open(z)) }
        compose.waitUntil(10000) { m.management.zones.state.value.selected?.ref==z && m.panelContext?.scope==z }
        compose.onNodeWithTag("zone-tab-dns").performClick()
        compose.onNodeWithTag("management-open-FILES").performScrollTo().performClick()
        compose.waitUntil(10000) { m.panelPorts.files!!.state.value.lease!=null && m.panelPorts.files!!.state.value.state.snapshot!=null && !m.panelPorts.files!!.state.value.state.loading }
        return m to z
    }
    @Test fun actualMainActivityNativeCreateExportsExactDemoBytesWithRealPersistedFlags() {
        val (m,z)=actualFilesModule();val port=m.panelPorts.files!!
        val name="flarego-b2b-export-"+UUID.randomUUID()+".bind"
        var uri:Uri?=null
        try {
            compose.onNodeWithTag("df-select-export").performScrollTo().performClick();waitSystem();saveSystem(name)
            compose.waitUntil(10000) { port.state.value.state.exportStage==DnsFilesExportStage.READY }
            val resolver=compose.activity.contentResolver
            val target=checkNotNull(port.state.value.state.exportTarget)
            assertTrue(target.displayName.startsWith(name.removeSuffix(".bind")))
            uri=actualGrant(target.displayName).also { assertTrue(it.isReadPermission);assertTrue(it.isWritePermission) }.uri
            compose.onNodeWithTag("df-export").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10000) { port.state.value.state.exportStage==DnsFilesExportStage.SUCCEEDED }
            val expected=("; FlareGo explicit example, not provider data\nflarego.dev. 60 IN TXT \"FlareGo example\"\n").toByteArray()
            assertArrayEquals(expected,resolver.openInputStream(uri!!)!!.use { it.readBytes() })
            val result=checkNotNull(port.state.value.state.exportResult).result
            assertEquals(z,result.scope);assertEquals(expected.size.toLong(),result.file.length)
            assertEquals(com.flarego.platform.AndroidFingerprint().sha256(expected),result.file.sha256)
            assertTrue(m.operationStore.list(z.connectionId).isEmpty())
        } finally {
            uri?.let { runCatching { DocumentsContract.deleteDocument(compose.activity.contentResolver,it) } }
            if(automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
    @Test fun actualMainActivityOpenFlightSurvivesRecreateWithoutRestoringConfirmation() {
        val (m,z)=actualFilesModule();val port=m.panelPorts.files!!;val old=port.state.value.lease!!
        val original=compose.activity
        val created=java.util.concurrent.CountDownLatch(1)
        val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val callback=androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity,stage ->
            if(activity is MainActivity && activity!==original && stage==androidx.test.runner.lifecycle.Stage.CREATED)created.countDown()
        }
        try {
            compose.onNodeWithTag("df-select-file").performScrollTo().performClick();waitSystem()
            // ActivityScenario.recreate forces RESUMED and cannot model a real stopped Activity under a picker.
            // Recreate the actual MainActivity without trying to bring it in front of the OS document task.
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback);original.recreate() }
            assertTrue("Actual replacement MainActivity must be created under the still-open system picker",created.await(10,java.util.concurrent.TimeUnit.SECONDS))
            waitSystem()
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10000) { port.state.value.state.selection==DnsFilesSelection.CANCELLED }
            compose.runOnIdle { assertNotSame(original,compose.activity);assertSame(m,ViewModelProvider(compose.activity)[FlareGoViewModel::class.java]) }
            assertEquals(z,m.management.zones.state.value.selected?.ref)
            assertNotSame(old,port.state.value.lease);assertNull(port.state.value.state.prepared)
            compose.onNodeWithTag("df-select-file").assertExists()
            assertTrue(m.operationStore.list(z.connectionId).isEmpty())
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
            if(automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
    @Test fun realCreatedTargetExternallyFilledBeforeCancelIsPreserved() {
        val (m,z)=actualFilesModule();val port=m.panelPorts.files!!
        val name="flarego-b2b-marker-"+UUID.randomUUID()+".bind";val marker="external-owned-marker"
        var actualName=name
        try {
            compose.onNodeWithTag("df-select-export").performScrollTo().performClick();waitSystem();saveSystem(name)
            compose.waitUntil(10000) { port.state.value.state.exportStage==DnsFilesExportStage.READY }
            val resolver=compose.activity.contentResolver
            actualName=checkNotNull(port.state.value.state.exportTarget).displayName
            assertTrue(actualName.startsWith(name.removeSuffix(".bind")))
            val uri=actualGrant(actualName).uri
            resolver.openOutputStream(uri,"wt")!!.use { it.write(marker.toByteArray()) }
            compose.runOnIdle { port.onIntent(port.state.value.lease!!,DnsFilesPanelIntent.CancelExport(z)) }
            compose.waitUntil(10000) { port.state.value.state.exportStage==DnsFilesExportStage.CANCELLED && resolver.persistedUriPermissions.none { it.uri==uri } }
            assertEquals(marker,shell("cat "+quote("/sdcard/Download/"+actualName)).decodeToString())
            assertTrue(m.operationStore.list(z.connectionId).isEmpty())
        } finally { shell("rm -f "+quote("/sdcard/Download/"+actualName)) }
    }

    @Test fun actualLateCreateAfterRouteExitCannotBindOrDeleteUnownedResult() {
        val (m,z)=actualFilesModule();val port=m.panelPorts.files!!;val lease=port.state.value.lease!!
        val prefix="flarego-b2b-orphan-"+UUID.randomUUID()
        val name=prefix+".bind"
        val resolver=compose.activity.contentResolver
        fun grants()=resolver.persistedUriPermissions.associate { it.uri to (it.isReadPermission to it.isWritePermission) }
        val beforeGrants=grants()
        fun createdPaths()=shell("find /sdcard/Download -maxdepth 1 -type f -name "+quote(prefix+"*")+" -print").decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
        try {
            compose.onNodeWithTag("df-select-export").performScrollTo().performClick();waitSystem()
            compose.runOnIdle { m.panelPorts.onRouteExit(lease);assertNull(port.state.value.lease) }
            saveSystem(name)
            compose.waitUntil(10000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true }
            compose.runOnIdle { m.onManagementResume();assertNull(port.state.value.lease);assertNull(port.state.value.state.exportTarget) }
            val actual=createdPaths()
            assertEquals("An unadopted late Create result must still exist under this exact test-owned UUID, irrespective of provider extension",1,actual.size)
            assertTrue(actual.single().startsWith("/sdcard/Download/"+prefix))
            assertEquals("A rejected late result cannot acquire a persisted grant",beforeGrants,grants())
            assertTrue(m.operationStore.list(z.connectionId).isEmpty())
        } finally { createdPaths().forEach { path -> if(path.startsWith("/sdcard/Download/"+prefix))shell("rm -f "+quote(path)) } }
    }

}
