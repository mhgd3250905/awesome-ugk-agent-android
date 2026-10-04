package com.ugk.pi.android.testapp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape table behind both demo readers.
 *
 * `DemoTeachingModelProvider.checkServiceResponse` and the workflow plan gate used
 * to decide by asking whether `error` is an object, so `{"error":"Overloaded"}`
 * passed as a success while `{"error":null}` in a valid plan was refused with a
 * message about missing evidence. Every row below is one arm of the rule, so
 * collapsing two arms into one ("present means failure", or "only an object means
 * failure") turns a named row red rather than quietly changing behavior.
 */
class DemoApiErrorSignalTest {

    @Test
    fun valuesThatReportNothingAreNotErrors() {
        val failures = emptySentinels.mapNotNull { (name, value) ->
            if (DemoApiErrorSignal.reportsAnything(value)) "$name: expected no report" else null
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun valuesThatSaySomethingAreErrors() {
        val failures = reportingValues.mapNotNull { (name, value) ->
            if (DemoApiErrorSignal.reportsAnything(value)) null else "$name: expected a report"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun theProtocolErrorMarkerReportsEvenWithoutAnErrorField() {
        assertTrue(DemoApiErrorSignal.reportedBy(buildJsonObject { put("type", JsonPrimitive("error")) }))
        assertTrue(DemoApiErrorSignal.reportedBy(buildJsonObject {
            put("type", JsonPrimitive("error")); put("error", JsonObject(emptyMap()))
        }))
    }

    /** Control: an ordinary answer document with no error signal is not a report. */
    @Test
    fun anOrdinaryAnswerDocumentReportsNothing() {
        assertFalse(DemoApiErrorSignal.reportedBy(buildJsonObject {
            put("type", JsonPrimitive("message")); put("error", JsonNull)
        }))
        assertFalse(DemoApiErrorSignal.reportedBy(buildJsonObject { put("type", JsonPrimitive("message")) }))
    }

    private val emptySentinels: List<Pair<String, JsonElement?>> = listOf(
        "absent key" to null,
        "JSON null" to JsonNull,
        "blank string" to JsonPrimitive(""),
        "whitespace string" to JsonPrimitive("   "),
        "false flag" to JsonPrimitive(false),
        "zero" to JsonPrimitive(0),
        "empty array" to JsonArray(emptyList()),
        "empty object" to JsonObject(emptyMap())
    )

    private val reportingValues: List<Pair<String, JsonElement?>> = listOf(
        "reason string" to JsonPrimitive("Overloaded"),
        "quoted true" to JsonPrimitive("true"),
        "true flag" to JsonPrimitive(true),
        "non-zero number" to JsonPrimitive(500),
        "array with content" to JsonArray(listOf(JsonPrimitive("rate_limit"))),
        "object with a type" to buildJsonObject { put("type", JsonPrimitive("overloaded_error")) }
    )
}
