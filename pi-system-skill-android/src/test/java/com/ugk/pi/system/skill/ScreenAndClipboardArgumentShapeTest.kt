package com.ugk.pi.system.skill

import com.ugk.pi.android.AgentImageContent
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optional Tool arguments in this family are read by private helpers that collapse
 * three different situations into one null: absent, JSON null, and "declared in a
 * shape this client cannot interpret". The first two mean "the caller left it out"
 * and take the documented default; the third is a value the caller DID send.
 *
 * Measured behaviour (build/review-evidence/r14-shape-oracle.txt): `jsonPrimitive`
 * throws IllegalArgumentException on an object or array; `intOrNull` returns null for
 * 20.5, true and "2e3". So a boolean maxChars used to read as "no limit requested"
 * and handed the whole 8000-character clipboard to the next model request, and an
 * empty text_exact used to read as "no selector" and widen the accessibility match
 * set the model then acts on.
 *
 * These cases pin the rule OptionalArgumentReading's own KDoc already states: refuse
 * an unusable declaration, name the argument, and keep tolerating null as absence.
 */
class ScreenAndClipboardArgumentShapeTest {
    private val context = ToolExecutionContext(sessionId = "argument-shape")

    private fun argument(key: String, value: JsonElement): JsonObject = JsonObject(mapOf(key to value))

    // ---------- clipboard read: an unusable bound must not widen the egress ----------

    @Test
    fun anUnusableMaxCharsIsRefusedInsteadOfReadingTheWholeDefaultBudget() = runBlocking {
        val backend = RecordingClipboardBackend()
        listOf(
            "boolean" to JsonPrimitive(true),
            "fractional number" to JsonPrimitive(20.5),
            "object" to buildJsonObject { put("a", 1) },
            "array" to Json.parseToJsonElement("[500]"),
            "non-numeric string" to JsonPrimitive("plenty")
        ).forEach { (label, declared) ->
            val result = ClipboardReadTextTool(backend).execute(
                ToolCall("read-" + label, "clipboard_read_text", argument("maxChars", declared)),
                context
            )
            assertTrue(label + ": the Tool ran anyway: " + result, result.isError)
            assertTrue(
                label + ": refusal must name maxChars: " + result.content,
                result.content.contains("maxChars")
            )
            assertEquals(
                label + ": nothing may be read while the bound is undecided",
                0,
                backend.readCalls
            )
        }
    }

    @Test
    fun aUsableMaxCharsStillBoundsTheReadAndANullStaysAbsent() = runBlocking {
        val backend = RecordingClipboardBackend()
        ClipboardReadTextTool(backend).execute(
            ToolCall("read-500", "clipboard_read_text", buildJsonObject { put("maxChars", 500) }),
            context
        )
        assertEquals(500, backend.lastMaxChars)

        val quoted = RecordingClipboardBackend()
        ClipboardReadTextTool(quoted).execute(
            ToolCall("read-quoted", "clipboard_read_text", buildJsonObject { put("maxChars", "500") }),
            context
        )
        assertEquals("a quoted number is the same request", 500, quoted.lastMaxChars)

        val unset = RecordingClipboardBackend()
        val nullResult = ClipboardReadTextTool(unset).execute(
            ToolCall("read-null", "clipboard_read_text", buildJsonObject { put("maxChars", JsonNull) }),
            context
        )
        assertFalse("an endpoint's unfilled field must not become an error", nullResult.isError)
        assertEquals("an endpoint's unfilled field stays the documented default", 8000, unset.lastMaxChars)
    }

    // ---------- screen read: a bound the model asked for is not the default ----------

    @Test
    fun anUnusableNodeBudgetNamesItselfInsteadOfQuietlyChangingTheSnapshot() = runBlocking {
        val backend = RecordingScreenBackend()
        val result = ScreenReadUiTreeTool(backend).execute(
            ToolCall("read", "screen_read_ui_tree", argument("max_nodes", JsonPrimitive(20.5))),
            context
        )
        assertTrue(
            "a 20.5 node budget was quietly read as the default 200: " + result.content,
            result.isError
        )
        assertTrue(result.content, result.content.contains("max_nodes"))
        assertEquals("the snapshot must not be taken under a different limit", 0, backend.readCount)
    }

    @Test
    fun aUsableNodeBudgetStillReachesTheBackend() = runBlocking {
        val backend = RecordingScreenBackend()
        val result = ScreenReadUiTreeTool(backend).execute(
            ToolCall("read", "screen_read_ui_tree", buildJsonObject { put("max_nodes", 42) }),
            context
        )
        assertFalse(result.isError)
        assertEquals(42, backend.lastMaxNodes)
    }

    // ---------- screen find: a declared selector is never dropped ----------

    @Test
    fun anEmptyDeclaredSelectorIsRefusedInsteadOfMatchingEverythingTheModelFilteredOut() = runBlocking {
        val backend = RecordingScreenBackend()
        val result = ScreenFindUiElementTool(backend).execute(
            ToolCall(
                "find",
                "screen_find_ui_element",
                buildJsonObject {
                    put("text_exact", "")
                    put("type", "Button")
                }
            ),
            context
        )
        assertTrue(
            "an empty exact selector silently became 'no text constraint' and returned every Button",
            result.isError
        )
        assertTrue(result.content, result.content.contains("text_exact"))
    }

    @Test
    fun aSelectorTheModelActuallyWroteStillFindsTheElement() = runBlocking {
        val result = ScreenFindUiElementTool(RecordingScreenBackend()).execute(
            ToolCall("find", "screen_find_ui_element", buildJsonObject { put("text", "Continue") }),
            context
        )
        assertFalse(result.isError)
        assertTrue(result.content, result.content.contains("node-continue"))
    }

