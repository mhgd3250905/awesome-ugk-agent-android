package com.ugk.pi.system.skill

import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three places in this module that read a model-supplied optional list or object
 * used `?.jsonArray` / `?.jsonObject`, which **throw** on JSON `null` - the shape an
 * OpenAI-compatible Java/Pojo gateway emits for an optional field it did not fill.
 * The throw reaches the runtime's tool catch, so the caller got a tool error naming a
 * serialization class instead of either the documented default (`permissions` has one)
 * or a refusal that names the argument.
 *
 * This is the same `JsonNull` fact round 7 fixed on the providers' response side and
 * round 11 fixed for the attention, agent-skill runtime, schedule and demo tools; the
 * round-11 sweep missed these two modules, and this file is the correction.
 *
 * Read through the module's own functions rather than the Tools, because
 * [AndroidPermissionStatusTool] needs an `Activity` and [AndroidAppIntentTool] a
 * `Context`. The cost is stated instead of hidden: that `execute` routes these answers
 * into the permission request and the Intent factory is not covered on the host.
 */
class SystemSkillOptionalArgumentTest {

    private fun permissionCall(permissions: JsonElement?): ToolCall = ToolCall(
        id = "call-1",
        name = "get_android_permission_status",
        input = buildJsonObject {
            if (permissions != null) put("permissions", permissions)
        }
    )

    private fun names(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    @Test
    fun absentPermissionsAreTheDocumentedDefault() {
        assertEquals(AndroidPermissionCatalog.defaultRuntimePermissions(), permissionCall(null).permissionsOrDefault())
    }

    @Test
    fun nullPermissionsAreTheDocumentedDefaultAndNotAThrownSerializationError() {
        assertEquals(
            "permissions serialized as null means nobody filled the field in",
            AndroidPermissionCatalog.defaultRuntimePermissions(),
            permissionCall(JsonNull).permissionsOrDefault()
        )
    }

    @Test
    fun declaredPermissionsAreHonoured() {
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            permissionCall(names("android.permission.CAMERA", "android.permission.RECORD_AUDIO"))
                .permissionsOrDefault()
        )
    }

    /** The empty-list behaviour the module already had, kept on purpose. */
    @Test
    fun emptyDeclaredPermissionsFallBackToTheDefault() {
        assertEquals(
            AndroidPermissionCatalog.defaultRuntimePermissions(),
            permissionCall(buildJsonArray {}).permissionsOrDefault()
        )
    }

    /** Declared-but-not-a-list is refused, not defaulted: the null rule is not a wildcard. */
    @Test
    fun declaredNonListPermissionsAreVisibleAsUnusable() {
        val unusable = listOf(
            "a string" to JsonPrimitive("camera"),
            "a number" to JsonPrimitive(5),
            "an object" to buildJsonObject { put("name", "camera") }
        )
        for ((label, value) in unusable) {
            assertNull("$label must not be read as a permission list", permissionCall(value).permissionsOrDefault())
        }
    }

    /**
     * An item that is declared and is not a string is garbage too, and garbage must
     * be refused rather than filtered down into "nobody asked for anything" - because
     * "nobody asked" is exactly this reader's trigger for handing back the *default
     * permission set*, which is a set of runtime permissions the caller never named.
     *
     * Before round 5 this shape silently became the default; on `main` it threw
     * (`it.jsonPrimitive` on an object element), so neither behaviour refused with the
     * argument's own name.
     */
    @Test
    fun declaredNonStringPermissionItemsAreVisibleAsUnusable() {
        val unusable = listOf(
            "an object item" to buildJsonArray { add(buildJsonObject { put("name", "camera") }) },
            "a number item" to buildJsonArray { add(JsonPrimitive(5)) },
            "a boolean item" to buildJsonArray { add(JsonPrimitive(true)) },
            "one good item and one object" to buildJsonArray {
                add(JsonPrimitive("android.permission.CAMERA"))
                add(buildJsonObject { put("name", "camera") })
            }
        )
        for ((label, value) in unusable) {
            assertNull(
                "$label must be refused rather than folded into the default set",
                permissionCall(value).permissionsOrDefault()
            )
        }
    }

    /** A `null` *entry* is an unfilled entry, not garbage: it is dropped, the rest stands. */
    @Test
    fun nullPermissionEntriesAreTreatedAsUnfilledItems() {
        assertEquals(
            listOf("android.permission.CAMERA"),
            permissionCall(
                buildJsonArray { add(JsonPrimitive("android.permission.CAMERA")); add(JsonNull) }
            ).permissionsOrDefault()
        )
    }

    /** The three states of the shared list reader, pinned directly. */
    @Test
    fun theListReaderAnswersAbsentUnusableAndDeclaredSeparately() {
        assertEquals(DeclaredStringList.Undeclared, buildJsonObject {}.declaredStringList("permissions"))
        assertEquals(DeclaredStringList.Undeclared, buildJsonObject { put("permissions", JsonNull) }.declaredStringList("permissions"))
        assertEquals(DeclaredStringList.Unusable, buildJsonObject { put("permissions", JsonPrimitive("x")) }.declaredStringList("permissions"))
        assertEquals(
            listOf("android.permission.CAMERA"),
            (buildJsonObject { put("permissions", names("android.permission.CAMERA")) }.declaredStringList("permissions")
                as DeclaredStringList.Values).values
        )
    }

    /**
     * The refusal has to name the argument, and name it in the *message* - the error
     * code `invalid_permissions` contains the word too, so a `contains("permissions")`
     * assertion is satisfied by the code alone and would stay green if the explanation
     * were deleted.
     */
    @Test
    fun unusablePermissionsAnswerWithARefusalThatNamesTheArgument() {
        val result = permissionCall(JsonPrimitive("camera"))
            .unusablePermissions("request_android_runtime_permissions")
        val payload = Json.parseToJsonElement(result.content).jsonObject

        assertTrue(result.isError)
        assertEquals("invalid_permissions", (payload["error"] as? JsonPrimitive)?.content)
        assertTrue(
            "the refusal must explain the argument the caller has to fix: ${result.content}",
            (payload["message"] as? JsonPrimitive)?.content?.contains("permissions must be a list") == true
        )
    }

    @Test
    fun absentIntentParametersFallBackToTheFlatArguments() {
        val input = buildJsonObject {
            put("target", "open_url")
            put("url", "https://example.com")
        }
        assertEquals(mapOf("url" to "https://example.com"), input.appIntentParameters())
    }

    @Test
    fun nullIntentParametersFallBackToTheFlatArguments() {
        val input = buildJsonObject {
            put("target", "open_url")
            put("url", "https://example.com")
            put("parameters", JsonNull)
        }
        assertEquals(
            "a null parameters object means nobody filled the field in",
            mapOf("url" to "https://example.com"),
            input.appIntentParameters()
        )
    }

    @Test
    fun declaredIntentParametersAreHonoured() {
        val input = buildJsonObject {
            put("target", "open_url")
            putJsonObject("parameters") { put("url", "https://example.com") }
        }
        assertEquals(mapOf("url" to "https://example.com"), input.appIntentParameters())
    }

    @Test
    fun declaredNonObjectIntentParametersAreVisibleAsUnusable() {
        val stringParameters = buildJsonObject {
            put("target", "open_url")
            put("parameters", "https://example.com")
        }
        assertNull("a string is not a parameter object", stringParameters.appIntentParameters())

        val arrayParameters = buildJsonObject {
            put("target", "open_url")
            putJsonArray("parameters") { add(JsonPrimitive("https://example.com")) }
        }
        assertNull("an array is not a parameter object", arrayParameters.appIntentParameters())
    }
}
