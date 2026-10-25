package com.flarego.platform

import com.flarego.core.model.CloudException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Public application metadata, never a client secret. Only verified HTTPS App Links are supported. */
class OAuthConfig(val clientId: String, val redirectUri: String, val scopes: List<String>) {
    val configured: Boolean get() {
        val uri = redirectUri.toHttpUrlOrNull() ?: return false
        return clientId.isNotBlank() && clientId.none(Char::isWhitespace) && uri.scheme == "https" &&
            uri.username.isEmpty() && uri.password.isEmpty() && uri.query == null && uri.fragment == null &&
            uri.port == 443 && uri.encodedPath != "/" && !uri.host.endsWith(".invalid") &&
            scopes.isNotEmpty() && scopes.all { it.matches(Regex("[a-z][a-z0-9_.]*")) }
    }
    internal fun requireConfigured() {
        if (!configured) throw CloudException("此构建尚未配置 Cloudflare OAuth，请使用 API Token 或联系维护者")
    }
}

/** Deliberately not a data class: no secret-bearing generated toString(). */
class OAuthTokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) {
    override fun toString() = "OAuthTokens([redacted])"
}

/** In-memory, ten-minute, one-shot authorization. Process death requires starting authorization again. */
class OAuthFlow(
    private val config: OAuthConfig,
    private val endpoint: OAuthTokenEndpoint = OAuthTokenEndpoint(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val state: String, val verifier: String, val createdMs: Long)
    private var pending: Pending? = null
    @Synchronized fun begin(): String {
        config.requireConfigured()
        val random = SecureRandom()
        fun nonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        // Reopening the browser must not invalidate the page the user already has open.
        val request = pending?.takeIf { nowMs() - it.createdMs in 0..600_000L }
            ?: Pending(nonce(), nonce(), nowMs())
        pending = request
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(request.verifier.toByteArray(Charsets.US_ASCII)))
        return "https://dash.cloudflare.com/oauth2/auth".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", (config.scopes + "offline_access").distinct().joinToString(" "))
            .addQueryParameter("state", request.state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256").build().toString()
    }
    @Synchronized fun cancel() { pending = null }
    fun matchesCallback(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        val expected = config.redirectUri.toHttpUrlOrNull() ?: return false
        return url.scheme == expected.scheme && url.host == expected.host && url.port == expected.port &&
            url.encodedPath == expected.encodedPath && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }
    @Synchronized fun matchesPendingCallback(value: String): Boolean {
        if (!matchesCallback(value)) return false
        val request = pending ?: return false
        val states = value.toHttpUrlOrNull()?.queryParameterValues("state") ?: return false
        val state = states.singleOrNull() ?: return false
        return MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), request.state.toByteArray(Charsets.UTF_8))
    }
    suspend fun complete(callback: String): OAuthTokens {
        val request = synchronized(this) {
            // An unrelated/stale callback cannot consume a newer authorization.
            if (!matchesPendingCallback(callback)) throw oauthFailure()
            pending.also { pending = null }
        } ?: throw oauthFailure()
        val uri = callback.toHttpUrlOrNull() ?: throw oauthFailure()
        if (nowMs() - request.createdMs !in 0..600_000L) throw oauthFailure()
        val errors = uri.queryParameterValues("error")
        if (errors.isNotEmpty()) throw CloudException("Cloudflare 授权未完成或已取消，请重新授权")
        val code = uri.queryParameterValues("code").singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: throw oauthFailure()
        return endpoint.exchange(config, code, request.verifier, nowMs())
    }
}

