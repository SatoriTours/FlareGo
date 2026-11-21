package com.flarego.ui.workers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.flarego.core.application.workers.*
import com.flarego.core.model.CloudFailureCode
import com.flarego.ui.Muted
import com.flarego.ui.SectionCard
import com.flarego.ui.observationTime

internal fun WorkerReleaseState.toolbarTitle(compactWorkerName: Boolean): String = when (route) {
    WorkerRoute.Overview -> if (compactWorkerName) "Worker 详情" else worker.name
    WorkerRoute.History -> "部署历史"
    WorkerRoute.Versions -> "版本列表"
    is WorkerRoute.Deployment -> "部署快照"
    is WorkerRoute.Version -> "版本详情"
}

@Composable
internal fun WorkerReleaseScreen(
    state: WorkerReleaseState, browser: WorkerReleaseBrowser, accountName: String, compactWorkerName: Boolean,
) {
    val route = state.route
    val active = when (route) {
        WorkerRoute.Overview, WorkerRoute.History -> state.deployments
        WorkerRoute.Versions -> state.versions
        is WorkerRoute.Deployment -> state.deployment
        is WorkerRoute.Version -> state.version
    }
    val lastKnown = (state.deployments.error != null && !state.deployments.appendFailed) || state.deployments.loading
    val openVersion: (String) -> Unit = { browser.go(WorkerRoute.Version(it)) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("worker-screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("context") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (route == WorkerRoute.Overview && compactWorkerName) Heading(state.worker.name)
                        Muted(if (route == WorkerRoute.Overview) "Workers · $accountName · 只读"
                            else "${state.worker.name} · $accountName")
                        if (state.worker.legacy) Muted("旧脚本目录")
                        active.observedAtMs?.let { time ->
                            val label = if (active.loading) "正在读取 · 上次更新" else "更新于"
                            Muted("$label ${observationTime(time)}")
                        }
                    }
                    if (active.error == null || active.appendFailed) {
                        IconButton(onClick = { browser.load() }, enabled = !active.loading, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
                if (active.loading) {
                    if (active.value == null) Muted("正在读取 Cloudflare…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (active.error != null && !active.appendFailed) item("error") { ReleaseError(active) { browser.load() } }

        when (route) {
            WorkerRoute.Overview -> {
                item("live") {
                    SectionCard(Modifier.fillMaxWidth().testTag("worker-current")) {
                        Heading(if (lastKnown && state.deployments.value != null) "上次确认的线上流量" else "线上流量")
                        when {
                            state.current != null -> {
                                val deployment = state.current ?: return@SectionCard
                                Muted("生产 · 分配比例，非请求量")
                                AllocationList(deployment, state.versions.value?.items.orEmpty(), openVersion)
                                Muted("部署于 ${deployment.createdOn}")
                            }
                            state.deployments.value != null && lastKnown -> Text("上次读取时尚无部署")
                            state.deployments.value != null -> Text("尚无部署")
                            !active.loading -> Muted("未能确认当前分配")
                        }
                    }
                }
                state.current?.let { deployment ->
                    item("recent") {
                        SectionCard {
                            Heading("最近部署")
                            ReleaseRow("部署 ${deployment.id.take(8)}", deployment.createdOn, "worker-recent") {
                                browser.go(WorkerRoute.Deployment(deployment.id))
                            }
                        }
                    }
                }
                item("links") {
                    SectionCard {
                        ReleaseRow("全部部署", null, "worker-history") { browser.go(WorkerRoute.History) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ReleaseRow("版本列表", null, "worker-versions") { browser.go(WorkerRoute.Versions) }
                    }
                }
                item("worker-id") { FullId("Worker ID", state.worker.id) }
            }
            WorkerRoute.History -> {
                val rows = state.deployments.value
                if (rows != null && rows.items.isEmpty()) item("empty") {
                    Text(if (lastKnown) "上次读取时尚无部署" else "尚无部署")
                }
                items(rows?.items.orEmpty(), key = { it.id }) { deployment ->
                    val position = if (deployment.id != state.current?.id) "历史记录"
                        else if (lastKnown) "上次确认的当前部署" else "当前部署"
                    ReleaseRow("部署 ${deployment.id.take(8)}",
                        "$position · ${deployment.allocations.size} 个版本\n${deployment.createdOn}",
                        "worker-deployment-${deployment.id}") { browser.go(WorkerRoute.Deployment(deployment.id)) }
                }
                pagination(state.deployments, browser)
            }
            is WorkerRoute.Deployment -> state.deployment.value?.let { release ->
                item("historic") {
                    SectionCard(Modifier.fillMaxWidth().testTag("worker-historic-allocation")) {
                        Heading("此次分配")
                        Muted("历史快照，不代表当前线上")
                        AllocationList(release, state.versions.value?.items.orEmpty(), openVersion)
                        Muted("部署于 ${release.createdOn}")
                    }
                }
                item("metadata") {
                    SectionCard {
                        Heading("部署记录")
                        release.source?.let { Text("部署来源 · $it") }
                        release.message?.let { Text("部署备注 · $it") }
                        FullId("部署 ID", release.id)
                    }
                }
            }
            WorkerRoute.Versions -> {
                val rows = state.versions.value
                item("order") { Muted("按创建时间 · 最新创建的版本不一定已部署") }
                if (rows != null && rows.items.isEmpty()) item("empty") { Text("暂无可见版本") }
                items(rows?.items.orEmpty(), key = { it.id }) { version ->
                    ReleaseRow(version.title(), listOfNotNull(state.allocationOf(version.id), version.createdOn).joinToString("\n"),
                        "worker-version-${version.id}") { openVersion(version.id) }
                }
                pagination(state.versions, browser)
            }
            is WorkerRoute.Version -> state.version.value?.let { version ->
                item("version-information") {
                    SectionCard(Modifier.fillMaxWidth().testTag("worker-version-allocation")) {
                        Heading(version.title())
                        Text(state.allocationOf(version.id))
                        state.deployments.observedAtMs?.let { Muted("分配核对于 ${observationTime(it)}") }
                    }
                }
                item("metadata") {
                    SectionCard {
                        Heading("版本信息")
                        version.createdOn?.let { Text("创建时间 · $it") }
                        version.source?.let { Text("创建来源 · $it") }
                        version.compatibilityDate?.let { Text("兼容日期 · $it") }
                        FullId("版本 ID", version.id)
                    }
                }
                item("overview") {
                    ReleaseRow("查看当前部署", null, "worker-return-current") { browser.go(WorkerRoute.Overview) }
                }
            }
        }
    }
}

private fun WorkerReleaseState.allocationOf(versionId: String): String {
    if (deployments.value == null) return "当前流量分配未确认"
    val share = current?.allocations?.find { it.versionId == versionId }?.percentage
    val label = if (share == null) "未分配流量" else "$share% 线上流量"
    return if (!deployments.appendFailed && deployments.error != null) "上次确认 · $label" else label
}

private fun WorkerVersion.title(): String = "版本 ${number ?: id.take(8)}"

private fun <T> androidx.compose.foundation.lazy.LazyListScope.pagination(
    read: ReleaseRead<ReleasePage<T>>, browser: WorkerReleaseBrowser,
) {
    if (read.appendFailed) item("paging-error") { ReleaseError(read) { browser.load(more = true) } }
    else if (read.value?.next != null) item("more") {
        OutlinedButton(onClick = { browser.load(more = true) }, enabled = !read.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多") }
    }
}

@Composable
private fun ReleaseError(read: ReleaseRead<*>, retry: () -> Unit) {
    val error = read.error ?: return
    val message = when (error.code) {
        CloudFailureCode.PERMISSION_REQUIRED -> "Cloudflare 拒绝读取（403）。请核对现有 Token 的 Workers Scripts Read 和账号范围。"
        CloudFailureCode.RATE_LIMITED -> "Cloudflare 限流（429）。请等待一段时间后手动重试。"
        CloudFailureCode.NOT_FOUND -> "Cloudflare 未找到请求的目标（404）。可返回目录刷新。"
        else -> error.publicMessage
    }
    SectionCard {
        Text(if (read.appendFailed) "加载下一页失败" else "读取失败",
            style = MaterialTheme.typography.titleSmall)
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (read.value != null) Muted("保留上次读取的数据")
        TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ReleaseRow(title: String, detail: String?, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                detail?.let { Muted(it) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FullId(label: String, id: String) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "收起完整 $label" else "$label · ${id.take(8)} · 展开")
        }
        if (expanded) SelectionContainer { Text(id, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun AllocationList(release: WorkerDeployment, versions: List<WorkerVersion>, open: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        release.allocations.forEachIndexed { i, entry ->
            Box(Modifier.weight(entry.percentage.toFloat()).fillMaxHeight().background(colors[i % colors.size]))
        }
    }
    release.allocations.forEach { entry ->
        val title = versions.find { it.id == entry.versionId }?.title() ?: "版本 ${entry.versionId.take(8)}"
        ReleaseRow("$title · ${entry.percentage}%", null, "worker-allocation-${entry.versionId}") { open(entry.versionId) }
    }
}
