package com.flarego

import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.model.*
import com.flarego.platform.AndroidLocalStore
import com.flarego.platform.TokenVault
import org.junit.Assert.*
import org.junit.Test

class LocalPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tokenRoundTripIsEncryptedAndCanBeRemoved() {
        val id = "instrumentation-token"
        val value = "fixture-secret-not-a-real-cloud-token"
        val vault = TokenVault(context)
        try {
            vault.put(id, value)
            assertEquals(value, TokenVault(context).get(id))
            val stored = context.getSharedPreferences("cloud-credentials", 0).getString(id, "")!!
            assertFalse(stored.contains(value))
            assertTrue(stored.contains('.'))
            vault.remove(id)
            assertNull(vault.get(id))
        } finally {
            vault.remove(id)
        }
    }

    @Test
    fun databasePersistsConnectionAndReplacesOperationStatus() {
        val store = AndroidLocalStore(context)
        val connection = Connection("instrumentation-connection", "Fixture account", "1234")
        try {
            store.putConnection(connection)
            store.putOperation(
                Operation("fixture-op", connection.id, "DNS 变更", OperationStatus.UNKNOWN, "待核对")
            )
            store.putOperation(
                Operation("fixture-op", connection.id, "DNS 变更", OperationStatus.SUCCESS, "已完成")
            )
            val reopened = AndroidLocalStore(context)
            assertTrue(reopened.connections().contains(connection))
            assertEquals(
                OperationStatus.SUCCESS,
                reopened.operations().single { it.id == "fixture-op" }.status,
            )
            reopened.removeConnection(connection.id)
            assertFalse(reopened.operations().any { it.connectionId == connection.id })
            assertFalse(reopened.connections().any { it.id == connection.id })
        } finally {
            store.removeConnection(connection.id)
        }
    }
}
