package com.ugk.pi.file.skill

import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.UserConfirmationRequiredTool
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmation gate of the app-private file workspace.
 *
 * `app_file_delete` is the one tool this plugin protects, and until now nothing
 * observed that: the module's plugin test asserts only the tool *names*, and
 * `UserConfirmationRequiredTool` delegates `name`, so changing the protection set
 * to a name that matches no tool left all 13 tests green (measured:
 * mut-m3-file-delete-protection-dead.log). The skill text shown to the model
 * promises the gate ("app_file_delete requires a prior user confirmation through
 * show_user_confirmation_dialog"), which makes a silently dead entry worse than
 * an absent one - the model is told there is a guard.
 *
 * Both directions are pinned: the gated tool refuses without a ticket and leaves
 * the file on disk, the other five execute normally, and `requireDeleteConfirmation
 * = false` removes the gate everywhere at once (wrapper, skill text).
 */
class AppFileConfirmationGateTest {
    @Test
    fun deleteIsGatedByDefaultAndThePromiseMatches() = runBlocking {
        val root = tempRoot()
        val target = File(root, "notes/today.md").also { file ->
            file.parentFile.mkdirs()
            file.writeText("keep me")
        }
        val plugin = AppFileAgentPlugin(root)
        val delete = plugin.tools().single { it.name == "app_file_delete" }

        assertTrue(
            "app_file_delete must be wrapped by default; the protection set no longer names it",
            delete is UserConfirmationRequiredTool
        )
        val blocked = delete.execute(
            ToolCall("call-1", "app_file_delete", buildJsonObject { put("path", "notes/today.md") }),
            ToolExecutionContext(sessionId = "test")
        )
        assertTrue(blocked.content, blocked.isError)
        assertTrue(
            blocked.content,
            blocked.content.contains("show_user_confirmation_dialog")
        )
        assertTrue("the refused delete still removed the file", target.exists())

        val instruction = plugin.skills().single().instructions
        assertTrue(
            "the skill text promises a confirmation gate that the wiring does not enforce: $instruction",
            instruction.contains("app_file_delete requires a prior user confirmation")
        )
    }

    @Test
    fun onlyTheDeclaredToolIsGated() = runBlocking {
        val root = tempRoot()
        File(root, "notes").mkdirs()
        File(root, "notes/today.md").writeText("hello")
        val plugin = AppFileAgentPlugin(root)

        assertEquals(
            listOf("app_file_delete"),
            plugin.tools().filterIsInstance<UserConfirmationRequiredTool>().map { it.name }
        )
        val read = plugin.tools().single { it.name == "app_file_read" }.execute(
            ToolCall("call-read", "app_file_read", buildJsonObject { put("path", "notes/today.md") }),
            ToolExecutionContext(sessionId = "test")
        )
        assertFalse(read.content, read.isError)
    }

    @Test
    fun turningTheFlagOffRemovesTheWrapperAndRewritesThePromise() = runBlocking {
        val root = tempRoot()
        val target = File(root, "notes/today.md").also { file ->
            file.parentFile.mkdirs()
            file.writeText("bye")
        }
        val plugin = AppFileAgentPlugin(root, requireDeleteConfirmation = false)

        assertEquals(
            emptyList<String>(),
            plugin.tools().filterIsInstance<UserConfirmationRequiredTool>().map { it.name }
        )
        val deleted = plugin.tools().single { it.name == "app_file_delete" }.execute(
            ToolCall("call-del", "app_file_delete", buildJsonObject { put("path", "notes/today.md") }),
            ToolExecutionContext(sessionId = "test")
        )
        assertFalse(deleted.content, deleted.isError)
        assertFalse("the ungated delete did not remove the file", target.exists())

        val instruction = plugin.skills().single().instructions
        assertFalse(
            "the skill text still asks for a confirmation the plugin no longer requires",
            instruction.contains("app_file_delete requires a prior user confirmation")
        )
    }

    private fun tempRoot(): File = createTempDirectory("app-file-gate").toFile()

    /** Keeps the assertion target explicit: every tool this plugin exposes is accounted for. */
    @Test
    fun toolSetIsAccountedFor() {
        val tools: List<AgentTool> = AppFileAgentPlugin(tempRoot()).tools()
        assertEquals(
            listOf(
                "app_file_list",
                "app_file_read",
                "app_file_write",
                "app_file_append",
                "app_file_stat",
                "app_file_delete"
            ),
            tools.map { it.name }
        )
        assertTrue(tools.isNotEmpty())
    }
}
