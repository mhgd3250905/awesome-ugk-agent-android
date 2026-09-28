package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoTeachingExperiencePluginTest {
    @get:Rule val temporary = TemporaryFolder()
    private val currentTask = AgentMessage.User(
        "看看谷歌商店哪些应用有更新",
        AgentTimeContext("2026-09-29 12:00:00", "Asia/Shanghai")
    )
    private val context = ToolExecutionContext("chat", listOf(currentTask))
    private val guide = DemoTeachingGuide("检查谷歌商店更新", "检查可更新应用", emptyList(), listOf("只查看待更新列表，不安装"),
        emptyList(), listOf("看到可更新列表或无更新状态"), emptyList(), listOf("看看哪些应用有更新"), listOf("Google Play"), listOf("安装更新"))
    private fun fixture(): Pair<DemoTeachingStore, String> {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString()
        store.create(id, guide.title); store.update(id) { it.copy(status = "finished") }; store.saveGuide(id, guide)
        return store to id
    }
    private suspend fun call(plugin: DemoTeachingExperiencePlugin, suffix: String, input: JsonObject, ctx: ToolExecutionContext = context): ToolResult {
        val name = "teaching_experience_$suffix"
        return plugin.tools().single { it.name == name }.execute(ToolCall(UUID.randomUUID().toString(), name, input), ctx)
    }
    private fun use(id: String) = buildJsonObject { put("id", id); put("revision", 1) }

    @Test fun declinedOrDismissedChoiceNeverReturnsGuide() = runBlocking {
        val (store, id) = fixture()
        for (result in listOf(UserConfirmationDialogResult("cancel"), UserConfirmationDialogResult("use", true))) {
            val plugin = DemoTeachingExperiencePlugin(store) { result }
            val response = call(plugin, "use", use(id))
            assertFalse(response.isError); assertFalse(response.content.contains("只查看待更新列表"))
            assertFalse(Json.parseToJsonElement(response.content).jsonObject.getValue("approved").jsonPrimitive.boolean)
        }
    }
    @Test fun summariesDoNotLoadStepsAndDisabledOrStaleGuidesCannotBeUsed() = runBlocking {
        val (store, id) = fixture(); var prompts = 0
        val plugin = DemoTeachingExperiencePlugin(store) { prompts++; UserConfirmationDialogResult("use") }
        val search = call(plugin, "search", buildJsonObject { put("query", "谷歌商店更新") })
        assertTrue(search.content.contains(id)); assertFalse(search.content.contains("只查看待更新列表"))
        store.setAvailability(id, "disabled")
        assertTrue(call(plugin, "use", use(id)).isError)
        store.saveGuide(id, guide)
        assertTrue(call(plugin, "use", use(id)).isError); assertEquals(0, prompts)
    }
    @Test fun revisionChangeDuringChoiceCannotAuthorizeNewGuide() = runBlocking {
        val (store, id) = fixture()
        val plugin = DemoTeachingExperiencePlugin(store) { store.saveGuide(id, guide); UserConfirmationDialogResult("use") }
        assertTrue(call(plugin, "use", use(id)).isError)
    }
    @Test fun reportIsBoundToApprovalAndUserMustValidateSuccess() = runBlocking {
        val (store, id) = fixture(); var prompts = 0
        val plugin = DemoTeachingExperiencePlugin(store) {
            prompts++; UserConfirmationDialogResult(if (prompts == 1) "use" else "cancel")
        }
        val approved = call(plugin, "use", use(id))
        assertFalse(approved.isError)
        val token = Json.parseToJsonElement(approved.content).jsonObject.getValue("usageId").jsonPrimitive.content
        val report = buildJsonObject { put("usageId", token); put("outcome", "success"); put("summary", "已核对列表") }
        assertTrue(call(plugin, "report", report, context.copy(sessionId = "other")).isError)
        assertFalse(call(plugin, "report", report).isError)
        assertEquals("pending_validation", store.read(id)!!.availability)
        assertEquals(1, store.read(id)!!.usageHistory.size)
        assertTrue(call(plugin, "report", report).isError)
    }
    @Test fun explicitValidationMarksExactRevisionAvailable() = runBlocking {
        val (store, id) = fixture(); var prompts = 0
        val plugin = DemoTeachingExperiencePlugin(store) { prompts++; UserConfirmationDialogResult(if (prompts == 1) "use" else "verified") }
        val token = Json.parseToJsonElement(call(plugin, "use", use(id)).content).jsonObject.getValue("usageId").jsonPrimitive.content
        assertFalse(call(plugin, "report", buildJsonObject { put("usageId", token); put("outcome", "success"); put("summary", "完成检查") }).isError)
        assertEquals("available", store.read(id)!!.availability)
        assertEquals(2, prompts)
    }

    @Test fun unusedApprovalFromAnEarlierTurnDoesNotBlockCurrentReport() = runBlocking {
        val (store, id) = fixture()
        val plugin = DemoTeachingExperiencePlugin(store) { UserConfirmationDialogResult("use") }
        val firstContext = context
        val nextContext = context.copy(priorMessages = listOf(AgentMessage.User("再检查一次更新")))
        val firstToken = Json.parseToJsonElement(call(plugin, "use", use(id), firstContext).content)
            .jsonObject.getValue("usageId").jsonPrimitive.content
        val nextToken = Json.parseToJsonElement(call(plugin, "use", use(id), nextContext).content)
            .jsonObject.getValue("usageId").jsonPrimitive.content

        assertTrue(call(plugin, "report", report(firstToken), nextContext).isError)
        assertFalse(call(plugin, "report", report(nextToken, "failure"), nextContext).isError)
        assertEquals(1, store.read(id)!!.usageHistory.size)
    }

    @Test fun transcriptCompactionDoesNotInvalidateApprovalForCurrentRun() = runBlocking {
        val (store, id) = fixture()
        val plugin = DemoTeachingExperiencePlugin(store) { UserConfirmationDialogResult("use") }
        val approved = call(plugin, "use", use(id))
        val token = Json.parseToJsonElement(approved.content).jsonObject.getValue("usageId").jsonPrimitive.content
        val compactedContext = context.copy(priorMessages = listOf(
            AgentMessage.User("[历史对话摘要：更早的任务已折叠]"), currentTask
        ))

        assertFalse(call(plugin, "report", report(token), compactedContext).isError)
        assertEquals(1, store.read(id)!!.usageHistory.size)
    }

    @Test fun neglectedApprovalsCannotExhaustFutureGuideUse() = runBlocking {
        val (store, id) = fixture()
        val plugin = DemoTeachingExperiencePlugin(store) { UserConfirmationDialogResult("use") }
        repeat(40) { index ->
            val result = call(plugin, "use", use(id), context.copy(sessionId = "chat-$index"))
            assertFalse("Approval $index should not fail", result.isError)
        }
    }

    private fun report(token: String, outcome: String = "failure") = buildJsonObject {
        put("usageId", token); put("outcome", outcome); put("summary", "本次未完成")
    }
}
