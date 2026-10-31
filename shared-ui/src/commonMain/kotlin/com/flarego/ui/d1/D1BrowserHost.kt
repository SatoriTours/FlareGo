package com.flarego.ui.d1

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import com.flarego.ui.AppBackHandler

@Composable
fun D1BrowserHost(browser: D1Browser, account: AccountScope?, backEnabled: Boolean = true) {
    val state by browser.state.collectAsState()
    val private by browser.content.state.collectAsState()
    LaunchedEffect(account) { browser.refreshContext() }
    DisposableEffect(browser) { onDispose { browser.background() } }
    if (state.context?.account != account || account == null) { Text("请先选择当前账号"); return }
    val protected = private.secure && browser.content.secureNow()
    AppBackHandler(backEnabled && (state.writing || private.resultReady || state.editor != null || private.secure || state.database != null)) {
        if (!state.writing) when {
            private.resultReady -> {
                browser.content.closeResult()
                // Browse filters and pending exports are drafts too, even without
                // an editor. Only retire a genuinely idle private session.
                if (state.editor == null && !private.exportReady && !browser.content.hasDraftInput())
                    browser.content.withdraw()
            }
            state.editor != null -> { browser.cancel(); browser.content.withdraw() }
            private.secure -> browser.content.withdraw()
            else -> browser.back()
        }
    }
    if (protected) DisposableEffect(private.surface) { val surface = private.surface; onDispose { browser.content.surfaceRemoved(surface) } }
    LazyColumn(Modifier.fillMaxSize().testTag("d1-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("D1 数据库", style = MaterialTheme.typography.headlineSmall); Text("任意 SQL 均按潜在写入处理；写入不自动重试。导入、导出、恢复及删除恢复的生产入口因官方证据不足保持关闭。") }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("d1-error")) } }
        state.notice?.let { item { Text(it, Modifier.testTag("d1-notice")) } }
        if (state.loading || state.writing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.database == null) {
            item {
                OutlinedTextField(state.filter, browser::filterName, label = { Text("按名称筛选") }, modifier = Modifier.fillMaxWidth().testTag("d1-filter"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ browser.refreshDatabases() }) { Text("刷新") }
                    Button({ browser.begin(D1Editor.CREATE) }, Modifier.testTag("d1-create")) { Text("创建数据库") }
                    OutlinedButton({ browser.begin(D1Editor.UNDELETE) }, Modifier.testTag("d1-undelete")) { Text("恢复已删除 UUID") }
                }
            }
            items(state.databases, key = { it.ref.id }) { database ->
                OutlinedButton({ browser.openDatabase(database.ref) }, modifier = Modifier.fillMaxWidth().testTag("d1-db-${database.ref.id}"),
                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(16.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(database.name); Text(database.ref.id); Text("复制：${database.replication ?: "未提供"} · ${database.fileSize ?: "未知"} bytes")
                    }
                }
            }
            if (state.next != null) item { OutlinedButton({ browser.refreshDatabases(true) }, Modifier.testTag("d1-more")) { Text("加载更多数据库") } }
        } else {
            val database = state.database!!
            item {
                OutlinedButton(browser::back, Modifier.testTag("d1-back")) { Text("返回数据库列表") }
                Text(database.name, style = MaterialTheme.typography.titleLarge); Text(database.ref.id)
                Text("复制：${database.replication ?: "未提供"} · 文件大小：${database.fileSize ?: "未知"} bytes")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(browser::showDetails, Modifier.testTag("d1-details")) { Text("完整详情") }
                    D1Editor.entries.filter { it !in setOf(D1Editor.CREATE, D1Editor.UNDELETE) }.forEach { editor ->
                        OutlinedButton({ browser.begin(editor) }, enabled = !state.writing, modifier = Modifier.testTag("d1-open-${editor.name}")) { Text(editorLabel(editor)) }
                    }
                }
            }
            item {
                OutlinedButton({ browser.content.begin() }, Modifier.testTag("d1-private-begin")) { Text("开启受保护的内容会话") }
                if (protected) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivateField(browser,D1Field.TABLE,"表名", "d1-table",private.revealed)
                    PrivateField(browser,D1Field.FILTER_COLUMN,"筛选列（可选）","d1-filter-column",private.revealed)
                    PrivateField(browser,D1Field.FILTER_VALUE,"等值筛选值","d1-filter-value",private.revealed)
                    PrivateField(browser,D1Field.SORT_COLUMN,"排序列（可选）","d1-sort-column",private.revealed)
                    Check("降序",state.form.descending,"d1-descending") { value -> browser.edit { it.copy(descending = value) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        D1BrowseKind.entries.forEach { kind -> OutlinedButton({ browser.browse(kind) }, Modifier.testTag("d1-browse-${kind.name}")) { Text(when (kind) { D1BrowseKind.SCHEMA -> "结构"; D1BrowseKind.COLUMNS -> "列"; D1BrowseKind.INDEXES -> "索引"; D1BrowseKind.ROWS -> "数据" }) } }
                        OutlinedButton({ browser.browse(D1BrowseKind.SCHEMA,if(state.browseKind == D1BrowseKind.SCHEMA) state.browsePage+1 else 0) }, Modifier.testTag("d1-schema-more")) { Text("下一页结构") }
                        OutlinedButton({ browser.browse(D1BrowseKind.ROWS,if(state.browseKind == D1BrowseKind.ROWS) state.browsePage+1 else 0) }, Modifier.testTag("d1-rows-more")) { Text("下一页数据") }
                    }
                }
            }
        }
        if (protected) {
            item { Check("显式显示私有内容",private.revealed,"d1-reveal",browser.content::reveal) }
            state.editor?.let { editor ->
                item { Text(editorLabel(editor), style = MaterialTheme.typography.titleLarge) }
                if (editor in setOf(D1Editor.CREATE,D1Editor.UNDELETE)) item {
                    OutlinedTextField(state.form.name,{ value -> browser.edit { it.copy(name = value) } },label = { Text(if(editor == D1Editor.CREATE) "数据库名称" else "恢复后名称（可选）") }, modifier = Modifier.fillMaxWidth().testTag("d1-name"))
                    if (editor == D1Editor.UNDELETE) OutlinedTextField(state.form.uuid,{ value -> browser.edit { it.copy(uuid = value) } },label = { Text("已删除数据库的精确 UUID") }, modifier = Modifier.fillMaxWidth().testTag("d1-uuid"))
                }
                if (editor == D1Editor.CREATE) item {
                    Text("jurisdiction 优先于位置提示；两者均会按选择提交。")
                    Choices("jurisdiction",listOf(null)+D1Jurisdiction.entries,state.form.jurisdiction) { value -> browser.edit { it.copy(jurisdiction = value) } }
                    Choices("位置提示",listOf(null)+D1Location.entries,state.form.location) { value -> browser.edit { it.copy(location = value) } }
                }
                if (editor in setOf(D1Editor.CREATE,D1Editor.PUT,D1Editor.PATCH)) item {
                    Text("当前复制：${state.database?.replication ?: "未提供"}；新值：${state.form.replication ?: "省略字段"}")
                    Choices("复制",(if(editor == D1Editor.PUT) emptyList() else listOf(null))+D1Replication.entries,state.form.replication) { value -> browser.edit { it.copy(replication = value) } }
                }
                if (editor in setOf(D1Editor.SQL,D1Editor.RAW)) {
                    item {
                        Check("完整 typed JSON（单个 sql/params 或 batch）",state.form.json,"d1-json-mode") { value -> browser.edit { it.copy(json = value) } }
                        Text("保留完整 SQL 程序，不拆分分号；参数仅字符串，每个 query 最多 100 项。单条 SQL 官方上限 100000 bytes，整个请求含 batch 为 30 秒；失败可能已有写入。编辑器 65536 bytes，完整文件入口不受编辑器限制。")
                        if (state.form.json) PrivateField(browser,D1Field.JSON,"完整 JSON","d1-json",private.revealed)
                        else {
                            PrivateField(browser,D1Field.SQL,"SQL 程序","d1-sql",private.revealed)
                            PrivateField(browser,D1Field.PARAMS,"字符串参数 JSON 数组（可选）","d1-params",private.revealed)
                        }
                        OutlinedButton({ browser.document(if(state.form.json) D1DocumentKind.SQL_JSON else D1DocumentKind.SQL_TEXT) }, Modifier.testTag("d1-import-query")) { Text("读取完整 SQL / JSON 文件") }
                    }
                }
                if (editor == D1Editor.IMPORT) item {
                    Text("完整 UTF-8 SQL 文件，最大 5 GiB（5368709120 bytes）。校验、MD5 和加密暂存完成后，再核对三级确认；init 可能立即开始导入。")
                    OutlinedButton({ browser.document(D1DocumentKind.IMPORT_SQL) }, Modifier.testTag("d1-import-sql")) { Text("选择完整导入文件") }
                }
                if (editor == D1Editor.EXPORT) item {
                    PrivateField(browser,D1Field.TABLES,"完整表范围（每行一个，留空表示全库）","d1-tables",private.revealed)
                    Check("仅结构，不含数据",state.form.noData,"d1-no-data") { value -> browser.edit { it.copy(noData = value) } }
                    Check("仅数据，不含结构",state.form.noSchema,"d1-no-schema") { value -> browser.edit { it.copy(noSchema = value) } }
                    Text("云导出完成、完整下载、SAF 保存分别核对；链接约一小时失效，不会自动重新导出。")
                }
                if (editor == D1Editor.RESTORE) item {
                    Check("按 UTC 时间点解析并冻结 bookmark",state.form.byTimestamp,"d1-restore-time") { value -> browser.edit { it.copy(byTimestamp = value) } }
                    PrivateField(browser,D1Field.TIMESTAMP,"UTC 时间（以 Z 结尾，可选读当前 bookmark）","d1-timestamp",private.revealed)
                    OutlinedButton(browser::readBookmark, Modifier.testTag("d1-read-bookmark")) { Text("读取 bookmark") }
                    PrivateField(browser,D1Field.BOOKMARK,"bookmark","d1-bookmark",private.revealed)
                    Text("免费七日、付费三十日；恢复覆盖当前数据。previous_bookmark 必须另行完整确认后才能再次恢复。")
                }
                item {
                    private.inputBytes?.let { Text("完整暂存：${private.inputKind} · $it bytes",Modifier.testTag("d1-input-ready")) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(browser::cancel, enabled = !state.writing) { Text("取消编辑") }
                        Button(browser::prepare,enabled = !state.loading && !state.writing,modifier = Modifier.testTag("d1-prepare")) { Text("核对完整范围") }
                    }
                }
            }
            if (private.exportReady) item { Button(browser::downloadExport,enabled = !state.loading, modifier = Modifier.testTag("d1-download-export")) { Text("下载完整导出到私有结果") } }
            if (private.resultReady) item {
                Text("完整私有结果：${private.resultBytes} bytes · 预览第 ${private.resultPageNumber} 页",Modifier.testTag("d1-result-summary"))
                browser.content.resultPage()?.let { Text(if(private.revealed) it.text else "结果已遮蔽；显式显示后查看", Modifier.testTag("d1-result-page")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ browser.pageResult(true) },enabled = private.resultPageNumber > 1,modifier = Modifier.testTag("d1-result-prev")) { Text("上一页结果") }
                    OutlinedButton({ browser.pageResult() },enabled = private.resultPageNumber == 0 || private.hasNext,modifier = Modifier.testTag("d1-result-next")) { Text(if(private.resultPageNumber == 0) "查看结果" else "下一页结果") }
                    OutlinedButton({ browser.document(D1DocumentKind.EXPORT_RESULT) },Modifier.testTag("d1-export-result")) { Text("保存完整结果文件") }
                    OutlinedButton(browser.content::closeResult,Modifier.testTag("d1-close-result")) { Text("关闭并释放结果") }
                    OutlinedButton(browser::restorePrevious,Modifier.testTag("d1-restore-previous")) { Text("另行恢复 previous_bookmark") }
                }
            }
        }
        if (state.tasks.isNotEmpty()) item { Text("D1 操作任务",style = MaterialTheme.typography.titleLarge) }
        items(state.tasks,key = { it.id }) { task -> TaskItem(browser,task) }
    }
    if (protected) browser.content.confirmation()?.let { Confirmation(browser,it,state.writing) }
}

