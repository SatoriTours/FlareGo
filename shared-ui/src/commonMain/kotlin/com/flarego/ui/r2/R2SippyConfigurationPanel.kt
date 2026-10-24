package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2_JURISDICTIONS

/**
 * Batch B3 Sippy: the production, credential-editing form.
 *
 * Security contract of this surface:
 * - no secret control exists until the **real** window protection is active
 *   ([R2SippySurfaceUi.secureReady]); the "establish protected window" action is the only way in, so
 *   there is no frame in which an editable secret field is drawn unprotected;
 * - every credential field is always masked and marked as a password for accessibility; there is no
 *   reveal toggle, no clipboard write, no autosave and no rememberSaveable/saved-state copy;
 * - the only material owner is the shared [R2SippyCredentialOwner]; the text a control shows comes
 *   from its minimal private getter for that one field and is never copied into a public state;
 * - an issued preview is revoked on every edit/provider/target change, and the exact bucket name plus
 *   the explicit "these credentials are sent to Cloudflare once" disclosure are required before any
 *   preview is requested (single bucket-name / Preview / Confirm control; the shared preview card
 *   supplies Confirm).
 */
@Composable
fun R2SippyConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    editor: R2SippyCredentialOwner? = null,
) {
    val sippy = state.sippy
    val enabled = !state.loading && !state.writing && state.preview == null
    // Composition fact only: the mount that actually draws the credential controls reports its own
    // removal when that branch (or the whole panel/tab/screen) leaves the composition, carrying the
    // immutable session epoch it drew. The window then binds the *next* committed frame for exactly
    // that session; a late/duplicate report of an older mount cannot confirm a newer session and the
    // report itself is never treated as a frame commit.
    // NOTE: this panel lives inside the settings screen's own vertical scroll; adding a second
    // verticalScroll here would be measured with an infinite height constraint. Long labels/URLs wrap
    // and the parent scroll keeps the footer reachable, including with the keyboard open.
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sippy 按需迁移", style = MaterialTheme.typography.titleMedium)
        Text(
            "开启后由 Cloudflare 按需把源桶对象迁移到本桶。保存成功只表示配置被接受，" +
                "不宣称凭据已生效、源已连通或历史对象已迁移；停用不会删除已复制对象。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "目标地区：${state.target.jurisdiction}（可选：${R2_JURISDICTIONS.sorted().joinToString("/")}）",
            style = MaterialTheme.typography.bodySmall,
        )

        if (!sippy.available) {
            Text(
                "当前应用未接通 Sippy 配置写入；本页只读。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("rc-sippy-unwired"),
            )
        }

        // ---- current configuration (safe projection only: no secret, no echoed raw text)
        sippy.snapshotError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("rc-sippy-snapshot-error"),
            )
        }
        val snapshot = sippy.snapshot
        if (snapshot != null) {
            Text(
                when {
                    !snapshot.detailsProvided && !snapshot.enabled -> "当前状态：未启用（公开详情未提供，可全新配置）"
                    snapshot.enabled -> "当前状态：已启用"
                    else -> "当前状态：未启用"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("rc-sippy-state"),
            )
            if (!snapshot.editable) {
                Text(
                    snapshot.readOnlyReason ?: "该配置当前无法解释，仅可查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("rc-sippy-readonly"),
                )
            }
            snapshot.provider?.let {
                Text(
                    "源：$it" + (snapshot.sourceBucket?.let { b -> " / 桶 $b" } ?: "") +
                        (snapshot.sourceRegion?.let { r -> " / 地区 $r" } ?: "") +
                        (snapshot.sourceContainer?.let { c -> " / 容器 $c" } ?: "") +
                        (snapshot.sourceBucketUrl?.let { u -> " / 地址 $u" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("rc-sippy-source"),
                )
            }
            if (snapshot.destinationBucket != null) {
                Text(
                    "目标：${snapshot.destinationAccount ?: ""}/${snapshot.destinationBucket}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("rc-sippy-destination"),
                )
            }
        }

        // ---- secure-window gate: no secret control exists before protection is real
        if (!sippy.secureReady) {
            Text(
                "安全窗口未就绪：用户凭据输入与 Sippy 写入均被禁用。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("rc-sippy-secure"),
            )
            Button(
                onClick = { onIntent(R2ConfigurationIntent.BeginSippySecureEditing) },
                enabled = sippy.available && enabled,
                modifier = Modifier.testTag("rc-sippy-secure-begin"),
            ) { Text("建立受保护窗口后再输入凭据") }
        } else {
            // The removal report belongs to the mount that draws the secrets: this effect runs when
            // *this* branch leaves the composition (the gate closed again). It is confirmation only, so
            // a subtree re-creation that forgets this effect is a harmless no-op; the whole panel
            // leaving is covered by the route-level mount in `R2ConfigurationScreen`.
            val epoch = sippy.secureEpoch
            DisposableEffect(editor, epoch) {
                onDispose { editor?.onSecretSurfaceRemoved(epoch) }
            }
            Text(
                "安全窗口已就绪：当前窗口已应用 FLAG_SECURE（草稿修订 ${sippy.credentialRevision}）。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("rc-sippy-secure"),
            )
            OutlinedButton(
                onClick = { onIntent(R2ConfigurationIntent.EndSippySecureEditing) },
                enabled = enabled,
                modifier = Modifier.testTag("rc-sippy-secure-end"),
            ) { Text("结束受保护编辑并清除已输入凭据") }

            if (editor == null) {
                Text(
                    "当前环境未提供私有凭据编辑器；本页只读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("rc-sippy-no-editor"),
                )
            } else {
                val revision = sippy.credentialRevision
                ProviderSelection(sippy.provider, enabled, onIntent)
                when (sippy.provider) {
                    R2SippyProvider.AWS -> {
                        SecretField(editor, R2SippySecretField.SOURCE_BUCKET, "源桶名", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.SOURCE_REGION, "源区域（例如 us-east-1）", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.SOURCE_ACCESS_KEY_ID, "源 Access Key ID", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, "源 Secret Access Key", revision, enabled, false, onIntent)
                    }
                    R2SippyProvider.GCS -> {
                        SecretField(editor, R2SippySecretField.SOURCE_BUCKET, "源桶名", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.GCS_CLIENT_EMAIL, "服务账号邮箱", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.GCS_PRIVATE_KEY, "服务账号私钥（可多行粘贴）", revision, enabled, true, onIntent)
                    }
                    R2SippyProvider.S3 -> {
                        // The label must stay short enough to be readable at the larger system font; the
                        // full explanation is its own wrapped supporting text right below the control.
                        SecretField(editor, R2SippySecretField.SOURCE_BUCKET_URL, "源 HTTPS 地址", revision, enabled, false, onIntent)
                        Text(
                            "仅提交给 Cloudflare，App 不直接访问或探测该地址。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("rc-sippy-s3-url-help"),
                        )
                        SecretField(editor, R2SippySecretField.SOURCE_ACCESS_KEY_ID, "源 Access Key ID", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, "源 Secret Access Key", revision, enabled, false, onIntent)
                    }
                    R2SippyProvider.AZURE -> {
                        SecretField(editor, R2SippySecretField.AZURE_ACCOUNT_NAME, "存储账号名", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.SOURCE_CONTAINER, "容器名", revision, enabled, false, onIntent)
                        Text(
                            "账户密钥与 SAS Token 只能填写其中一项（两项都填或都不填都会被拒绝）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SecretField(editor, R2SippySecretField.AZURE_ACCOUNT_KEY, "账户密钥", revision, enabled, false, onIntent)
                        SecretField(editor, R2SippySecretField.AZURE_SAS_TOKEN, "SAS Token", revision, enabled, false, onIntent)
                    }
                }
                Text(
                    "目标（本桶已有 R2 Access Key，不使用 REST Token）",
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretField(editor, R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "目标 Access Key ID", revision, enabled, false, onIntent)
                SecretField(editor, R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, "目标 Secret Access Key", revision, enabled, false, onIntent)

                CheckboxRow(
                    label = "我确认：本次会把上面填写的源与目标凭据随请求提交给 Cloudflare 一次" +
                        "（不保存、不回显、不可从公开配置恢复）",
                    checked = sippy.disclosureAcknowledged,
                    enabled = enabled,
                    tag = "rc-sippy-ack-disclosure",
                ) { onIntent(R2ConfigurationIntent.SippyDisclosureAcknowledgedChanged(it)) }
                CheckboxRow(
                    label = "我确认：目标使用本桶已有的 R2 Access Key，本次只提交一次",
                    checked = sippy.credentialsAcknowledged,
                    enabled = enabled,
                    tag = "rc-sippy-ack-credentials",
                ) { onIntent(R2ConfigurationIntent.SippyCredentialsAcknowledgedChanged(it)) }

                OutlinedTextField(
                    value = sippy.typedBucketName,
                    onValueChange = { onIntent(R2ConfigurationIntent.SippyTypedBucketChanged(it)) },
                    label = { Text("请输入完整的目标桶名以确认") },
                    placeholder = { Text(state.target.bucket) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("rc-sippy-typed-bucket"),
                )
                Button(
                    onClick = { onIntent(R2ConfigurationIntent.Preview) },
                    enabled = state.canPreview && enabled && !sippy.disableRequested,
                    modifier = Modifier.fillMaxWidth().testTag("rc-sippy-preview"),
                ) { Text("预览启用请求（尚未发送）") }
            }
        }

        // ---- disable (DELETE) path: its own acknowledgement, same preview/confirm path
        if (sippy.secureReady && snapshot != null && snapshot.enabled && snapshot.editable) {
            HorizontalDivider()
            Text(
                "停用 Sippy 按需迁移",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "停用只影响新的按需迁移；已复制的对象保留，不会删除任何对象，也不代表迁移历史。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!sippy.disableRequested) {
                OutlinedButton(
                    onClick = { onIntent(R2ConfigurationIntent.RequestDisableSippy) },
                    enabled = enabled && sippy.available,
                    modifier = Modifier.testTag("rc-sippy-disable"),
                ) { Text("请求停用（需再次预览确认）") }
            } else {
                CheckboxRow(
                    label = "我确认：停用只影响新的按需迁移，已复制对象保留",
                    checked = sippy.disableAcknowledged,
                    enabled = enabled,
                    tag = "rc-sippy-ack-disable",
                ) { onIntent(R2ConfigurationIntent.SippyDisableAcknowledgedChanged(it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onIntent(R2ConfigurationIntent.CancelDisableSippy) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).testTag("rc-sippy-disable-cancel"),
                    ) { Text("取消") }
                    Button(
                        onClick = { onIntent(R2ConfigurationIntent.Preview) },
                        enabled = state.canPreview && enabled,
                        modifier = Modifier.weight(1f).testTag("rc-sippy-disable-preview"),
                    ) { Text("预览停用请求") }
                }
            }
        }

        state.validationMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("rc-sippy-validation"),
            )
        }
        sippy.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("rc-sippy-credential-error"),
            )
        }
        sippy.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-sippy-notice"))
        }
    }
}

