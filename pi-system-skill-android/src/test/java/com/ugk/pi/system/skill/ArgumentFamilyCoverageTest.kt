package com.ugk.pi.system.skill

import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule, every landing point.
 *
 * This table is the fold `OptionalArgumentReading`'s KDoc demands: each Tool in this
 * module that reads a model-authored argument must (a) never throw for a structured
 * value, (b) refuse it naming that argument, and (c) keep treating JSON null as
 * absence. Round 13 fixed only the Anthropic streaming arm of a four-arm rule, so
 * every key of every Tool is driven here rather than sampled, and the expected count
 * is asserted independently so a shrinking table cannot pass.
 *
 * The four Tools in this module that need an Android Context (`find_android_app`,
 * `launch_android_app`, `open_android_system_page`) are covered only through the
 * shared `DeclaredArgument` judgement they route through, not through their own
 * `execute`; that half is stated as a measured limitation in the review record.
 */
class ArgumentFamilyCoverageTest {
    private val context = ToolExecutionContext(sessionId = "family")
    private val structured: JsonElement = buildJsonObject { put("nested", 1) }

    private class Case(
        val toolName: String,
        val tool: AgentTool,
        val keys: List<String>
    )

    private val cases = listOf(
        Case("screen_read_ui_tree", ScreenReadUiTreeTool(RecordingBackend()), listOf("max_depth", "max_nodes")),
        Case(
            "screen_find_ui_element",
            ScreenFindUiElementTool(RecordingBackend()),
            listOf("text", "text_exact", "content_desc", "content_desc_exact", "view_id", "type", "max_results")
        ),
        Case(
            "screen_perform_action",
            ScreenPerformActionTool(RecordingBackend()),
            listOf("snapshotId", "nodeId", "action", "text")
        ),
        Case("screen_gesture", ScreenGestureTool(RecordingBackend()), listOf("action", "x", "y")),
        Case("screen_press_key", ScreenPressKeyTool(RecordingBackend()), listOf("key")),
        Case("screen_global_action", ScreenGlobalActionTool(RecordingBackend()), listOf("action")),
        Case(
            "screen_visual_gesture",
            ScreenVisualGestureTool(RecordingVisualBackend()),
            listOf("observationId", "action", "targetDescription")
        ),
        Case("clipboard_read_text", ClipboardReadTextTool(RecordingClipboard()), listOf("maxChars")),
        Case("clipboard_write_text", ClipboardWriteTextTool(RecordingClipboard()), listOf("text", "label"))
    )

    @Test
    fun everyStructuredArgumentIsRefusedByNameByItsTool() {
        val expectedKeys = cases.sumOf { it.keys.size }
        assertEquals("the table must name every argument of every Tool in the family", 24, expectedKeys)

        cases.forEach { case ->
            case.keys.forEach { key ->
                val result = runBlocking {
                    case.tool.execute(
                        ToolCall(case.toolName + "-" + key, case.toolName, JsonObject(mapOf(key to structured))),
                        context
                    )
                }
                assertTrue(case.toolName + "." + key + ": must be refused", result.isError)
                assertTrue(
                    case.toolName + "." + key + ": refusal must name the argument, got: " + result.content,
                    result.content.contains("'" + key + "'")
                )
                assertFalse(
                    case.toolName + "." + key + ": must not quote the serialization library: " + result.content,
                    result.content.contains("JsonPrimitive") || result.content.contains("JsonObject")
                )
            }
        }
    }

    @Test
    fun aStructuredVisualTargetCoordinateNamesItsOwnField() {
        val result = runBlocking {
            ScreenVisualGestureTool(RecordingVisualBackend()).execute(
                ToolCall(
                    "visual",
                    "screen_visual_gesture",
                    buildJsonObject {
                        put("observationId", "observation-1")
                        put("action", "tap")
                        putJsonObject("target") {
                            putJsonObject("left") { put("value", 0.1) }
                            put("top", 0.1)
                            put("right", 0.5)
                            put("bottom", 0.6)
                        }
                    }
                ),
                context
            )
        }
        assertTrue(result.isError)
        assertTrue(
            "the refusal must name target.left, not just target: " + result.content,
            result.content.contains("'target.left'")
        )
    }

