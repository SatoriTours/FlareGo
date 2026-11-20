package com.flarego

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.connection.*
import com.flarego.core.cloudflare.*
import com.flarego.core.ports.TokenKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class TokenUpgradePersistenceTest {
    @Test fun refusingAndDuplicateCandidatesKeepTheOldTokenAndOnlyCoverageAllowsReplacement() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val account = UUID.randomUUID().toString().replace("-", "")
        val old = "synthetic-old-token"
        val incomplete = "synthetic-incomplete-token"
        val candidate = "synthetic-candidate-token"
        val calls = CopyOnWriteArrayList<HttpRequest>()
        val oldId = "a".repeat(32)
        val oldIdAvailable = AtomicBoolean(false)
        val vm = withContext(Dispatchers.Main) {
            FlareGoViewModel(app, Dispatchers.IO, transportForToken = { token ->
                HttpTransport { request ->
                    calls += request
                    fun ok(result: String) = HttpResponse(200, """{"success":true,"result":$result}""")
                    when (request.path.substringBefore('?')) {
                        "/user/tokens/verify" -> {
                            val id = if (token == old) {
                                if (oldIdAvailable.get()) oldId else "invalid-id"
                            } else "b".repeat(32)
                            ok("""{"id":"$id","status":"active"}""")
                        }
                        "/accounts/$account" -> ok("""{"id":"$account","name":"Synthetic upgrade"}""")
                        "/zones" -> HttpResponse(200, """{"success":true,"result":[],"result_info":{"total_count":0}}""")
                        "/accounts/$account/workers/workers" ->
                            if (token == incomplete) ok("[]") else ok("""[{"id":"retained","name":"Retained"}]""")
                        "/accounts/$account/d1/database" -> if (token == old) HttpResponse(403, "") else ok("[]")
                        else -> HttpResponse(403, "")
                    }
                }
            })
        }
        val viewModels = ViewModelStore().apply { put("token-upgrade", vm) }
        val vault = CredentialVaultOwner.get(app)
        try {
            withContext(Dispatchers.Main) { vm.connect("Synthetic upgrade", account, old) }
            val connection = vm.controller.state.value.connections.single { it.accountId == account }
            withContext(Dispatchers.Main) { vm.upgradeToken(connection, setOf(ReadTarget(ReadFeature.D1))) }
            suspend fun awaitStatus(predicate: (TokenConnectionStatus) -> Boolean): TokenConnectionStatus =
                withTimeout(10_000) { vm.tokenConnection.state.first(predicate) }

            val blocked = awaitStatus { !it.busy }
            assertNull("不能核验旧 ID 时不得生成可提交的方案", blocked.upgradePlan)
            assertTrue(blocked.canRetry)
            assertTrue(blocked.error.orEmpty().contains("旧 Token ID"))
            withContext(Dispatchers.Main) { vm.tokenConnection.submit(candidate) }
            assertFalse(vm.tokenConnection.state.value.connected)
            assertEquals(old, vault.token(connection.id))
            oldIdAvailable.set(true)
            withContext(Dispatchers.Main) { vm.tokenConnection.retry() }
            val plan = awaitStatus { it.upgradePlan != null }.upgradePlan!!
            assertEquals(account, plan.accountId)
            assertEquals(setOf(ReadFeature.ACCOUNT, ReadFeature.ZONES, ReadFeature.WORKERS, ReadFeature.D1), plan.features)
            for (refused in listOf(old, incomplete)) {
                withContext(Dispatchers.Main) { vm.tokenConnection.submit(refused) }
                val status = awaitStatus { it.error != null }
                assertFalse(status.connected)
                assertNull(status.retiredToken)
                assertEquals(old, vault.token(connection.id))
            }
            withContext(Dispatchers.Main) { vm.tokenConnection.submit(candidate) }
            val replaced = awaitStatus { it.connected }
            assertEquals(RetiredToken(oldId, TokenKind.USER_OWNED), replaced.retiredToken)
            assertEquals(candidate, vault.token(connection.id))
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.all { it.method == "GET" })
        } finally {
            withContext(Dispatchers.Main) {
                vm.controller.state.value.connections.firstOrNull { it.accountId == account }?.let(vm::disconnect)
            }
            withTimeout(10_000) { vm.controller.state.first { state -> state.connections.none { it.accountId == account } } }
            withContext(Dispatchers.Main) { viewModels.clear() }
        }
    }
}
