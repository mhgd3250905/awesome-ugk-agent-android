package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.terminal.runtime.LocalHttpServerController
import com.ugk.pi.terminal.runtime.LocalHttpServerException
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
        assertTrue(result.content.contains("http://127.0.0.1:9001/$TOKEN_PATH_SEGMENT/"))
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
        assertTrue(skill.instructions.contains("local_http_server_stop"))
        assertTrue(skill.instructions.contains("do not write nohup"))
        assertTrue(skill.instructions.contains("127.0.0.1"))
        assertTrue(skill.instructions.contains("target.toolName"))
        assertTrue(skill.instructions.contains("target.input"))
        assertTrue(skill.instructions.contains("selectedButtonId only records"))
        assertTrue(skill.instructions.contains("does not authorize a protected Tool by itself"))
        assertTrue(skill.instructions.contains("local_http_server_status is read-only"))
        assertTrue(skill.instructions.contains("launch_android_app_intent"))
        assertEquals(
            setOf("local_http_server_start", "local_http_server_status", "local_http_server_stop"),
            skill.methods.map { it.toolName }.toSet()
        )
    }

    @Test
    fun mapsControllerErrorCodesToPlainTextErrors() = runBlocking {
        listOf(
            LocalHttpServerException("PORT_IN_USE", "Port 8765 is already in use on 127.0.0.1."),
            LocalHttpServerException("TOO_MANY_SERVERS", "The Runtime allows at most 2 managed local HTTP servers."),
            LocalHttpServerException("START_FAILED", "Unable to start the managed Python HTTP server.")
        ).forEach { failure ->
            val tool = LocalHttpServerStartTool(FailingController { throw failure })

            val result = tool.execute(
                ToolCall(
                    id = "start-${failure.code}",
                    name = tool.name,
                    input = buildJsonObject { put("directory", "weather-site") }
                ),
                ToolExecutionContext(sessionId = "session")
            )

            assertTrue(result.isError)
            assertEquals(failure.code, errorCode(result))
            assertTrue(result.content.startsWith("${failure.code}: "))
            assertTrue(result.content.contains(failure.message))
            assertFalse(result.content.trim().startsWith("{"))
            assertEquals(failure.message, result.metadata?.get("message")?.toString()?.trim('"'))
        }
    }

    @Test
    fun mapsStopFailureToItsStructuredCode() = runBlocking {
        val failure = LocalHttpServerException(
            "STOP_FAILED",
            "Unable to terminate the managed HTTP server process group 7."
        )
        val tool = LocalHttpServerStopTool(FailingController { throw failure })

        val result = tool.execute(
            ToolCall(
                id = "stop-failed",
                name = tool.name,
                input = buildJsonObject { put("port", 8765) }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(result.isError)
        assertEquals("STOP_FAILED", errorCode(result))
        assertTrue(result.content.startsWith("STOP_FAILED: "))
        assertEquals(failure.message, result.metadata?.get("message")?.toString()?.trim('"'))
    }

    @Test
    fun mapsUnexpectedControllerFailuresToLocalHttpServerFailed() = runBlocking {
        listOf(
            RuntimeException("boom"),
            RuntimeException(),
            IllegalStateException("manager state is broken")
        ).forEachIndexed { index, failure ->
            val tool = LocalHttpServerStatusTool(FailingController { throw failure })

            val result = tool.execute(
                ToolCall(
                    id = "unexpected-$index",
                    name = tool.name,
                    input = buildJsonObject { }
                ),
                ToolExecutionContext(sessionId = "session")
            )

            assertTrue(result.isError)
            assertEquals("LOCAL_HTTP_SERVER_FAILED", errorCode(result))
            assertTrue(result.content.startsWith("LOCAL_HTTP_SERVER_FAILED: "))
            val expectedMessage = failure.message ?: failure::class.java.name
            assertTrue(result.content.contains(expectedMessage))
            assertEquals(expectedMessage, result.metadata?.get("message")?.toString()?.trim('"'))
        }
    }

    @Test
    fun invalidInputsOnAllThreeToolsMapToInvalidInputCode() = runBlocking {
        val controller = RecordingController()
        val cases = listOf(
            LocalHttpServerStartTool(controller) to buildJsonObject { put("directory", "   ") },
            LocalHttpServerStatusTool(controller) to buildJsonObject { put("port", "not-a-port") },
            LocalHttpServerStopTool(controller) to buildJsonObject { },
            LocalHttpServerStopTool(controller) to buildJsonObject { put("port", "not-a-port") }
        )

        cases.forEachIndexed { index, (tool, input) ->
            val result = tool.execute(
                ToolCall(id = "invalid-input-$index", name = tool.name, input = input),
                ToolExecutionContext(sessionId = "session")
            )

            assertTrue(result.isError)
            assertEquals("INVALID_INPUT", errorCode(result))
            assertTrue(result.content.startsWith("INVALID_INPUT: "))
        }
        assertEquals(null, controller.lastStart)
    }

    @Test
    fun toolDescriptionsDocumentThePlainTextErrorFormat() {
        val controller = RecordingController()

        listOf(
            LocalHttpServerStartTool(controller).description,
            LocalHttpServerStatusTool(controller).description,
            LocalHttpServerStopTool(controller).description
        ).forEach { description ->
            assertTrue(description.contains("plain-text message prefixed with the error code"))
        }
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
                url = "http://127.0.0.1:${request.port}/$TOKEN_PATH_SEGMENT/",
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

    private class FailingController(
        private val failure: () -> Nothing
    ) : LocalHttpServerController {
        override fun start(request: LocalHttpServerRequest): LocalHttpServerStatus = failure()

        override fun status(port: Int?): List<LocalHttpServerStatus> = failure()

        override fun stop(port: Int): LocalHttpServerStatus = failure()

        override fun stopAll(): Int = failure()
    }

    private companion object {
        // Same shape as a real token-gated URL path segment produced by the
        // Runtime manager: unpadded URL-safe Base64.
        const val TOKEN_PATH_SEGMENT = "AbCdEfGhIjKlMnOpQrSt"

        fun errorCode(result: ToolResult): String? =
            result.metadata?.get("code")?.toString()?.trim('"')
    }
}
