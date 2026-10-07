package com.flarego.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.flarego.core.updates.AppUpdate
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidUpdateInstaller(private val context: Context) {
    private val manager = context.packageManager
    @Suppress("DEPRECATION")
    private val flags
        get() =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo) =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return signatures.orEmpty().map { GitHubUpdateService.sha256(it.toByteArray()) }.toSet()
    }

    suspend fun prepare(path: String, update: AppUpdate): Intent =
        withContext(Dispatchers.IO) {
            val file = File(path).canonicalFile
            val root = File(context.cacheDir, "updates").canonicalFile
            require(file.parentFile == root && file.isFile && file.extension == "apk") { "安装包路径无效" }
            require(file.length() == update.apk.size) { "安装包大小不符" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            require(GitHubUpdateService.hex(digest.digest()) == update.sha256) { "安装包已发生变化，请重新下载" }
            val archive =
                requireNotNull(manager.getPackageArchiveInfo(file.path, flags)) { "安装包无效" }
            val installed = manager.getPackageInfo(context.packageName, flags)
            require(archive.packageName == context.packageName) { "安装包属于其他应用" }
            require(
                code(archive) == update.versionCode.toLong() &&
                    archive.versionName == update.versionName
            ) {
                "安装包与发布版本不匹配"
            }
            require(code(archive) > code(installed)) { "此版本已安装或低于当前版本" }
            require(
                requireNotNull(archive.applicationInfo).minSdkVersion <= Build.VERSION.SDK_INT
            ) {
                "此版本不支持当前 Android 系统"
            }
            val expected = signers(installed)
            require(expected.isNotEmpty() && signers(archive) == expected) {
                "签名与当前应用不同，请安装同一来源的版本"
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .also {
                    it.clipData = ClipData.newRawUri("FlareGo update", uri)
                }
        }
}
