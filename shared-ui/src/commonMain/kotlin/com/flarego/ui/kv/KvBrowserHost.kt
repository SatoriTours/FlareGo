package com.flarego.ui.kv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flarego.core.application.kv.*
import com.flarego.core.model.*
import com.flarego.ui.AppBackHandler

@Composable
fun KvBrowserHost(browser:KvBrowser,account:AccountScope?,backEnabled:Boolean=true) {
    val s by browser.state.collectAsState()
    val private by browser.content.state.collectAsState()
    var failedOpen by remember(browser){mutableStateOf(false)}
    var closingTask by remember(browser,account){mutableStateOf<OperationTask?>(null)}
    LaunchedEffect(browser,account){browser.refreshContext()}
    DisposableEffect(browser){onDispose{browser.close()}}
    val surface=private.surface
    DisposableEffect(browser,surface){onDispose{browser.content.surfaceRemoved(surface)}}
    AppBackHandler(backEnabled && account != null && s.context?.account == account &&
        (s.writing || s.editor != null || private.secure || s.namespace != null)) {
        if (!s.writing) {
            if (s.editor != null || private.secure) browser.cancel()
            else browser.backToNamespaces()
        }
    }
    Column(Modifier.fillMaxSize().testTag("kv-browser")){
        if(s.context?.account!=account||account==null){Text("正在绑定当前账号的 KV…",Modifier.padding(16.dp));return@Column}
        if(s.loading||s.writing)LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp).testTag("kv-content"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)){
            item{
                Text("标准 Workers KV",style=MaterialTheme.typography.titleLarge)
                Text("账号：${account.accountId}",style=MaterialTheme.typography.bodySmall)
                Text("读取仅代表本次观察；API 接纳不代表全球传播。写入需单独核实当前 Token 的 KV 写权限。",style=MaterialTheme.typography.bodySmall)
            }
            s.error?.let{item{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("kv-error"))}}
            s.notice?.let{item{Text(it,Modifier.testTag("kv-notice"))}}
            if(s.namespace==null){
                item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={browser.refreshNamespaces()},enabled=!s.loading,modifier=Modifier.testTag("kv-refresh-namespaces")){Text("刷新")}
                    Button(onClick={browser.begin(KvEditor.CREATE)},enabled=!s.writing,modifier=Modifier.testTag("kv-create")){Text("创建 namespace")}
                }}
                items(s.namespaces,key={it.ref.id}){ns->OutlinedCard(onClick={browser.openNamespace(ns.ref)},modifier=Modifier.fillMaxWidth().testTag("kv-ns-${ns.ref.id}")){
                    Column(Modifier.padding(12.dp)){Text(ns.title,style=MaterialTheme.typography.titleMedium);Text(ns.ref.id)}
                }}
                if(s.namespaceNext!=null)item{OutlinedButton(onClick={browser.refreshNamespaces(true)},enabled=!s.loading,modifier=Modifier.testTag("kv-more-namespaces")){Text("加载更多 namespace")}}
            }else{
                item{
                    Text(s.namespace!!.title,style=MaterialTheme.typography.titleMedium)
                    Text("namespace ID：${s.namespace!!.ref.id}",Modifier.testTag("kv-namespace-id"))
                    Text("URL 编码支持：${s.namespace!!.supportsUrlEncoding?.toString()?:"未知"}")
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick=browser::backToNamespaces,modifier=Modifier.testTag("kv-back")){Text("返回目录")}
                        OutlinedButton(onClick={browser.openNamespace(s.namespace!!.ref)},modifier=Modifier.testTag("kv-get-namespace")){Text("读取详情")}
                        OutlinedButton(onClick={browser.begin(KvEditor.RENAME)},enabled=!s.writing,modifier=Modifier.testTag("kv-rename")){Text("改名")}
                        OutlinedButton(onClick=browser::prepareDeleteNamespace,enabled=!s.writing,modifier=Modifier.testTag("kv-delete-namespace")){Text("删除 namespace")}
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={browser.begin(KvEditor.VALUE)},enabled=!s.writing,modifier=Modifier.testTag("kv-new-value")){Text("写入单键")}
                        OutlinedButton(onClick={browser.begin(KvEditor.BULK_READ)},modifier=Modifier.testTag("kv-bulk-read")){Text("批量读")}
                        OutlinedButton(onClick={browser.begin(KvEditor.BULK_WRITE)},modifier=Modifier.testTag("kv-bulk-write")){Text("批量写")}
                        OutlinedButton(onClick={browser.begin(KvEditor.BULK_DELETE)},modifier=Modifier.testTag("kv-bulk-delete")){Text("批量删")}
                    }
                }
                if(s.editor==null){
                    item{OutlinedTextField(s.prefix,{browser.filterPrefix(it)},label={Text("键名前缀（原样匹配）")},modifier=Modifier.fillMaxWidth().testTag("kv-prefix"))}
                    item{OutlinedButton(onClick={browser.refreshKeys()},enabled=!s.loading,modifier=Modifier.testTag("kv-refresh-keys")){Text("刷新键列表")}}
                    items(s.keys,key={it.key}){key->OutlinedCard(onClick={browser.selectKey(key)},modifier=Modifier.fillMaxWidth().testTag("kv-key-${key.key}")){
                        Column(Modifier.padding(12.dp)){Text(key.key);Text("到期 UTC Unix 秒：${key.expiration?.toString()?:"未报告"} · metadata：${if(key.hasMetadata)"存在（隐藏）"else"未报告"}",style=MaterialTheme.typography.bodySmall)}
                    }}
                    if(s.keyNext!=null)item{OutlinedButton(onClick={browser.refreshKeys(true)},enabled=!s.loading,modifier=Modifier.testTag("kv-more-keys")){Text("继续读取键（含空页）")}}
                    s.selectedKey?.let{key->item{
                        Text("当前键：${key.key}",style=MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            OutlinedButton(onClick=browser::readValue,modifier=Modifier.testTag("kv-read-value")){Text("读取值")}
                            OutlinedButton(onClick=browser::readMetadata,modifier=Modifier.testTag("kv-read-metadata")){Text("读取 metadata")}
                            OutlinedButton(onClick={browser.begin(KvEditor.VALUE)},modifier=Modifier.testTag("kv-edit-value")){Text("编辑覆盖")}
                            OutlinedButton(onClick=browser::prepareDeleteKey,enabled=!s.writing,modifier=Modifier.testTag("kv-delete-key")){Text("删除此键")}
                        }
                    }}
                }
            }
            s.editor?.let{editor->item{KvEditorPanel(browser,s,private,editor)}}
            if(s.failedKeys.isNotEmpty())item{
                Text("失败键：${s.failedKeys.size}；不会自动重试")
                OutlinedButton(onClick={failedOpen=true},modifier=Modifier.testTag("kv-failed-keys")){Text("核对完整失败列表")}
            }
            if(s.tasks.isNotEmpty())item{Text("本地写入任务",style=MaterialTheme.typography.titleMedium)}
            items(s.tasks.sortedByDescending{it.updatedAtMs},key={it.id}){task->
                Column(Modifier.fillMaxWidth()){
                    Text("${kvActionLabel(task.actionKey)}：${task.state}")
                    Text(task.safeDetail,style=MaterialTheme.typography.bodySmall)
                    Text("定位：${task.resourceRef?.externalId.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={browser.observeTask(task.id)},modifier=Modifier.testTag("kv-observe-${task.id}")){Text("只读核对")}
                        if(KvTaskTargets.unsettled(task.state))OutlinedButton(onClick={closingTask=task},modifier=Modifier.testTag("kv-close-${task.id}")){Text("人工处置")}
                    }
                }
            }
        }
    }
    s.confirmation?.let{preview->KvConfirmationDialog(preview,s.writing,
        {browser.cancel()}, {browser.acceptRisk(preview.handle)}, {browser.acceptTyped(preview.handle,it)}, {browser.submit(preview.handle)})}
    if(failedOpen)KvKeysDialog("完整失败键",s.failedKeys){failedOpen=false}
    closingTask?.let{task->KvManualCloseDialog(task,{closingTask=null}){text,ack->browser.closeTask(task.id,text,ack);closingTask=null}}
}