/** Fixed Cloudflare endpoint, no redirects/retries, bounded responses, cancellation closes the call. */
class OAuthTokenEndpoint(private val client: OkHttpClient) {
    constructor() : this(cloudflareHttpClient().newBuilder().retryOnConnectionFailure(false).build())
    suspend fun exchange(config: OAuthConfig, code: String, verifier: String, nowMs: Long): OAuthTokens =
        request(config, FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", config.redirectUri).add("code_verifier", verifier), nowMs)
    suspend fun refresh(config: OAuthConfig, refresh: String, nowMs: Long): OAuthTokens =
        request(config, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh), nowMs)
    private suspend fun request(config: OAuthConfig, form: FormBody.Builder, nowMs: Long): OAuthTokens {
        config.requireConfigured()
        val request = Request.Builder().url("https://dash.cloudflare.com/oauth2/token")
            .header("Accept", "application/json").post(form.add("client_id", config.clientId).build()).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(oauthFailure())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val tokens = response.use {
                            if (!it.isSuccessful) throw oauthFailure()
                            val body = it.body ?: throw oauthFailure()
                            if (body.contentLength() > 65_536) throw oauthFailure()
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            val input = body.byteStream()
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0 || output.size() + count > 65_536) throw oauthFailure()
                                output.write(buffer, 0, count)
                            }
                            val bytes = output.toByteArray()
                            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw oauthFailure()
                            fun text(name: String): String? = (root[name] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                            val access = text("access_token")?.takeIf { s -> s.isNotBlank() && s.none(Char::isWhitespace) } ?: throw oauthFailure()
                            if (!text("token_type").equals("bearer", ignoreCase = true)) throw oauthFailure()
                            val seconds = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { n -> n in 1..31_536_000 } ?: throw oauthFailure()
                            val refresh = text("refresh_token")
                            if (refresh != null && (refresh.isBlank() || refresh.any(Char::isWhitespace))) throw oauthFailure()
                            OAuthTokens(access, refresh, Math.addExact(nowMs, seconds * 1000))
                        }
                        if (continuation.isActive) continuation.resume(tokens)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(oauthFailure())
                    }
                }
            })
        }
    }
}

/** Store callbacks must be backed by TokenVault in production; legacy API tokens retain their format. */
class OAuthCredentials(
    private val config: OAuthConfig,
    private val endpoint: OAuthTokenEndpoint,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val delete: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }
    suspend fun putApiToken(id: String, token: String) = lock(id).withLock { write(id, token) }
    suspend fun putOAuth(id: String, tokens: OAuthTokens) = lock(id).withLock { write(id, encode(tokens)) }
    suspend fun remove(id: String) = lock(id).withLock { delete(id) }
    suspend fun accessToken(id: String): String? = lock(id).withLock {
        val raw = read(id) ?: return@withLock null
        if (!raw.startsWith(PREFIX)) return@withLock raw
        val root = try { Json.parseToJsonElement(raw.removePrefix(PREFIX)).jsonObject } catch (_: Exception) { throw oauthFailure() }
        // Prevent a different build/client from silently refreshing someone else's grant.
        if (root["client_id"]?.jsonPrimitive?.content != config.clientId || root["redirect_uri"]?.jsonPrimitive?.content != config.redirectUri) throw oauthFailure()
        val access = root["access_token"]?.jsonPrimitive?.content ?: throw oauthFailure()
        val expiry = root["expires_at"]?.jsonPrimitive?.longOrNull ?: throw oauthFailure()
        if (expiry > nowMs() + 30_000) return@withLock access
        val refresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull ?: throw oauthFailure()
        val next = endpoint.refresh(config, refresh, nowMs())
        val rotated = OAuthTokens(next.accessToken, next.refreshToken ?: refresh, next.expiresAtMs)
        write(id, encode(rotated))
        rotated.accessToken
    }
    private fun encode(tokens: OAuthTokens): String = PREFIX + buildJsonObject {
        put("client_id", config.clientId); put("redirect_uri", config.redirectUri)
        put("access_token", tokens.accessToken); put("refresh_token", tokens.refreshToken?.let(::JsonPrimitive) ?: JsonNull)
        put("expires_at", tokens.expiresAtMs)
    }.toString()
    companion object { private const val PREFIX = "flarego-oauth-v1:" }
}

private fun oauthFailure() = CloudException("Cloudflare 授权无效、已过期或续期失败，请重新连接；不会自动重试云端操作")
