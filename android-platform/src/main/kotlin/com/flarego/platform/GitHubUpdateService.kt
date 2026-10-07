package com.flarego.platform

import com.flarego.core.updates.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** No cloud credentials or GitHub token are sent by the public-repository updater. */
class GitHubHttp(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
) {
    companion object {
        private val hosts =
            setOf(
                "api.github.com",
                "github.com",
                "release-assets.githubusercontent.com",
                "objects.githubusercontent.com",
            )

        fun trusted(url: String): Boolean {
            val value = url.toHttpUrlOrNull() ?: return false
            return value.scheme == "https" &&
                value.host in hosts &&
                value.port == 443 &&
                value.username.isEmpty() &&
                value.password.isEmpty()
        }
    }

    fun open(initial: String, binary: Boolean = true): Response {
        var url = initial
        repeat(7) { index ->
            require(trusted(url)) { "更新下载地址不受信任" }
            val request =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "FlareGo-Android")
                    .header(
                        "Accept",
                        if (binary) "application/octet-stream" else "application/vnd.github+json",
                    )
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
            val response = client.newCall(request).execute()
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            val resolved = location?.let { response.request.url.resolve(it)?.toString() }
            response.close()
            require(index < 6 && resolved != null && trusted(resolved)) { "更新重定向地址无效" }
            url = resolved
        }
        error("更新重定向次数过多")
    }
}

class GitHubUpdateService(
    private val directory: File,
    private val http: GitHubHttp = GitHubHttp(),
) : UpdateService {
    private val base = "https://api.github.com/repos/SatoriTours/FlareGo"

    override suspend fun check(channel: UpdateChannel): AppUpdate? =
        withContext(Dispatchers.IO) {
            val body =
                http.open("$base/releases/${channel.endpoint}", false).use { response ->
                    if (response.code == 404) return@withContext null
                    require(response.isSuccessful) { "GitHub 暂时无法读取发布信息" }
                    readBounded(response)
                }
            val release = ReleaseFormat.release(body.toString(Charsets.UTF_8), channel)
            val sums = readAsset(release.asset("SHA256SUMS")).toString(Charsets.UTF_8)
            val metadata = readAsset(release.asset("build-metadata.json"))
            require(sha256(metadata) == ReleaseFormat.checksum(sums, "build-metadata.json")) {
                "更新元数据校验失败"
            }
            ReleaseFormat.update(release, metadata.toString(Charsets.UTF_8), sums, channel)
        }

    private fun readAsset(asset: ReleaseAsset): ByteArray =
        http.open("$base/releases/assets/${asset.id}").use { response ->
            require(response.isSuccessful)
            readBounded(response).also { require(it.size.toLong() == asset.size) { "发布附件大小不符" } }
        }

    private fun readBounded(response: Response): ByteArray {
        val body = requireNotNull(response.body)
        require(body.contentLength() <= 1024 * 1024)
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                require(output.size() + read <= 1024 * 1024) { "发布信息过大" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    override suspend fun download(update: AppUpdate, progress: (Float) -> Unit): String =
        withContext(Dispatchers.IO) {
            require(update.apk.size in 1..ReleaseFormat.MAX_APK_BYTES)
            directory.mkdirs()
            require(directory.isDirectory)
            val target =
                File(directory, "flarego-${update.versionCode}-${update.sha256.take(12)}.apk")
            val partial = File(directory, target.name + ".part")
            try {
                http.open("$base/releases/assets/${update.apk.id}").use { response ->
                    require(response.isSuccessful) { "安装包下载失败" }
                    val body = requireNotNull(response.body)
                    require(
                        body.contentLength() == -1L || body.contentLength() == update.apk.size
                    ) {
                        "安装包大小不符"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                total += read
                                require(total <= update.apk.size) { "安装包超出预期大小" }
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                progress(total.toFloat() / update.apk.size)
                            }
                        }
                    }
                    require(total == update.apk.size && hex(digest.digest()) == update.sha256) {
                        "安装包完整性校验失败"
                    }
                }
                // A rename inside private cache commits only a completely verified download.
                if (target.exists()) target.delete()
                require(partial.renameTo(target)) { "无法保存安装包" }
                directory
                    .listFiles()
                    ?.filter {
                        it != target && (it.name.endsWith(".apk") || it.name.endsWith(".part"))
                    }
                    ?.forEach { it.delete() }
                target.absolutePath
            } finally {
                partial.delete()
            }
        }

    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
