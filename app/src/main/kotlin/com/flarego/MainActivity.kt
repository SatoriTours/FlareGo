package com.flarego

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.flarego.core.model.Page
import com.flarego.platform.AndroidUpdateInstaller
import com.flarego.ui.FlareGoApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: FlareGoViewModel by viewModels()
    private val installPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.permissionRequestPending = false
            if (packageManager.canRequestPackageInstalls())
                lifecycleScope.launch { installOrAuthorize(model) }
            else {
                model.pendingInstall = null
                model.updater.installationMessage("尚未允许安装更新，可点击下载并安装重试")
            }
        }

    private suspend fun installOrAuthorize(current: FlareGoViewModel) {
        val request = current.pendingInstall ?: return
        try {
            // Preserve the pending request across Activity recreation and revalidate after
            // settings.
            val intent = AndroidUpdateInstaller(this).prepare(request.first, request.second)
            // Settings delivery and recreation recovery may both await verification.
            // Only the request still owned by the ViewModel may launch an installer.
            if (current.pendingInstall !== request) return
            if (packageManager.canRequestPackageInstalls()) {
                startActivity(intent)
                current.pendingInstall = null
                current.updater.installationMessage("请在系统安装器中确认更新")
            } else if (!current.permissionRequestPending) {
                current.updater.installationMessage("请允许 FlareGo 安装更新，然后返回应用确认")
                current.permissionRequestPending = true
                installPermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            current.pendingInstall = null
            current.permissionRequestPending = false
            current.updater.installationMessage(error.message?.take(180) ?: "无法准备更新安装")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val current = model
            val state by current.controller.state.collectAsState()
            LaunchedEffect(current) {
                if (current.pendingInstall != null && !current.permissionRequestPending)
                    installOrAuthorize(current)
                // collect, rather than collectLatest/a state-keyed effect: consuming an event must
                // not cancel the asynchronous APK verification that follows it.
                current.updater.state.collect {
                    val request = current.updater.takeInstallRequest() ?: return@collect
                    current.pendingInstall = request
                    installOrAuthorize(current)
                }
            }
            BackHandler(
                enabled = state.page in setOf(Page.DNS, Page.BUY, Page.RESOURCE, Page.JOBS)
            ) {
                current.controller.back()
            }
            FlareGoApp(
                current.controller,
                current::connect,
                current::disconnect,
                { page ->
                    val account = state.selected.accountId.takeIf { !state.selected.demo }
                    val path =
                        when (page) {
                            Page.BILLING -> if (account != null) "/$account/billing" else "/"
                            Page.BUY -> if (account != null) "/$account/domains/register" else "/"
                            Page.RESOURCES,
                            Page.RESOURCE ->
                                if (account != null) "/$account/workers-and-pages" else "/"
                            else -> "/"
                        }
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com$path"))
                    )
                },
                sensitiveScreen = { sensitive ->
                    if (sensitive) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                },
                updater = current.updater,
            )
        }
    }
}