@Composable private fun PrivateField(browser: D1Browser, field: D1Field, label: String, tag: String, reveal: Boolean) {
    val state by browser.content.state.collectAsState()
    if (!state.secure || !browser.content.secureNow()) return
    OutlinedTextField(browser.content.field(field).orEmpty(),{ browser.content.set(field,it) },label = { Text(label) },modifier = Modifier.fillMaxWidth().testTag(tag),
        visualTransformation = if(reveal) VisualTransformation.None else PasswordVisualTransformation())
}
@Composable private fun Check(label: String,value: Boolean,tag: String,change: (Boolean) -> Unit) {
    Row { Checkbox(value,change,Modifier.testTag(tag)); Text(label,Modifier.weight(1f).padding(top = 12.dp)) }
}
@Composable private fun <T> Choices(label: String, values: List<T>, current: T, change: (T) -> Unit) {
    Text(label); FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value -> FilterChip(current == value,{ change(value) },label = { Text(value?.toString() ?: "省略") }) }
    }
}
@Composable private fun Confirmation(browser: D1Browser, preview: D1ConfirmationView, busy: Boolean) {
    val private by browser.content.state.collectAsState()
    if (!private.secure || !browser.content.secureNow()) return
    val stage = preview.handle.stage
    var tablePage by remember(preview.handle) { mutableStateOf(0) }
    var offset by remember(preview.handle) { mutableStateOf(0L) }
    var history by remember(preview.handle) { mutableStateOf(emptyList<Long>()) }
    var contentPage by remember(preview.handle) { mutableStateOf<D1PrivatePage?>(null) }
    LaunchedEffect(preview.handle, offset) {
        contentPage = null
        preview.content?.let { content -> runCatching { browser.content.contentPage(content,offset) }.getOrNull()?.let { contentPage = it } }
    }
    Dialog(browser::cancel,properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f),shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${editorAction(preview.action)} · ${when(stage) { D1ConfirmationStage.RISK -> "风险核对"; D1ConfirmationStage.TYPED -> "手动输入"; D1ConfirmationStage.FINAL -> "最终确认" }}",style = MaterialTheme.typography.titleLarge,modifier = Modifier.testTag("d1-confirm-title"))
                LazyColumn(Modifier.weight(1f).testTag("d1-confirm-scroll"),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text("连接：${preview.connectionLabel}"); Text("连接 ID：${preview.connectionId}")
                        Text("账号：${preview.account.accountId}"); Text("数据库 UUID：${preview.databaseId ?: "新建，未知结果不得按名称认领"}"); Text("名称：${preview.databaseName}")
                        Text(preview.warning)
                        Text("复制旧值：${preview.oldReplication ?: "未提供"}；新值：${preview.newReplication ?: if(preview.omittedReplication) "省略（不提交该字段）" else "不适用"}")
                        if(preview.action == "d1-create-database") Text("jurisdiction：${preview.createJurisdiction ?: "省略"}；位置提示：${preview.createLocation ?: "省略"}。jurisdiction 优先于位置提示。")
                        preview.newName?.let { Text("恢复后的名称：$it") }
                        Check("显式显示完整私有范围",private.revealed,"d1-confirm-reveal",browser.content::reveal)
                        preview.restoreTimestamp?.let { Text(if(private.revealed) "请求 UTC：$it" else "请求时间已遮蔽") }
                        preview.restoreBookmark?.let { Text(if(private.revealed) "冻结 bookmark：$it" else "冻结 bookmark 已遮蔽") }
                    }
                    preview.tables?.let { tables -> item {
                        Text(if(preview.allTables == true) "范围：全库（空 tables）" else "完整表范围：${tables.size} 项")
                        Text("no_data=${preview.noData}；no_schema=${preview.noSchema}")
                        val first = tablePage*5; val end = minOf(first+5,tables.size)
                        Text("${if(tables.isEmpty()) 0 else first+1}–$end / ${tables.size}",Modifier.testTag("d1-confirm-table-page"))
                        tables.drop(first).take(5).forEachIndexed { i, table -> Text(if(private.revealed) "${first+i+1}. $table" else "${first+i+1}. 表名已遮蔽",Modifier.testTag("d1-confirm-table-${first+i}")) }
                        FlowRow {
                            OutlinedButton({ tablePage-- },enabled = tablePage > 0,modifier = Modifier.testTag("d1-tables-prev")) { Text("上一页表") }
                            OutlinedButton({ tablePage++ },enabled = end < tables.size,modifier = Modifier.testTag("d1-tables-next")) { Text("下一页表") }
                        }
                    } }
                    preview.content?.let { content -> item {
                        Text("完整输入：${content.descriptor.length} bytes；预览偏移 $offset",Modifier.testTag("d1-confirm-content-size"))
                        Text(if(private.revealed) contentPage?.text ?: "正在完整认证并载入此页" else "SQL / 参数 / 文件已遮蔽；显式显示后核对",Modifier.testTag("d1-confirm-private-page"))
                        FlowRow {
                            OutlinedButton({ offset = history.last(); history = history.dropLast(1) },enabled = history.isNotEmpty(),modifier = Modifier.testTag("d1-content-prev")) { Text("上一段") }
                            OutlinedButton({ history = history + offset; offset = contentPage!!.nextOffset!! },enabled = contentPage?.hasMore == true,modifier = Modifier.testTag("d1-content-next")) { Text("下一段") }
                        }
                    } }
                    if (stage == D1ConfirmationStage.TYPED) item { PrivateField(browser,D1Field.TYPED,if(preview.databaseId == null) "精确账号 ID" else "精确数据库 UUID","d1-typed-id",true) }
                    if (stage == D1ConfirmationStage.FINAL) item { Text("只发送本次冻结请求。权限变化、范围变化或结果未知均不会自动重发。",Modifier.testTag("d1-final-end")) }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(browser::cancel,enabled = !busy,modifier = Modifier.weight(1f).testTag("d1-cancel-confirm")) { Text("取消") }
                    Button({ when(stage) { D1ConfirmationStage.RISK -> browser.acceptRisk(); D1ConfirmationStage.TYPED -> browser.acceptTyped(browser.content.field(D1Field.TYPED).orEmpty()); D1ConfirmationStage.FINAL -> browser.submit() } },
                        enabled = !busy && (stage != D1ConfirmationStage.TYPED || browser.content.field(D1Field.TYPED) == (preview.databaseId ?: preview.account.accountId)),modifier = Modifier.weight(1f).testTag(when(stage) { D1ConfirmationStage.RISK -> "d1-risk-next"; D1ConfirmationStage.TYPED -> "d1-typed-next"; D1ConfirmationStage.FINAL -> "d1-final-send" })) { Text(if(stage == D1ConfirmationStage.FINAL) "确认" else "继续") }
                }
            }
        }
    }
}
@Composable private fun TaskItem(browser: D1Browser, task: OperationTask) {
    var close by remember(task.id) { mutableStateOf(false) }
    Column {
        Text("${editorAction(task.actionKey)} · ${task.state}"); Text("${task.resourceRef?.externalId}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ browser.observeTask(task.id) }) { Text("重读精确目标") }
            if (task.pollPath != null && task.state in setOf(TaskState.ACCEPTED,TaskState.UNKNOWN,TaskState.RUNNING)) OutlinedButton({ browser.continueTask(task.id) },Modifier.testTag("d1-continue-${task.id}")) { Text("仅继续原任务轮询") }
            if (D1TaskTargets.unsettled(task)) OutlinedButton({ close = true }) { Text("人工处置") }
        }
    }
    if (close) {
        var typed by remember(task.id) { mutableStateOf("") }; var acknowledged by remember(task.id) { mutableStateOf(false) }
        Dialog({ close = false },properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.9f)) { Column(Modifier.padding(16.dp)) {
                Text("人工处置原任务",style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f)) {
                    item { Text("只解除本地门禁，云端仍未知，不重放、不认定成功。\n账号：${(task.scope as? AccountScope)?.accountId}\n目标：${task.resourceRef?.externalId}") }
                    item { OutlinedTextField(typed,{ typed = it },label = { Text("精确目标 ID") },modifier = Modifier.fillMaxWidth().testTag("d1-close-id")) }
                    item { Check("我已在外部核对并承担继续写入的影响",acknowledged,"d1-close-ack") { acknowledged = it } }
                }
                Row { OutlinedButton({ close = false },Modifier.weight(1f)) { Text("取消") }; Button({ browser.closeTask(task.id,typed,acknowledged); close = false },enabled = acknowledged && typed == task.resourceRef?.externalId,modifier = Modifier.weight(1f).testTag("d1-close-confirm")) { Text("解除") } }
            } }
        }
    }
}
private fun editorLabel(editor: D1Editor) = when(editor) { D1Editor.CREATE -> "创建数据库"; D1Editor.PUT -> "更新复制 PUT"; D1Editor.PATCH -> "部分更新 PATCH"; D1Editor.DELETE -> "删除数据库"; D1Editor.UNDELETE -> "恢复已删除数据库"; D1Editor.SQL -> "SQL query"; D1Editor.RAW -> "SQL raw"; D1Editor.IMPORT -> "完整导入"; D1Editor.EXPORT -> "完整导出"; D1Editor.RESTORE -> "Time Travel 恢复" }
private fun editorAction(action: String) = when(action) { "d1-create-database" -> editorLabel(D1Editor.CREATE); "d1-update-database" -> editorLabel(D1Editor.PUT); "d1-update-partial-database" -> editorLabel(D1Editor.PATCH); "d1-delete-database" -> editorLabel(D1Editor.DELETE); "d1-undelete-database" -> editorLabel(D1Editor.UNDELETE); "d1-query-database" -> editorLabel(D1Editor.SQL); "d1-raw-database-query" -> editorLabel(D1Editor.RAW); "d1-import-database" -> editorLabel(D1Editor.IMPORT); "d1-export-database" -> editorLabel(D1Editor.EXPORT); "d1-time-travel-restore" -> editorLabel(D1Editor.RESTORE); else -> "D1 操作" }
