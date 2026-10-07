package com.flarego.core.cloudflare

data class HttpRequest(val method: String, val path: String, val body: String? = null)

data class HttpResponse(val status: Int, val body: String)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

fun encodePath(value: String): String =
    value.encodeToByteArray().joinToString("") { byte ->
        val n = byte.toInt() and 255
        val c = n.toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~") c.toString()
        else "%" + n.toString(16).uppercase().padStart(2, '0')
    }
