package com.ugk.pi.attention

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
 * omitting it - the shape round 7 had to fix on the providers' *response* side,
 * where `"delta":null` aborted a stream. Nothing strips null-valued keys out of a
 * tool's arguments on the way in, so every reader of an optional argument decides
 * this for itself, and these readers asked the raw map: a `"blocks": null` failed
 * the whole call, so a screen the user was waiting for was refused over a field
 * nobody had filled in.
 *
 * The readers are exercised directly because the Tools that call them need an
 * [AndroidNotificationPublisher], and that needs an Android
 * [android.content.Context]. The cost of that seam is stated rather than hidden:
 * these cases pin what a reader answers and which keys the Tools look at, not that
 * a Tool routes a given key through a given reader - that half needs the device
 * side.
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
     *
     * One test per reader, because the cases used to share a single loop over all
     * three: the first refused `blocks` case then hid the `actions` and `form`
     * discriminators, which is exactly how a rule that survives at only one landing
     * point gets past review.
     */
    private class Case(val label: String, val input: JsonObject)

    @Test
    fun declaredGarbageBlocksAreStillRefused() {
        assertDeclaredGarbageRefused("blocks", { readUrgentBlocks() },
            Case("blocks as a number", buildJsonObject { put("blocks", 5) }),
            Case("blocks as an object", buildJsonObject { put("blocks", buildJsonObject { put("a", "b") }) }),
            Case(
                "unknown block type",
                buildJsonObject {
                    put("blocks", buildJsonArray {
                        add(buildJsonObject { put("type", "sidestep"); put("text", "内容") })
                    })
                }
            ),
            Case(
                "block with an extra key",
                buildJsonObject {
                    put("blocks", buildJsonArray {
                        add(buildJsonObject { put("type", "paragraph"); put("text", "内容"); put("url", "x") })
                    })
                }
            ),
            Case(
                "nine blocks",
                buildJsonObject {
                    put("blocks", buildJsonArray {
                        repeat(9) {
                            add(buildJsonObject { put("type", "paragraph"); put("text", "内容") })
                        }
                    })
                }
            )
        )
    }

    @Test
    fun declaredGarbageActionsAreStillRefused() {
        assertDeclaredGarbageRefused("actions", { readUrgentActions() },
            Case("actions as a string", buildJsonObject { put("actions", "confirm") }),
            Case(
                "duplicate action ids",
                buildJsonObject {
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("id", "ok"); put("label", "好") })
                        add(buildJsonObject { put("id", "ok"); put("label", "好") })
                    })
                }
            )
        )
    }

    @Test
    fun declaredGarbageFormIsStillRefused() {
        assertDeclaredGarbageRefused("form", { readUrgentForm() },
            Case("form as an array", buildJsonObject { put("form", buildJsonArray {}) }),
            Case(
                "form with an id that is not an identifier",
                buildJsonObject {
                    put("form", buildJsonObject {
                        put("id", "1 bad id"); put("label", "答案"); put("submitLabel", "提交")
                    })
                }
            ),
            Case(
                "form with a non-string placeholder",
                buildJsonObject {
                    put("form", buildJsonObject {
                        put("id", "answer"); put("label", "答案"); put("placeholder", 7); put("submitLabel", "提交")
                    })
                }
            )
        )
    }

    private fun assertDeclaredGarbageRefused(
        controlKey: String,
        read: JsonObject.() -> Any?,
        vararg cases: Case
    ) {
        for (case in cases) {
            assertTrue(
                "${case.label}: a declared control key must stay visible",
                case.input.declaresControl(controlKey)
            )
            assertNull("${case.label} must be refused", read(case.input))
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
     * Every optional argument the urgent-message schema declares, with the reader
     * that owns it. The fold below is what makes this list a check rather than a
     * list: the schema's own optional keys must equal the keys that have a reader
     * waiting here, so a new optional argument cannot arrive without a case.
     */
    private val urgentOptionalReaders: Map<String, UrgentOptionalReader> = mapOf(
        "accent" to UrgentOptionalReader({ readUrgentAccent() }, UrgentAccent.AMBER),
        "blocks" to UrgentOptionalReader({ readUrgentBlocks() }, emptyList<UrgentContentBlock>()),
        "actions" to UrgentOptionalReader({ readUrgentActions() }, emptyList<UrgentAction>()),
        "form" to UrgentOptionalReader({ readUrgentForm() }, null)
    )

    private class UrgentOptionalReader(
        val read: JsonObject.() -> Any?,
        val default: Any?
    )

    /**
     * The optional keys are read off the tool's own schema rather than a list
     * written next to this test, so a new optional argument arrives with a reader
     * case already waiting for it - and the expected set is pinned by literal too,
     * otherwise a schema edit could quietly shrink the fold to nothing.
     */
    @Test
    fun everyOptionalArgumentOfTheUrgentSchemaHasAReaderWaitingForIt() {
        val schema = urgentMessageSchema()
        val properties = (schema["properties"] as JsonObject).keys
        val required = (schema["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(
            "the urgent-message tool's optional arguments",
            setOf("accent", "blocks", "actions", "form"),
            properties - required.toSet()
        )
        assertEquals(
            "every optional argument needs a reader, and only the declared ones do",
            properties - required.toSet(),
            urgentOptionalReaders.keys
        )
    }

    /**
     * One test per optional argument: a shared loop would let the first key that
     * goes red hide the other three, which is the same masking that made the
     * schema fold worth splitting in the first place.
     *
     * `declaresControl` alone does not care which key it is handed, so each key is
     * also asserted through *its own reader* - the default it answers with when a
     * gateway filled it with `null`.
     */
    @Test
    fun nullAccentReadsAsTheDefaultAndDoesNotDeclare() = assertNullOptionalArgumentReadsAsTheDefault("accent")

    @Test
    fun nullBlocksReadsAsTheDefaultAndDoesNotDeclare() = assertNullOptionalArgumentReadsAsTheDefault("blocks")

    @Test
    fun nullActionsReadsAsTheDefaultAndDoesNotDeclare() = assertNullOptionalArgumentReadsAsTheDefault("actions")

    @Test
    fun nullFormReadsAsTheDefaultAndDoesNotDeclare() = assertNullOptionalArgumentReadsAsTheDefault("form")

    private fun assertNullOptionalArgumentReadsAsTheDefault(key: String) {
        val reader = urgentOptionalReaders.getValue(key)
        val nullArgument = buildJsonObject { put(key, JsonNull) }
        assertEquals(
            "$key serialized as null must read as the default, not a refusal",
            reader.default,
            reader.read(nullArgument)
        )
        assertFalse("$key: a null is not a request for that control", nullArgument.declaresControl(key))
        assertFalse("$key: absent must not declare", buildJsonObject {}.declaresControl(key))
        assertTrue(
            "$key: a value must declare",
            buildJsonObject { put(key, "value") }.declaresControl(key)
        )
    }

    /**
     * Both tools publish `additionalProperties: false`, so a key outside the schema
     * is something the model was told not to send - and a host that cannot route
     * interactions must not accept `actions`. Three things have to hold at once, and
     * the second is the shape this guard itself broke when it was first written:
     * a null-valued key is not a declaration, so a gateway filling every optional
     * with null must still get its screen.
     *
     * The expected key sets are literals, not the schema read back: asserting
     * `messageArgumentKeys(…)` against the schema it derives from is true by
     * construction and would keep passing even if the derivation started answering
     * the wrong shape. The literals are what make the derivation a test subject.
     */
    @Test
    fun eachToolValidatesAgainstTheArgumentsItsOwnSchemaPublishes() {
        assertEquals(
            "the notification tool declares only title and body",
            setOf("title", "body"),
            messageArgumentKeys(withReason = false, withInteractions = false)
        )
        assertEquals(
            "a non-interactive urgent host has no controls to accept",
            setOf("title", "body", "reason", "accent", "blocks"),
            messageArgumentKeys(withReason = true, withInteractions = false)
        )
        assertEquals(
            "an interactive urgent host adds exactly actions and form",
            setOf("title", "body", "reason", "accent", "blocks", "actions", "form"),
            messageArgumentKeys(withReason = true, withInteractions = true)
        )
    }

    @Test
    fun undeclaredArgumentNamesAreVisibleToBothTools() {
        val notificationKeys = messageArgumentKeys(withReason = false, withInteractions = false)
        assertFalse(
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
            }.declaresUndeclaredArgument(notificationKeys)
        )
        assertTrue(
            "accent is not an argument of agent_send_notification",
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
                put("accent", "red")
            }.declaresUndeclaredArgument(notificationKeys)
        )
        assertTrue(
            "an urgency knob is not an argument either",
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
                put("urgency", "critical")
            }.declaresUndeclaredArgument(notificationKeys)
        )

        val nonInteractiveKeys = messageArgumentKeys(withReason = true, withInteractions = false)
        val interactiveKeys = messageArgumentKeys(withReason = true, withInteractions = true)
        assertFalse(
            "a declared blocks list is fine on either host",
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
                put("reason", "理由")
                put("blocks", JsonArray(emptyList()))
            }.declaresUndeclaredArgument(nonInteractiveKeys)
        )
        assertTrue(
            "a non-interactive host must refuse actions it cannot route",
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
                put("reason", "理由")
                put("actions", buildJsonArray { add(buildJsonObject { put("id", "ok"); put("label", "好") }) })
            }.declaresUndeclaredArgument(nonInteractiveKeys)
        )
        assertFalse(
            "an interactive host accepts actions",
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
                put("reason", "理由")
                put("actions", JsonArray(emptyList()))
            }.declaresUndeclaredArgument(interactiveKeys)
        )
        assertFalse(
            "a null actions/form is an unfilled optional, even on a non-interactive host",
            buildJsonObject {
                put("title", "标题")
                put("body", "正文")
                put("reason", "理由")
                put("blocks", JsonNull)
                put("actions", JsonNull)
                put("form", JsonNull)
            }.declaresUndeclaredArgument(nonInteractiveKeys)
        )
    }

    @Test
    fun accentDefaultsOnlyWhenItWasNotFilledIn() {
        assertEquals(UrgentAccent.AMBER, buildJsonObject {}.readUrgentAccent())
        assertEquals(UrgentAccent.AMBER, buildJsonObject { put("accent", JsonNull) }.readUrgentAccent())
        assertEquals(UrgentAccent.RED, buildJsonObject { put("accent", "RED") }.readUrgentAccent())
        assertEquals(UrgentAccent.BLUE, buildJsonObject { put("accent", "blue") }.readUrgentAccent())
    }

    /** Declared-but-not-an-accent must be refused, not silently recoloured to amber. */
    @Test
    fun declaredNonAccentValueIsRefused() {
        val cases = listOf(
            "a number" to JsonPrimitive(5),
            "an unknown name" to JsonPrimitive("purple"),
            "an empty string" to JsonPrimitive("")
        )
        for ((label, primitive) in cases) {
            val input = buildJsonObject { put("accent", primitive) }
            assertTrue("$label must declare a value", input.declaresControl("accent"))
            assertNull("$label must be refused, not defaulted", input.readUrgentAccent())
        }
        for (value in listOf(buildJsonObject { }, buildJsonArray { })) {
            val input = buildJsonObject { put("accent", value) }
            assertNull("an accent of ${value::class.simpleName} must be refused", input.readUrgentAccent())
        }
    }
}
