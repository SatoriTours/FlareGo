package com.flarego.core.application.management

import com.flarego.core.ports.DnsFileStore
import kotlinx.coroutines.CoroutineDispatcher

/** App-owned opaque file handles. Picker/URI/grants remain outside core. */
data class ManagementPlatformPorts(val files: DnsFileStore, val fileDispatcher: CoroutineDispatcher)
