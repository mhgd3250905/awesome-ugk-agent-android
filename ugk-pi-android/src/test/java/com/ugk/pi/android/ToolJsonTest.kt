package com.ugk.pi.android

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the parsing contract of the shared tool-input accessors, most of all
 * the boolean boundary: the strict parse must reject the case variants
 * kotlinx booleanOrNull accepts, because those feed overwrite-style flags.
 */
class ToolJsonTest {
    @Test
    fun stringReadsScalarContentAndNullsOutMissingKeys() {
        val input: JsonObject = buildJsonObject {
            put("name", "weather-site")
            put("count", 3)
        }
        assertEquals("weather-site", input.string("name"))
        assertEquals("3", input.string("count"))
        assertNull(input.string("absent"))
        // A non-scalar value throws from jsonPrimitive - the exact behavior
        // of the per-module copies this accessor replaces.
        val nested = buildJsonObject { put("nested", buildJsonObject { }) }
        var thrown: Throwable? = null
        try {
            nested.string("nested")
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertEquals(IllegalArgumentException::class.java, thrown?.javaClass)
    }

    @Test
    fun booleanAcceptsOnlyExactLowercaseTrueAndFalse() {
        val input: JsonObject = buildJsonObject {
            put("yes", true)
            put("no", false)
            put("trueText", "true")
            put("caseVariant", "True")
            put("shoutVariant", "FALSE")
            put("count", 3)
        }
        assertEquals(true, input.boolean("yes"))
        assertEquals(false, input.boolean("no"))
        assertEquals(true, input.boolean("trueText"))
        assertNull("kotlinx booleanOrNull accepts this; the shared accessor must not", input.boolean("caseVariant"))
        assertNull(input.boolean("shoutVariant"))
        assertNull(input.boolean("count"))
        assertNull(input.boolean("absent"))
        assertNull(buildJsonObject { put("n", JsonNull) }.boolean("n"))
    }

    @Test
    fun longReadsIntegralNumbersAndNumericStrings() {
        val input: JsonObject = buildJsonObject {
            put("seconds", 30)
            put("big", 4_000_000_000)
            put("textual", "86400")
            put("fraction", 1.5)
        }
        assertEquals(30L, input.long("seconds"))
        assertEquals(4_000_000_000L, input.long("big"))
        assertEquals(86_400L, input.long("textual"))
        assertNull(input.long("fraction"))
        assertNull(input.long("absent"))
    }
}
