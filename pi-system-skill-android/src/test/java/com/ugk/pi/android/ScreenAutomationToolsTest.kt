package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAutomationToolsTest {
    @Test
    fun readToolReturnsBoundedSnapshotAndFindToolReturnsExactTarget() = runBlocking {
        val backend = FakeScreenAutomationBackend()
        val context = ToolExecutionContext(sessionId = "tool-test")

        val read = ScreenReadUiTreeTool(backend).execute(
            ToolCall("read", "screen_read_ui_tree", buildJsonObject { put("max_nodes", 42) }),
            context
        )
        val readJson = Json.parseToJsonElement(read.content).jsonObject
        assertEquals("snapshot-1", readJson["snapshotId"]?.toString()?.trim('"'))
        assertEquals(42, backend.lastMaxNodes)
        assertFalse(read.isError)

        val find = ScreenFindUiElementTool(backend).execute(
            ToolCall(
                "find",
                "screen_find_ui_element",
                buildJsonObject { put("text", "Continue") }
            ),
            context
        )
        val findJson = Json.parseToJsonElement(find.content).jsonObject
        assertEquals("snapshot-2", findJson["snapshotId"]?.toString()?.trim('"'))
        assertEquals("2", findJson["totalCount"]?.toString())
        assertEquals("true", findJson["ambiguous"]?.toString())
        assertTrue(find.content.contains("node-continue"))
        assertFalse(find.isError)

        val exact = ScreenFindUiElementTool(backend).execute(
            ToolCall(
                "find-exact",
                "screen_find_ui_element",
                buildJsonObject { put("text_exact", "Continue") }
            ),
            context
        )
        assertTrue(exact.content.contains("\"totalCount\":1"))
    }

    @Test
    fun actionToolPassesSnapshotNodeAndTextWithoutGuessing() = runBlocking {
        val backend = FakeScreenAutomationBackend()
        val result = ScreenPerformActionTool(backend).execute(
            ToolCall(
                "action",
                "screen_perform_action",
                buildJsonObject {
                    put("snapshotId", "snapshot-1")
                    put("nodeId", "node-continue")
                    put("action", "set_text")
                    put("text", "hello")
                }
            ),
            ToolExecutionContext(sessionId = "tool-test")
        )

        assertFalse(result.isError)
        assertEquals("snapshot-1", backend.lastAction?.snapshotId)
        assertEquals("node-continue", backend.lastAction?.nodeId)
        assertEquals("hello", backend.lastAction?.text)
    }

    @Test
    fun failedScreenActionExposesCodeAndRecoveryHint() = runBlocking {
        val backend = FakeScreenAutomationBackend().apply {
            actionResult = ScreenOperationResult(
                success = false,
                code = ScreenAutomationErrorCodes.STALE_SNAPSHOT,
                message = "Read again",
                nodeId = "node-continue",
                action = ScreenActionNames.CLICK,
                snapshotId = "snapshot-1"
            )
        }

        val result = ScreenPerformActionTool(backend).execute(
            ToolCall(
                "action-failed",
                "screen_perform_action",
                buildJsonObject {
                    put("snapshotId", "snapshot-1")
                    put("nodeId", "node-continue")
                    put("action", "click")
                }
            ),
            ToolExecutionContext(sessionId = "tool-test")
        )

        assertTrue(result.isError)
        assertEquals(
            ScreenAutomationErrorCodes.STALE_SNAPSHOT,
            result.metadata["code"]?.toString()?.trim('"')
        )
        assertEquals(
            "screen_read_ui_tree",
            result.metadata["recoveryTool"]?.toString()?.trim('"')
        )
        assertTrue(result.content.contains("screen_read_ui_tree"))
        assertTrue(result.content.contains("terminal_bash_execute"))
    }

    @Test
    fun findToolRejectsAnUnscopedQuery() = runBlocking {
        val backend = FakeScreenAutomationBackend()
        val result = ScreenFindUiElementTool(backend).execute(
            ToolCall("find", "screen_find_ui_element", buildJsonObject {}),
            ToolExecutionContext(sessionId = "tool-test")
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains(ScreenAutomationErrorCodes.INVALID_INPUT))
        assertEquals(0, backend.readCount)
    }

    private class FakeScreenAutomationBackend : ScreenAutomationBackend {
        var readCount = 0
        var lastMaxNodes: Int? = null
        var lastAction: ScreenActionRequest? = null
        var actionResult = ScreenOperationResult(
            success = true,
            code = ScreenAutomationErrorCodes.OK,
            nodeId = "node-continue",
            action = ScreenActionNames.SET_TEXT,
            snapshotId = "snapshot-1"
        )

        override fun readUiTree(
            sessionId: String,
            maxDepth: Int,
            maxNodes: Int
        ): ScreenReadResult {
            readCount++
            lastMaxNodes = maxNodes
            val snapshotId = "snapshot-$readCount"
            return ScreenReadResult(
                snapshot = ScreenUiSnapshot(
                    snapshotId = snapshotId,
                    sessionId = sessionId,
                    packageName = "com.example.target",
                    screenWidth = 1080,
                    screenHeight = 2400,
                    windowCount = 1,
                    nodeCount = 3,
                    truncated = false,
                    elements = listOf(
                        ScreenUiElement(
                            nodeId = "node-continue",
                            windowIndex = 0,
                            packageName = "com.example.target",
                            type = "Button",
                            text = "Continue",
                            bounds = ScreenBounds(20, 200, 500, 280),
                            actions = listOf(ScreenUiAction(16, "click")),
                            clickable = true
                        ),
                        ScreenUiElement(
                            nodeId = "node-continue-later",
                            windowIndex = 0,
                            packageName = "com.example.target",
                            type = "Button",
                            text = "Continue later",
                            bounds = ScreenBounds(20, 400, 500, 480),
                            actions = listOf(ScreenUiAction(16, "click")),
                            clickable = true
                        ),
                        ScreenUiElement(
                            nodeId = "node-input",
                            windowIndex = 0,
                            packageName = "com.example.target",
                            type = "EditText",
                            hint = "Name",
                            bounds = ScreenBounds(20, 300, 500, 380),
                            actions = listOf(ScreenUiAction(2097152, "set_text")),
                            editable = true
                        )
                    )
                )
            )
        }

        override suspend fun performAction(
            sessionId: String,
            request: ScreenActionRequest
        ): ScreenOperationResult {
            lastAction = request
            return actionResult.copy(
                nodeId = request.nodeId,
                action = request.action,
                snapshotId = request.snapshotId
            )
        }

        override suspend fun performGesture(request: ScreenGestureRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)

        override suspend fun pressKey(request: ScreenKeyRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.key)

        override fun performGlobalAction(request: ScreenGlobalActionRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)
    }
}
