package com.flarego

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.dns.DnsChangePreview as Preview
import com.flarego.core.application.dns.DnsWriteMode
import com.flarego.core.application.dns.*
import com.flarego.core.application.zones.*
import com.flarego.core.application.management.CloudflareManagement
import com.flarego.core.application.AppController
import com.flarego.core.demo.DemoSession
import com.flarego.core.demo.demoConnections
import com.flarego.core.ports.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.*
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.FlareGoApp
import com.flarego.ui.dns.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real leaf UI, fake callback boundary only: these tests do not claim API writes. */
class DnsManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private var latest = DnsFormDraft()
    private val previews = mutableListOf<DnsRecordInput>()
    private var confirmed = 0
    private var cancelled = 0
    private val target = DnsPreviewTarget("测试连接", "测试账号", "example.com", "record-fixture")
    private val pageScope = ZoneScope(AccountScope("page-connection", "page-account"), "page-zone")
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun form(initial: DnsFormDraft = DnsFormDraft(), fontScale: Float = 1f,
                     method: DnsWriteMethod = DnsWriteMethod.POST,
                     constraints: DnsConstraints = DnsConstraints()) {
        latest = initial
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    var draft by remember { mutableStateOf(initial) }
                    var preview by remember { mutableStateOf<DnsRecordInput?>(null) }
                    if (preview == null) DnsRecordForm(draft, "example.com", {
                        latest = it; draft = it
                    }, { previews += it; preview = it }, { cancelled++ }, method = method, constraints = constraints)
                    else DnsChangePreview(Preview(DnsWriteMode.CREATE, preview!!.name, preview!!.fields.keys, emptyList()),
                        target, { confirmed++ }, { preview = null }, { cancelled++ })
                } }
            }
        }
    }

    private fun edit(tag: String, text: String) = compose.onNodeWithTag(tag)
        .performScrollTo().performTextReplacement(text)
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun preview() = click("dns-preview")

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "task4-leaf-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun numericDoneDismissesFocusWithoutPreviewingOrSubmitting() {
        form(DnsFormDraft(type = "MX", fields = obj("""{"content":"mx.example.com","priority":10}""")))
        compose.onNodeWithTag("dns-priority").performScrollTo().performClick()
        screenshot("dns-numeric-keyboard")
        compose.onNodeWithTag("dns-priority").performImeAction()
        compose.onNodeWithTag("dns-priority").assertIsNotFocused()
        compose.runOnIdle { assertTrue(previews.isEmpty()); assertEquals(0, confirmed) }
    }

    @Test fun invalidNumericEditHasImmediateFieldFeedbackAndKeepsDraft() {
        form(DnsFormDraft(fields = obj("""{"content":"192.0.2.1"}""")))
        edit("dns-ttl", "not-a-number")
        compose.onNodeWithTag("dns-ttl").assert(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.runOnIdle {
            assertEquals("not-a-number", latest.edits["ttl"])
            assertTrue(previews.isEmpty())
        }
        screenshot("dns-numeric-invalid")
        edit("dns-ttl", "60")
        compose.onNodeWithTag("dns-ttl").assert(
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        preview()
        compose.runOnIdle { assertEquals(60, previews.single().fields["ttl"]!!.jsonPrimitive.int) }
    }

    @Test fun nestedNumericErrorsUseReadableFieldNamesWithoutChangingDraftOrSubmitting() {
        form(DnsFormDraft(type = "SRV", name = "_sip._tcp"))
        edit("dns-data-port", "invalid")
        compose.onNodeWithText("端口请输入有效数字；不会自动转换为 0").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("invalid", latest.edits["data.port"]); assertTrue(previews.isEmpty()) }
        screenshot("dns-port-error")
        preview()
        compose.onNodeWithTag("dns-form-error").performScrollTo()
            .assertTextEquals("端口请输入有效数字；不会自动转换为 0")
        compose.runOnIdle { assertTrue(previews.isEmpty()) }
        edit("dns-data-port", "")
        compose.onNodeWithTag("dns-data-port").assert(
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.runOnIdle { assertEquals("", latest.edits["data.port"]); assertTrue(previews.isEmpty()) }
    }

    @Test fun all21TrustedTypesAndFiveQuickFormsRemainAvailable() {
        form()
        DnsSchemas.types.forEach { type -> compose.onNodeWithTag("dns-type-$type").assertExists() }
        val fixtures = mapOf("A" to "198.51.100.4", "AAAA" to "2001:db8::1",
            "CNAME" to "origin.example.com", "TXT" to "  quoted \"value\"\nline  ", "MX" to "mx.example.com")
        fixtures.forEach { (type, content) ->
            click("dns-type-$type")
            edit("dns-content", content)
            if (type == "MX") edit("dns-priority", "10")
            preview()
            compose.runOnIdle {
                assertEquals(type, previews.last().type)
                assertEquals(content, previews.last().fields["content"]!!.jsonPrimitive.content)
                assertEquals(0, confirmed)
            }
            click("dns-preview-back")
        }
    }

    @Test fun srvEditsStructuredFieldsAndOnlyExplicitConfirmInvokesCallback() {
        form()
        click("dns-type-SRV")
        edit("dns-name", "_sip._tcp")
        mapOf("priority" to "10", "weight" to "5", "port" to "443", "target" to "sip.example.com").forEach {
            (key, value) -> edit("dns-data-$key", value)
        }
        screenshot("srv-structured-fields")
        preview()
        compose.runOnIdle {
            assertEquals(obj("""{"priority":10,"weight":5,"port":443,"target":"sip.example.com"}"""), previews.single().fields["data"])
            assertEquals("_sip._tcp.example.com", previews.single().name)
            assertEquals(0, confirmed)
        }
        click("dns-preview-confirm")
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun caaAndHttpsUseStructuredInputsAndClearOldPayload() {
        form()
        click("dns-type-CAA")
        edit("dns-data-flags", "0"); edit("dns-data-tag", "issue"); edit("dns-data-value", "letsencrypt.org")
        preview()
        compose.runOnIdle { assertEquals(0, previews.last().fields["data"]!!.jsonObject["flags"]!!.jsonPrimitive.int) }
        click("dns-preview-back")
        click("dns-type-HTTPS")
        compose.onNodeWithTag("dns-data-flags").assertDoesNotExist()
        edit("dns-data-priority", "1"); edit("dns-data-target", "."); edit("dns-data-value", "alpn=\"h3,h2\"")
        preview()
        compose.runOnIdle {
            val data = previews.last().fields["data"]!!.jsonObject
            assertEquals(setOf("priority", "target", "value"), data.keys)
            assertEquals("alpn=\"h3,h2\"", data["value"]!!.jsonPrimitive.content)
        }
    }

    @Test fun invalidNumberStaysVisibleAndNeverBecomesZero() {
        form(DnsFormDraft("SRV", "@", obj("""{"data":{"priority":10,"weight":5,"port":443,"target":"sip.example.com"}}""")))
        edit("dns-data-port", "oops")
        preview()
        compose.onNodeWithTag("dns-form-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-data-port").performScrollTo().assertTextContains("oops")
        compose.runOnIdle { assertTrue(previews.isEmpty()); assertEquals("oops", latest.edits["data.port"]) }
        edit("dns-data-port", "65536"); preview()
        compose.runOnIdle { assertTrue(previews.isEmpty()) }
        edit("dns-data-port", "8443"); preview()
        compose.runOnIdle { assertEquals(8443, previews.single().fields["data"]!!.jsonObject["port"]!!.jsonPrimitive.int) }
    }

    @Test fun txtWhitespaceQuotesAndNewlinesSurvivePreviewBackAndCancel() {
        form(DnsFormDraft("TXT"))
        val text = "  \"verification value\"\nsecond line  \n"
        edit("dns-content", text); preview()
        compose.runOnIdle { assertEquals(text, previews.single().fields["content"]!!.jsonPrimitive.content) }
        click("dns-preview-back")
        compose.onNodeWithTag("dns-content").performScrollTo().assertTextContains(text)
        click("dns-form-cancel")
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, cancelled) }
    }

    @Test fun typeSwitchDiscardsSpecificFieldsEvenWithSameNames() {
        form(DnsFormDraft("MX", "mail", obj("""{"content":"mx.example.com","priority":10,"ttl":3600}""")))
        edit("dns-priority", "bad-number")
        click("dns-type-A")
        compose.onNodeWithTag("dns-priority").assertDoesNotExist()
        compose.onNodeWithTag("dns-content").performScrollTo().assertTextContains("")
        edit("dns-content", "198.51.100.5"); preview()
        compose.runOnIdle {
            assertEquals("mail.example.com", previews.single().name)
            assertFalse("priority" in previews.single().fields)
            assertEquals("198.51.100.5", previews.single().fields["content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun futureTypeIsReadOnlyWithReasonAndNoPreview() {
        form(DnsFormDraft("FUTURE", "future.example.com", obj("""{"content":"opaque","future":{"x":1}}""")))
        compose.onNodeWithTag("dns-readonly-reason").performScrollTo().assertTextContains("仅支持查看", substring = true)
        compose.onNodeWithTag("dns-preview").assertIsNotEnabled()
        compose.onNodeWithTag("dns-content").assertDoesNotExist()
        click("dns-form-cancel")
        compose.runOnIdle { assertTrue(previews.isEmpty()); assertEquals(1, cancelled) }
    }

    @Test fun patchOmitsUntouchedFieldsAndDoesNotInventDefaults() {
        form(DnsFormDraft("SRV", "_sip._tcp", obj("""{"comment":"edited"}""")), method = DnsWriteMethod.PATCH)
        preview()
        compose.runOnIdle { assertEquals(obj("""{"comment":"edited"}"""), previews.single().fields) }
    }

    @Test fun ttlEntitlementAndProxyConstraintsAreRespected() {
        form(DnsFormDraft("A", "@", obj("""{"content":"198.51.100.4"}""")))
        edit("dns-ttl", "30"); preview()
        compose.runOnIdle { assertTrue(previews.isEmpty()) }
        compose.onNodeWithTag("dns-form-error").performScrollTo().assertTextContains("60", substring = true)
        edit("dns-ttl", "3600"); click("dns-proxied"); preview()
        compose.runOnIdle { assertEquals(1, previews.single().fields["ttl"]!!.jsonPrimitive.int) }
    }

    @Test fun unknownNestedFieldsAreRetainedAndBlockUnsafePreview() {
        form(DnsFormDraft("SRV", "@", obj("""{"data":{"priority":10,"weight":5,"port":443,"target":"sip.example.com","future":7}}""")))
        edit("dns-data-port", "8443"); preview()
        compose.onNodeWithTag("dns-form-error").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(previews.isEmpty()); assertTrue("future" in latest.fields["data"]!!.jsonObject) }
    }

    @Test fun complexTypesRenderAndPreviewEveryStructuredComponent() {
        form()
        val fixtures = linkedMapOf(
            "CERT" to """{"data":{"algorithm":8,"certificate":"AQID","key_tag":1,"type":9}}""",
            "DNSKEY" to """{"data":{"algorithm":5,"flags":1,"protocol":3,"public_key":"AQID"}}""",
            "DS" to """{"data":{"algorithm":3,"digest":"1234ABCD","digest_type":1,"key_tag":1}}""",
            "LOC" to """{"data":{"altitude":0,"lat_degrees":37,"lat_direction":"N","lat_minutes":46,"lat_seconds":46,"long_degrees":122,"long_direction":"W","long_minutes":23,"long_seconds":35,"precision_horz":0,"precision_vert":0,"size":100}}""",
            "NAPTR" to """{"data":{"flags":"S","order":100,"preference":10,"regex":"","replacement":"_sip._udp.example.com","service":"SIP+D2U"}}""",
            "NS" to """{"content":"ns1.example.com"}""",
            "OPENPGPKEY" to """{"content":"AQID"}""",
            "PTR" to """{"content":"example.com"}""",
            "SMIMEA" to """{"data":{"certificate":"ABCD","matching_type":0,"selector":0,"usage":3}}""",
            "SSHFP" to """{"data":{"algorithm":2,"fingerprint":"ABCD","type":1}}""",
            "SVCB" to """{"data":{"priority":1,"target":".","value":"alpn=\"h3,h2\""}}""",
            "TLSA" to """{"data":{"certificate":"ABCD","matching_type":1,"selector":0,"usage":0}}""",
            "URI" to """{"data":{"target":"https://example.com/service","weight":20},"priority":10}""",
        )
        fixtures.forEach { (type, text) ->
            click("dns-type-$type")
            val expected = obj(text)
            expected.forEach { (key, value) ->
                if (value is JsonObject) value.forEach { (nested, entry) ->
                    edit("dns-$key-$nested", entry.jsonPrimitive.content)
                } else edit("dns-$key", value.jsonPrimitive.content)
            }
            preview()
            compose.onNodeWithTag("dns-form-error").assertDoesNotExist()
            compose.runOnIdle { assertEquals(type, previews.last().type); assertEquals(expected, previews.last().fields) }
            click("dns-preview-back")
        }
    }

    @Test fun structuredJsonArrayRejectsObjectAndPreservesTypedTagsAndSettings() {
        form(DnsFormDraft("A", "@", obj("""{"content":"198.51.100.4"}""")))
        edit("dns-tags", """{"url":"https://example.invalid"}"""); preview()
        compose.runOnIdle { assertTrue(previews.isEmpty()) }
        edit("dns-tags", """["team:dns","env:test"]""")
        click("dns-settings-ipv4_only")
        preview()
        compose.runOnIdle {
            assertEquals(obj("""{"content":"198.51.100.4","tags":["team:dns","env:test"],"settings":{"ipv4_only":true}}"""), previews.single().fields)
        }
    }

    @Test fun fullNaptrFormIncludesVisibleEmptyRegexWithoutInventingNumericValues() {
        form(DnsFormDraft("NAPTR", "@", obj("""{"data":{"flags":"S","order":100,"preference":10,"replacement":"_sip._udp.example.com","service":"SIP+D2U"}}""")))
        compose.onNodeWithTag("dns-data-regex").performScrollTo().assertTextContains("")
        preview()
        compose.onNodeWithTag("dns-form-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", previews.single().fields["data"]!!.jsonObject["regex"]!!.jsonPrimitive.content) }
    }

    @Test fun fullTxtFormCanPreviewAnIntentionallyEmptyVisibleContent() {
        form(DnsFormDraft("TXT"))
        preview()
        compose.onNodeWithTag("dns-form-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", previews.single().fields["content"]!!.jsonPrimitive.content) }
    }

    @Test fun numericInputRejectsJsonStringsNonFiniteFractionalAndLeadingZeroText() {
        form(DnsFormDraft("SRV", "@", obj("""{"data":{"priority":10,"weight":5,"port":443,"target":"sip.example.com"}}""")))
        listOf("NaN", "\"0\"", "1e9999", "1.5", "01").forEach { value ->
            edit("dns-data-port", value); preview()
            compose.runOnIdle { assertTrue("Unexpected preview for $value", previews.isEmpty()) }
            compose.onNodeWithTag("dns-data-port").performScrollTo().assertTextContains(value)
        }
    }

    @Test fun omittedPatchValueIsShownAsUnchangedRatherThanRemoved() {
        compose.setContent { FlareGoTheme {
            DnsChangePreview(Preview(DnsWriteMode.PATCH, "example.com", setOf("comment"), emptyList()), target,
                { confirmed++ }, {}, { cancelled++ }, fields = listOf(DnsPreviewField("ttl", "3600", null)))
        } }
        compose.onNodeWithText("变更后：\n未提供（保持原值）").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmed) }
    }

    private fun standalonePreview(mode: DnsWriteMode, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FlareGoTheme { Box(Modifier.fillMaxSize()) {
                    DnsChangePreview(Preview(mode, "very-long-record-name.example.com", setOf("data.port", "comment"), listOf("额外风险提示")),
                        target, { confirmed++ }, {}, { cancelled++ },
                        fields = listOf(DnsPreviewField("data.port", "443", "8443"),
                            DnsPreviewField("comment", "before", "long value ".repeat(80))))
                } }
            }
        }
    }

    @Test fun patchPreviewExplainsDeltaTargetNoCasAndCancelDoesNotConfirm() {
        standalonePreview(DnsWriteMode.PATCH)
        compose.onNodeWithTag("dns-preview-mode").assertTextContains("PATCH 差量", substring = true)
        compose.onNodeWithTag("dns-preview-target").performScrollTo().assertTextContains("测试账号", substring = true)
        compose.onNodeWithTag("dns-preview-target").assertTextContains("example.com", substring = true)
        compose.onNodeWithTag("dns-preview-cas").performScrollTo().assertTextContains("CAS", substring = true)
        click("dns-preview-cancel")
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, cancelled) }
    }

    @Test fun replacePreviewRequiresExplicitConfirmationAndShowsReplacementRisk() {
        standalonePreview(DnsWriteMode.REPLACE)
        compose.onNodeWithTag("dns-preview-mode").assertTextContains("PUT 完整替换")
        compose.onNodeWithTag("dns-preview-semantics").performScrollTo().assertTextContains("默认值", substring = true)
        compose.onNodeWithText("8443", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmed) }
        click("dns-preview-confirm")
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun narrowShortScreenLargeFontAndLongTxtKeepBottomActionsReachable() {
        form(DnsFormDraft("TXT"), fontScale = 2f)
        val title = compose.onNodeWithText("DNS 记录").getUnclippedBoundsInRoot()
        compose.runOnIdle { assertTrue("Title overlaps the 24dp system status bar", title.top >= 24.dp) }
        val bounds = compose.onRoot().getUnclippedBoundsInRoot()
        compose.runOnIdle {
            assertEquals("This fixture must run at 320dp width", 320f, bounds.right.value - bounds.left.value, 1f)
            assertTrue("Do not increase viewport height for this test", bounds.bottom.value - bounds.top.value <= 640f)
        }
        val long = "  \"long TXT value\"\n".repeat(40)
        edit("dns-content", long)
        compose.onNodeWithTag("dns-content").performScrollTo().assertIsDisplayed()
        screenshot("narrow-large-font-txt")
        compose.onNodeWithTag("dns-form-cancel").performScrollTo().assertIsDisplayed()
        screenshot("narrow-large-font-form-actions")
        preview()
        compose.runOnIdle { assertEquals(long, previews.single().fields["content"]!!.jsonPrimitive.content) }
        compose.onNodeWithTag("dns-preview-confirm").performScrollTo().assertIsDisplayed()
        screenshot("narrow-large-font-preview-confirm")
        click("dns-preview-back")
        compose.onNodeWithTag("dns-content").performScrollTo().assertTextContains(long)
    }

    @Test fun narrowLargeFontReplacementPreviewCanScrollThroughLongValuesToCancel() {
        standalonePreview(DnsWriteMode.REPLACE, fontScale = 2f)
        val title = compose.onNodeWithText("确认 DNS 变更").getUnclippedBoundsInRoot()
        compose.runOnIdle { assertTrue("Preview title overlaps the system status bar", title.top >= 24.dp) }
        compose.onNodeWithTag("dns-preview-cas").performScrollTo().assertIsDisplayed()
        screenshot("narrow-large-font-preview-cas")
        compose.onNodeWithTag("dns-preview-confirm").performScrollTo().assertIsDisplayed()
        click("dns-preview-cancel")
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, cancelled) }
    }

    private fun pagePort(type: String = "SRV"): TestDnsPagePort {
        val fields = if (type == "SRV") obj("""{"data":{"priority":10,"weight":5,"port":443,"target":"sip.example.com"},"ttl":3600,"comment":"keep"}""")
            else obj("""{"content":"opaque"}""")
        val row = DnsRecordView("page-record", type, "_sip._tcp.example.com", null, 3600, false, null, fields,
            if (type == "FUTURE") "未来类型仅支持查看" else null, setOf("future"))
        return TestDnsPagePort(DnsPageState(scope = pageScope, zoneName = "example.com", rows = listOf(row), detail = row,
            records = listOf(DnsRecordDocument(row.id, pageScope, obj("""{"id":"page-record","type":"$type","future":{"opaque":true}}""")))))
    }

    private fun installPage(port: TestDnsPagePort) {
        compose.setContent { FlareGoTheme { val state by port.state.collectAsState(); DnsPage(state, port::onIntent) } }
    }

    @Test fun pagePatchUsesOnlyEditedNestedFieldAndInvalidatesUnparsedText() {
        val port = pagePort(); installPage(port)
        click("dns-detail-patch"); edit("dns-data-port", "oops")
        compose.runOnIdle { assertTrue(port.intents.last() == DnsIntent.DraftInvalidated) }
        preview()
        compose.runOnIdle { assertTrue(port.intents.none { it is DnsIntent.Preview }) }
        edit("dns-data-port", "8443"); preview()
        compose.runOnIdle {
            val intent = port.intents.filterIsInstance<DnsIntent.Preview>().single()
            assertEquals(DnsWriteMode.PATCH, intent.mode)
            assertEquals(obj("""{"data":{"port":8443}}"""), intent.input!!.fields)
            assertEquals("_sip._tcp.example.com", intent.input!!.name)
            assertEquals("SRV", intent.input!!.type)
            assertTrue(port.intents.none { it is DnsIntent.Confirm })
        }
        click("dns-preview-back")
        compose.onNodeWithTag("dns-data-port").performScrollTo().assertTextContains("8443")
        click("dns-form-cancel")
        compose.runOnIdle { assertTrue(port.intents.last() == DnsIntent.Cancel); assertTrue(port.intents.none { it is DnsIntent.Confirm }) }
    }

    @Test fun pagePutHasSeparatePreviewAndConfirmsOnlyDisplayedIdAndRevision() {
        val port = pagePort(); installPage(port)
        click("dns-detail-replace"); edit("dns-data-port", "8443"); preview()
        compose.onNodeWithTag("dns-preview-mode").assertTextContains("PUT 完整替换")
        compose.onNodeWithTag("dns-preview-semantics").performScrollTo().assertTextContains("默认值", substring = true)
        compose.runOnIdle {
            assertEquals(DnsWriteMode.REPLACE, port.intents.filterIsInstance<DnsIntent.Preview>().single().mode)
            assertEquals(3600, port.intents.filterIsInstance<DnsIntent.Preview>().single().input!!.fields["ttl"]!!.jsonPrimitive.int)
            assertTrue(port.intents.none { it is DnsIntent.Confirm })
        }
        val prepared = port.state.value.preparedChange!!
        click("dns-preview-confirm")
        compose.runOnIdle { assertEquals(DnsIntent.Confirm(prepared.id, prepared.revision), port.intents.last()) }
    }

    @Test fun pageFilterPagingAndReadonlyFutureDetailUsePublicIntents() {
        val port = pagePort("FUTURE")
        port.mutable.value = port.state.value.copy(detail = null, next = PageToken.Number(2))
        installPage(port)
        edit("dns-filter-name", "www"); edit("dns-filter-type", "TXT"); click("dns-filter-apply")
        compose.runOnIdle { assertEquals(DnsIntent.Load(pageScope, DnsFilter(name = "www", type = "TXT")), port.intents.last()) }
        click("dns-load-more")
        compose.runOnIdle { assertEquals(DnsIntent.LoadMore, port.intents.last()) }
        click("dns-record-page-record")
        compose.onNodeWithTag("dns-detail-readonly").performScrollTo().assertTextContains("仅支持查看", substring = true)
        compose.onNodeWithTag("dns-detail-patch").assertIsNotEnabled()
        compose.onNodeWithTag("dns-detail-replace").assertIsNotEnabled()
        click("dns-raw-toggle")
        compose.onNodeWithTag("dns-raw-readonly").performScrollTo().assertTextContains("opaque", substring = true)
        compose.runOnIdle { assertTrue(port.intents.none { it is DnsIntent.Preview || it is DnsIntent.Confirm }) }
    }

    @Test fun pageUnknownTaskOffersReadOnlyReconcileAndBlocksWrites() {
        val port = pagePort()
        val task = OperationTask("task-page", pageScope.connectionId, pageScope, "dns-records-for-a-zone-patch-dns-record",
            ResourceRef(pageScope, "dns_record", "page-record"), "a".repeat(64), "b".repeat(64), TaskState.UNKNOWN,
            "结果未知，请只读核对", null, null, 1, 1)
        port.mutable.value = port.state.value.copy(pendingTask = task)
        installPage(port)
        compose.onNodeWithTag("dns-detail-patch").performScrollTo().assertIsNotEnabled()
        click("dns-task-reconcile")
        compose.runOnIdle { assertEquals(DnsIntent.Reconcile(task.id), port.intents.last()); assertTrue(port.intents.none { it is DnsIntent.Preview || it is DnsIntent.Confirm }) }
    }

    @Test fun pageAccountReplacementDropsLocalTextAndStalePreview() {
        val port = pagePort(); installPage(port)
        click("dns-detail-patch"); edit("dns-data-port", "8443"); preview()
        val old = port.state.value.preparedChange!!
        compose.runOnIdle { port.mutable.value = DnsPageState(scope = ZoneScope(AccountScope("new-connection", "new-account"), "new-zone"),
            revision = old.revision + 1, editorRevision = 10) }
        compose.onNodeWithTag("dns-preview-confirm").assertDoesNotExist()
        compose.onNodeWithTag("dns-data-port").assertDoesNotExist()
        compose.runOnIdle { assertTrue(port.intents.none { it is DnsIntent.Confirm }) }
    }

    @Test fun pageMissingFreshProviderZoneNameCannotOpenDnsForm() {
        val port = pagePort()
        port.mutable.value = port.state.value.copy(zoneName = null, mode = DnsWriteMode.PATCH,
            draft = DnsRecordInput("SRV", "_sip._tcp.example.com", port.state.value.detail!!.fields))
        installPage(port)
        compose.onNodeWithTag("dns-zone-name-required").assertExists()
        compose.onNodeWithTag("dns-data-port").assertDoesNotExist()
        compose.runOnIdle { assertTrue(port.intents.none { it is DnsIntent.Preview || it is DnsIntent.Confirm }) }
    }

    @Test fun pageCreateAndDeleteBothWaitForExplicitPreviewAndConfirm() {
        val port = pagePort(); installPage(port)
        click("dns-create"); click("dns-type-TXT"); edit("dns-content", "  \"new\"\nline  "); preview()
        compose.runOnIdle {
            val intent = port.intents.filterIsInstance<DnsIntent.Preview>().single()
            assertEquals(DnsWriteMode.CREATE, intent.mode)
            assertEquals("  \"new\"\nline  ", intent.input!!.fields["content"]!!.jsonPrimitive.content)
            assertTrue(port.intents.none { it is DnsIntent.Confirm })
        }
        click("dns-preview-cancel")
        click("dns-detail-delete")
        compose.runOnIdle { assertEquals(1, port.intents.filterIsInstance<DnsIntent.Preview>().size) }
        click("dns-delete-preview")
        compose.runOnIdle {
            val intent = port.intents.filterIsInstance<DnsIntent.Preview>().last()
            assertEquals(DnsIntent.Preview(null, DnsWriteMode.DELETE, "page-record"), intent)
            assertTrue(port.intents.none { it is DnsIntent.Confirm })
        }
        click("dns-preview-confirm")
        compose.runOnIdle { assertEquals(1, port.intents.filterIsInstance<DnsIntent.Confirm>().size) }
    }

    @Test fun appOptionalManagementRoutesZonesAndKeepsFiveBottomTabs() {
        val dns = pagePort()
        val connection = demoConnections.first()
        val account = AccountScope(connection.id, connection.accountId)
        val zone = ZoneDetails(ZoneScope(account, "ui-zone"), "ui.example.com", "pending", false, "full",
            listOf("ada.ns.cloudflare.com", "ben.ns.cloudflare.com"), JsonObject(emptyMap()))
        val zones = object : ZonePagePort {
            val mutable = MutableStateFlow(ZonePageState(account = account, zones = listOf(zone), demo = true))
            override val state: StateFlow<ZonePageState> = mutable
            override fun onIntent(intent: ZoneIntent) {
                when (intent) {
                    is ZoneIntent.Open -> mutable.value = mutable.value.copy(selected = zone)
                    is ZoneIntent.Load -> mutable.value = if (intent.account == account) ZonePageState(account = account, zones = listOf(zone), demo = true)
                        else ZonePageState(account = intent.account)
                    ZoneIntent.Clear -> mutable.value = ZonePageState()
                    else -> Unit
                }
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), demoConnections) }
            FlareGoApp(controller, { _, _, _ -> }, {}, {}, management = CloudflareManagement(dns, zones))
        }
        compose.onNodeWithTag("nav-domains").performClick()
        click("zone-row-ui-zone")
        compose.onNodeWithTag("zone-nameservers").performScrollTo().assertTextContains("ada.ns.cloudflare.com", substring = true)
        listOf("overview", "domains", "resources", "billing", "accounts").forEach { compose.onNodeWithTag("nav-$it").assertExists() }
        compose.onNodeWithTag("zone-demo-notice").assertExists()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText(demoConnections[1].name).performClick()
        compose.onNodeWithText("ui.example.com", substring = true).assertDoesNotExist()
        compose.runOnIdle { zones.mutable.value = ZonePageState(account = account, selected = zone, error = "old-account-error") }
        compose.onNodeWithText("old-account-error").assertDoesNotExist()
    }
}

