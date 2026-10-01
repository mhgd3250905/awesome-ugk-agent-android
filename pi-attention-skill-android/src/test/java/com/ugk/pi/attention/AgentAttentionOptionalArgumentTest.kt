package com.ugk.pi.attention

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optional urgent-screen arguments a gateway serialized as JSON `null` have to
 * read the same way as a field the model left out.
 *
 * The endpoints this SDK is pointed at are frequently OpenAI-compatible
 * Java/Pojo services that fill every optional field with `null` rather than
 * omitting it; the providers already treat that shape as absent (see the
 * round-7 `ProviderStreamNullFieldTest`), and `readMessage`/`readUrgentBlocks`
 * were the last readers in this module still asking the raw map whether a key
 * was present. A `"blocks": null` used to fail the whole call, so a screen the
 * user was waiting for was refused over a field nobody had filled in.
 *
 * The readings are exercised directly because the Tools that use them need an
 * [AndroidNotificationPublisher], and that needs an Android Context. Which
 * reader the Tool calls is pinned by [declaresControlCasesCoverEveryOptionalControlKey]
 * and by the argument table below, not by a stand-in.
 */
class AgentAttentionOptionalArgumentTest {

    @Test
    fun absentBlocksAndActionsReadAsNoContent() {
        assertEquals(emptyList<UrgentContentBlock>(), buildJsonObject {}.readUrgentBlocks())
        assertEquals(emptyList<UrgentAction>(), buildJsonObject {}.readUrgentActions())
        assertNull(buildJsonObject {}.readUrgentForm())
    }

    @Test
    fun nullBlocksReadAsAbsentInsteadOfRefusingTheScreen() {
        val input = buildJsonObject { put("blocks", JsonNull) }
        assertEquals(emptyList<UrgentContentBlock>(), input.readUrgentBlocks())
    }

    @Test
    fun nullActionsReadAsAbsentInsteadOfRefusingTheScreen() {
        val input = buildJsonObject { put("actions", JsonNull) }
        assertEquals(emptyList<UrgentAction>(), input.readUrgentActions())
    }

    @Test
    fun nullFormReadsAsAbsentAndIsNotTreatedAsARequestForControls() {
        val absent = buildJsonObject {}
        val nullForm = buildJsonObject { put("form", JsonNull) }
        val declared = buildJsonObject {
            put("form", buildJsonObject {
                put("id", "answer")
                put("label", "答案")
                put("submitLabel", "提交")
            })
        }
        assertNull(nullForm.readUrgentForm())
        assertFalse("a null form is not a request for a form", nullForm.declaresControl("form"))
        assertFalse(absent.declaresControl("form"))
        assertTrue(declared.declaresControl("form"))
    }

    @Test
    fun nullPlaceholderReadsAsNoHintInsteadOfRefusingTheForm() {
        val input = buildJsonObject {
            put("form", buildJsonObject {
                put("id", "answer")
                put("label", "答案")
                put("placeholder", JsonNull)
                put("submitLabel", "提交")
            })
        }
        assertEquals(UrgentForm("answer", "答案", "", "提交"), input.readUrgentForm())
    }

    /**
     * The guard has to stay strict in the other direction: a declared value of
     * the wrong shape is still refused rather than silently dropped, otherwise
     * this fix would degrade into "ignore whatever the model asked for".
     */
    private class Case(
        val label: String,
        val controlKey: String,
        val input: JsonObject,
        val read: JsonObject.() -> Any?
    )

