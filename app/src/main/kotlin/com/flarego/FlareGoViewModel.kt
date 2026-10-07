package com.flarego

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flarego.core.application.AppController
import com.flarego.core.cloudflare.CloudflareSession
import com.flarego.core.demo.*
import com.flarego.core.model.*
import com.flarego.core.ports.SessionFactory
import com.flarego.core.updates.*
import com.flarego.platform.*
import com.flarego.ui.ConnectionDraft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FlareGoViewModel(application: Application) : AndroidViewModel(application) {
    val updater =
        UpdateController(
            viewModelScope,
            GitHubUpdateService(File(application.cacheDir, "updates")),
            AndroidUpdateStore(application),
            installedCode(application),
            application.packageManager
                .getPackageInfo(application.packageName, 0)
                .versionName
                .orEmpty(),
            System::currentTimeMillis,
        )
    var permissionRequestPending = false
    var pendingInstall: Pair<String, AppUpdate>? = null

    private fun installedCode(application: Application): Int {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else {
            @Suppress("DEPRECATION") info.versionCode
        }
    }

    private val store = AndroidLocalStore(application)
    private val vault = TokenVault(application)
    val connectionDraft = ConnectionDraft()
    val controller =
        AppController(
            viewModelScope,
            SessionFactory { connection ->
                if (connection.demo) DemoSession(connection)
                else {
                    val token =
                        try {
                            withContext(Dispatchers.IO) { vault.get(connection.id) }
                        } catch (_: Exception) {
                            null
                        }
                    if (token == null) throw CloudException("本机凭据无法解密，请断开后重新连接账号")
                    CloudflareSession(connection, CloudflareTransport(token))
                }
            },
            store,
            demoConnections + store.connections(),
        )

    suspend fun connect(name: String, accountId: String, token: String) {
        val cleanAccount = accountId.trim().lowercase()
        val cleanToken = token.trim()
        if (!cleanAccount.matches(Regex("[a-f0-9]{32}")))
            throw CloudException("Account ID 必须为 32 位十六进制字符")
        if (
            name.trim().length !in 1..80 ||
                cleanToken.isBlank() ||
                cleanToken.any(Char::isWhitespace)
        )
            throw CloudException("请检查账号名称和 Token；Token 不能包含空格或换行")
        val existing = store.connections().find { it.accountId == cleanAccount }
        val connection =
            Connection(existing?.id ?: UUID.randomUUID().toString(), name.trim(), cleanAccount)
        CloudflareSession(connection, CloudflareTransport(cleanToken)).verifyAccount()
        withContext(Dispatchers.IO) { vault.put(connection.id, cleanToken) }
        store.putConnection(connection)
        controller.invalidateSession(connection.id)
        controller.reloadConnections(demoConnections + store.connections())
        controller.selectConnection(connection)
    }

    override fun onCleared() {
        connectionDraft.clear()
        super.onCleared()
    }

    fun disconnect(connection: Connection) {
        vault.remove(connection.id)
        store.removeConnection(connection.id)
        if (controller.state.value.selected.id == connection.id)
            controller.selectConnection(demoConnections.first())
        controller.invalidateSession(connection.id)
        controller.reloadConnections(demoConnections + store.connections())
    }
}