    @Test
    fun jsonNullStillMeansNobodyFilledItIn() {
        val backend = RecordingClipboard()
        val read = runBlocking {
            ClipboardReadTextTool(backend).execute(
                ToolCall("read", "clipboard_read_text", JsonObject(mapOf("maxChars" to JsonNull))),
                context
            )
        }
        assertFalse("an endpoint's unfilled field must not become an error", read.isError)
        assertEquals("and it keeps the documented bound", 8000, backend.lastMaxChars)

        val unset = runBlocking {
            ScreenReadUiTreeTool(RecordingBackend()).execute(
                ToolCall("read", "screen_read_ui_tree", buildJsonObject { put("max_nodes", JsonNull) }),
                context
            )
        }
        assertFalse("JSON null on a snapshot limit is absence, not a refusal", unset.isError)
    }

    @Test
    fun anUnusableIntentParameterIsRefusedInsteadOfDropped() {
        val dropped = buildJsonObject {
            put("target", "open_url")
            putJsonObject("parameters") { put("subject", structured) }
        }
        assertEquals(
            "an entry that is an object was supplied; dropping it dispatches an Intent " +
                "missing an extra the caller named",
            null,
            dropped.appIntentParameters()
        )
        val tolerated = buildJsonObject {
            put("target", "open_url")
            putJsonObject("parameters") { put("subject", JsonNull); put("body", "text") }
        }
        assertEquals(mapOf("body" to "text"), tolerated.appIntentParameters())
        val usable = buildJsonObject {
            put("target", "open_url")
            putJsonObject("parameters") { put("subject", "hi") }
        }
        assertEquals(mapOf("subject" to "hi"), usable.appIntentParameters())
    }

