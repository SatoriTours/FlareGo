package com.flarego.platform

import com.flarego.core.cloudflare.HttpRequest
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

internal fun cloudflareHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

/** A one-shot body also blocks HTTP follow-ups, including 503 Retry-After: 0. */
private class SingleWriteBody(private val bytes: ByteArray) : RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun contentLength() = bytes.size.toLong()

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

internal fun cloudflareRequest(token: String, request: HttpRequest): Request {
    require(request.path.startsWith("/") && !request.path.startsWith("//"))
    val body =
        if (request.method == "GET") null
        else SingleWriteBody((request.body ?: "").toByteArray(Charsets.UTF_8))
    return Request.Builder()
        .url("https://api.cloudflare.com/client/v4${request.path}")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .method(request.method, body)
        .build()
}
