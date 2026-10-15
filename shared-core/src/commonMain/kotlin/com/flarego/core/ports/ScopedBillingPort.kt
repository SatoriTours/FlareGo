package com.flarego.core.ports

import com.flarego.core.model.*

interface ScopedBillingPort {
    suspend fun history(scope: CloudScope, request: PageRequest = PageRequest()): CloudPage<BillingEntry>
}
