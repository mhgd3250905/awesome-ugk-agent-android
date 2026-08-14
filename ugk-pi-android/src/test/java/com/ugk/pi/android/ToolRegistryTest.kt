package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun firstRegistrationStoresTool() {
        val tool = TestTool("first_tool")

        val registry = ToolRegistry().register(tool)

        assertSame(tool, registry.get("first_tool"))
        assertEquals(listOf(tool), registry.all())
    }

    @Test
    fun duplicateRegistrationOfSameInstanceFailsWithoutReplacingTool() {
        val tool = TestTool("duplicate_tool")
        val registry = ToolRegistry().register(tool)

        val error = captureIllegalArgumentException {
            registry.register(tool)
        }

        assertEquals("Tool name already registered: 'duplicate_tool'", error.message)
        assertSame(tool, registry.get("duplicate_tool"))
        assertEquals(listOf(tool), registry.all())
    }

    @Test
    fun duplicateRegistrationOfDifferentInstanceFailsWithoutReplacingTool() {
        val firstTool = TestTool("duplicate_tool")
        val secondTool = TestTool("duplicate_tool")
        val registry = ToolRegistry().register(firstTool)

        val error = captureIllegalArgumentException {
            registry.register(secondTool)
        }

        assertEquals("Tool name already registered: 'duplicate_tool'", error.message)
        assertSame(firstTool, registry.get("duplicate_tool"))
        assertEquals(listOf(firstTool), registry.all())
    }

    @Test
    fun differentNamesCanBeRegistered() {
        val firstTool = TestTool("first_tool")
        val secondTool = TestTool("second_tool")

        val registry = ToolRegistry()
            .register(firstTool)
            .register(secondTool)

        assertEquals(listOf("first_tool", "second_tool"), registry.all().map { it.name })
        assertSame(firstTool, registry.get("first_tool"))
        assertSame(secondTool, registry.get("second_tool"))
    }

    private fun captureIllegalArgumentException(block: () -> Unit): IllegalArgumentException {
        return try {
            block()
            fail("Expected duplicate Tool registration to fail")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
    }

    private class TestTool(override val name: String) : AgentTool {
        override val description: String = "Test tool $name"
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult = ToolResult(call.id, name, "ok")
    }
}
