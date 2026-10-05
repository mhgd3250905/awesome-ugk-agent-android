package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import java.io.File
import java.util.concurrent.Executor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The three ways this round's own display fold went wrong, each pinned here.
 *
 * `DisplayedArgument.Sent` is not a data class, so the first version compared
 * `displayedArgument("sensitive") != Sent("false")` - two different instances every
 * call, always true, and the label said 敏感 for a clipboard write the caller had
 * marked non-sensitive. That sentence is the one thing telling the user whether private
 * text is about to leave the device, and no test read it.
 *
 * Folding `DemoAgentTraceStore.stringValue` onto the bounded argument reader also cut
 * every recorded `recovery` hint to 32 characters mid-word, and the presence flags kept
 * using containsKey, which records an endpoint's JSON null as "the model sent it" - the
 * exact collapse this round exists to remove.
 */
class DemoArgumentRemediationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class ImmediateExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    @Test
    fun theClipboardLabelReportsSensitivityByContentNotByInstance() {
        assertEquals(
            "a write the caller marked non-sensitive must not read as sensitive",
            "写入剪贴板（16 字符，普通）",
            DemoToolSemanticMapper.formatInputSummary(
                "clipboard_write_text",
                buildJsonObject {
                    put("text", "abcdefghijklmnop")
                    put("sensitive", false)
                }
            )
        )
        assertEquals(
            "a quoted false is the same request as the boolean",
            "写入剪贴板（16 字符，普通）",
            DemoToolSemanticMapper.formatInputSummary(
                "clipboard_write_text",
                buildJsonObject {
                    put("text", "abcdefghijklmnop")
                    put("sensitive", "false")
                }
            )
        )
        assertTrue(
            "declared true stays sensitive",
            DemoToolSemanticMapper.formatInputSummary(
                "clipboard_write_text",
                buildJsonObject {
                    put("text", "abcdefghijklmnop")
                    put("sensitive", true)
                }
            ).contains("敏感")
        )
        assertTrue(
            "absent is fail-closed",
            DemoToolSemanticMapper.formatInputSummary(
                "clipboard_write_text",
                buildJsonObject { put("text", "abcdefghijklmnop") }
            ).contains("敏感")
        )
        assertTrue(
            "an unreadable flag is fail-closed too, not an error and not 普通",
            DemoToolSemanticMapper.formatInputSummary(
                "clipboard_write_text",
                buildJsonObject {
                    put("text", "abcdefghijklmnop")
                    putJsonObject("sensitive") { put("value", false) }
                }
            ).contains("敏感")
        )
    }

    @Test
    fun theFindLabelShowsEverySelectorTheToolAcceptsAndOmitsNobodyWrote() {
        val exact = DemoToolSemanticMapper.formatInputSummary(
            "screen_find_ui_element",
            buildJsonObject {
                put("text_exact", "Continue")
                put("content_desc_exact", "Go on")
            }
        )
        assertTrue(exact, exact.contains("text_exact=Continue"))
        assertTrue(exact, exact.contains("content_desc_exact=Go on"))

        val nullSelector = DemoToolSemanticMapper.formatInputSummary(
            "screen_find_ui_element",
            buildJsonObject {
                put("text", JsonPrimitive(null as String?))
                put("type", "Button")
            }
        )
        assertFalse(
            "an endpoint's unfilled field rendered as `text=` - a filter nobody chose: $nullSelector",
            nullSelector.contains("text=")
        )
        assertTrue(nullSelector, nullSelector.contains("type=Button"))
    }

    @Test
    fun theTraceKeepsTheWholeRecoverySentenceAndReportsPresenceHonestly() {
        val traceFile = File(folder.root, "trace.jsonl")
        val store = DemoAgentTraceStore(traceFile, ImmediateExecutor())
        store.append(
            AgentEvent.ToolStarted(
                ToolCall(
                    "call-1",
                    "screen_perform_action",
                    buildJsonObject {
                        put("snapshotId", JsonPrimitive(null as String?))
                        put("nodeId", "0.1")
                        putJsonObject("action") { put("name", "click") }
                    }
                )
            )
        )
        val longRecovery = "Call screen_read_ui_tree or screen_find_ui_element now and use " +
            "only its latest snapshotId and nodeId."
        store.append(
            AgentEvent.ToolFinished(
                ToolResult(
                    toolCallId = "call-1",
                    name = "screen_perform_action",
                    content = "refused",
                    isError = true,
                    metadata = buildJsonObject {
                        put("code", "STALE_SNAPSHOT")
                        put("recovery", longRecovery)
                    }
                )
            )
        )

        val recorded = traceFile.readLines().joinToString("\n")
        assertTrue(
            "the trace must hold the recovery sentence the next reader acts on: $recorded",
            recorded.contains(longRecovery)
        )
        assertTrue(
            "an argument that was not usable is not 'missing': $recorded",
            recorded.contains("action=$UNREADABLE_ARGUMENT_STATE") ||
                recorded.contains("\"action\":\"$UNREADABLE_ARGUMENT_STATE\"")
        )
        val startLine = traceFile.readLines().first { it.contains("tool_started") }
        assertFalse(
            "snapshotId was JSON null, so it was not supplied: $startLine",
            startLine.contains("\"snapshotIdPresent\":true")
        )
        assertTrue(
            "nodeId really was supplied: $startLine",
            startLine.contains("\"nodeIdPresent\":true")
        )
    }

    @Test
    fun theBoundedAndUnboundedReadersStayDistinct() {
        val long = "x".repeat(200)
        val input = buildJsonObject { put("v", long) }
        assertEquals(32, input.displayedArgumentText("v").length)
        assertEquals(200, input.displayedMetadata("v")?.length)
    }
}
