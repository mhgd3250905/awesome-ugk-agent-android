package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared error result shape for the terminal Tools: one human-readable content
 * line prefixed with the searchable error code, and metadata that always
 * carries the same {code, message} pair.
 */
internal fun terminalToolError(
    toolCallId: String,
    toolName: String,
    code: String,
    message: String
): ToolResult {
    return ToolResult(
        toolCallId = toolCallId,
        name = toolName,
        content = "$code: $message",
        isError = true,
        metadata = buildJsonObject {
            put("code", code)
            put("message", message)
        }
    )
}
