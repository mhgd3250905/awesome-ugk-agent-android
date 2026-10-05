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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Starts a Runtime-managed, loopback-only Python HTTP server. */
class LocalHttpServerStartTool(
    private val controller: LocalHttpServerController,
    override val name: String = "local_http_server_start"
) : AgentTool {
    override val description: String =
        "Starts or reuses a managed Python HTTP server bound only to 127.0.0.1 for a directory inside the terminal workspace. Errors are reported as a plain-text message prefixed with the error code."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("directory") {
                put("type", "string")
                put("description", "Relative directory inside the terminal workspace to serve, for example weather-site.")
            }
            putJsonObject("port") {
                put("type", "integer")
                put("description", "TCP port on 127.0.0.1. Defaults to $DEFAULT_LOCAL_HTTP_SERVER_PORT.")
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

/**
 * Reads managed local HTTP server state without confirmation.
 *
 * Not side-effect free: a query forgets records whose process group is
 * confirmed gone, which is what frees their port for a later start. It never
 * signals, stops, or rewrites a live service.
 */
class LocalHttpServerStatusTool(
    private val controller: LocalHttpServerController,
    override val name: String = "local_http_server_status"
) : AgentTool {
    override val description: String =
        "Reads the state of Runtime-managed local HTTP servers without starting, stopping, or signalling anything. Errors are reported as a plain-text message prefixed with the error code."

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
        "Stops a Runtime-managed local HTTP server by port; it never terminates an unmanaged process. Errors are reported as a plain-text message prefixed with the error code."

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
            terminalToolError(call.id, call.name, error.code, error.message)
        } catch (error: IllegalArgumentException) {
            terminalToolError(
                call.id,
                call.name,
                "INVALID_INPUT",
                error.message ?: "Invalid local HTTP server input."
            )
        } catch (error: Exception) {
            terminalToolError(
                call.id,
                call.name,
                "LOCAL_HTTP_SERVER_FAILED",
                error.message ?: error::class.java.name
            )
        }
    }
}

/**
 * A declared value that is not a string, or a string that is only whitespace, is
 * absence for this Tool: `element.jsonPrimitive` used to throw for an object or array,
 * and although `runToolCall` caught that as an input error, the sentence the caller
 * read named `kotlinx.serialization.json.JsonObject` instead of `directory`.
 */
private fun JsonObject.requiredString(name: String): String {
    val declared = this[name]?.takeUnless { it is JsonNull }
    val primitive = declared as? JsonPrimitive
        ?: throw IllegalArgumentException(if (declared == null) "$name is required" else "$name must be a string")
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$name is required")
}

/** The same rule for the port slot: JSON null is "no port asked for", a structured
 *  value is refused by name rather than by the serialization library's wording. */
private fun JsonObject.portPrimitive(name: String): JsonPrimitive? {
    val declared = this[name]?.takeUnless { it is JsonNull } ?: return null
    return declared as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be an integer")
}

private fun JsonObject.startPort(): Int {
    val primitive = portPrimitive("port") ?: return DEFAULT_LOCAL_HTTP_SERVER_PORT
    return primitive.contentOrNull?.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
}

private fun JsonObject.optionalPort(): Int? {
    val primitive = portPrimitive("port") ?: return null
    return primitive.contentOrNull?.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
}

private fun JsonObject.requiredPort(): Int {
    val primitive = portPrimitive("port")
        ?: throw IllegalArgumentException("port is required")
    return primitive.contentOrNull?.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
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
