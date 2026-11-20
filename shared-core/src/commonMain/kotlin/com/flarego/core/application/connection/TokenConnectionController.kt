package com.flarego.core.application.connection

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.PageToken
import com.flarego.core.ports.AccountSummary
import com.flarego.core.ports.IdentityPort
import com.flarego.core.ports.TokenKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the connection flow currently is; every phase is safe to render without a token. */
enum class TokenPhase { IDLE, VERIFYING, DISCOVERING, CHOOSING, CONNECTING, CONNECTED }

/** Stable, app-owned classification so the UI never has to parse provider text. */
enum class TokenProblem { NONE, NETWORK, TOKEN_REJECTED, PERMISSION, INVALID_INPUT, ACCOUNT_UNAVAILABLE }

/**
 * What the persisting layer actually achieved. A thrown exception is never used as a proxy for
 * "nothing was written": the credential and account-row stores are separate, and a write that
 * throws may already have taken effect.
 */
enum class CredentialSaveOutcome {
    /** Credential and account row are both stored. */
    SAVED,

    /** Nothing usable was stored: the previous state was restored (or never touched). */
    NOT_SAVED,

    /** Unknown: a partial write may exist. Fail closed and require reconfiguration. */
    STATE_UNCERTAIN,
}

/**
 * API Token connection state.
 *
 * It deliberately carries no secret: [tokenHeld] only says a token is kept in memory for the
 * next step, never the value. Nothing here is written to SavedState, logs or analytics.
 */
data class TokenConnectionStatus(
    val phase: TokenPhase = TokenPhase.IDLE,
    /** Last detected credential kind; null while unknown. */
    val kind: TokenKind? = null,
    val accounts: List<AccountSummary> = emptyList(),
    val hasMore: Boolean = false,
    /** The token could not be used to list accounts; a manual Account ID is the supported path. */
    val needsAccountId: Boolean = false,
    val tokenHeld: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val problem: TokenProblem = TokenProblem.NONE,
    val canRetry: Boolean = false,
    val connected: Boolean = false,
    val upgrading: Boolean = false,
    val upgradePlan: TokenUpgradePlan? = null,
    val retiredToken: RetiredToken? = null,
)

/**
 * Credential-bound API Token connection.
 *
 * Design rules:
 * 1. The token is held only in this object's memory, only between submission and save. It is
 *    never placed in [status], an exception message, a log or a saved state.
 * 2. A failed `/user/tokens/verify` is not the same as an invalid token: account-owned tokens
 *    reject that route, so the flow falls back to an explicit Account ID verification instead
 *    of calling the credential dead.
 * 3. Nothing is saved until the provider confirms the exact account (account details read or
 *    an active account-scoped token verification). A missing optional product permission never
 *    invalidates the connection.
 * 4. Every step is versioned by [generation]; cancelling or submitting again invalidates late
 *    results so a previous token can never resurrect a connection.
 */