@Composable
private fun KvEditorPanel(browser:KvBrowser,s:KvBrowserState,p:KvContentState,editor:KvEditor){
    val form=s.form
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(when(editor){KvEditor.CREATE->"创建 namespace";KvEditor.RENAME->"修改 namespace 标题";KvEditor.VALUE->"单键完整值";KvEditor.BULK_READ->"批量读取";KvEditor.BULK_WRITE->"批量覆盖写入";KvEditor.BULK_DELETE->"批量删除"},style=MaterialTheme.typography.titleMedium)
        if(editor in setOf(KvEditor.CREATE,KvEditor.RENAME)){
            OutlinedTextField(form.title,{v->browser.edit{it.copy(title=v)}},label={Text("新标题")},modifier=Modifier.fillMaxWidth().testTag("kv-title-input"))
        }
        if(editor==KvEditor.VALUE){
            OutlinedTextField(form.key,{v->browser.edit{it.copy(key=v)}},label={Text("精确键名（不去空格或改写）")},modifier=Modifier.fillMaxWidth().testTag("kv-key-input"))
            if(!p.secure){
                Text("内容已撤销；重新开启受保护编辑后再输入或读取")
                Button(onClick={browser.content.begin()},modifier=Modifier.testTag("kv-open-secure")){Text("开启私有编辑")}
            }else{
                val value=browser.content.valueForDisplay()?.raw().orEmpty()
                if(p.textEditable)OutlinedTextField(value,{v->if(!browser.content.setValue(KvSensitiveInput.of(v)))browser.documentNotice("文本超过 64KiB 或不是有效 UTF-8；请使用完整文件导入")},label={Text("值（空值合法，文本最多 64KiB）")},
                    visualTransformation=if(p.revealed)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth().testTag("kv-value-input"))
                else Text("完整值 ${p.valueBytes} 字节；二进制或超 64KiB，使用文件入口，不提供截断编辑",Modifier.testTag("kv-binary-value"))
                KvCheck("显式显示值与 metadata",p.revealed,"kv-reveal"){browser.content.reveal(it)}
                OutlinedTextField(browser.content.metadataForDisplay()?.raw().orEmpty(),{v->browser.content.setMetadata(KvSensitiveInput.of(v))},label={Text("metadata（任意 JSON，序列化后最多 1024 bytes）")},
                    visualTransformation=if(p.revealed)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth().testTag("kv-metadata-input"))
                KvCheck("显式写回已输入／完整观察到的 metadata",form.includeMetadata,"kv-include-metadata"){v->browser.edit{it.copy(includeMetadata=v)}}
                Text("未提供 metadata 时不承诺保留旧 metadata。选择写回仅采用当前完整观察，无法锁定其他客户端。",style=MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={browser.requestDocument(KvDocumentKind.IMPORT_VALUE)},modifier=Modifier.testTag("kv-import-value")){Text("导入完整文件")}
                OutlinedButton(onClick={browser.requestDocument(KvDocumentKind.EXPORT_VALUE)},enabled=p.valueReady,modifier=Modifier.testTag("kv-export-value")){Text("导出完整明文")}
            }
            Text("文件导出将由你持有明文。仅在支持创建、删除和重命名的所选目录发布完整文件；不覆盖已有文件。",style=MaterialTheme.typography.bodySmall)
            Text("到期策略（PUT 会替换旧到期时间）")
            KvExpiryChoice.entries.forEach{choice->Row{
                RadioButton(form.expiry==choice,{browser.edit{it.copy(expiry=choice)}},modifier=Modifier.testTag("kv-expiry-${choice.name}"))
                Text(when(choice){KvExpiryChoice.NEVER->"不过期";KvExpiryChoice.ABSOLUTE_UTC->"绝对 UTC Unix 秒";KvExpiryChoice.TTL->"相对 TTL 秒（至少 60）"},Modifier.padding(top=12.dp))
            }}
            if(form.expiry!=KvExpiryChoice.NEVER)OutlinedTextField(form.expirySeconds,{v->browser.edit{it.copy(expirySeconds=v)}},label={Text("整数秒；绝对 UTC 时间至少为当前时间加 60 秒")},modifier=Modifier.fillMaxWidth().testTag("kv-expiry-input"))
            s.selectedKey?.expiration?.let{observed->OutlinedButton(onClick={browser.edit{it.copy(expiry=KvExpiryChoice.ABSOLUTE_UTC,expirySeconds=observed.toString())}},modifier=Modifier.testTag("kv-observed-expiry")){Text("采用本次观察的到期值：$observed")}}
        }
        if(editor==KvEditor.BULK_READ){
            OutlinedTextField(form.keysText,{v->browser.edit{it.copy(keysText=v)}},label={Text("每行一个精确键，最多 100；不去空格")},modifier=Modifier.fillMaxWidth().testTag("kv-bulk-read-keys"))
            FlowRow{KvBulkReadType.entries.forEach{type->Row{RadioButton(form.bulkType==type,{browser.edit{it.copy(bulkType=type)}},modifier=Modifier.testTag("kv-bulk-type-${type.name}"));Text(type.name,Modifier.padding(top=12.dp))}}}
            KvCheck("同时读取 metadata",form.withMetadata,"kv-bulk-with-metadata"){v->browser.edit{it.copy(withMetadata=v)}}
            Button(onClick=browser::bulkRead,modifier=Modifier.testTag("kv-bulk-read-send")){Text("读取并加密暂存")}
            if(p.bulkKind==KvBulkContentKind.RESPONSE){
                Text("完整响应：${p.bulkBytes} bytes，${p.bulkKeys.size} 个键；选择一个键查看有界 JSON，类型原样保留")
                p.bulkKeys.forEach{key->TextButton(onClick={browser.previewBulk(key)},modifier=Modifier.testTag("kv-preview-bulk-$key")){Text(key)}}
                KvCheck("显式显示所选 JSON",p.revealed,"kv-reveal-bulk"){browser.content.reveal(it)}
                browser.content.previewForDisplay()?.let{Text(it.raw(),Modifier.testTag("kv-bulk-preview"))}
                if(!p.previewReady)Text("尚未选择预览或该项超过 64KiB；可导出完整响应")
                OutlinedButton(onClick={browser.requestDocument(KvDocumentKind.EXPORT_BULK)},modifier=Modifier.testTag("kv-export-bulk")){Text("导出完整明文 JSON")}
            }
        }
        if(editor in setOf(KvEditor.BULK_WRITE,KvEditor.BULK_DELETE)){
            Text(if(editor==KvEditor.BULK_WRITE)"导入完整 JSON 数组，每项包含 key/value，可选择 base64、metadata、expiration 或 expiration_ttl；最多 10,000 键、整包小于 100MB。"else"导入完整精确键名 JSON 数组，最多 10,000 键。旧 DELETE /bulk 已映射到现行 POST /bulk/delete。")
            OutlinedButton(onClick={browser.requestDocument(if(editor==KvEditor.BULK_WRITE)KvDocumentKind.IMPORT_BULK_WRITE else KvDocumentKind.IMPORT_BULK_DELETE)},modifier=Modifier.testTag("kv-import-bulk")){Text("导入完整 JSON 文件")}
            Text("已验证：${p.bulkBytes?:0} bytes，${p.bulkKeys.size} 键；完整列表会在每阶段确认中分页核对")
        }
        if(editor in setOf(KvEditor.VALUE,KvEditor.BULK_WRITE))KvCheck("我明确接受可能覆盖同名值、metadata 和到期策略；没有只创建或 CAS 保证",form.overwrite,"kv-overwrite"){v->browser.edit{it.copy(overwrite=v)}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick=browser::cancel,enabled=!s.writing,modifier=Modifier.testTag("kv-cancel-editor")){Text("取消")}
            if(editor!=KvEditor.BULK_READ)Button(onClick=browser::prepare,enabled=!s.writing,modifier=Modifier.testTag("kv-prepare")){Text("准备核对")}
        }
    }
}

