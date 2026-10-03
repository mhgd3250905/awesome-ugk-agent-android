package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * An unusable teaching-experience argument must say which one.
 *
 * These tools read every field through one helper, `ToolCall.text`, which used
 * `jsonPrimitive.content`. `JsonNull` is a `JsonPrimitive`, and its `content` is
 * the four-letter string "null" - so a gateway that fills each optional with null
 * did not get a missing-argument refusal: `{"usageId":null}` looked up the literal
 * id "null" and answered "没有已确认的经验使用", which points the reader at the
 * approval flow instead of at their own argument. A declared object or array threw
 * from the serialization library, and an `outcome` outside its accepted set hit a
 * `require` with no message, so the refusal became the file's generic fallback and
 * named nothing at all.
 *
 * The `summary` key-absent case is the control: it already refused with the
 * argument name before this change, which is what makes the other reds about the
 * value shapes rather than about the harness.
 */
class DemoTeachingExperienceArgumentRefusalTest {

    @get:Rule val temporary = TemporaryFolder()

    private val currentTask = AgentMessage.User(
        "看看谷歌商店哪些应用有更新",
        AgentTimeContext("2026-09-29 12:00:00", "Asia/Shanghai")
    )
    private val context = ToolExecutionContext("chat", listOf(currentTask))
    private val guide = DemoTeachingGuide(
        "检查谷歌商店更新", "检查可更新应用", emptyList(), listOf("只查看待更新列表，不安装"),
        emptyList(), listOf("看到可更新列表或无更新状态"), emptyList(),
        listOf("看看哪些应用有更新"), listOf("Google Play"), listOf("安装更新")
    )

    @Test
    fun nullUsageIdIsRefusedAsTheArgumentNotAsAMissingApproval() = runBlocking {
        val fixture = approvedFixture()
        val result = call(fixture.plugin, "report", buildJsonObject {
            put("usageId", JsonNull)
            put("outcome", "failure")
            put("summary", "本次未完成")
        })
        assertRefusalNaming(result, "usageId")
        assertTrue(
            "a null id must not be looked up as the literal string \"null\": ${result.content}",
            !result.content.contains("没有已确认的经验使用")
        )
    }

    @Test
    fun nullOutcomeIsRefusedAsTheArgumentNotWithTheGenericFallback() = runBlocking {
        val fixture = approvedFixture()
        val result = call(fixture.plugin, "report", buildJsonObject {
            put("usageId", fixture.token)
            put("outcome", JsonNull)
            put("summary", "本次未完成")
        })
        assertRefusalNaming(result, "outcome")
    }

    @Test
    fun outcomeOutsideItsAcceptedSetIsNamedAlongWithTheSet() = runBlocking {
        val fixture = approvedFixture()
        val result = call(fixture.plugin, "report", buildJsonObject {
            put("usageId", fixture.token)
            put("outcome", "succes")
            put("summary", "本次未完成")
        })
        assertRefusalNaming(result, "outcome")
        listOf("success", "failure", "needs_revision").forEach { expected ->
            assertTrue(
                "the refusal should list the accepted outcomes, got: ${result.content}",
                result.content.contains(expected)
            )
        }
    }

    @Test
    fun objectValuedUsageIdIsRefusedWithoutTheSerializationInternal() = runBlocking {
        val fixture = approvedFixture()
        val result = call(fixture.plugin, "report", JsonObject(mapOf(
            "usageId" to JsonObject(mapOf("value" to JsonPrimitive("x"))),
            "outcome" to JsonPrimitive("failure"),
            "summary" to JsonPrimitive("本次未完成")
        )))
        assertRefusalNaming(result, "usageId")
        assertFalse(
            "a serialization internal must not replace the argument name: ${result.content}",
            result.content.contains("is not a JsonPrimitive")
        )
    }

    @Test
    fun nonNumericRevisionIsRefusedAsTheArgument() = runBlocking {
        val (store, id) = fixture()
        val plugin = DemoTeachingExperiencePlugin(store) { UserConfirmationDialogResult("use") }
        val result = call(plugin, "use", buildJsonObject {
            put("id", id)
            put("revision", "abc")
        })
        assertRefusalNaming(result, "revision")
    }

    /** Control: a key that is simply absent already named the argument. */
    @Test
    fun absentSummaryIsRefusedByName() = runBlocking {
        val fixture = approvedFixture()
        val result = call(fixture.plugin, "report", buildJsonObject {
            put("usageId", fixture.token)
            put("outcome", "failure")
        })
        assertRefusalNaming(result, "summary")
    }

    /** Control: a well-formed report still succeeds, so the refusals are not a blanket block. */
    @Test
    fun wellFormedReportStillRecordsTheOutcome() = runBlocking {
        val fixture = approvedFixture()
        val result = call(fixture.plugin, "report", buildJsonObject {
            put("usageId", fixture.token)
            put("outcome", "failure")
            put("summary", "本次未完成")
        })
        assertFalse("expected the report to succeed, got: ${result.content}", result.isError)
        assertEquals(1, fixture.store.read(fixture.id)!!.usageHistory.size)
    }

    private class Approved(val store: DemoTeachingStore, val id: String, val plugin: DemoTeachingExperiencePlugin, val token: String)

    private suspend fun approvedFixture(): Approved {
        val (store, id) = fixture()
        val plugin = DemoTeachingExperiencePlugin(store) { UserConfirmationDialogResult("use") }
        val approved = call(plugin, "use", buildJsonObject { put("id", id); put("revision", 1) })
        assertFalse("approval setup failed: ${approved.content}", approved.isError)
        val token = Json.parseToJsonElement(approved.content).let { it as JsonObject }.getValue("usageId")
            .let { it as JsonPrimitive }.content
        return Approved(store, id, plugin, token)
    }

    private fun assertRefusalNaming(result: ToolResult, argument: String) {
        assertTrue("expected a refusal, got content=${result.content}", result.isError)
        assertTrue(
            "the refusal must name the argument it refused ($argument): ${result.content}",
            result.content.contains(argument)
        )
    }

    private fun fixture(): Pair<DemoTeachingStore, String> {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString()
        store.create(id, guide.title)
        store.update(id) { it.copy(status = "finished") }
        store.saveGuide(id, guide)
        return store to id
    }

    private suspend fun call(plugin: DemoTeachingExperiencePlugin, suffix: String, input: JsonObject): ToolResult {
        val name = "teaching_experience_$suffix"
        return plugin.tools().single { it.name == name }
            .execute(ToolCall(UUID.randomUUID().toString(), name, input), context)
    }
}
