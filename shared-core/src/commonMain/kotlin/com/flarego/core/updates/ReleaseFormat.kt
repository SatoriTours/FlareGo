package com.flarego.core.updates

import kotlinx.serialization.json.*

/** Fail closed on incomplete, conflicting, or mixed-generation release files. */
object ReleaseFormat {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val version = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long

    fun release(body: String, channel: UpdateChannel): ReleaseDescriptor {
        val value = json.parseToJsonElement(body).jsonObject
        require(!value.getValue("draft").jsonPrimitive.boolean) { "发布尚未完成" }
        val prerelease = value.getValue("prerelease").jsonPrimitive.boolean
        val tag = value.text("tag_name")
        require(
            if (channel == UpdateChannel.RELEASE)
                !prerelease && tag.startsWith("v") && version.matches(tag.drop(1))
            else prerelease && tag == "latest"
        ) {
            "发布通道不匹配"
        }
        val assets =
            value.getValue("assets").jsonArray.map {
                val asset = it.jsonObject
                require(asset.text("state") == "uploaded") { "发布附件尚未上传完成" }
                ReleaseAsset(asset.number("id"), asset.text("name"), asset.number("size")).also {
                    file ->
                    require(file.id > 0 && file.size > 0) { "发布附件信息无效" }
                }
            }
        require(assets.map { it.name }.distinct().size == assets.size) { "发布附件名称重复" }
        val result =
            ReleaseDescriptor(tag, value["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), assets)
        require(result.asset("build-metadata.json").size <= 1024 * 1024)
        require(result.asset("SHA256SUMS").size <= 1024 * 1024)
        val apkName =
            if (channel == UpdateChannel.RELEASE) "flarego-${tag.drop(1)}.apk"
            else "flarego-latest.apk"
        require(result.asset(apkName).size <= MAX_APK_BYTES) { "安装包过大" }
        return result
    }

    fun checksum(body: String, filename: String): String {
        val line = Regex("^([a-fA-F0-9]{64}) [ *]([^\\r\\n]+)$")
        val entries =
            body
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { value ->
                    val match = requireNotNull(line.matchEntire(value)) { "校验文件格式错误" }
                    match.groupValues[2] to match.groupValues[1].lowercase()
                }
                .toList()
        require(entries.map { it.first }.distinct().size == entries.size) { "校验文件包含重复名称" }
        return entries.single { it.first == filename }.second
    }

    fun update(
        release: ReleaseDescriptor,
        metadata: String,
        sums: String,
        channel: UpdateChannel,
    ): AppUpdate {
        val value = json.parseToJsonElement(metadata).jsonObject
        require(value.text("channel") == channel.key && value.text("release_tag") == release.tag) {
            "更新元数据通道或 Tag 不匹配"
        }
        val name = value.text("version_name")
        require(
            if (channel == UpdateChannel.RELEASE) name == release.tag.drop(1)
            else name.matches(Regex("0\\.1\\.0-dev\\.[1-9][0-9]*"))
        ) {
            "版本名称不匹配"
        }
        val code = value.number("version_code")
        require(code in 1..Int.MAX_VALUE.toLong()) { "版本号无效" }
        val sha = value.text("commit_sha")
        require(sha.matches(Regex("[a-f0-9]{40}"))) { "提交标识无效" }
        val apkName =
            if (channel == UpdateChannel.RELEASE) "flarego-$name.apk" else "flarego-latest.apk"
        require(value.text("apk_name") == apkName) { "安装包名称不匹配" }
        // Both hashes are mandatory; the transport verifies the metadata bytes before parsing.
        checksum(sums, "build-metadata.json")
        return AppUpdate(
            channel,
            name,
            code.toInt(),
            sha,
            release.notes,
            release.asset(apkName),
            checksum(sums, apkName),
        )
    }
}
