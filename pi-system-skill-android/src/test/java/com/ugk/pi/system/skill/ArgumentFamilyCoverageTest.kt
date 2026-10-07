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
 * Four Tools in this module read a model argument and need an Android Context to run
 * at all: `find_android_app`, `launch_android_app`, `open_android_settings_page` and
 * `launch_android_app_intent`. Their argument *decisions* are pinned here through the
 * shared `DeclaredArgument` readers they route through, including the trim rule; their
 * `execute` wiring - which error code the refusal carries - is not host-reachable and
 * is registered as a measured limitation with the device command in the review record.
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
    }

    /**
     * A library fact, not a regression pin: this holds with the production change fully
     * reverted, and exists so a reader can check the premise instead of re-deriving it.
     * `element.jsonPrimitive` throws for a structured value while `as? JsonPrimitive`
     * reads it as "not a primitive"; the fold itself is pinned by the table above.
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

    /**
     * Blank means different things per selector kind, and getting this wrong either
     * way is a defect: refusing a blank substring selector rejects a call that asked for
     * nothing unusual, while dropping a blank exact selector silently widens the match
     * set the model then acts on.
     */
    @Test
    fun aBlankSubstringSelectorStillMeansNoConstraintAndABlankExactSelectorIsRefused() {
        val substring = runBlocking {
            ScreenFindUiElementTool(RecordingBackend()).execute(
                ToolCall(
                    "find",
                    "screen_find_ui_element",
                    buildJsonObject {
                        put("text", "")
                        put("type", "Button")
                    }
                ),
                context
            )
        }
        assertFalse(
            "an empty substring selector contains everything, exactly as dropping it does: " +
                substring.content,
            substring.isError
        )
        assertTrue(substring.content, substring.content.contains("node-continue"))

        val identity = runBlocking {
            ScreenFindUiElementTool(RecordingBackend()).execute(
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
        }
        assertTrue(identity.isError)
        assertTrue(identity.content, identity.content.contains("'text_exact'"))
    }

    @Test
    fun anUnreadableLimitIsNamedEvenBesideAValidSelector() {
        val result = runBlocking {
            ScreenFindUiElementTool(RecordingBackend()).execute(
                ToolCall(
                    "find",
                    "screen_find_ui_element",
                    buildJsonObject {
                        put("type", "Button")
                        put("max_results", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(5))))
                    }
                ),
                context
            )
        }
        assertTrue(result.isError)
        assertTrue(
            "the refusal must name max_results, not the healthy selector: " + result.content,
            result.content.contains("'max_results'")
        )
    }

    /**
     * The trim the round first dropped: `launch_android_app` matches an anchored pattern
     * and `find_android_app` compares labels directly, so an untrimmed value is either
     * refused or scores zero and answers ok with nothing. Those two Tools need an
     * Android Context, so what is pinned here is the shared decision they now both route
     * through; the Tool-level wiring is a registered device-side gap.
     */
    @Test
    fun theTrimRuleKeepsPaddedValuesUsable() {
        assertEquals(
            "com.example.app",
            (JsonObject(mapOf("v" to JsonPrimitive("  com.example.app "))).declaredTrimmed("v")
                as DeclaredArgument.Of).value
        )
        assertEquals(
            "a value that is only whitespace is nobody-wrote-it, not an empty name",
            DeclaredArgument.Undeclared,
            JsonObject(mapOf("v" to JsonPrimitive("   "))).declaredTrimmed("v")
        )
        assertEquals(
            DeclaredArgument.Undeclared,
            kotlinx.serialization.json.JsonObject(emptyMap()).declaredTrimmed("v")
        )
        assertEquals(
            DeclaredArgument.Unusable,
            JsonObject(mapOf("v" to structured)).declaredTrimmed("v")
        )
    }

    /**
     * Two arms the closing round's own probe found.
     *
     * `doubleOrNull` measured NaN and +/-Infinity for both the bare and the quoted
     * spelling, and Kotlin's `Double.roundToInt()` *throws* on them - so the reader that
     * exists to never throw was throwing, in its own integral-double branch
     * (build/review-evidence/r14-value-domain.txt, rows INT "NaN" / NaN / roundToInt=THROWS).
     *
     * A whitespace substring selector is a filter the caller stated ("matches anything
     * containing a space"), not an absent one; only the identity selectors treat a blank
     * value as meaningless.
     */
    @Test
    fun nonFiniteNumbersAreRefusedInsteadOfThrowing() {
        listOf<JsonElement>(
            JsonPrimitive(Double.NaN),
            JsonPrimitive(Double.POSITIVE_INFINITY),
            JsonPrimitive(Double.NEGATIVE_INFINITY),
            JsonPrimitive("NaN"),
            JsonPrimitive("Infinity"),
            JsonPrimitive(1e30),
            JsonPrimitive(2147483648.0)
        ).forEach { element ->
            val failure = runCatching { JsonObject(mapOf("v" to element)).declaredInt("v") }
            assertTrue("declaredInt must not throw for $element", failure.isSuccess)
            assertEquals(
                "and must refuse the value rather than default it: $element",
                DeclaredArgument.Unusable,
                failure.getOrNull()
            )
        }
    }

    @Test
    fun aWhitespaceSubstringSelectorIsAFilterNotAnAbsentOne() {
        val result = runBlocking {
            ScreenFindUiElementTool(RecordingBackend()).execute(
                ToolCall(
                    "find",
                    "screen_find_ui_element",
                    buildJsonObject {
                        put("text", "\t")
                        put("type", "Button")
                    }
                ),
                context
            )
        }
        assertFalse("a tab filter is a real request: " + result.content, result.isError)
        assertTrue(
            "and it must narrow the result set instead of being dropped: " + result.content,
            result.content.contains("\"totalCount\":0")
        )
    }

    /**
     * A selector key this tool does not implement is not a constraint, and dropping it is
     * not neutral: the match set then holds everything the *remaining* selectors allow, which
     * is wider than the caller asked for and is what the model then acts on. The
     * `else -> false` arm of `matchesSelector` cannot cover this - the argument reader never
     * hands an unknown key to the matcher - so the refusal belongs where the arguments are
     * read, naming the key it cannot honour.
     */
    @Test
    fun aSelectorKeyThisToolDoesNotImplementIsRefusedInsteadOfSilentlyDropped() {
        val widened = runBlocking {
            ScreenFindUiElementTool(RecordingBackend()).execute(
                ToolCall(
                    "find",
                    "screen_find_ui_element",
                    buildJsonObject {
                        put("text", "Continue")
                        put("textContains", "Exit")
                    }
                ),
                context
            )
        }

        assertTrue(
            "an unsupported selector key silently removed one constraint: ${widened.content}",
            widened.isError
        )
        assertTrue(
            "the refusal must name the key the caller has to fix: ${widened.content}",
            widened.content.contains("textContains")
        )

        // The other direction: the new check must not reject a call that only used the
        // keys this tool implements.
        val supported = runBlocking {
            ScreenFindUiElementTool(RecordingBackend()).execute(
                ToolCall(
                    "find",
                    "screen_find_ui_element",
                    buildJsonObject {
                        put("text", "Continue")
                        put("max_results", 3)
                    }
                ),
                context
            )
        }
        assertFalse(
            "a supported selector and a supported limit must still run: ${supported.content}",
            supported.isError
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
