package com.flarego.core.updates

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel-owned state survives Activity recreation; automatic attempts persist across launches.
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val service: UpdateService,
    private val store: UpdateStore,
    installedCode: Int,
    installedName: String,
    private val now: () -> Long,
    autoStart: Boolean = true,
) {
    private val mutable = MutableStateFlow(UpdateState(installedName, installedCode, store.read()))
    val state: StateFlow<UpdateState> = mutable.asStateFlow()

    init {
        if (autoStart) check(automatic = true)
    }

    fun check(automatic: Boolean = false) {
        val current = mutable.value
        if (current.busy) return
        val prefs = current.preferences
        if (
            automatic &&
                (!prefs.automatic ||
                    (prefs.lastAttemptMillis > 0 &&
                        now() - prefs.lastAttemptMillis in 0 until 6 * 60 * 60 * 1000L))
        )
            return
        if (automatic) save(prefs.copy(lastAttemptMillis = now()))
        mutable.update { it.copy(busy = true, message = "正在检查更新…") }
        scope.launch {
            try {
                val update =
                    service.check(prefs.channel)?.takeIf { it.versionCode > current.installedCode }
                mutable.update {
                    it.copy(
                        available = update,
                        downloadedPath = null,
                        installRequested = false,
                        message =
                            if (update == null) "当前已是此通道的最新版本"
                            else "发现 ${update.versionName}，可下载更新",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(message = "暂时无法检查更新，请稍后重试") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun save(prefs: UpdatePreferences) {
        store.write(prefs)
        mutable.update { it.copy(preferences = prefs) }
    }

    fun setAutomatic(enabled: Boolean) {
        if (!mutable.value.busy) save(mutable.value.preferences.copy(automatic = enabled))
    }

    fun setChannel(channel: UpdateChannel) {
        if (mutable.value.busy || mutable.value.preferences.channel == channel) return
        save(mutable.value.preferences.copy(channel = channel, lastAttemptMillis = 0))
        mutable.update {
            it.copy(
                available = null,
                downloadedPath = null,
                installRequested = false,
                progress = null,
            )
        }
        check()
    }

    fun download() {
        val current = mutable.value
        val update = current.available ?: return
        if (current.busy || update.versionCode <= current.installedCode) return
        mutable.update { it.copy(busy = true, progress = 0f, message = "正在下载并校验安装包…") }
        scope.launch {
            try {
                val path =
                    service.download(update) { fraction ->
                        mutable.update { it.copy(progress = fraction.coerceIn(0f, 1f)) }
                    }
                mutable.update {
                    it.copy(
                        downloadedPath = path,
                        installRequested = true,
                        message = "下载完成，等待系统安装确认",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        downloadedPath = null,
                        installRequested = false,
                        message = "下载或完整性校验失败，请重试",
                    )
                }
            } finally {
                mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    fun takeInstallRequest(): Pair<String, AppUpdate>? {
        val current = mutable.value
        if (current.busy || !current.installRequested) return null
        mutable.update { it.copy(installRequested = false) }
        return current.downloadedPath?.let { path -> current.available?.let { path to it } }
    }

    fun installationMessage(message: String) {
        mutable.update { it.copy(message = message) }
    }
}
