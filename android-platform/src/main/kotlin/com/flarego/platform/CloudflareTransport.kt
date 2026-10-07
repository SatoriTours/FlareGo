package com.flarego.platform

import com.flarego.core.cloudflare.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudflareTransport(private val token: String) : HttpTransport {
    private val client = cloudflareHttpClient()

    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            client.newCall(cloudflareRequest(token, request)).execute().use { response ->
                val body =
                    response.body?.byteStream()?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            require(output.size() + size <= 4 * 1024 * 1024)
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    } ?: byteArrayOf()
                HttpResponse(response.code, body.toString(Charsets.UTF_8))
            }
        }
}
