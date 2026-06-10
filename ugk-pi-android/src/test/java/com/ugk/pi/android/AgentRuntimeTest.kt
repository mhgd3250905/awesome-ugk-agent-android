package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun `completes when provider returns no tool calls`() = runBlocking {
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "done")
        )
        val session = AgentSession(id = "s1")
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runtime.run(session, "hello").toList()

        assertEquals(AgentEvent.Started(session.id), events[0])
        assertEquals(
            AgentEvent.ModelRequestStarted(
                iteration = 1,
                messageCount = 1,
                toolCount = 0
            ),
            events[1]
        )
        val modelResponded = events[2] as AgentEvent.ModelResponded
        assertEquals("done", modelResponded.content)
        assertEquals(emptyList<ToolCall>(), modelResponded.toolCalls)
        val elapsedMillis = modelResponded.elapsedMillis
        assertTrue(elapsedMillis != null && elapsedMillis >= 0L)
        assertEquals(AgentEvent.Completed("done"), events[3])
        assertEquals(
            listOf(AgentMessage.User("hello"), AgentMessage.Assistant("done")),
            session.messages
        )
    }

    @Test
    fun `model request user messages include hidden current time prefix`() = runBlocking {
        val provider = ScriptedLLMProvider(ModelResponse(content = "done"))
        val session = AgentSession(id = "time-context")
        val runtime = AgentRuntime(
            llmProvider = provider,
            toolRegistry = ToolRegistry(),
            timeContextProvider = FixedTimeContextProvider(
                AgentTimeContext("2026-06-07 14:52:31", "Asia/Shanghai")
            )
        )

        runtime.run(session, "最近半小时血糖").toList()

        assertEquals(
            "[Current time: 2026-06-07 14:52:31, timezone: Asia/Shanghai]\n最近半小时血糖",
            (provider.requests.single().messages.single() as AgentMessage.User).content
        )
        assertEquals(
            listOf(AgentMessage.User("最近半小时血糖"), AgentMessage.Assistant("done")),
            session.messages
        )
    }

    @Test
    fun `executes registered tool calls and continues the loop`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "echo",
            input = JsonObject(mapOf("text" to JsonPrimitive("ping")))
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "I will call a tool", toolCalls = listOf(call)),
            ModelResponse(content = "tool said pong")
        )
        val tools = ToolRegistry().register(EchoTool())
        val session = AgentSession(id = "s2")
        val runtime = AgentRuntime(provider, tools)

        val events = runtime.run(session, "run echo").toList()

        assertEquals(
            listOf(1, 2),
            events.filterIsInstance<AgentEvent.ModelRequestStarted>().map { it.iteration }
        )
        assertTrue(events.contains(AgentEvent.ToolStarted(call)))
        assertTrue(
            events.contains(
                AgentEvent.ToolFinished(
                    ToolResult(
                        toolCallId = "tool-1",
                        name = "echo",
                        content = "pong: ping"
                    )
                )
            )
        )
        assertEquals(AgentEvent.Completed("tool said pong"), events.last())
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.last().messages.any { it is AgentMessage.Tool })
    }

    @Test
    fun `preserves tool call reasoning content in subsequent model requests`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "echo",
            input = JsonObject(mapOf("text" to JsonPrimitive("ping")))
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(
                content = "checking",
                toolCalls = listOf(call),
                stopReason = "tool_use",
                reasoningContent = "I need the tool result."
            ),
            ModelResponse(content = "done", stopReason = "end_turn")
        )
        val runtime = AgentRuntime(provider, ToolRegistry().register(EchoTool()))

        val events = runtime.run(AgentSession(id = "reasoning-loop"), "run echo").toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertTrue(
            provider.requests[1].messages
                .filterIsInstance<AgentMessage.Assistant>()
                .any {
                    it.toolCalls == listOf(call) &&
                        it.reasoningContent == "I need the tool result."
                }
        )
    }

    @Test
    fun `empty final model response retries and completes`() = runBlocking {
        val provider = ScriptedLLMProvider(
            ModelResponse(content = ""),
            ModelResponse(content = "recovered answer")
        )
        val session = AgentSession(id = "empty-final")
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runtime.run(session, "hello").toList()

        assertEquals(AgentEvent.Completed("recovered answer"), events.last())
        assertEquals(
            listOf(AgentMessage.User("hello"), AgentMessage.Assistant("recovered answer")),
            session.messages
        )
        assertEquals(2, provider.requests.size)
        assertTrue(
            provider.requests[1].messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("Your previous response was empty") }
        )
        assertFalse(
            session.messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("Your previous response was empty") }
        )
    }

    @Test
    fun `three consecutive empty model responses fail`() = runBlocking {
        val provider = ScriptedLLMProvider(
            ModelResponse(content = ""),
            ModelResponse(content = " "),
            ModelResponse(content = "\n")
        )
        val session = AgentSession(id = "repeated-empty-final")
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runtime.run(session, "hello").toList()

        assertEquals(
            AgentEvent.Failed("Model returned an incomplete final response three consecutive times."),
            events.last()
        )
        assertEquals(3, provider.requests.size)
        assertEquals(listOf(AgentMessage.User("hello")), session.messages)
    }

    @Test
    fun `default max iterations is fifty`() {
        assertEquals(50, DEFAULT_MAX_ITERATIONS)
    }

    @Test
    fun `max tokens partial response after tool results retries from the beginning`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "echo",
            input = JsonObject(mapOf("text" to JsonPrimitive("data")))
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call), stopReason = "tool_use"),
            ModelResponse(content = "当前最新读数测量时间为 11", stopReason = "max_tokens"),
            ModelResponse(content = "完整分析：凌晨 0 点到 2 点的数据整体平稳。", stopReason = "end_turn")
        )
        val session = AgentSession(id = "partial-final")
        val runtime = AgentRuntime(provider, ToolRegistry().register(EchoTool()))

        val events = runtime.run(session, "analyze data").toList()

        assertEquals(
            AgentEvent.Completed("完整分析：凌晨 0 点到 2 点的数据整体平稳。"),
            events.last()
        )
        assertEquals(3, provider.requests.size)
        assertTrue(
            provider.requests.last().messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("当前最新读数测量时间为 11") }
        )
        assertFalse(
            session.messages
                .filterIsInstance<AgentMessage.Assistant>()
                .any { it.content == "当前最新读数测量时间为 11" }
        )
    }

    @Test
    fun `max tokens stop reason retries even when partial response is long`() = runBlocking {
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "a".repeat(200), stopReason = "max_tokens"),
            ModelResponse(content = "complete.", stopReason = "end_turn")
        )
        val session = AgentSession(id = "max-tokens")
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runtime.run(session, "write answer").toList()

        assertEquals(AgentEvent.Completed("complete."), events.last())
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `scheduled task run carries source context to events and tools`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "context_probe",
            input = JsonObject(emptyMap())
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call)),
            ModelResponse(content = "checked")
        )
        val tool = ContextProbeTool()
        val tools = ToolRegistry().register(tool)
        val session = AgentSession(id = "scheduled-session")
        val runtime = AgentRuntime(provider, tools)

        val events = runtime.run(
            session = session,
            input = AgentRunInput(
                content = "Check glucose trend now.",
                source = AgentRunSource.SCHEDULED_TASK,
                taskId = "task-123",
                visibleInConversation = false
            )
        ).toList()

        assertEquals(
            AgentEvent.Started(
                sessionId = session.id,
                source = AgentRunSource.SCHEDULED_TASK,
                taskId = "task-123",
                visibleInConversation = false
            ),
            events.first()
        )
        assertEquals(AgentRunSource.SCHEDULED_TASK, tool.lastContext?.runSource)
        assertEquals("task-123", tool.lastContext?.taskId)
        assertFalse(tool.lastContext?.visibleInConversation ?: true)
    }

    @Test
    fun `sdk event run carries its source context`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "context_probe",
            input = JsonObject(emptyMap())
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call)),
            ModelResponse(content = "checked")
        )
        val tool = ContextProbeTool()
        val runtime = AgentRuntime(provider, ToolRegistry().register(tool))

        val events = runtime.run(
            session = AgentSession(id = "sdk-event-session"),
            input = AgentRunInput(
                content = "Recover the CGM connection.",
                source = AgentRunSource.SDK_EVENT,
                visibleInConversation = false
            )
        ).toList()

        assertEquals(AgentRunSource.SDK_EVENT, (events.first() as AgentEvent.Started).source)
        assertEquals(AgentRunSource.SDK_EVENT, tool.lastContext?.runSource)
    }

    @Test
    fun `missing tool is returned to the model as an error tool result`() = runBlocking {
        val call = ToolCall(id = "missing-1", name = "missing", input = JsonObject(emptyMap()))
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "need missing tool", toolCalls = listOf(call)),
            ModelResponse(content = "I cannot use that tool")
        )
        val session = AgentSession(id = "s3")
        val runtime = AgentRuntime(provider, ToolRegistry())

        runtime.run(session, "try missing").toList()

        val toolMessage = session.messages.filterIsInstance<AgentMessage.Tool>().single()
        assertTrue(toolMessage.result.isError)
        assertEquals("missing", toolMessage.result.name)
        assertTrue(toolMessage.result.content.contains("Tool not registered"))
    }

    @Test
    fun `pending user messages are appended after tool batch before next model request`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "echo",
            input = JsonObject(mapOf("text" to JsonPrimitive("first")))
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call)),
            ModelResponse(content = "updated answer")
        )
        val session = AgentSession(id = "interrupt-session")
        val runtime = AgentRuntime(provider, ToolRegistry().register(EchoTool()))
        var pendingDelivered = false

        val events = runtime.run(
            session = session,
            input = AgentRunInput(content = "initial request"),
            pendingUserMessages = {
                if (pendingDelivered) {
                    emptyList()
                } else {
                    pendingDelivered = true
                    listOf("additional context")
                }
            }
        ).toList()

        assertTrue(events.contains(AgentEvent.UserMessageAppended("additional context")))
        assertEquals(
            listOf(
                AgentMessage.User("initial request"),
                AgentMessage.Assistant("checking", toolCalls = listOf(call)),
                AgentMessage.Tool(
                    ToolResult(
                        toolCallId = "tool-1",
                        name = "echo",
                        content = "pong: first"
                    )
                ),
                AgentMessage.User("additional context"),
                AgentMessage.Assistant("updated answer")
            ),
            session.messages
        )
        assertEquals(2, provider.requests.size)
        assertTrue(
            (provider.requests.last().messages.first() as AgentMessage.User)
                .content
                .endsWith("\ninitial request")
        )
        assertTrue(
            (provider.requests.last().messages[3] as AgentMessage.User)
                .content
                .endsWith("\nadditional context")
        )
    }

    @Test
    fun `pending user messages carry their own hidden time context`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "echo",
            input = JsonObject(mapOf("text" to JsonPrimitive("first")))
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call)),
            ModelResponse(content = "updated answer")
        )
        val runtime = AgentRuntime(
            llmProvider = provider,
            toolRegistry = ToolRegistry().register(EchoTool()),
            timeContextProvider = SequentialTimeContextProvider(
                AgentTimeContext("2026-06-07 14:52:31", "Asia/Shanghai"),
                AgentTimeContext("2026-06-07 14:53:02", "Asia/Shanghai")
            )
        )
        var pendingDelivered = false

        runtime.run(
            session = AgentSession(id = "pending-time-context"),
            input = AgentRunInput(content = "initial request"),
            pendingUserMessages = {
                if (pendingDelivered) {
                    emptyList()
                } else {
                    pendingDelivered = true
                    listOf("additional context")
                }
            }
        ).toList()

        val userMessages = provider.requests.last().messages.filterIsInstance<AgentMessage.User>()
        assertEquals(
            "[Current time: 2026-06-07 14:52:31, timezone: Asia/Shanghai]\ninitial request",
            userMessages[0].content
        )
        assertEquals(
            "[Current time: 2026-06-07 14:53:02, timezone: Asia/Shanghai]\nadditional context",
            userMessages[1].content
        )
    }


    @Test
    fun `terminal tool result completes run without another model request`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "terminal_tool",
            input = JsonObject(emptyMap())
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call)),
            ModelResponse(content = "should not be requested")
        )
        val session = AgentSession(id = "terminal-session")
        val runtime = AgentRuntime(provider, ToolRegistry().register(TerminalTool()))

        val events = runtime.run(session, "run terminal tool").toList()

        assertEquals(1, provider.requests.size)
        assertEquals(AgentEvent.Completed("Stop here."), events.last())
        assertEquals(
            listOf(
                AgentMessage.User("run terminal tool"),
                AgentMessage.Assistant("checking", toolCalls = listOf(call)),
                AgentMessage.Tool(
                    ToolResult(
                        toolCallId = "tool-1",
                        name = "terminal_tool",
                        content = "raw stop",
                        metadata = JsonObject(
                            mapOf(
                                "terminalForTurn" to JsonPrimitive(true),
                                "assistantMessage" to JsonPrimitive("Stop here.")
                            )
                        )
                    )
                ),
                AgentMessage.Assistant("Stop here.")
            ),
            session.messages
        )
    }

    @Test
    fun `tool progress is emitted while tool is running`() = runBlocking {
        val call = ToolCall(
            id = "tool-1",
            name = "progress_tool",
            input = JsonObject(emptyMap())
        )
        val provider = ScriptedLLMProvider(
            ModelResponse(content = "checking", toolCalls = listOf(call)),
            ModelResponse(content = "done")
        )
        val session = AgentSession(id = "progress-session")
        val runtime = AgentRuntime(provider, ToolRegistry().register(ProgressTool()))

        val events = runtime.run(session, "run progress tool").toList()

        assertTrue(
            events.contains(
                AgentEvent.ToolProgress(
                    call = call,
                    progress = ToolProgress(
                        title = "Working",
                        detail = "Step 1",
                        current = 1,
                        total = 2
                    )
                )
            )
        )
        assertTrue(events.indexOf(AgentEvent.ToolStarted(call)) < events.indexOfFirst { it is AgentEvent.ToolProgress })
        assertTrue(events.indexOfFirst { it is AgentEvent.ToolProgress } < events.indexOfFirst { it is AgentEvent.ToolFinished })
    }

    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "Echoes text for tests."
        override val inputSchema = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            val text = call.input["text"]?.toString()?.trim('"') ?: ""
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "pong: $text"
            )
        }
    }

    private class TerminalTool : AgentTool {
        override val name = "terminal_tool"
        override val description = "Stops the current test run."
        override val inputSchema = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "raw stop",
                metadata = JsonObject(
                    mapOf(
                        "terminalForTurn" to JsonPrimitive(true),
                        "assistantMessage" to JsonPrimitive("Stop here.")
                    )
                )
            )
        }
    }

    private class ProgressTool : AgentTool {
        override val name = "progress_tool"
        override val description = "Reports progress for tests."
        override val inputSchema = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            context.reportProgress(
                ToolProgress(
                    title = "Working",
                    detail = "Step 1",
                    current = 1,
                    total = 2
                )
            )
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "done"
            )
        }
    }

    private class ContextProbeTool : AgentTool {
        override val name = "context_probe"
        override val description = "Captures tool context for tests."
        override val inputSchema = JsonObject(emptyMap())
        var lastContext: ToolExecutionContext? = null

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            lastContext = context
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "ok"
            )
        }
    }

    private class ScriptedLLMProvider(
        private vararg val responses: ModelResponse
    ) : LLMProvider {
        val requests = mutableListOf<ModelRequest>()
        private var index = 0

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return responses[index++]
        }
    }

    private class FixedTimeContextProvider(
        private val context: AgentTimeContext
    ) : AgentTimeContextProvider {
        override fun currentContext(): AgentTimeContext = context
    }

    private class SequentialTimeContextProvider(
        private vararg val contexts: AgentTimeContext
    ) : AgentTimeContextProvider {
        private var index = 0

        override fun currentContext(): AgentTimeContext {
            return contexts[index++]
        }
    }
}
