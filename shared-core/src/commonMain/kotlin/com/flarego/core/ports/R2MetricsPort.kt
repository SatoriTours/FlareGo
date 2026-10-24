package com.flarego.core.ports

import com.flarego.core.model.AccountScope
import com.flarego.core.model.R2AccountMetrics

/**
 * Typed port for the reviewed batch C account-metrics read.
 *
 * It is account-level only: there is no bucket or jurisdiction argument, so a caller cannot ask for
 * a per-bucket or per-region view that the pinned endpoint does not provide. The values are
 * provider numbers, never a write-settlement or billing proof.
 */
interface R2MetricsPort {
    suspend fun getMetrics(scope: AccountScope): R2AccountMetrics
}