    // ---------- every other reader in the family refuses by name ----------

    @Test
    fun anActionSentAsAnObjectIsRefusedWithoutQuotingTheSerializationLibrary() = runBlocking {
        val result = ScreenPerformActionTool(RecordingScreenBackend()).execute(
            ToolCall(
                "act",
                "screen_perform_action",
                buildJsonObject {
                    put("snapshotId", "snapshot-1")
                    put("nodeId", "0.1")
                    putJsonObject("action") { put("name", "click") }
                }
            ),
            context
        )
        assertTrue(result.isError)
        assertTrue(
            "the refusal must name the argument, not the library class: " + result.content,
            result.content.contains("action") && !result.content.contains("JsonPrimitive")
        )
    }

    @Test
    fun anUnreadableCoordinateIsRefusedByTheToolAndNeverDispatched() = runBlocking {
        val backend = RecordingScreenBackend()
        val result = ScreenGestureTool(backend).execute(
            ToolCall(
                "gesture",
                "screen_gesture",
                buildJsonObject {
                    put("action", "tap")
                    put("x", "middle")
                    put("y", 100)
                }
            ),
            context
        )
        assertTrue(result.isError)
        assertEquals(
            "a coordinate nobody can read must not be dispatched as a null position",
            null,
            backend.lastGesture
        )
        assertTrue(
            "the refusal must name x instead of blaming the screen bounds: " + result.content,
            result.content.contains("x")
        )
    }

    @Test
    fun aVisualTargetCoordinateSentAsAnObjectNamesTheFieldThatIsWrong() = runBlocking {
        val result = ScreenVisualGestureTool(RecordingVisualBackend()).execute(
            ToolCall(
                "visual",
                "screen_visual_gesture",
                buildJsonObject {
                    put("observationId", "observation-1")
                    put("action", "tap")
                    putJsonObject("target") {
                        putJsonObject("left") { put("value", 0.1) }
                        put("top", 0.2)
                        put("right", 0.4)
                        put("bottom", 0.6)
                    }
                }
            ),
            context
        )
        assertTrue(result.isError)
        assertTrue(
            "the refusal must name the field the caller has to fix, not just 'target': " + result.content,
            result.content.contains("left")
        )
    }

    private class RecordingClipboardBackend : ClipboardBackend {
        var readCalls = 0
        var lastMaxChars: Int? = null
        private val readResult = ClipboardReadResult(
            code = ClipboardErrorCodes.OK,
            text = "内容".repeat(900),
            itemCount = 1,
            mimeTypes = listOf("text/plain")
        )

        override fun readText(maxChars: Int): ClipboardReadResult {
            readCalls++
            lastMaxChars = maxChars
            return readResult.copy(text = readResult.text?.take(maxChars))
        }

        override fun writeText(text: String, label: String, sensitive: Boolean): ClipboardOperationResult =
            ClipboardOperationResult(ClipboardErrorCodes.OK)

        override fun clear(): ClipboardOperationResult = ClipboardOperationResult(ClipboardErrorCodes.OK)
    }

    private class RecordingScreenBackend : ScreenAutomationBackend {
        var readCount = 0
        var lastMaxNodes: Int? = null
        var lastAction: ScreenActionRequest? = null
        var lastGesture: ScreenGestureRequest? = null

        override fun readUiTree(sessionId: String, maxDepth: Int, maxNodes: Int): ScreenReadResult {
            readCount++
            lastMaxNodes = maxNodes
            return ScreenReadResult(
                snapshot = ScreenUiSnapshot(
                    snapshotId = "snapshot-" + readCount,
                    sessionId = sessionId,
                    packageName = "com.example.target",
                    screenWidth = 1080,
                    screenHeight = 2400,
                    windowCount = 1,
                    nodeCount = 2,
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
                            nodeId = "node-second",
                            windowIndex = 0,
                            packageName = "com.example.target",
                            type = "Button",
                            text = "Continue later",
                            bounds = ScreenBounds(20, 400, 500, 480),
                            actions = listOf(ScreenUiAction(16, "click")),
                            clickable = true
                        )
                    )
                )
            )
        }

        override suspend fun performAction(sessionId: String, request: ScreenActionRequest): ScreenOperationResult {
            lastAction = request
            return ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, nodeId = request.nodeId)
        }

        override suspend fun performGesture(request: ScreenGestureRequest): ScreenOperationResult {
            lastGesture = request
            return ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)
        }

        override suspend fun pressKey(request: ScreenKeyRequest): ScreenOperationResult =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.key)

        override fun performGlobalAction(request: ScreenGlobalActionRequest): ScreenOperationResult =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)
    }

    private class RecordingVisualBackend : ScreenVisualAutomationBackend {
        private val observation = ScreenVisualObservation(
            observationId = "observation-1",
            sessionId = "argument-shape",
            packageName = "com.example.target",
            screenWidth = 1080,
            screenHeight = 2400,
            imageWidth = 576,
            imageHeight = 1280,
            displayId = 0,
            rotation = 0,
            capturedAtEpochMillis = 1L,
            image = AgentImageContent("AQID")
        )

        override suspend fun captureVisualObservation(sessionId: String) =
            ScreenVisualCaptureResult(observation = observation)

        override suspend fun performVisualGesture(
            sessionId: String,
            request: ScreenVisualGestureRequest
        ) = ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)
    }
}
