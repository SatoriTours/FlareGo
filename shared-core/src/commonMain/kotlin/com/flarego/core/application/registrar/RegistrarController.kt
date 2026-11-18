package com.flarego.core.application.registrar

import com.flarego.core.application.safeMessage
import com.flarego.core.cloudflare.registrar.RegistrarKeyword
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.RegistrarCandidate
import com.flarego.core.model.RegistrarIntent
import com.flarego.core.model.RegistrarSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only Registrar search facade.
 *
 * Three rules shape this controller:
 *
 * 1. It never writes. Only the discovery read is reachable, so no intent can register,
 *    transfer or pay for a domain.
 * 2. [enabled] is the caller's authorization fact, not a guess: when the current
 *    authorization has no Registrar permission the controller reports
 *    [RegistrarSearch.unsupported] and sends no request at all.
 * 3. A result is only published for the account and request it was started for. Rebinding
 *    the account, clearing, or starting a newer query bumps [generation], so a late answer
 *    from an older request can never overwrite the current state.
 *
 * Search output is cached provider suggestion data, never proof of availability or price,
 * which is why every success carries the fixed [NOTICE].
 */
class RegistrarController(
    private val scope: CoroutineScope,
    /** Performs one read for exactly this account; the controller owns every state guard. */
    private val search: suspend (AccountScope, String, Int) -> List<RegistrarCandidate>,
    private val enabled: (AccountScope) -> Boolean = { false },
    private val nowMs: () -> Long = { 0L },
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val mutable = MutableStateFlow(RegistrarSearch())
    val state: StateFlow<RegistrarSearch> = mutable.asStateFlow()
    private var generation = 0L
    private var bound: AccountScope? = null
    private var lastQuery: String? = null

    /** The account this controller is currently bound to; null means "no account selected". */
    val account: AccountScope? get() = bound

    /**
     * Binds the account the page is showing. Any in-flight search for the previous account
     * is invalidated immediately, and its late result is dropped instead of landing.
     */
    fun bind(account: AccountScope?) {
        if (bound == account) return
        bound = account
        generation++
        lastQuery = null
        mutable.value = RegistrarSearch(scope = account, unsupported = account != null && !enabled(account))
    }

    fun onIntent(intent: RegistrarIntent) {
        when (intent) {
            is RegistrarIntent.Query -> query(intent.keyword)
            RegistrarIntent.Retry -> lastQuery?.let(::query)
            RegistrarIntent.Clear -> {
                generation++
                lastQuery = null
                mutable.value = RegistrarSearch(scope = bound, unsupported = bound?.let(enabled) == false)
            }
        }
    }

    private fun query(raw: String) {
        val account = bound ?: run {
            mutable.update { it.copy(searching = false, error = "请先选择账号后再搜索域名", notice = null) }
            return
        }
        val keyword = raw.trim()
        if (!enabled(account)) {
            generation++
            lastQuery = null
            mutable.value = RegistrarSearch(scope = account, keyword = keyword, unsupported = true)
            return
        }
        val normalized = try {
            RegistrarKeyword.normalize(raw)
        } catch (_: CloudFailure) {
            generation++
            lastQuery = null
            mutable.value = RegistrarSearch(scope = account, keyword = keyword, error = INVALID_KEYWORD)
            return
        }
        lastQuery = raw
        val read = ++generation
        mutable.update {
            it.copy(
                scope = account, keyword = normalized, searching = true,
                error = null, notice = null, unsupported = false,
            )
        }
        scope.launch {
            try {
                val results = search(account, normalized, limit)
                if (!current(account, read)) return@launch
                mutable.update {
                    it.copy(
                        searching = false, results = results, observedAtMs = nowMs(),
                        notice = NOTICE, error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(account, read)) {
                    mutable.update {
                        it.copy(
                            searching = false, results = emptyList(), observedAtMs = null,
                            notice = null, error = safeMessage(failure),
                        )
                    }
                }
            }
        }
    }

    /** True only while [read] is still the newest request for the still-bound [account]. */
    private fun current(account: AccountScope, read: Long): Boolean =
        read == generation && bound == account && mutable.value.scope == account

    companion object {
        /** Requested suggestion count; the client refuses anything outside 1..10. */
        const val DEFAULT_LIMIT: Int = 8
        const val NOTICE: String = "搜索结果为缓存数据，注册前需在官方控制台确认可用性与价格"
        const val INVALID_KEYWORD: String = "请输入 1–100 位、仅含字母、数字、点（.）或连字符（-）的域名关键词"
    }
}