class TokenConnectionController(
    private val scope: CoroutineScope,
    private val identity: (String) -> IdentityPort,
    private val save: suspend (name: String, accountId: String, token: String) -> CredentialSaveOutcome,
) {
    private enum class Step { VERIFY, DISCOVER, SELECT, MANUAL }

    private val mutable = MutableStateFlow(TokenConnectionStatus())
    val status: StateFlow<TokenConnectionStatus> = mutable.asStateFlow()

    /** Memory only; cleared as soon as the account is saved or the flow is cancelled. */
    private var token: String? = null
    private var generation = 0L
    private var job: Job? = null
    private var next: PageToken? = null
    private var lastStep: Step = Step.VERIFY
    private var lastAccountId: String? = null

    private var prepareUpgrade: (suspend () -> TokenUpgradeAttempt)? = null
    private var upgrade: TokenUpgradeAttempt? = null

    fun startUpgrade(prepare: suspend () -> TokenUpgradeAttempt) {
        if (status.value.phase == TokenPhase.CONNECTING) return
        cancel()
        prepareUpgrade = prepare
        prepareUpgrade()
    }

    private fun prepareUpgrade() {
        val prepare = prepareUpgrade ?: return
        val read = begin()
        mutable.value = TokenConnectionStatus(
            upgrading = true, busy = true, phase = TokenPhase.VERIFYING, notice = "正在核查当前 Token 的读取权限和范围…",
        )
        job = scope.launch {
            try {
                val attempt = prepare()
                if (!current(read)) return@launch
                upgrade = attempt
                mutable.value = TokenConnectionStatus(upgrading = true, upgradePlan = attempt.plan)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { upgradeFailed(read, failure, preparing = true) }
        }
    }

    private fun verifyUpgrade(held: String) {
        val attempt = upgrade ?: return
        val read = begin()
        token = held
        mutable.value = TokenConnectionStatus(upgrading = true, upgradePlan = attempt.plan,
            phase = TokenPhase.VERIFYING, busy = true, tokenHeld = true, notice = "正在核验新 Token 的读取权限和范围…")
        job = scope.launch {
            try {
                val commit = attempt.verify(held)
                if (!current(read)) return@launch
                mutable.update { it.copy(phase = TokenPhase.CONNECTING, notice = "正在保存新 Token…") }
                val result = commit()
                if (!current(read)) return@launch
                finish(read, attempt.plan.accountId, attempt.plan.accountName, null, result.outcome, result.retired)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { upgradeFailed(read, failure) }
        }
    }

    private fun upgradeFailed(read: Long, failure: Exception, preparing: Boolean = false) {
        if (!current(read)) return
        val problem = problemOf(failure)
        val oldIdUnavailable = preparing && failure is OldTokenIdentityUnavailable
        val message = if (oldIdUnavailable) failure.message.orEmpty() else when ((failure as? CloudFailure)?.code) {
            CloudFailureCode.CONFLICT, CloudFailureCode.SCOPE_MISMATCH -> failure.publicMessage
            CloudFailureCode.PERMISSION_REQUIRED -> "读取被拒绝；请检查 Token 的功能权限和账号、域名范围。"
            CloudFailureCode.CREDENTIAL_EXPIRED -> "Token 无效、已过期或不是用户 Token。"
            else -> if (problem == TokenProblem.NETWORK) "暂时无法核验权限，请检查网络后重试。" else "无法确认读取权限和资源范围。"
        }
        val retry = oldIdUnavailable || problem == TokenProblem.NETWORK
        if (!retry) token = null
        mutable.update { it.copy(phase = TokenPhase.IDLE, error = message, problem = problem,
            busy = false, notice = null, tokenHeld = token != null, canRetry = retry && (preparing || token != null)) }
    }

    /** Starts (or restarts) the flow with a token. The value never leaves this object. */
    fun submit(raw: String) {
        if (status.value.phase == TokenPhase.CONNECTING || (status.value.upgrading && (status.value.busy || upgrade == null))) return
        val clean = raw.trim()
        if (clean.isEmpty() || clean.any(Char::isWhitespace) || clean.length !in MIN_TOKEN..MAX_TOKEN) {
            begin()
            token = null
            next = null
            mutable.value = TokenConnectionStatus(
                upgrading = status.value.upgrading, upgradePlan = upgrade?.plan,
                phase = TokenPhase.IDLE,
                problem = TokenProblem.INVALID_INPUT,
                error = "Token 不能为空，且不能包含空格或换行；请粘贴完整 Token",
            )
            return
        }
        if (status.value.upgrading) {
            verifyUpgrade(clean)
            return
        }
        token = clean
        next = null
        lastAccountId = null
        lastStep = Step.VERIFY
        val read = begin()
        mutable.value = TokenConnectionStatus(
            phase = TokenPhase.VERIFYING, busy = true, tokenHeld = true,
            notice = "正在校验 Token…",
        )
        job = scope.launch { verifyUserToken(read, clean) }
    }

    /** Repeats the failed step; only meaningful while a token is still held. */
    fun retry() {
        if (status.value.busy) return
        if (status.value.upgrading) {
            val held = token
            if (held != null) verifyUpgrade(held) else if (upgrade == null) prepareUpgrade()
            return
        }
        val held = token ?: return
        val read = begin()
        when (lastStep) {
            Step.VERIFY -> {
                mutable.value = mutable.value.copy(
                    phase = TokenPhase.VERIFYING, busy = true, error = null,
                    problem = TokenProblem.NONE, canRetry = false, notice = "正在重新校验 Token…",
                )
                job = scope.launch { verifyUserToken(read, held) }
            }
            Step.DISCOVER -> job = scope.launch { discover(read, held, status.value.kind) }
            Step.SELECT, Step.MANUAL -> {
                val accountId = lastAccountId ?: return
                job = scope.launch {
                    if (status.value.needsAccountId) connectManually(read, held, accountId)
                    else connectSelected(read, held, accountId)
                }
            }
        }
    }

    /** Loads the next account page; only valid while accounts are already listed. */
    fun loadMore() {
        val held = token ?: return
        val cursor = next ?: return
        if (status.value.busy || status.value.hasMore.not()) return
        val read = begin(keepStatus = true)
        mutable.value = mutable.value.copy(busy = true, error = null, problem = TokenProblem.NONE)
        job = scope.launch {
            try {
                val page = identity(held).discover(com.flarego.core.model.PageRequest(token = cursor))
                if (!current(read)) return@launch
                next = page.next
                val merged = (status.value.accounts + page.items).distinctBy { it.id }
                mutable.update {
                    it.copy(
                        phase = TokenPhase.CHOOSING, busy = false, accounts = merged,
                        hasMore = page.next != null, notice = null, canRetry = false,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current(read)) {
                    val problem = problemOf(failure)
                    mutable.update {
                        it.copy(
                            busy = false, problem = problem,
                            error = if (problem == TokenProblem.NETWORK) "加载更多账号失败，请检查网络后重试。"
                            else "加载更多账号返回无法识别的内容，请重试。",
                            canRetry = false,
                        )
                    }
                }
            }
        }
    }

    fun selectAccount(accountId: String) {
        val held = token ?: return
        val clean = normalizeAccountId(accountId) ?: return invalidAccount()
        val read = begin()
        lastStep = Step.SELECT
        lastAccountId = clean
        mutable.value = mutable.value.copy(
            phase = TokenPhase.CONNECTING, busy = true, error = null,
            problem = TokenProblem.NONE, canRetry = false, notice = "正在验证所选账号…",
        )
        job = scope.launch { connectSelected(read, held, clean) }
    }

    /** Manual path for restricted/account-owned tokens: verify this exact account first. */
    fun useAccountId(accountId: String) {
        val held = token ?: return
        val clean = normalizeAccountId(accountId) ?: return invalidAccount()
        val read = begin()
        lastStep = Step.MANUAL
        lastAccountId = clean
        mutable.value = mutable.value.copy(
            phase = TokenPhase.CONNECTING, busy = true, error = null,
            problem = TokenProblem.NONE, canRetry = false, notice = "正在验证该账号的 Token 绑定…",
        )
        job = scope.launch { connectManually(read, held, clean) }
    }

    /** Cancels the flow and forgets the token; an already saved connection is never touched. */
    fun cancel() {
        if (status.value.phase == TokenPhase.CONNECTING && status.value.upgrading) return
        prepareUpgrade = null
        upgrade = null
        generation++
        job?.cancel(); job = null
        token = null; next = null; lastAccountId = null; lastStep = Step.VERIFY
        mutable.value = TokenConnectionStatus()
    }

    private fun begin(keepStatus: Boolean = false): Long {
        generation++
        if (!keepStatus) job?.cancel()
        return generation
    }

    private fun current(read: Long) = read == generation

    private suspend fun verifyUserToken(read: Long, held: String) {
        try {
            val verification = identity(held).verifyToken(TokenKind.USER_OWNED, null)
            if (!current(read)) return
            when (verification.active) {
                true -> discover(read, held, TokenKind.USER_OWNED)
                false -> reject(read, "该 Token 未处于有效状态（可能已禁用或已过期）；请在官方控制台检查后重试。")
                null -> manualFallback(
                    read, TokenKind.UNKNOWN,
                    "无法确认 Token 状态。若是 Account-owned Token，请输入 Account ID 进行只读验证。",
                    TokenProblem.NONE,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(read)) return
            val code = (failure as? CloudFailure)?.code
            when (code) {
                // Account-owned tokens reject the user-token route. This is a routing fact, not
                // proof that the credential is invalid.
                CloudFailureCode.CREDENTIAL_EXPIRED, CloudFailureCode.PERMISSION_REQUIRED ->
                    manualFallback(
                        read, TokenKind.ACCOUNT_OWNED,
                        "该 Token 不是个人（user-owned）Token，或缺少用户 Token 校验权限。" +
                            "请输入 32 位 Account ID 做只读验证；Account-owned Token 必须指定账号。",
                        TokenProblem.NONE,
                    )
                CloudFailureCode.UNAVAILABLE, CloudFailureCode.RATE_LIMITED ->
                    retryable(read, TokenProblem.NETWORK, "暂时无法连接 Cloudflare，请检查网络后重试；Token 未保存。")
                else -> retryable(
                    read, TokenProblem.ACCOUNT_UNAVAILABLE,
                    "Token 校验返回了无法识别的结果，请稍后重试或改用手动 Account ID 验证。",
                )
            }
        }
    }

    private suspend fun discover(read: Long, held: String, kind: TokenKind?) {
        lastStep = Step.DISCOVER
        mutable.value = mutable.value.copy(
            phase = TokenPhase.DISCOVERING, busy = true, kind = kind,
            error = null, problem = TokenProblem.NONE, canRetry = false, notice = "正在读取账号列表…",
        )
        try {
            val page = identity(held).discover(com.flarego.core.model.PageRequest())
            if (!current(read)) return
            next = page.next
            val accounts = page.items.distinctBy { it.id }
            when {
                accounts.isEmpty() -> manualFallback(
                    read, kind,
                    "该 Token 没有返回任何账号。可输入 Account ID 做只读验证；空列表不等于 Token 无效。",
                    TokenProblem.NONE,
                )
                accounts.size == 1 && page.next == null ->
                    connectSelected(read, held, accounts.single().id, knownName = accounts.single().name)
                else -> mutable.update {
                    it.copy(
                        phase = TokenPhase.CHOOSING, busy = false, accounts = accounts,
                        hasMore = page.next != null, notice = null,
                    )
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(read)) return
            when ((failure as? CloudFailure)?.code) {
                // A token limited to one product may not list accounts. That is a permission
                // boundary, not a broken credential, so the manual path stays available.
                CloudFailureCode.PERMISSION_REQUIRED -> manualFallback(
                    read, kind,
                    "该 Token 不能列出账号（缺少账号列表读取权限）。请输入 32 位 Account ID 做只读验证。",
                    TokenProblem.PERMISSION,
                )
                CloudFailureCode.CREDENTIAL_EXPIRED -> reject(
                    read, "读取账号列表时 Token 被拒绝（401）。请确认 Token 仍然有效，或改用手动 Account ID 验证。",
                )
                CloudFailureCode.UNAVAILABLE, CloudFailureCode.RATE_LIMITED ->
                    retryable(read, TokenProblem.NETWORK, "读取账号列表失败，请检查网络后重试。")
                else -> retryable(read, TokenProblem.ACCOUNT_UNAVAILABLE, "读取账号列表返回无法识别的内容，请重试。")
            }
        }
    }

    /** Selected from the discovered list: verify the account details, then save. */
    private suspend fun connectSelected(read: Long, held: String, accountId: String, knownName: String? = null) {
        lastStep = Step.SELECT
        lastAccountId = accountId
        if (status.value.phase != TokenPhase.CONNECTING) mutable.update {
            it.copy(phase = TokenPhase.CONNECTING, busy = true, notice = "正在验证所选账号…")
        }
        try {
            val account = try {
                identity(held).verifyAccount(accountId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!current(read)) return
                // A restricted token may not read account metadata; an active account-scoped
                // token verification still proves the binding, so the id becomes the name.
                if ((failure as? CloudFailure)?.code == CloudFailureCode.PERMISSION_REQUIRED &&
                    activeForAccount(held, accountId)
                ) {
                    saveAndFinish(read, held, accountId, knownName ?: accountId,
                        "该 Token 不能读取账号名称，已用 Account ID 作为名称保存。")
                    return
                }
                throw failure
            }
            if (!current(read)) return
            saveAndFinish(read, held, accountId, account.name, null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(read)) return
            when ((failure as? CloudFailure)?.code) {
                CloudFailureCode.CREDENTIAL_EXPIRED ->
                    reject(read, "该账号拒绝了此 Token（401）。Token 可能已失效或不属于该账号，未保存。")
                CloudFailureCode.PERMISSION_REQUIRED ->
                    retryable(read, TokenProblem.PERMISSION, "该 Token 缺少读取该账号的权限，无法验证归属，未保存。")
                CloudFailureCode.NOT_FOUND, CloudFailureCode.SCOPE_MISMATCH ->
                    retryable(read, TokenProblem.ACCOUNT_UNAVAILABLE, "该账号不存在或 Token 不能访问它，未保存。")
                CloudFailureCode.UNAVAILABLE, CloudFailureCode.RATE_LIMITED ->
                    retryable(read, TokenProblem.NETWORK, "验证账号失败，请检查网络后重试。")
                else -> retryable(read, TokenProblem.ACCOUNT_UNAVAILABLE, "验证账号返回无法识别的内容，请重试。")
            }
        }
    }

    /** Manual Account ID: account-scoped token verification first, account read as fallback. */
    private suspend fun connectManually(read: Long, held: String, accountId: String) {
        lastStep = Step.MANUAL
        lastAccountId = accountId
        var accountTokenActive: Boolean? = null
        var accountTokenProblem: TokenProblem = TokenProblem.NONE
        try {
            accountTokenActive = identity(held).verifyToken(TokenKind.ACCOUNT_OWNED, accountId).active
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(read)) return
            accountTokenProblem = problemOf(failure)
        }
        if (!current(read)) return
        if (accountTokenActive == false) {
            reject(read, "该账号报告此 Token 未处于有效状态（禁用或过期），未保存。")
            return
        }
        try {
            val account = identity(held).verifyAccount(accountId)
            if (!current(read)) return
            saveAndFinish(read, held, accountId, account.name, null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(read)) return
            if (accountTokenActive == true) {
                saveAndFinish(read, held, accountId, accountId, "该 Token 不能读取账号名称，已用 Account ID 作为名称保存。")
                return
            }
            val problem = problemOf(failure)
            val message = when (problem) {
                TokenProblem.NETWORK -> "验证账号失败，请检查网络后重试；Token 未保存。"
                TokenProblem.TOKEN_REJECTED ->
                    if (accountTokenProblem == TokenProblem.TOKEN_REJECTED)
                        "该 Token 既不能作为该账号的 Account-owned Token 验证，也不能读取该账号（401）。请核对 Account ID 与 Token 类型。"
                    else "该账号拒绝了此 Token（401），未保存。"
                TokenProblem.PERMISSION ->
                    "该 Token 不能验证此账号（账号级 Token 校验与账号信息读取均被拒绝）。" +
                        "请确认 Account ID 正确，或换用具备账号读取权限的 Token；未保存。"
                TokenProblem.ACCOUNT_UNAVAILABLE -> "该账号不存在或返回无法识别的结果，未保存。"
                else -> "无法验证该账号，请检查 Account ID 后重试；未保存。"
            }
            retryable(read, problem, message)
        }
    }

    private suspend fun activeForAccount(held: String, accountId: String): Boolean =
        try {
            identity(held).verifyToken(TokenKind.ACCOUNT_OWNED, accountId).active == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }

    private suspend fun saveAndFinish(
        read: Long, held: String, accountId: String, name: String, notice: String?,
    ) {
        if (!current(read)) return
        val outcome = try {
            save(name.take(MAX_NAME), accountId, held)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(read)) return
            // A throw is not proof that nothing was stored, so the message stays neutral.
            retryable(read, problemOf(failure), NOT_SAVED_MESSAGE)
            return
        }
        finish(read, accountId, name, notice, outcome)
    }

    private fun finish(read: Long, accountId: String, name: String, notice: String?,
        outcome: CredentialSaveOutcome, retired: RetiredToken? = null,
    ) {
        if (!current(read)) return
        when (outcome) {
            CredentialSaveOutcome.NOT_SAVED -> {
                retryable(read, TokenProblem.ACCOUNT_UNAVAILABLE, NOT_SAVED_MESSAGE)
                return
            }
            CredentialSaveOutcome.STATE_UNCERTAIN -> {
                upgrade = null
                prepareUpgrade = null
                // Fail closed: forget the token and never auto-reconnect this credential.
                token = null; next = null; lastAccountId = null; lastStep = Step.VERIFY
                mutable.update {
                    it.copy(
                        phase = TokenPhase.IDLE, busy = false, accounts = emptyList(), hasMore = false,
                        needsAccountId = false, tokenHeld = false, connected = false, notice = null,
                        upgradePlan = null,
                        problem = TokenProblem.ACCOUNT_UNAVAILABLE, canRetry = false,
                        error = "保存结果不确定：可能已写入部分内容。请重新连接并重新确认 Token 后再试；" +
                            "在重新确认前，该账号不会使用本次凭据。",
                    )
                }
                return
            }
            CredentialSaveOutcome.SAVED -> Unit
        }
        val upgrading = status.value.upgrading
        upgrade = null
        prepareUpgrade = null
        token = null
        next = null; lastAccountId = null; lastStep = Step.VERIFY
        mutable.value = TokenConnectionStatus(
            phase = TokenPhase.CONNECTED, connected = true, busy = false,
            accounts = listOf(AccountSummary(accountId, name)), notice = notice,
            upgrading = upgrading, retiredToken = retired,
        )
    }

    private fun manualFallback(read: Long, kind: TokenKind?, notice: String, problem: TokenProblem) {
        if (!current(read)) return
        mutable.update {
            it.copy(
                phase = TokenPhase.CHOOSING, busy = false, kind = kind, accounts = emptyList(),
                hasMore = false, needsAccountId = true, notice = notice,
                problem = problem, canRetry = false, error = null,
            )
        }
    }

    private fun reject(read: Long, message: String) {
        if (!current(read)) return
        mutable.update {
            it.copy(
                phase = TokenPhase.CHOOSING, busy = false, problem = TokenProblem.TOKEN_REJECTED,
                error = message, canRetry = false, notice = null,
            )
        }
    }

    private fun retryable(read: Long, problem: TokenProblem, message: String) {
        if (!current(read)) return
        mutable.update {
            it.copy(
                phase = if (it.needsAccountId) TokenPhase.CHOOSING else TokenPhase.IDLE,
                busy = false, problem = problem, error = message, canRetry = token != null, notice = null,
            )
        }
    }

    private fun invalidAccount() {
        mutable.update {
            it.copy(
                phase = TokenPhase.CHOOSING, busy = false, problem = TokenProblem.INVALID_INPUT,
                error = "Account ID 必须是 32 位十六进制字符", canRetry = false, notice = null,
            )
        }
    }

    private fun normalizeAccountId(raw: String): String? =
        raw.trim().lowercase().takeIf { it.matches(AccountIdPattern) }

    private fun problemOf(failure: Exception): TokenProblem = when ((failure as? CloudFailure)?.code) {
        CloudFailureCode.CREDENTIAL_EXPIRED -> TokenProblem.TOKEN_REJECTED
        CloudFailureCode.PERMISSION_REQUIRED, CloudFailureCode.SCOPE_MISMATCH -> TokenProblem.PERMISSION
        CloudFailureCode.UNAVAILABLE, CloudFailureCode.RATE_LIMITED -> TokenProblem.NETWORK
        CloudFailureCode.INVALID_INPUT -> TokenProblem.INVALID_INPUT
        CloudFailureCode.INVALID_RESPONSE, CloudFailureCode.NOT_FOUND, CloudFailureCode.CONFLICT,
        CloudFailureCode.UNKNOWN_RESULT, CloudFailureCode.UNSUPPORTED_CAPABILITY,
        -> TokenProblem.ACCOUNT_UNAVAILABLE
        null -> TokenProblem.ACCOUNT_UNAVAILABLE
    }

    private companion object {
        /** Precise: the previous state was restored, which is not the same as "nothing happened". */
        const val NOT_SAVED_MESSAGE =
            "连接信息未能保存，已恢复到连接前的状态；请检查设备存储后重试。"
        const val MIN_TOKEN = 10
        const val MAX_TOKEN = 4096
        const val MAX_NAME = 80
        val AccountIdPattern = Regex("[a-f0-9]{32}")
    }
}
