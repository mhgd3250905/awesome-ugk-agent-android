package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A reusable composition seam for host-owned AgentTool policies. */
fun interface AgentToolDecorator {
    fun decorate(tool: AgentTool): AgentTool

    companion object {
        val Identity: AgentToolDecorator = AgentToolDecorator { it }
    }
}

/** A generic reason why a host-owned capability temporarily blocks a Tool. */
data class AgentToolInterlockDecision(
    val blockingCapability: String,
    val message: String? = null
)

/** Evaluates whether a Tool call is blocked by another capability. */
fun interface AgentToolInterlockPolicy {
    fun evaluate(
        tool: AgentTool,
        call: ToolCall,
        context: ToolExecutionContext
    ): AgentToolInterlockDecision?
}

/**
 * Applies a host-owned capability interlock to an AgentTool.
 *
 * This decorator is intended to be placed outside other Tool decorators. A
 * blocked call therefore returns a structured error without entering the
 * wrapped Tool or any confirmation policy inside it.
 */
class AgentToolInterlock(
    private val delegate: AgentTool,
    private val policy: AgentToolInterlockPolicy
) : AgentTool {
    override val name: String = delegate.name
    override val description: String =
        "${delegate.description} This Tool may be temporarily unavailable while another host capability owns the current run."
    override val inputSchema: JsonObject = delegate.inputSchema

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val decision = policy.evaluate(this, call, context)
            ?: return delegate.execute(call, context)
        val capability = decision.blockingCapability.trim().ifBlank { "unknown" }
        val message = decision.message?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Tool '$name' is blocked while capability '$capability' owns the current run."
        val payload = buildJsonObject {
            put("code", AgentToolInterlockErrorCodes.BLOCKED)
            put("blockingCapability", capability)
            put("message", message)
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = payload.toString(),
            isError = true,
            metadata = payload
        )
    }
}

object AgentToolInterlockErrorCodes {
    const val BLOCKED = "CAPABILITY_INTERLOCKED"
}