    @Test
    fun declaredButUnusableArgumentsAreStillRefused() {
        val readBlocks: JsonObject.() -> Any? = { readUrgentBlocks() }
        val readActions: JsonObject.() -> Any? = { readUrgentActions() }
        val readForm: JsonObject.() -> Any? = { readUrgentForm() }

        val cases = listOf(
            Case(
                "blocks as a number",
                "blocks",
                buildJsonObject { put("blocks", 5) },
                readBlocks
            ),
            Case(
                "blocks as an object",
                "blocks",
                buildJsonObject { put("blocks", buildJsonObject { put("a", "b") }) },
                readBlocks
            ),
            Case(
                "unknown block type",
                "blocks",
                buildJsonObject {
                    put("blocks", buildJsonArray {
                        add(buildJsonObject { put("type", "sidestep"); put("text", "内容") })
                    })
                },
                readBlocks
            ),
            Case(
                "block with an extra key",
                "blocks",
                buildJsonObject {
                    put("blocks", buildJsonArray {
                        add(buildJsonObject { put("type", "paragraph"); put("text", "内容"); put("url", "x") })
                    })
                },
                readBlocks
            ),
            Case(
                "nine blocks",
                "blocks",
                buildJsonObject {
                    put("blocks", buildJsonArray {
                        repeat(9) {
                            add(buildJsonObject { put("type", "paragraph"); put("text", "内容") })
                        }
                    })
                },
                readBlocks
            ),
            Case(
                "actions as a string",
                "actions",
                buildJsonObject { put("actions", "confirm") },
                readActions
            ),
            Case(
                "duplicate action ids",
                "actions",
                buildJsonObject {
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("id", "ok"); put("label", "好") })
                        add(buildJsonObject { put("id", "ok"); put("label", "好") })
                    })
                },
                readActions
            ),
            Case(
                "form as an array",
                "form",
                buildJsonObject { put("form", buildJsonArray {}) },
                readForm
            ),
            Case(
                "form with an id that is not an identifier",
                "form",
                buildJsonObject {
                    put("form", buildJsonObject {
                        put("id", "1 bad id"); put("label", "答案"); put("submitLabel", "提交")
                    })
                },
                readForm
            ),
            Case(
                "form with a non-string placeholder",
                "form",
                buildJsonObject {
                    put("form", buildJsonObject {
                        put("id", "answer"); put("label", "答案"); put("placeholder", 7); put("submitLabel", "提交")
                    })
                },
                readForm
            )
        )
        for (case in cases) {
            assertTrue(
                "${case.label}: a declared control key must stay visible",
                case.input.declaresControl(case.controlKey)
            )
            assertNull(case.label, case.read(case.input))
        }
    }


    @Test
    fun wellFormedArgumentsStillParse() {
        val blocks = buildJsonObject {
            put("blocks", buildJsonArray {
                add(buildJsonObject { put("type", "HEADING"); put("text", "标题") })
                add(buildJsonObject { put("type", "paragraph"); put("text", "内容") })
            })
        }
        assertEquals(
            listOf(
                UrgentContentBlock(UrgentBlockType.HEADING, "标题"),
                UrgentContentBlock(UrgentBlockType.PARAGRAPH, "内容")
            ),
            blocks.readUrgentBlocks()
        )
        val actions = buildJsonObject {
            put("actions", buildJsonArray {
                add(buildJsonObject { put("id", "confirm"); put("label", "确认") })
                add(buildJsonObject { put("id", "snooze-it"); put("label", "稍后") })
            })
        }
        assertEquals(listOf(UrgentAction("confirm", "确认"), UrgentAction("snooze-it", "稍后")), actions.readUrgentActions())
        assertEquals(emptyList<UrgentContentBlock>(), buildJsonObject { put("blocks", buildJsonArray {}) }.readUrgentBlocks())
    }

    /**
     * Folded over every optional control key this module reads, so a new one
     * cannot be added with a raw presence test and no null case.
     */
    @Test
    fun declaresControlCasesCoverEveryOptionalControlKey() {
        val optionalControlKeys = listOf("blocks", "actions", "form", "accent", "reason", "placeholder")
        for (key in optionalControlKeys) {
            assertFalse(
                "$key: absent must not declare",
                buildJsonObject {}.declaresControl(key)
            )
            assertFalse(
                "$key: JsonNull must not declare",
                buildJsonObject { put(key, JsonNull) }.declaresControl(key)
            )
            assertTrue(
                "$key: a value must declare",
                buildJsonObject { put(key, "value") }.declaresControl(key)
            )
        }
    }
}
