package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.terminal.runtime.BashRuntime
import com.ugk.pi.terminal.runtime.LocalHttpServerManager
import com.ugk.pi.terminal.runtime.LocalHttpServerRequest
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalHttpServerManagerInstrumentedTest {
    @Test
    fun managedServerServesWorkspaceAndStopsByProcessGroup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)
        val directory = File(runtime.defaultWorkspace(), "managed-http-test").apply {
            deleteRecursively()
            check(mkdirs())
            File(this, "index.html").writeText("managed-weather-site")
        }
        val manager = LocalHttpServerManager(runtime)
        val port = 18_765

        try {
            val started = manager.start(
                LocalHttpServerRequest(
                    directory = "managed-http-test",
                    port = port
                )
            )
            assertEquals("running", started.state)
            // The URL is token-gated: a random URL-safe path segment that
            // other loopback Apps cannot enumerate or guess.
            val url = started.url.orEmpty()
            assertTrue(url.matches(Regex("http://127\\.0\\.0\\.1:$port/[A-Za-z0-9_-]{22}/")))
            val tokenPath = url
                .removePrefix("http://127.0.0.1:$port/")
                .removeSuffix("/")

            val gatedResponse = rawGet(port, "/$tokenPath/")
            assertTrue(gatedResponse.contains("200 OK"))
            assertTrue(gatedResponse.contains("managed-weather-site"))

            // Without the token the same port must answer 404, not the tree.
            val ungatedResponse = rawGet(port, "/")
            assertTrue(ungatedResponse.contains("404"))

            assertEquals("running", manager.status(port).single().state)
            // A newly constructed manager must recover the persisted process
            // group and stop the same service without the original Process object.
            val recreatedManager = LocalHttpServerManager(runtime)
            assertEquals("running", recreatedManager.status(port).single().state)
            assertEquals("stopped", recreatedManager.stop(port).state)
            assertTrue(manager.status(port).isEmpty())
        } finally {
            manager.stopAll()
            directory.deleteRecursively()
        }
    }

    private fun rawGet(port: Int, rawPath: String): String {
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            socket.soTimeout = 1_000
            val writer = socket.getOutputStream().bufferedWriter()
            writer.write("GET $rawPath HTTP/1.0")
            writer.write(13)
            writer.write(10)
            writer.write("Host: 127.0.0.1")
            writer.write(13)
            writer.write(10)
            writer.write(13)
            writer.write(10)
            writer.flush()
            socket.getInputStream().bufferedReader().use { it.readText() }
        }
    }
}
