package com.flarego.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flarego.core.application.connection.TokenConnectionStatus
import com.flarego.core.application.connection.TokenPhase
import com.flarego.core.application.connection.TokenProblem
import com.flarego.core.application.connection.ReadFeature
import com.flarego.core.ports.TokenKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class JourneyStep { CREATE, EXISTING_TOKEN, BROWSER_HANDOFF }

/** Cloudflare account IDs are 32 hex characters; anything else is a typo, not an account. */
private val AccountIdPattern = Regex("^[0-9a-fA-F]{32}$")

private fun problemHint(problem: TokenProblem): String? = when (problem) {
    TokenProblem.NETWORK -> "网络不可用，请检查连接后重试。"
    TokenProblem.TOKEN_REJECTED -> "Token 被拒绝：请确认已完整复制且未过期或被撤销。"
    TokenProblem.PERMISSION -> "该 Token 权限不足：请在官方页面补充 Account 读取权限后重试。"
    TokenProblem.INVALID_INPUT -> "输入格式不正确，请检查后重试。"
    TokenProblem.ACCOUNT_UNAVAILABLE -> "此 Account ID 无法用当前 Token 访问，请确认 ID 与账号归属。"
    TokenProblem.NONE -> null
}

/** UI-only journey; validation and persistence stay in [TokenConnection]. */
@Composable
fun TokenConnectionDialog(onDismiss: () -> Unit, connection: TokenConnection, onConnected: () -> Unit = onDismiss) {
    val status by connection.state.collectAsState()
    val canCancel = status.phase != TokenPhase.CONNECTING
    val dismiss = { connection.cancel(); onDismiss() }
    LaunchedEffect(status.connected) { if (status.connected && !status.upgrading) onConnected() }

    // No token, account ID, or clipboard data in saved state.
    var step by rememberSaveable { mutableStateOf(JourneyStep.CREATE) }
    var browserFailed by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val choosing = !status.connected && (status.accounts.isNotEmpty() || status.needsAccountId)
    val createScreen = step == JourneyStep.CREATE && (!status.upgrading || status.upgradePlan != null) &&
        status.phase == TokenPhase.IDLE && (status.upgrading || (!status.tokenHeld && status.error == null))
    val addAccountScreen = createScreen && !status.upgrading
    val colors = MaterialTheme.colorScheme
    val lightSurface = colors.surface.luminance() > 0.5f
    val warmIntro = addAccountScreen && lightSurface
    val title = when {
        status.connected && status.upgrading -> "Token 已替换"
        status.upgrading -> "补充读取权限"
        choosing -> "选择账号"
        createScreen -> "添加账号"
        else -> "连接账号"
    }

    Dialog(
        onDismissRequest = { if (canCancel) dismiss() },
        properties = tokenDialogProperties(canCancel),
    ) {
        TokenSystemBarAppearance(lightSurface)
        Surface(
            Modifier.fillMaxSize().testTag("token-screen"),
            color = if (warmIntro) Color(0xFFFFFCF8) else colors.surface,
            contentColor = if (warmIntro) Color(0xFF252D29) else colors.onSurface,
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(
                    Modifier.fillMaxWidth().padding(top = if (addAccountScreen) 16.dp else 0.dp)
                        .heightIn(min = 52.dp).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title, Modifier.padding(horizontal = 48.dp, vertical = 8.dp).testTag("token-title"),
                        style = MaterialTheme.typography.titleMedium)
                    IconButton(dismiss, Modifier.align(Alignment.CenterStart).testTag("token-cancel"), enabled = canCancel) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, "取消", Modifier.size(20.dp))
                    }
                }
                if (!addAccountScreen) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    key(title) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                                .padding(horizontal = if (addAccountScreen) 24.dp else 20.dp)
                                .padding(top = if (addAccountScreen) 20.dp else 24.dp, bottom = 16.dp),
                        ) {
                            if (status.connected && status.upgrading) {
                                JourneyHeading("新 Token 已启用", "FlareGo 已替换本机凭据，没有撤销旧 Token。")
                                Spacer(Modifier.height(16.dp))
                                val old = status.retiredToken
                                if (old != null) {
                                    SupportingText("旧 Token ID：${old.id}", Modifier.testTag("old-token-id"))
                                    SupportingText("只有确认没有其他设备、本机其他连接或服务使用它时，才应撤销。请在官网核对旧 ID，勿撤销新 Token。")
                                    val accountId = status.accounts.singleOrNull()?.id
                                    val url = when (old.kind) {
                                        TokenKind.USER_OWNED -> UserTokenManagementUrl
                                        TokenKind.ACCOUNT_OWNED -> accountId?.let { "https://dash.cloudflare.com/$it/api-tokens" }
                                        TokenKind.UNKNOWN -> null
                                    }
                                    if (url != null) CenterAction("打开 Cloudflare Token 管理 ↗", "old-token-manage") {
                                        browserFailed = try { uriHandler.openUri(url); false } catch (_: Exception) { true }
                                    }
                                    if (browserFailed) SupportingText("无法打开浏览器；可稍后手动前往 Cloudflare 检查。")
                                } else {
                                    SupportingText("无法确认旧 Token 的 ID。若需撤销，请先确认其身份及其他设备或服务是否仍在使用。")
                                }
                                TokenPrimary("完成（不撤销）", "old-token-skip", onClick = dismiss)
                            } else if (createScreen) {
                                val plan = status.upgradePlan
                                val create = {
                                    browserFailed = try {
                                        uriHandler.openUri(tokenTemplateUrl(plan))
                                        step = JourneyStep.BROWSER_HANDOFF
                                        false
                                    } catch (_: Exception) {
                                        true
                                    }
                                }
                                val existing = {
                                    browserFailed = false
                                    step = JourneyStep.EXISTING_TOKEN
                                }
                                if (plan == null) {
                                    AddAccountIntro(warmIntro, browserFailed, create, existing)
                                } else {
                                    JourneyHeading("为 ${plan.accountName} 创建新 Token",
                                        "在 Cloudflare 官网一次预填所需读取权限，不申请管理权限。")
                                    Spacer(Modifier.height(14.dp))
                                    SupportingText(plan.features.distinctBy { it.key }.joinToString("、") { it.label })
                                    Spacer(Modifier.height(16.dp))
                                    SupportingText(if (plan.zoneId != null) "已预填当前账号及单个域名；请在官网核对资源范围。"
                                        else "已预填当前账号；请在官网将域名范围收窄至原有域名及本次所需域名。")
                                    SupportingText("新 Token 必须能读取原有及新增功能。模板不迁移写入或其他自定义权限；若仍依赖这些权限，请先在官网核对，或取消并保留现有 Token。")
                                    SupportingText("Registrar 不在预填范围内。")
                                    TokenStatusFeedback(status, connection)
                                    if (browserFailed) TokenBrowserError()
                                    Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                                    TokenPrimary("前往 Cloudflare 创建 ↗", "token-create", onClick = create)
                                    CenterAction("已有新 Token，直接验证", "token-existing", onClick = existing)
                                }
                            } else {
                                if (status.busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("token-progress"))
                                    Spacer(Modifier.height(12.dp))
                                }
                                if (status.upgrading && status.upgradePlan == null) {
                                    JourneyHeading("核查前不会替换 Token", "此步骤只读取当前账号的权限和资源范围。")
                                    TokenStatusFeedback(status, connection)
                                } else if (choosing) {
                                    JourneyHeading("选择要连接的账号", if (status.accounts.isNotEmpty())
                                        "这个 Token 可以访问以下账号。" else "无法自动发现可用账号。")
                                    Spacer(Modifier.height(16.dp))
                                    status.accounts.forEach { account ->
                                        AccountRow(account.name, account.id, enabled = !status.busy) {
                                            connection.selectAccount(account.id)
                                        }
                                    }
                                    if (status.hasMore) {
                                        TextButton(onClick = connection::loadMore, enabled = !status.busy,
                                            modifier = Modifier.testTag("token-load-more")) { Text("加载更多账号") }
                                    }
                                    TokenStatusFeedback(status, connection)
                                    if (status.needsAccountId) {
                                        ManualAccount(status.busy, connection::useAccountId)
                                    } else {
                                        Spacer(Modifier.height(24.dp))
                                        SupportingText("点选账号后保存连接。只有一个账号时，自动跳过此页。")
                                    }
                                } else {
                                    PasteToken(step == JourneyStep.BROWSER_HANDOFF, status, connection, clipboard) {
                                        if (!status.upgrading) connection.cancel()
                                        step = JourneyStep.CREATE
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TokenBrowserError() {
    Spacer(Modifier.height(8.dp))
    Text("无法打开系统浏览器。请安装或启用浏览器后重试，或直接连接已有 Token。",
        Modifier.testTag("token-browser-error"), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun JourneyHeading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    SupportingText(subtitle)
}

@Composable
private fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TokenPrimary(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) Color(0xFFFFB691) else Color(0xFFA84420),
            contentColor = if (dark) Color(0xFF4B1C06) else Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.CenterAction(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.align(Alignment.CenterHorizontally).testTag(tag), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) { Text(label) }
}

@Composable
private fun ColumnScope.PasteToken(
    browserHandoff: Boolean,
    status: TokenConnectionStatus,
    connection: TokenConnection,
    clipboard: Clipboard,
    onBack: () -> Unit,
) {
    // Plaintext is strictly in composition memory and is dropped on submit or when leaving.
    var token by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var pasteError by remember { mutableStateOf(false) }
    var pendingPaste by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        val value = token.trim()
        if (value.isNotEmpty() && !status.busy && !pendingPaste) {
            token = ""
            revealed = false
            pasteError = false
            keyboard?.hide()
            connection.submit(value)
        }
    }
    JourneyHeading("连接 API Token", if (browserHandoff) "在 Cloudflare 完成创建并复制 Token，再回到这里粘贴。"
        else "请粘贴你在 Cloudflare 官网复制的 API Token。")
    Spacer(Modifier.height(20.dp))
    TextField(
        value = token,
        onValueChange = { token = it; pasteError = false },
        enabled = !status.busy && !pendingPaste,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("token-field")
            .semantics { stateDescription = if (revealed) "Token 已显示" else "Token 已隐藏" },
        label = { Text("API Token") },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        // The accessibility password hint follows the explicit reveal state.
        keyboardOptions = KeyboardOptions(
            keyboardType = if (revealed) KeyboardType.Text else KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(
                onClick = { revealed = !revealed },
                modifier = Modifier.testTag("token-reveal"),
                enabled = !status.busy,
            ) {
                Icon(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (revealed) "隐藏 Token" else "显示 Token",
                    Modifier.size(20.dp),
                )
            }
        },
    )
    TextButton(
        onClick = {
            pendingPaste = true
            scope.launch {
                try {
                    val text = clipboard.getClipEntry()?.plainTokenText()?.trim()
                    pasteError = text.isNullOrEmpty()
                    if (!text.isNullOrEmpty()) {
                        token = text
                        revealed = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    pasteError = true
                } finally {
                    pendingPaste = false
                }
            }
        },
        modifier = Modifier.testTag("token-paste"),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        enabled = !status.busy && !pendingPaste,
        contentPadding = PaddingValues(0.dp),
    ) { Text("从剪贴板粘贴") }
    if (pasteError) {
        Text("剪贴板没有可粘贴的文本。请先复制 API Token。",
            Modifier.testTag("token-paste-error"), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    SupportingText("验证通过后仅在本机加密保存。")
    SupportingText("FlareGo 不会自动读取剪贴板。不支持 Global API Key。")

    TokenStatusFeedback(status, connection)
    Spacer(Modifier.weight(1f).heightIn(min = 20.dp))
    TokenPrimary(if (status.upgrading) "验证并替换" else "验证并连接", "token-submit", token.isNotBlank() && !status.busy && !pendingPaste, submit)
    CenterAction("还没创建？返回创建 Token", "token-create-back", !status.busy, onBack)
}

@Composable
private fun TokenStatusFeedback(status: TokenConnectionStatus, connection: TokenConnection) {
    status.notice?.let { notice ->
        Spacer(Modifier.height(8.dp))
        SupportingText(notice, Modifier.testTag("token-notice"))
    }
    status.error?.let { error ->
        Spacer(Modifier.height(8.dp))
        Column(Modifier.testTag("token-error"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Notice(error)
            if (!status.upgrading) problemHint(status.problem)?.let { SupportingText(it) }
        }
    }
    if (status.canRetry && (status.tokenHeld || status.upgrading)) {
        TextButton(onClick = connection::retry, enabled = !status.busy,
            modifier = Modifier.testTag("token-retry")) { Text("重试") }
    }
}

@Composable
private fun ManualAccount(busy: Boolean, onVerify: (String) -> Unit) {
    var manualOpen by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf("") }
    Spacer(Modifier.height(16.dp))
    SupportingText("该 Token 无法列出账号（常见于账号级受限 Token）。可手动填写 32 位 Account ID，验证通过后才会保存。")
    TextButton(
        onClick = { manualOpen = !manualOpen },
        enabled = !busy,
        modifier = Modifier.testTag("token-manual-toggle"),
    ) { Text(if (manualOpen) "收起手动填写" else "手动填写 Account ID") }
    if (manualOpen) {
        val valid = AccountIdPattern.matches(accountId)
        OutlinedTextField(
            value = accountId,
            onValueChange = { accountId = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("token-account-id"),
            label = { Text("32 位 Account ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid && !busy) onVerify(accountId) }),
        )
        Spacer(Modifier.height(8.dp))
        SupportingText("只接受 32 位十六进制 ID；验证前不会保存任何内容。")
        Spacer(Modifier.height(12.dp))
        TokenPrimary("验证该账号", "token-manual-submit", valid && !busy) { onVerify(accountId) }
    }
}

@Composable
private fun AccountRow(name: String, id: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("token-account-$id")) {
        Row(Modifier.heightIn(min = 68.dp).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                // Display only: the full id is what gets selected and verified.
                SupportingText(if (id.length > 18) id.take(4) + "…" + id.takeLast(4) else id)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

internal expect fun tokenDialogProperties(canCancel: Boolean): DialogProperties

@Composable
internal expect fun TokenSystemBarAppearance(light: Boolean)

/** Kept for callers that need the same classification text outside the dialog. */
internal fun tokenProblemHint(problem: TokenProblem) = problemHint(problem)
