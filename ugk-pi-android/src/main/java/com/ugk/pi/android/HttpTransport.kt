package com.ugk.pi.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    val readTimeoutMillis: Int = 60_000,
    val maxResponseBytes: Int = 4 * 1024 * 1024
) : HttpTransport {
    init {
        require(connectTimeoutMillis >= 0) { "connectTimeoutMillis must be greater than or equal to 0" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must be greater than or equal to 0" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be greater than 0" }
    }

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
            val body = stream?.use { it.readUtf8(maxResponseBytes) } ?: ""
            HttpResponse(connection.responseCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readUtf8(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count > maxBytes - totalBytes) {
                throw IOException("HTTP response exceeds maxResponseBytes=$maxBytes")
            }
            output.write(buffer, 0, count)
            totalBytes += count
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
