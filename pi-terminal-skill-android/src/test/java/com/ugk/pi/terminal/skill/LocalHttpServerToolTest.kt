package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.terminal.runtime.DEFAULT_LOCAL_HTTP_SERVER_PORT
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

    /**
     * The three Tools read `directory` and `port` through `element.jsonPrimitive`, which
     * measured raises IllegalArgumentException for an object or array. `runToolCall`
     * catches IllegalArgumentException as an input problem, so the code stayed
     * `INVALID_INPUT` - but the sentence the caller reads was the serialization
     * library's own, which names no argument they can fix. Measured before this case
     * was folded: `build/review-evidence/r14-terminal-baseline-red.log`.
     * A refusal must name the argument the caller has to fix.
     */
    @Test
    fun structuredArgumentSlotsAreRefusedByTheirOwnName() = runBlocking {
        val controller = RecordingController()
        val structured = buildJsonObject { put("nested", 1) }
        val cases = listOf(
            Triple(
                LocalHttpServerStartTool(controller),
                kotlinx.serialization.json.JsonObject(mapOf("directory" to structured)),
                "directory"
            ),
            Triple(
                LocalHttpServerStartTool(controller),
                buildJsonObject {
                    put("directory", "weather-site")
                    put("port", structured)
                },
                "port"
            ),
            Triple(
                LocalHttpServerStatusTool(controller),
                kotlinx.serialization.json.JsonObject(mapOf("port" to structured)),
                "port"
            ),
            Triple(
                LocalHttpServerStopTool(controller),
                kotlinx.serialization.json.JsonObject(mapOf("port" to structured)),
                "port"
            )
        )

        cases.forEachIndexed { index, (tool, input, namedArgument) ->
            val result = tool.execute(
                ToolCall(id = "structured-$index", name = tool.name, input = input),
                ToolExecutionContext(sessionId = "session")
            )
            assertTrue("$namedArgument: must be an error", result.isError)
            assertEquals("$namedArgument: must be an input problem", "INVALID_INPUT", errorCode(result))
            assertTrue(
                "$namedArgument: refusal must name the argument, got: ${result.content}",
                result.content.contains(namedArgument)
            )
            assertTrue(
                "$namedArgument: must not quote the serialization library: ${result.content}",
                !result.content.contains("JsonPrimitive") && !result.content.contains("JsonObject")
            )
        }
        assertEquals("no server may start while an argument is unreadable", null, controller.lastStart)

        // The other direction: the fold must not turn a legal call into a refusal.
        // JSON null in the port slot is an endpoint's unfilled field and still means
        // "use the documented default", exactly as before.
        val tolerated = LocalHttpServerStartTool(controller).execute(
            ToolCall(
                id = "null-port",
                name = "local_http_server_start",
                input = buildJsonObject {
                    put("directory", "weather-site")
                    put("port", kotlinx.serialization.json.JsonNull)
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )
        assertTrue("a null-valued port is absence, not an error: ${tolerated.content}", !tolerated.isError)
        assertEquals(DEFAULT_LOCAL_HTTP_SERVER_PORT, controller.lastStart?.port)
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
