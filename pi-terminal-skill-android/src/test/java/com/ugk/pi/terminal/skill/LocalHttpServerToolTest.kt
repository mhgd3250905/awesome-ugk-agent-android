package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.terminal.runtime.LocalHttpServerController
import com.ugk.pi.terminal.runtime.LocalHttpServerRequest
import com.ugk.pi.terminal.runtime.LocalHttpServerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHttpServerToolTest {
    @Test
    fun startUsesStructuredDirectoryAndPort() = runBlocking {
        val controller = RecordingController()
        val tool = LocalHttpServerStartTool(controller)

        val result = tool.execute(
            ToolCall(
                id = "start-1",
                name = tool.name,
                input = buildJsonObject {
                    put("directory", "weather-site")
                    put("port", 9001)
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("9001"))
        assertEquals(LocalHttpServerRequest("weather-site", 9001), controller.lastStart)
    }

    @Test
    fun statusIsReadOnlyAndCanInspectAllServers() = runBlocking {
        val controller = RecordingController()
        val tool = LocalHttpServerStatusTool(controller)

        val result = tool.execute(
            ToolCall(
                id = "status-1",
                name = tool.name,
                input = buildJsonObject { }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("servers"))
        assertEquals(null, controller.lastStatusPort)
        assertEquals(0, controller.stopCalls)
    }

    @Test
    fun invalidStartInputReturnsStructuredErrorWithoutStarting() = runBlocking {
        val controller = RecordingController()
        val tool = LocalHttpServerStartTool(controller)

        val result = tool.execute(
            ToolCall(
                id = "start-invalid",
                name = tool.name,
                input = buildJsonObject { put("port", 9001) }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("INVALID_INPUT"))
        assertEquals(null, controller.lastStart)
    }

    @Test
    fun skillTeachesManagedServiceInsteadOfShellDaemon() {
        val skill = localHttpServerSkill()

        assertTrue(skill.instructions.contains("local_http_server_start"))
        assertTrue(skill.instructions.contains("do not write nohup"))
        assertTrue(skill.instructions.contains("127.0.0.1"))
        assertEquals(
            setOf("local_http_server_start", "local_http_server_status", "local_http_server_stop"),
            skill.methods.map { it.toolName }.toSet()
        )
    }

    private class RecordingController : LocalHttpServerController {
        var lastStart: LocalHttpServerRequest? = null
        var lastStatusPort: Int? = Int.MIN_VALUE
        var stopCalls: Int = 0

        override fun start(request: LocalHttpServerRequest): LocalHttpServerStatus {
            lastStart = request
            return LocalHttpServerStatus(
                state = "running",
                port = request.port,
                directory = request.directory,
                url = "http://127.0.0.1:${request.port}/",
                logFile = "/private/http-${request.port}.log",
                processGroupId = 1234
            )
        }

        override fun status(port: Int?): List<LocalHttpServerStatus> {
            lastStatusPort = port
            return emptyList()
        }

        override fun stop(port: Int): LocalHttpServerStatus {
            stopCalls++
            return LocalHttpServerStatus.notFound(port)
        }

        override fun stopAll(): Int = stopCalls
    }
}
