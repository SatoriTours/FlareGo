package com.flarego.core.updates

enum class UpdateChannel(val key: String, val label: String, val endpoint: String) {
    RELEASE("release", "正式版本", "latest"),
    SNAPSHOT("snapshot", "最新提交", "tags/latest"),
}

data class ReleaseAsset(val id: Long, val name: String, val size: Long)

data class ReleaseDescriptor(val tag: String, val notes: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String): ReleaseAsset = assets.single { it.name == name }
}

data class AppUpdate(
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Int,
    val commitSha: String,
    val notes: String,
    val apk: ReleaseAsset,
    val sha256: String,
)

data class UpdatePreferences(
    val channel: UpdateChannel = UpdateChannel.RELEASE,
    val automatic: Boolean = true,
    val lastAttemptMillis: Long = 0,
)

data class UpdateState(
    val installedName: String,
    val installedCode: Int,
    val preferences: UpdatePreferences,
    val busy: Boolean = false,
    val progress: Float? = null,
    val available: AppUpdate? = null,
    val downloadedPath: String? = null,
    val installRequested: Boolean = false,
    val message: String = "可检查并安装 GitHub 发布的更新",
)

interface UpdateStore {
    fun read(): UpdatePreferences

    fun write(preferences: UpdatePreferences)
}

interface UpdateService {
    suspend fun check(channel: UpdateChannel): AppUpdate?

    suspend fun download(update: AppUpdate, progress: (Float) -> Unit): String
}
