package com.flarego.ui

import com.flarego.core.application.connection.*
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class TokenTemplateTest {
    @Test fun firstConnectionPrefillsAllSupportedReadGroupsAndNoManagement() {
        val uri = URI(tokenTemplateUrl())
        val params = params(uri)
        assertEquals("https://dash.cloudflare.com/profile/api-tokens", uri.toString().substringBefore('?'))
        assertEquals("*", params["accountId"])
        assertEquals("all", params["zoneId"])
        assertEquals(
            setOf("account_settings", "zone", "dns", "zone_settings", "ssl_and_certificates",
                "workers_scripts", "workers_kv_storage", "workers_r2", "d1", "billing"),
            Json.parseToJsonElement(params.getValue("permissionGroupKeys")).jsonArray.map {
                val group = it.jsonObject
                assertEquals("read", group.getValue("type").jsonPrimitive.content)
                group.getValue("key").jsonPrimitive.content
            }.toSet(),
        )
    }

    @Test fun upgradeLinkIncludesOnlyThePlanAndItsExactScope() {
        val account = "1".repeat(32)
        val zone = "2".repeat(32)
        val plan = TokenUpgradePlan(account, "Not exported", setOf(ReadFeature.ACCOUNT, ReadFeature.DNS, ReadFeature.D1), zone)
        val params = params(URI(tokenTemplateUrl(plan)))
        assertEquals(account, params["accountId"])
        assertEquals(zone, params["zoneId"])
        assertEquals(setOf("account_settings", "dns", "d1"),
            Json.parseToJsonElement(params.getValue("permissionGroupKeys")).jsonArray.map {
                it.jsonObject.getValue("key").jsonPrimitive.content
            }.toSet())
        assertEquals("FlareGo Read Only", params["name"])
    }

    private fun params(uri: URI) = uri.rawQuery.split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }
}
