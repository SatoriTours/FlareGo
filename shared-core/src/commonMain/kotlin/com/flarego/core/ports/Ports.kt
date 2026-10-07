package com.flarego.core.ports

import com.flarego.core.model.*

interface InventoryPort {
    suspend fun inventory(): Inventory
}

interface DnsPort {
    suspend fun records(zoneId: String): List<DnsRecord>

    suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft)

    suspend fun delete(zoneId: String, recordId: String)
}

interface BillingPort {
    suspend fun billing(): Billing
}

interface CloudSession : InventoryPort, DnsPort, BillingPort

fun interface SessionFactory {
    suspend fun create(connection: Connection): CloudSession
}

interface LocalStore {
    fun connections(): List<Connection>

    fun putConnection(connection: Connection)

    fun removeConnection(id: String)

    fun operations(): List<Operation>

    fun putOperation(operation: Operation)
}

class MemoryStore : LocalStore {
    private val accounts = linkedMapOf<String, Connection>()
    private val jobs = linkedMapOf<String, Operation>()

    override fun connections() = accounts.values.toList()

    override fun putConnection(connection: Connection) {
        accounts[connection.id] = connection
    }

    override fun removeConnection(id: String) {
        accounts.remove(id)
        jobs.entries.removeAll { it.value.connectionId == id }
    }

    override fun operations() = jobs.values.toList().reversed()

    override fun putOperation(operation: Operation) {
        jobs[operation.id] = operation
    }
}