    @Test
    fun theSharedRuleReadsEveryShapeWithoutThrowing() {
        val shapes: List<Pair<String, JsonElement>> = listOf(
            "object" to structured,
            "array" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(1))),
            "fraction" to JsonPrimitive(20.5),
            "boolean" to JsonPrimitive(true),
            "text" to JsonPrimitive("plenty"),
            "exponential" to JsonPrimitive("2e3"),
            "integral double" to JsonPrimitive(2000.0)
        )
        val refusals = shapes.map { (_, element) ->
            val parsed = JsonObject(mapOf("v" to element)).declaredInt("v")
            if (parsed === DeclaredArgument.Unusable) null else (parsed as DeclaredArgument.Of).value
        }
        assertEquals(
            "only an exponential or integral double is the same request; the rest name themselves",
            listOf<Int?>(null, null, null, null, null, 2000, 2000),
            refusals
        )
    }

    /**
     * The one arm in the family that must NOT become a refusal: an unreadable
     * `sensitive` still writes, and writes as sensitive. A guard that rejects a legal
     * operation is worse than no guard (round 13), and fail-closed was already the
     * documented direction here.
     */
    @Test
    fun anUnreadableSensitiveFlagStaysFailClosedInsteadOfThrowingOrRefusing() {
        val backend = RecordingClipboard()
        var capturedSensitive: Boolean? = null
        val recording = object : ClipboardBackend {
            override fun readText(maxChars: Int) = ClipboardReadResult(code = ClipboardErrorCodes.OK)
            override fun writeText(text: String, label: String, sensitive: Boolean): ClipboardOperationResult {
                capturedSensitive = sensitive
                return ClipboardOperationResult(ClipboardErrorCodes.OK)
            }

            override fun clear() = ClipboardOperationResult(ClipboardErrorCodes.OK)
        }
        val result = runBlocking {
            ClipboardWriteTextTool(recording).execute(
                ToolCall(
                    "write",
                    "clipboard_write_text",
                    buildJsonObject {
                        put("text", "内容")
                        putJsonObject("sensitive") { put("value", false) }
                    }
                ),
                context
            )
        }
        assertFalse("a legal write must not be refused because of one unreadable flag", result.isError)
        assertEquals("and it must be recorded as sensitive", true, capturedSensitive)
        assertEquals(null, backend.lastMaxChars)
    }

    /**
     * The library fact this whole rule rests on, pinned so a future reader can check it
     * instead of re-deriving it: `element.jsonPrimitive` throws for a structured value,
     * while `as? JsonPrimitive` reads it as "not a primitive". Measured in
     * build/review-evidence/r14-shape-oracle.txt.
     */
    @Test
    fun theThrowingAccessorIsWhyTheRuleNeedsToExist() {
        val structured: JsonElement = buildJsonObject { put("nested", 1) }
        val threw = runCatching { structured.jsonPrimitive.contentOrNull }.isFailure
        assertTrue("the accessor the family used must throw for a structured value", threw)
        assertEquals(
            "and the safe cast used by the readers now must not",
            null,
            runCatching { (structured as? JsonPrimitive)?.contentOrNull }.getOrNull()
        )
    }

    /**
     * The pre-pass exists because an argument-check order can answer with a true
     * sentence about the WRONG argument: `clipboard_write_text` with only an object in
     * the `label` slot used to be refused as "text is required", which is accurate about
     * `text` and sends the caller away from the value they actually broke.
     */
    @Test
    fun anUnreadableArgumentIsNamedEvenWhenAnotherOneIsMissing() {
        val result = runBlocking {
            ClipboardWriteTextTool(RecordingClipboard()).execute(
                ToolCall("write", "clipboard_write_text", JsonObject(mapOf("label" to structured))),
                context
            )
        }
        assertTrue(result.isError)
        assertTrue(
            "the refusal must name label, not the absent text: " + result.content,
            result.content.contains("'label'") && !result.content.contains("text is required")
        )
    }

    @Test
    fun theSharedRuleSeparatesAnUnfilledFieldFromAnUnreadableOne() {
        val stringArms = listOf(
            "json-null" to JsonNull,
            "object" to structured,
            "array" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(1))),
            "blank" to JsonPrimitive("  "),
            "quoted-number" to JsonPrimitive("20"),
            "text" to JsonPrimitive("note")
        ).map { (_, element) ->
            JsonObject(mapOf("v" to element)).declaredString("v")::class.simpleName
        }
        assertEquals(
            "null is absence, an object or array is unusable, and every primitive - blank " +
                "included - is a value the caller sent",
            listOf(
                "Undeclared", "Unusable", "Unusable",
                "Of", "Of", "Of"
            ),
            stringArms
        )
        assertEquals(
            "a blank string is still a string that WAS sent; only non-blank readers collapse it",
            "  ",
            (JsonObject(mapOf("v" to JsonPrimitive("  "))).declaredString("v") as DeclaredArgument.Of).value
        )
        assertEquals(
            kotlinx.serialization.json.JsonObject(emptyMap()).declaredNonBlank("v"),
            DeclaredArgument.Undeclared
        )
        assertEquals(
            JsonObject(mapOf("v" to JsonPrimitive("   "))).declaredNonBlank("v"),
            DeclaredArgument.Undeclared
        )
        assertEquals(
            JsonObject(mapOf("v" to structured)).declaredNonBlank("v"),
            DeclaredArgument.Unusable
        )
    }

    private class RecordingBackend : ScreenAutomationBackend {
        override fun readUiTree(sessionId: String, maxDepth: Int, maxNodes: Int): ScreenReadResult =
            ScreenReadResult(
                snapshot = ScreenUiSnapshot(
                    snapshotId = "snapshot-1",
                    sessionId = sessionId,
                    packageName = "com.example.target",
                    screenWidth = 1080,
                    screenHeight = 2400,
                    windowCount = 1,
                    nodeCount = 1,
                    truncated = false,
                    elements = listOf(
                        ScreenUiElement(
                            nodeId = "node-continue",
                            windowIndex = 0,
                            packageName = "com.example.target",
                            type = "Button",
                            text = "Continue",
                            bounds = ScreenBounds(0, 0, 10, 10)
                        )
                    )
                )
            )

        override suspend fun performAction(sessionId: String, request: ScreenActionRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK)

        override suspend fun performGesture(request: ScreenGestureRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK)

        override suspend fun pressKey(request: ScreenKeyRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK)

        override fun performGlobalAction(request: ScreenGlobalActionRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK)
    }

    private class RecordingVisualBackend : ScreenVisualAutomationBackend {
        override suspend fun captureVisualObservation(sessionId: String) =
            ScreenVisualCaptureResult(code = ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_UNSUPPORTED)

        override suspend fun performVisualGesture(sessionId: String, request: ScreenVisualGestureRequest) =
            ScreenOperationResult(true, ScreenAutomationErrorCodes.OK, action = request.action)
    }

    private class RecordingClipboard : ClipboardBackend {
        var lastMaxChars: Int? = null

        override fun readText(maxChars: Int): ClipboardReadResult {
            lastMaxChars = maxChars
            return ClipboardReadResult(code = ClipboardErrorCodes.OK, text = "x", itemCount = 1)
        }

        override fun writeText(text: String, label: String, sensitive: Boolean) =
            ClipboardOperationResult(ClipboardErrorCodes.OK)

        override fun clear() = ClipboardOperationResult(ClipboardErrorCodes.OK)
    }
}