/** Fake the documented port only; never pretend these intents reached a cloud API. */
private class TestDnsPagePort(initial: DnsPageState) : DnsPagePort {
    val mutable = MutableStateFlow(initial)
    override val state: StateFlow<DnsPageState> = mutable
    val intents = mutableListOf<DnsIntent>()
    override fun onIntent(intent: DnsIntent) {
        intents += intent
        val s = mutable.value
        when (intent) {
            is DnsIntent.Open -> mutable.value = s.copy(detail = s.rows.single { it.id == intent.recordId }, selected = s.records.firstOrNull { it.id == intent.recordId })
            is DnsIntent.BeginEdit -> {
                val row = s.rows.single { it.id == intent.recordId }
                mutable.value = s.copy(mode = intent.mode, editingRecordId = row.id, detail = row,
                    draft = DnsRecordInput(row.type, row.name, row.fields), editorRevision = s.editorRevision + 1)
            }
            is DnsIntent.BeginCreate -> mutable.value = s.copy(mode = DnsWriteMode.CREATE, draft = DnsRecordInput(intent.type, "@", JsonObject(emptyMap())), editorRevision = s.editorRevision + 1)
            is DnsIntent.BeginDelete -> mutable.value = s.copy(mode = DnsWriteMode.DELETE, editingRecordId = intent.recordId, draft = null, preparedChange = null, editorRevision = s.editorRevision + 1)
            DnsIntent.DraftInvalidated -> mutable.value = s.copy(revision = s.revision + 1, preparedChange = null)
            is DnsIntent.Preview -> mutable.value = s.copy(draft = intent.input, mode = intent.mode,
                preparedChange = DnsPreparedPreview("ui-plan", s.revision, Preview(intent.mode, intent.input?.name ?: s.detail!!.name,
                    intent.input?.fields?.keys ?: setOf("deleted"), emptyList()), s.scope!!, intent.recordId,
                    s.detail?.let { DnsRecordInput(it.type, it.name, it.fields) }, intent.input))
            DnsIntent.BackToDraft -> mutable.value = s.copy(preparedChange = null)
            DnsIntent.Cancel -> mutable.value = s.copy(mode = null, draft = null, preparedChange = null, editorRevision = s.editorRevision + 1)
            DnsIntent.Clear -> mutable.value = DnsPageState()
            else -> Unit
        }
    }
}
