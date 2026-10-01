package com.ugk.pi.agent.skill.runtime

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `skill_save`'s four optional arguments all carry defaults in the schema, and a
 * Java/Pojo gateway emits `"loadPolicy":null`, `"triggers":null`,
 * `"embedFiles":null`, `"overwrite":null` for the ones it does not fill instead of
 * omitting the key. The readers asked the raw map, so a legal save was refused
 * with `INVALID_LOAD_POLICY` / `INVALID_TRIGGERS` / `INVALID_EMBED_FILES` /
 * `INVALID_OVERWRITE`.
 *
 * `overwrite` is the one where the direction of the mistake matters: reading null
 * as "no" keeps an existing skill, reading it as "yes" would replace one nobody
 * agreed to replace. Null therefore means the documented default, false.
 */
class SkillSaveNullOptionalArgumentTest {

    @get:Rule val tempFolder = TemporaryFolder()

    /** A fresh root per call: TemporaryFolder hands out a unique directory each time. */
    private fun repository(): SkillRepository = SkillRepository(tempFolder.newFolder())

    /**
     * One key at a time: a case that nulls all four would let the first refusal
     * hide the other three, and each landing point is a separate line of code.
     */
    @Test
    fun nullOptionalArgumentsUseTheDeclaredDefaults() = runBlocking {
        val optionalKeys = listOf("loadPolicy", "triggers", "embedFiles", "overwrite")
        for ((index, key) in optionalKeys.withIndex()) {
            val result = SkillSaveTool(repository()).execute(
                call(
                    "name" to JsonPrimitive("null-" + key.lowercase()),
                    "description" to JsonPrimitive("A guide."),
                    "body" to JsonPrimitive("Version one."),
                    key to JsonNull
                ),
                context()
            )
            assertFalse(
                "$index: a null $key is an unfilled $key, not a refusal: ${result.content}",
                result.isError
            )
            assertTrue("$index: $key must create the skill", result.content.startsWith("Created skill"))
        }
        val defaults = SkillSaveTool(repository()).execute(
            call(
                "name" to JsonPrimitive("null-loadpolicy"),
                "description" to JsonPrimitive("A guide."),
                "body" to JsonPrimitive("Version one."),
                "loadPolicy" to JsonNull
            ),
            context()
        )
        assertEquals(
            "the schema default must be what a null resolves to",
            "triggered",
            (defaults.metadata["loadPolicy"] as? JsonPrimitive)?.content
        )
    }

    /**
     * The safety side of the same rule: `overwrite` null must keep the existing
     * skill, exactly as an omitted `overwrite` does.
     */
    @Test
    fun nullOverwriteStillRefusesToReplaceAnExistingSkill() = runBlocking {
        val repository = repository()
        val first = SkillSaveTool(repository).execute(
            call(
                "name" to JsonPrimitive("mutable-guide"),
                "description" to JsonPrimitive("A guide."),
                "body" to JsonPrimitive("Version one.")
            ),
            context()
        )
        assertFalse("the first save must succeed: ${first.content}", first.isError)

        val refused = SkillSaveTool(repository).execute(
            call(
                "name" to JsonPrimitive("mutable-guide"),
                "description" to JsonPrimitive("A guide."),
                "body" to JsonPrimitive("Version two."),
                "overwrite" to JsonNull
            ),
            context()
        )
        assertTrue("a null overwrite must not replace an existing skill", refused.isError)

        val allowed = SkillSaveTool(repository).execute(
            call(
                "name" to JsonPrimitive("mutable-guide"),
                "description" to JsonPrimitive("A guide."),
                "body" to JsonPrimitive("Version two."),
                "overwrite" to JsonPrimitive(true)
            ),
            context()
        )
        assertFalse("a declared overwrite=true must still replace: ${allowed.content}", allowed.isError)
        assertTrue(allowed.content.startsWith("Updated skill"))
    }

    /** Declared-but-unusable must stay refused; the null rule is not a wildcard. */
    @Test
    fun declaredGarbageOptionalsAreStillRefused() = runBlocking {
        val cases = listOf(
            "loadPolicy as a number" to ("loadPolicy" to JsonPrimitive(5)),
            "triggers as a number" to ("triggers" to JsonPrimitive(5)),
            "embedFiles as a number" to ("embedFiles" to JsonPrimitive(5)),
            "overwrite as a string" to ("overwrite" to JsonPrimitive("true"))
        )
        cases.forEachIndexed { index, (label, argument) ->
            val result = SkillSaveTool(repository()).execute(
                call(
                    "name" to JsonPrimitive("garbage-$index"),
                    "description" to JsonPrimitive("A guide."),
                    "body" to JsonPrimitive("Body."),
                    argument.first to argument.second
                ),
                context()
            )
            assertTrue("$label must be refused, got: ${result.content}", result.isError)
        }
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(sessionId = "test")

    private fun call(vararg values: Pair<String, JsonElement>): ToolCall = ToolCall(
        id = "call-1",
        name = "skill_save",
        input = buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        }
    )
}
