package com.ugk.pi.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

data class HttpResponse(
    val statusCode: Int,
    val body: String
)

interface HttpTransport {
    suspend fun post(request: HttpRequest): HttpResponse
}

class JavaNetHttpTransport(
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 60_000
) : HttpTransport {
    override suspend fun post(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            request.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            connection.outputStream.use { output ->
                output.write(request.body.toByteArray(Charsets.UTF_8))
            }

            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResponse(connection.responseCode, body)
        } finally {
            connection.disconnect()
        }
    }
}
