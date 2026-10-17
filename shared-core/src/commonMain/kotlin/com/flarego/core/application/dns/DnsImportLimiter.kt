package com.flarego.core.application.dns

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.model.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One app-owned instance shared by all credential-bound file sessions; no per-connection quota. */
@OptIn(ExperimentalAtomicApi::class)
class DnsImportLimiter(private val clock: OperationClock) {
    private data class Window(val observed: Long, val sends: List<Long>)
    private val state = AtomicReference(Window(0, emptyList()))
    fun acquire() {
        while (true) {
            val old = state.load(); val now = clock.nowMs()
            if (now < old.observed || now < 0) throw rateLimited()
            val active = old.sends.filter { now - it < 60000 }
            if (active.size >= 3) throw rateLimited()
            if (state.compareAndSet(old, Window(now, active + now))) return
        }
    }
    private fun rateLimited() = CloudFailure(CloudFailureCode.RATE_LIMITED, "DNS 导入每分钟最多发送三次，请稍后重新预览确认；其他客户端也可能占用供应商额度")
}
