package com.ugk.pi.terminal.skill

import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.terminal.runtime.DEFAULT_LOCAL_HTTP_SERVER_PORT
import com.ugk.pi.terminal.runtime.LocalHttpServerController
import com.ugk.pi.terminal.runtime.LocalHttpServerException
import com.ugk.pi.terminal.runtime.LocalHttpServerRequest
import com.ugk.pi.terminal.runtime.LocalHttpServerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Starts a Runtime-managed, loopback-only Python HTTP server. */
class LocalHttpServerStartTool(
    private val controller: LocalHttpServerController,
    override val name: String = "local_http_server_start"
) : AgentTool {
    override val description: String =
        "Starts or reuses a managed Python HTTP server bound only to 127.0.0.1 for a directory inside the terminal workspace."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("directory") {
                put("type", "string")
                put("description", "Relative directory inside the terminal workspace to serve, for example weather-site.")
            }
            putJsonObject("port") {
                put("type", "integer")
                put("description", "TCP port on 127.0.0.1. Defaults to 8765.")
                put("default", DEFAULT_LOCAL_HTTP_SERVER_PORT)
            }
        }
        putJsonArray("required") { add(JsonPrimitive("directory")) }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        return runToolCall(call) {
            val directory = call.input.requiredString("directory")
            val port = call.input.startPort()
            controller.start(LocalHttpServerRequest(directory = directory, port = port)).toJson()
        }
    }
}

/** Reads managed local HTTP server state without confirmation or side effects. */
class LocalHttpServerStatusTool(
    private val controller: LocalHttpServerController,
    override val name: String = "local_http_server_status"
) : AgentTool {
    override val description: String =
        "Reads the state of Runtime-managed local HTTP servers without starting, stopping, or changing anything."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("port") {
                put("type", "integer")
                put("description", "Optional port to inspect. Omit it to inspect all managed servers.")
            }
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        return runToolCall(call) {
            val servers = controller.status(call.input.optionalPort())
            buildJsonObject {
                putJsonArray("servers") {
                    servers.forEach { add(it.toJson()) }
                }
            }
        }
    }
}

/** Stops one Runtime-managed local HTTP server. */
class LocalHttpServerStopTool(
    private val controller: LocalHttpServerController,
    override val name: String = "local_http_server_stop"
) : AgentTool {
    override val description: String =
        "Stops a Runtime-managed local HTTP server by port; it never terminates an unmanaged process."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("port") {
                put("type", "integer")
                put("description", "TCP port of the managed local HTTP server.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("port")) }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        return runToolCall(call) {
            val port = call.input.requiredPort()
            buildJsonObject {
                put("server", controller.stop(port).toJson())
            }
        }
    }
}

private suspend fun runToolCall(
    call: ToolCall,
    block: () -> JsonElement
): ToolResult {
    return withContext(Dispatchers.IO) {
        try {
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = block().toString()
            )
        } catch (error: LocalHttpServerException) {
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = buildJsonObject {
                    put("error", error.code)
                    put("message", error.message)
                }.toString(),
                isError = true,
                metadata = buildJsonObject { put("code", error.code) }
            )
        } catch (error: IllegalArgumentException) {
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = buildJsonObject {
                    put("error", "INVALID_INPUT")
                    put("message", error.message ?: "Invalid local HTTP server input.")
                }.toString(),
                isError = true,
                metadata = buildJsonObject { put("code", "INVALID_INPUT") }
            )
        } catch (error: Exception) {
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = buildJsonObject {
                    put("error", "LOCAL_HTTP_SERVER_FAILED")
                    put("message", error.message ?: error::class.java.name)
                }.toString(),
                isError = true,
                metadata = buildJsonObject { put("code", "LOCAL_HTTP_SERVER_FAILED") }
            )
        }
    }
}

private fun JsonObject.requiredString(name: String): String {
    return this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$name is required")
}

private fun JsonObject.startPort(): Int {
    val value = this["port"]?.jsonPrimitive?.contentOrNull ?: return DEFAULT_LOCAL_HTTP_SERVER_PORT
    return value.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
}

private fun JsonObject.optionalPort(): Int? {
    val value = this["port"]?.jsonPrimitive?.contentOrNull ?: return null
    return value.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
}

private fun JsonObject.requiredPort(): Int {
    val value = this["port"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("port is required")
    return value.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
}

private fun LocalHttpServerStatus.toJson(): JsonObject {
    return buildJsonObject {
        put("state", state)
        put("port", port)
        directory?.let { put("directory", it) }
        url?.let { put("url", it) }
        logFile?.let { put("logFile", it) }
        processGroupId?.let { put("processGroupId", it) }
        put("managed", managed)
    }
}
