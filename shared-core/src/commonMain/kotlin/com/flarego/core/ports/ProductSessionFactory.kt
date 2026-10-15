package com.flarego.core.ports

import com.flarego.core.model.Connection

data class ProductServices(val directory: ProductDirectoryPort, val billing: ScopedBillingPort, val capabilities: CapabilityPort, val legacyWorkers: ProductDirectoryPort? = null)
fun interface ProductSessionFactory { suspend fun create(connection: Connection): ProductServices }
