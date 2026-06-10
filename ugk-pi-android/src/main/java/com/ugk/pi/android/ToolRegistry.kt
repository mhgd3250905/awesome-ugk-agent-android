package com.ugk.pi.android

class ToolRegistry {
    private val tools = linkedMapOf<String, AgentTool>()

    fun register(tool: AgentTool): ToolRegistry {
        require(tool.name.isNotBlank()) { "Tool name must not be blank" }
        tools[tool.name] = tool
        return this
    }

    fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.toList()

    fun definitions(): List<AgentToolDefinition> {
        return tools.values.map { it.definition() }
    }
}