@Composable
private fun ProviderSelection(
    provider: R2SippyProvider,
    enabled: Boolean,
    onIntent: (R2ConfigurationIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("源提供方（切换会清除已输入的源凭据）", style = MaterialTheme.typography.bodySmall)
        // The same local responsive pattern the other panels use: at the larger system font the four
        // provider chips do not fit on one 320dp row, and a clipped chip is not a usable touch target.
        // `FlowRow` wraps instead, keeping the selection semantics, tags and disabled state unchanged.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("rc-sippy-providers"),
        ) {
            for (option in R2SippyProvider.entries) {
                FilterChip(
                    selected = provider == option,
                    onClick = { onIntent(R2ConfigurationIntent.SippyProviderSelected(option)) },
                    label = { Text(option.name) },
                    enabled = enabled,
                    modifier = Modifier.testTag("rc-sippy-provider-${option.name}"),
                )
            }
        }
    }
}

/**
 * One masked credential control.
 *
 * The drawn value is read from the owner's minimal private getter for **this field only**, on every
 * recomposition (the non-secret [revision] drives it), and only while the real window is still
 * protected — no Compose-side cache can outlive the gate. The framework's own text-editing state is
 * unavoidable and is best effort, like JVM string erasure. The value never enters a public state.
 */
@Composable
private fun SecretField(
    editor: R2SippyCredentialOwner,
    field: R2SippySecretField,
    label: String,
    revision: Long,
    enabled: Boolean,
    multiline: Boolean,
    onIntent: (R2ConfigurationIntent) -> Unit,
) {
    // Read-through on every recomposition (no remember of the raw value).
    val current = if (revision >= 0) editor.currentValue(field)?.reveal().orEmpty() else ""
    OutlinedTextField(
        value = current,
        onValueChange = {
            onIntent(R2ConfigurationIntent.SippyFieldChanged(field, R2SippySecretInput.of(it)))
        },
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                password()
                // The field's own `InputText` carries the raw value for accessibility/IME unless it is
                // overridden on this very node (measured: an outer `semantics` on the same node wins,
                // a wrapper does not). Blanking the *outward* value keeps editing/selection/paste and
                // the password semantics intact while no raw credential leaves through semantics.
                this[androidx.compose.ui.semantics.SemanticsProperties.InputText] =
                    androidx.compose.ui.text.AnnotatedString("")
            }
            .testTag("rc-sippy-field-${field.name}"),
    )
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