@Composable private fun KvCheck(label:String,checked:Boolean,tag:String,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth()){
    Checkbox(checked,onChange,modifier=Modifier.testTag(tag));Text(label,Modifier.weight(1f).padding(top=12.dp))
}}

@Composable
internal fun KvConfirmationDialog(p:KvConfirmationView,busy:Boolean,cancel:()->Unit,risk:()->Unit,typed:(String)->Unit,send:()->Unit){
    var text by remember(p.handle){mutableStateOf("")}
    var page by remember(p.handle){mutableStateOf(0)}
    val stage=p.handle.stage
    Dialog(cancel,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false,dismissOnBackPress=!busy)){
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f).widthIn(max=640.dp),shape=MaterialTheme.shapes.large){Column(Modifier.padding(16.dp)){
            Text(when(stage){KvConfirmationStage.RISK->"KV 风险核对";KvConfirmationStage.TYPED->"KV 身份核对";KvConfirmationStage.FINAL->"KV 最终确认"},style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("kv-confirm-title"))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("kv-confirm-scroll"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)){
                item{Text("账号：${p.account.accountId}");Text("连接：${p.account.connectionId}");Text("namespace ID：${p.namespaceId?:"待创建，尚无 ID"}");Text("标题：${p.namespaceTitle}");Text("动作：${kvActionLabel(p.action)}")}
                item{Text(p.warning);Text("到期策略：${p.expiration}");Text("metadata 策略：${p.metadata}");p.bytes?.let{Text("完整冻结正文：$it bytes")}}
                if(p.keys.isNotEmpty())item{KvKeyPage(p.keys,page,{page=it},"kv-confirm")}
                if(stage==KvConfirmationStage.TYPED)item{OutlinedTextField(text,{text=it},label={Text("手动精确输入 namespace ID")},modifier=Modifier.fillMaxWidth().testTag("kv-typed-id"))}
                if(stage==KvConfirmationStage.FINAL)item{Text("这是独立的最终确认。确认后只发送本次冻结请求；结果未知时不自动重试。",Modifier.testTag("kv-final-scope-end"))}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                OutlinedButton(cancel,enabled=!busy,modifier=Modifier.weight(1f).testTag("kv-cancel-confirm")){Text("取消")}
                Button(onClick={when(stage){KvConfirmationStage.RISK->risk();KvConfirmationStage.TYPED->typed(text);KvConfirmationStage.FINAL->send()}},enabled=!busy&&(stage!=KvConfirmationStage.TYPED||text==p.namespaceId),modifier=Modifier.weight(1f).testTag(when(stage){KvConfirmationStage.RISK->"kv-risk-next";KvConfirmationStage.TYPED->"kv-typed-next";KvConfirmationStage.FINAL->"kv-final-send"})){Text(if(stage==KvConfirmationStage.FINAL)"确认"else"继续")}
            }
        }}
    }
}
@Composable private fun KvKeyPage(keys:List<String>,page:Int,onPage:(Int)->Unit,tag:String){
    val pages=(keys.size+9)/10;val safe=page.coerceIn(0,(pages-1).coerceAtLeast(0));val first=safe*10;val end=minOf(first+10,keys.size)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("完整键范围：${if(keys.isEmpty())0 else first+1}–$end / ${keys.size}（第 ${safe+1}/$pages 页）",Modifier.testTag("$tag-key-page"))
        keys.subList(first,end).forEachIndexed{i,key->Text("${first+i+1}. $key",Modifier.testTag("$tag-key-${first+i}"))}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({onPage(safe-1)},enabled=safe>0,modifier=Modifier.testTag("$tag-keys-prev")){Text("上一页")}
            OutlinedButton({onPage(safe+1)},enabled=end<keys.size,modifier=Modifier.testTag("$tag-keys-next")){Text("下一页")}
        }
    }
}
@Composable private fun KvKeysDialog(title:String,keys:List<String>,close:()->Unit){
    var page by remember(keys){mutableStateOf(0)}
    Dialog(close,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),shape=MaterialTheme.shapes.large){Column(Modifier.padding(16.dp)){
        Text(title,style=MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f)){item{KvKeyPage(keys,page,{page=it},"kv-failed")}}
        OutlinedButton(close,modifier=Modifier.fillMaxWidth()){Text("关闭")}
    }}}
}
@Composable private fun KvManualCloseDialog(task:OperationTask,cancel:()->Unit,close:(String,Boolean)->Unit){
    var text by remember(task.id){mutableStateOf("")};var ack by remember(task.id){mutableStateOf(false)}
    Dialog(cancel,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),shape=MaterialTheme.shapes.large){Column(Modifier.padding(16.dp)){
        Text("人工处置",style=MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item{Text("账号：${(task.scope as? AccountScope)?.accountId}");Text("定位：${task.resourceRef?.externalId}");Text("动作：${kvActionLabel(task.actionKey)}");Text("解除只影响本地门禁，云端结果仍未知；不会重放请求或记为云端成功。")}
            item{OutlinedTextField(text,{text=it},label={Text(if(task.resourceRef?.kind==KvTaskTargets.ACCOUNT)"精确账号 ID"else"精确 namespace ID")},modifier=Modifier.fillMaxWidth().testTag("kv-close-id"))}
            item{KvCheck("我已自行在外部核对并承担继续写入的影响；解除本地门禁",ack,"kv-close-ack"){ack=it}}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(cancel,modifier=Modifier.weight(1f)){Text("取消")};Button({close(text,ack)},enabled=ack&&text==task.resourceRef?.externalId,modifier=Modifier.weight(1f).testTag("kv-close-confirm")){Text("解除")}}
    }}}
}
private fun kvActionLabel(action:String)=when(action.removePrefix("workers-kv-namespace-")){
    "create-a-namespace"->"创建 namespace";"rename-a-namespace"->"修改 namespace 标题";"remove-a-namespace"->"删除整个 namespace"
    "write-key-value-pair-with-metadata"->"单键覆盖写入";"delete-key-value-pair"->"单键删除";"write-multiple-key-value-pairs"->"完整批量覆盖写入";"delete-multiple-key-value-pairs"->"完整批量删除";else->"KV 操作"
}
