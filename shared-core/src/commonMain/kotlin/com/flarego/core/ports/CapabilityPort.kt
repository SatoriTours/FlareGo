package com.flarego.core.ports

import com.flarego.core.model.CapabilityStatus
import com.flarego.core.model.CloudScope

interface CapabilityPort {
    suspend fun status(scope: CloudScope, actionKey: String): CapabilityStatus
}
