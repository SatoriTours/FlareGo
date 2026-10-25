package com.flarego

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.flarego.ui.r2.SecurePurpose
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

    // Fixed unconditional registration order; the original APK launcher keeps its position.
    private val importDocument=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onDocumentResult(DnsDocumentKind.IMPORT,it.resultCode,it.data)
    }
    private val exportDocument=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onDocumentResult(DnsDocumentKind.EXPORT,it.resultCode,it.data)
    }
    // Append launchers to preserve the existing ActivityResult registration order.
    private val r2Upload = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onR2DocumentResult(com.flarego.ui.r2.R2DocumentKind.UPLOAD, it.resultCode, it.data)
    }
    private val r2Download = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onR2DocumentResult(com.flarego.ui.r2.R2DocumentKind.DOWNLOAD, it.resultCode, it.data)
    }
    /**
     * The one `FLAG_SECURE` owner of this Activity's real window.
     *
     * The pre-existing connect/token dialog and the batch B3 Sippy credential editor are *purposes*
     * of this single owner, so neither can clear the other's flag and neither clears a flag it does
     * not own. It is created per Activity and bound to this window only.
     */
    private lateinit var secureWindow: AndroidSecureWindow
    override fun onResume() { super.onResume();model.onManagementResume() }

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

    private fun consumeOAuthIntent(incoming: Intent?) {
        val callback = incoming?.dataString ?: return
        if (model.acceptOAuthCallback(callback)) {
            // Do not retain authorization codes in the Activity intent across recreation.
            incoming.data = null
            intent = Intent(incoming).setData(null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeOAuthIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Bound before any content is composed, so the first frame that could show a secret already
        // sees a real protection owner.
        secureWindow = AndroidSecureWindow(window)
        model.attachSecureWindow(secureWindow)
        consumeOAuthIntent(intent)
        setContent {
            val current = model
            val state by current.controller.state.collectAsState()
            val panelContext by current.panelContexts.collectAsState()
            LaunchedEffect(current) {
                current.documentLaunches.collect { event ->
                    if(event==null) return@collect
                    val launch=current.takeDocumentLaunch(event.requestId) ?: return@collect
                    when(launch.kind) {
                        DnsDocumentKind.IMPORT -> importDocument.launch(launch.intent)
                        DnsDocumentKind.EXPORT -> exportDocument.launch(launch.intent)
                    }
                }
            }
            LaunchedEffect(current) {
                current.r2Browser.documentRequests.collect { event ->
                    if (event == null) return@collect
                    val launch = current.takeR2DocumentLaunch(event.id) ?: return@collect
                    try {
                        when (launch.first) {
                            com.flarego.ui.r2.R2DocumentKind.UPLOAD -> r2Upload.launch(launch.second)
                            com.flarego.ui.r2.R2DocumentKind.DOWNLOAD -> r2Download.launch(launch.second)
                        }
                    } catch (_: Exception) { current.onR2DocumentLaunchFailed(launch.first) }
                }
            }
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
                    val account = state.selected?.takeUnless { it.demo }?.accountId
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
                // Pre-existing purpose (the connect/token dialog). It shares the one owner with the
                // Sippy credential editor, so this callback can no longer clear the other purpose's
                // flag and releasing it never clears a FLAG_SECURE this owner did not set.
                sensitiveScreen = { sensitive ->
                    secureWindow.setPurpose(SecurePurpose.CONNECTION_TOKEN, sensitive)
                },
                updater = current.updater,
                connectionDraft = current.connectionDraft,
                discoverAccounts = current::discoverAccounts,
                verifyToken = current::verifyDraftToken,
                reconcileTask = current::reconcileTask,
                management = current.management,
                panelPorts = current.panelPorts,
                panelContext = panelContext,
                r2Browser = current.r2Browser,
                oauthConnection = current.oauthConnection,
            )
        }
    }

    override fun onDestroy() {
        // Drop readiness and the private Sippy material of *this* generation; a new Activity's
        // window is untouched by this stale owner.
        model.onSecureWindowClosed(secureWindow)
        // Teardown cancels eligibility but never clears the physical flag: the dying window may still
        // show its last frame, and the layer disappears with the window itself.
        secureWindow.closeForTeardown()
        super.onDestroy()
    }
}
