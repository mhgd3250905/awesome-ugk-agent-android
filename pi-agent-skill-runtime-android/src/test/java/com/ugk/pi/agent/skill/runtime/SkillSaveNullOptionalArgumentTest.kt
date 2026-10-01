package com.ugk.pi.agent.skill.runtime

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonArray
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
     * One test per key, each asserting the *default that key owns*: a shared
     * assertion would let a regression in `triggers` pass under a `loadPolicy`
     * check, which is what the first version of this file did.
     */
    @Test
    fun nullLoadPolicyUsesTheSchemaDefault() = runBlocking {
        val result = saveWithNull("loadPolicy", "null-loadpolicy")
        assertEquals(
            "triggered",
            (result.metadata["loadPolicy"] as? JsonPrimitive)?.content
        )
    }

    @Test
    fun nullTriggersAreAnUnfilledList() = runBlocking {
        val result = saveWithNull("triggers", "null-triggers")
        assertEquals(
            "a null triggers must read as the empty default, not a refusal",
            JsonArray(emptyList()),
            result.metadata["triggers"]
        )
    }

    @Test
    fun nullEmbedFilesAreAnUnfilledList() = runBlocking {
        val result = saveWithNull("embedFiles", "null-embedfiles")
        assertEquals(
            JsonArray(emptyList()),
            result.metadata["embedFiles"]
        )
    }

    @Test
    fun nullOverwriteIsTheDefaultFalse() = runBlocking {
        val repository = repository()
        val first = saveWithNull("overwrite", "null-overwrite", repository)
        assertEquals(
            "a null overwrite must not claim it replaced anything",
            "false",
            (first.metadata["overwritten"] as? JsonPrimitive)?.content
        )
        val second = SkillSaveTool(repository).execute(
            call(
                "name" to JsonPrimitive("null-overwrite"),
                "description" to JsonPrimitive("A guide."),
                "body" to JsonPrimitive("Version two."),
                "overwrite" to JsonNull
            ),
            context()
        )
        assertTrue("the second null-overwrite save must not replace the first", second.isError)
        assertEquals(
            "SKILL_EXISTS",
            (second.metadata["code"] as? JsonPrimitive)?.content
        )
    }

    private suspend fun saveWithNull(
        key: String,
        skillName: String,
        into: SkillRepository = repository()
    ): ToolResult {
        val result = SkillSaveTool(into).execute(
            call(
                "name" to JsonPrimitive(skillName),
                "description" to JsonPrimitive("A guide."),
                "body" to JsonPrimitive("Version one."),
                key to JsonNull
            ),
            context()
        )
        assertFalse(
            "a null $key is an unfilled $key, not a refusal: ${result.content}",
            result.isError
        )
        assertTrue("a null $key must still create the skill", result.content.startsWith("Created skill"))
        return result
    }

    /**
     * The control for the case above: a declared `overwrite: true` really does
     * replace, so the null refusal is not just "this tool never overwrites".
     */
    @Test
    fun declaredOverwriteTrueStillReplaces() = runBlocking {
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
            "loadPolicy as a number" to Triple("loadPolicy", JsonPrimitive(5), "INVALID_LOAD_POLICY"),
            "triggers as a number" to Triple("triggers", JsonPrimitive(5), "INVALID_TRIGGERS"),
            "embedFiles as a number" to Triple("embedFiles", JsonPrimitive(5), "INVALID_EMBED_FILES"),
            "overwrite as a string" to Triple("overwrite", JsonPrimitive("true"), "INVALID_OVERWRITE")
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
            // Bare isError would also be satisfied by a bad name, a missing skill
            // or any other refusal, so the code is the assertion that carries the
            // meaning.
            assertEquals(
                "$label must be refused for its own reason, got: ${result.content}",
                argument.third,
                (result.metadata["code"] as? JsonPrimitive)?.content
            )
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
