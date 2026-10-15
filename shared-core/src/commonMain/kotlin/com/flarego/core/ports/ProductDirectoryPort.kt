package com.flarego.core.ports

import com.flarego.core.model.*

enum class WorkersDirectoryMode { MODERN, LEGACY }

interface ProductDirectoryPort {
    suspend fun zones(account: AccountScope, request: PageRequest = PageRequest()): CloudPage<Domain>
    suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest = PageRequest()): CloudPage<CloudResource>
}
