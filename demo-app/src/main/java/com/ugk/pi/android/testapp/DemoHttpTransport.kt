package com.ugk.pi.android.testapp

import com.ugk.pi.android.HttpRequest
import com.ugk.pi.android.HttpResponse
import com.ugk.pi.android.HttpTransport
import com.ugk.pi.android.JavaNetHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A demo-owned request model that supports GET as well as runtime POST. */
data class DemoHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class DemoHttpResponse(
    val statusCode: Int,
    val body: String
)

/** Injectable seam shared by runtime adapters, probes, and quota requests. */
interface DemoHttpTransport {
    suspend fun request(request: DemoHttpRequest): DemoHttpResponse

    fun postStream(request: DemoHttpRequest): Flow<String>
}

/** Adapts the demo seam to the SDK's intentionally POST-focused public API. */
class DemoHttpTransportAdapter(
    private val delegate: DemoHttpTransport
) : HttpTransport {
    override suspend fun post(request: HttpRequest): HttpResponse {
        val response = delegate.request(
            DemoHttpRequest(
                method = "POST",
                url = request.url,
                headers = request.headers,
                body = request.body
            )
        )
        return HttpResponse(response.statusCode, response.body)
    }

    override fun postStream(request: HttpRequest): Flow<String> = delegate.postStream(
        DemoHttpRequest(
            method = "POST",
            url = request.url,
            headers = request.headers,
            body = request.body
        )
    )
}

/**
 * Production demo transport. Runtime POST and SSE stay on the SDK's tested
 * JavaNetHttpTransport; GET and other request methods are handled here.
 */
class JavaNetDemoHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 180_000,
    private val maxResponseBytes: Int = 4 * 1024 * 1024
) : DemoHttpTransport {
    private val runtimeTransport = JavaNetHttpTransport(
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        maxResponseBytes = maxResponseBytes
    )

    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be greater than 0" }
    }

    override suspend fun request(request: DemoHttpRequest): DemoHttpResponse {
        if (request.method.equals("POST", ignoreCase = true)) {
            val response = runtimeTransport.post(
                HttpRequest(
                    url = request.url,
                    headers = request.headers,
                    body = request.body.orEmpty()
                )
            )
            return DemoHttpResponse(response.statusCode, response.body)
        }

        return withContext(Dispatchers.IO) {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method.uppercase()
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = true
                request.headers.forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }
                if (request.body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { output ->
                        output.write(request.body.toByteArray(Charsets.UTF_8))
                    }
                }

                val statusCode = connection.responseCode
                val stream = if (statusCode >= 400) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                val body = stream?.use { it.readUtf8(maxResponseBytes) }.orEmpty()
                DemoHttpResponse(statusCode, body)
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun postStream(request: DemoHttpRequest): Flow<String> = runtimeTransport.postStream(
        HttpRequest(
            url = request.url,
            headers = request.headers,
            body = request.body.orEmpty()
        )
    )

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
