package com.ugk.pi.android.testapp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `repeating` is optional with a documented default of false, and a Java/Pojo
 * gateway emits `"repeating":null` rather than omitting the key. `JsonNull` is a
 * `JsonPrimitive`, so the tool's own Kotlin-null test fell into the parse branch,
 * `booleanOrNull` answered null, and the whole delayed-task proposal was refused
 * with "repeating must be a boolean." - the same rule the schedule and skill
 * modules now read through one helper, in the one place where a user is waiting
 * for a confirmation dialog that never appears.
 */
class DemoDelayedTaskProposalArgumentsTest {

    private fun withRepeating(value: JsonElement?) = buildJsonObject {
        put("delaySeconds", JsonPrimitive(60))
        put("instruction", JsonPrimitive("提醒我喝水"))
        value?.let { put("repeating", it) }
    }

    private fun describe(result: DelayProposalArguments): String = when (result) {
        is DelayProposalArguments.Valid -> "valid ${result.delaySeconds}/${result.repeating}"
        is DelayProposalArguments.Refused -> "refused: ${result.message}"
    }

    @Test
    fun nullRepeatingMeansTheDefaultFalseAndKeepsTheRest() {
        val result = readDelayProposalArguments(withRepeating(JsonNull))
        assertTrue(
            "a null repeating is an unfilled repeating: ${describe(result)}",
            result is DelayProposalArguments.Valid
        )
        result as DelayProposalArguments.Valid
        assertEquals(false, result.repeating)
        assertEquals(60L, result.delaySeconds)
        assertEquals("提醒我喝水", result.instruction)
    }

    @Test
    fun absentRepeatingAlsoMeansFalseAndADeclaredValueIsHonoured() {
        assertEquals(
            false,
            (readDelayProposalArguments(withRepeating(null)) as DelayProposalArguments.Valid).repeating
        )
        assertEquals(
            true,
            (readDelayProposalArguments(withRepeating(JsonPrimitive(true))) as DelayProposalArguments.Valid).repeating
        )
    }

    /**
     * The guard stays strict the other way: a declared non-boolean is refused, so
     * the null rule cannot degrade into accepting anything.
     */
    @Test
    fun declaredNonBooleanRepeatingIsStillRefused() {
        val cases = listOf(
            "a string" to JsonPrimitive("true"),
            "a number" to JsonPrimitive(1),
            "an object" to buildJsonObject { put("yes", JsonPrimitive(true)) },
            "an array" to buildJsonArray { add(JsonPrimitive(true)) }
        )
        for ((label, value) in cases) {
            val result = readDelayProposalArguments(withRepeating(value))
            assertTrue(
                "$label must be refused: ${describe(result)}",
                result is DelayProposalArguments.Refused
            )
            assertEquals(
                "$label must name the field it refused",
                "repeating must be a boolean.",
                (result as DelayProposalArguments.Refused).message
            )
        }
    }

    @Test
    fun missingDelaySecondsIsRefusedEvenWhenRepeatingIsNull() {
        val missingDelay = readDelayProposalArguments(
            buildJsonObject {
                put("instruction", JsonPrimitive("提醒我喝水"))
                put("repeating", JsonNull)
            }
        )
        assertEquals(
            "delaySeconds must be an integer.",
            (missingDelay as DelayProposalArguments.Refused).message
        )
    }

    @Test
    fun unknownFieldIsRefused() {
        val unknown = readDelayProposalArguments(
            buildJsonObject {
                put("delaySeconds", JsonPrimitive(60))
                put("instruction", JsonPrimitive("提醒我喝水"))
                put("repeating", JsonNull)
                put("cron", JsonPrimitive("0 8 * * *"))
            }
        )
        assertEquals("Unknown timer proposal field.", (unknown as DelayProposalArguments.Refused).message)
    }

    @Test
    fun blankInstructionIsRefused() {
        val blank = readDelayProposalArguments(
            buildJsonObject {
                put("delaySeconds", JsonPrimitive(60))
                put("instruction", JsonPrimitive("   "))
                put("repeating", JsonNull)
            }
        )
        assertEquals(
            "instruction must contain 1 to 2000 characters.",
            (blank as DelayProposalArguments.Refused).message
        )
    }

    /**
     * The check order is what the model reads, so it is part of the behaviour: the
     * extraction must not reorder it. `repeating` is judged before `instruction`,
     * and the interval range before `repeating`.
     */
    @Test
    fun checkOrderIsUnchangedWhenSeveralFieldsAreBad() {
        val repeatingAndInstruction = readDelayProposalArguments(
            buildJsonObject {
                put("delaySeconds", JsonPrimitive(60))
                put("instruction", JsonPrimitive(""))
                put("repeating", JsonPrimitive("yes"))
            }
        )
        assertEquals(
            "repeating must be a boolean.",
            (repeatingAndInstruction as DelayProposalArguments.Refused).message
        )

        val rangeAndRepeating = readDelayProposalArguments(
            buildJsonObject {
                put("delaySeconds", JsonPrimitive(0))
                put("instruction", JsonPrimitive("提醒我喝水"))
                put("repeating", JsonPrimitive("yes"))
            }
        )
        assertEquals(
            "The timer accepts intervals from 1 second to 24 hours.",
            (rangeAndRepeating as DelayProposalArguments.Refused).message
        )

        val unknownAndDelay = readDelayProposalArguments(
            buildJsonObject {
                put("instruction", JsonPrimitive("提醒我喝水"))
                put("cron", JsonPrimitive("0 8 * * *"))
            }
        )
        assertEquals(
            "Unknown timer proposal field.",
            (unknownAndDelay as DelayProposalArguments.Refused).message
        )
    }
}
