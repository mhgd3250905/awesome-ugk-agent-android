package com.ugk.pi.android

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrivateFileToolsTest {
    @Test
    fun writeCreatesParentsAndReadReturnsContent() = runBlocking {
        val root = tempRoot()
        val write = AppFileWriteTool(root)
        val read = AppFileReadTool(root)

        val writeResult = write.execute(
            call("app_file_write", "path" to "notes/today.md", "content" to "hello"),
            context()
        )
        val readResult = read.execute(
            call("app_file_read", "path" to "notes/today.md"),
            context()
        )

        assertFalse(writeResult.isError)
        assertEquals("hello", File(root, "notes/today.md").readText())
        assertEquals("hello", readResult.content)
    }

    @Test
    fun rejectsAbsoluteAndTraversalPaths() = runBlocking {
        val root = tempRoot()
        val write = AppFileWriteTool(root)

        val absolute = write.execute(
            call("app_file_write", "path" to "/tmp/outside.md", "content" to "x"),
            context()
        )
        val traversal = write.execute(
            call("app_file_write", "path" to "../outside.md", "content" to "x"),
            context()
        )

        assertTrue(absolute.isError)
        assertEquals("INVALID_PATH", absolute.metadata["code"]!!.jsonPrimitive.content)
        assertTrue(traversal.isError)
        assertEquals("INVALID_PATH", traversal.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun writeDoesNotOverwriteUnlessExplicit() = runBlocking {
        val root = tempRoot()
        val write = AppFileWriteTool(root)
        write.execute(call("app_file_write", "path" to "note.md", "content" to "first"), context())

        val rejected = write.execute(call("app_file_write", "path" to "note.md", "content" to "second"), context())
        val overwritten = write.execute(
            call("app_file_write", "path" to "note.md", "content" to "second", "overwrite" to true),
            context()
        )

        assertTrue(rejected.isError)
        assertEquals("FILE_EXISTS", rejected.metadata["code"]!!.jsonPrimitive.content)
        assertFalse(overwritten.isError)
        assertEquals("second", File(root, "note.md").readText())
    }

    @Test
    fun appendCreatesFileAndRejectsTooLargeResult() = runBlocking {
        val root = tempRoot()
        val append = AppFileAppendTool(root, maxFileBytes = 5)

        val created = append.execute(call("app_file_append", "path" to "log.txt", "content" to "123"), context())
        val rejected = append.execute(call("app_file_append", "path" to "log.txt", "content" to "456"), context())

        assertFalse(created.isError)
        assertEquals("123", File(root, "log.txt").readText())
        assertTrue(rejected.isError)
        assertEquals("FILE_TOO_LARGE", rejected.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun readRejectsLargeAndUnsupportedFiles() = runBlocking {
        val root = tempRoot()
        File(root, "large.txt").writeText("123456")
        File(root, "image.png").writeText("not really an image")
        val read = AppFileReadTool(root, maxFileBytes = 5)

        val large = read.execute(call("app_file_read", "path" to "large.txt"), context())
        val unsupported = read.execute(call("app_file_read", "path" to "image.png"), context())

        assertTrue(large.isError)
        assertEquals("FILE_TOO_LARGE", large.metadata["code"]!!.jsonPrimitive.content)
        assertTrue(unsupported.isError)
        assertEquals("UNSUPPORTED_FILE_TYPE", unsupported.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun listAndStatReturnStructuredMetadata() = runBlocking {
        val root = tempRoot()
        File(root, "notes").mkdirs()
        File(root, "notes/a.md").writeText("a")
        val list = AppFileListTool(root)
        val stat = AppFileStatTool(root)

        val listResult = list.execute(call("app_file_list", "path" to "notes"), context())
        val statResult = stat.execute(call("app_file_stat", "path" to "notes/a.md"), context())

        assertFalse(listResult.isError)
        assertTrue(listResult.content.contains("a.md"))
        assertEquals("file", statResult.metadata["type"]!!.jsonPrimitive.content)
        assertEquals(1, statResult.metadata["sizeBytes"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun deleteRemovesFilesAndRejectsDirectories() = runBlocking {
        val root = tempRoot()
        val file = File(root, "notes/a.md").apply {
            parentFile!!.mkdirs()
            writeText("a")
        }
        val delete = AppFileDeleteTool(root)

        val deleted = delete.execute(call("app_file_delete", "path" to "notes/a.md"), context())
        val directory = delete.execute(call("app_file_delete", "path" to "notes"), context())

        assertFalse(deleted.isError)
        assertFalse(file.exists())
        assertTrue(directory.isError)
        assertEquals("DIRECTORY_DELETE_UNSUPPORTED", directory.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun appPrivateFilesSkillAdvertisesFileTools() {
        val skill = appPrivateFilesSkill()
        val toolNames = skill.methods.map { it.toolName }.toSet()

        assertTrue(toolNames.containsAll(appPrivateFileTools(tempRoot()).map { it.name }))
        assertTrue(skill.instructions.contains("relative paths"))
    }

    @Test
    fun appFileAgentPluginExposesWorkspaceToolsAndSkill() {
        val plugin = AppFileAgentPlugin(tempRoot())

        assertEquals("app-private-files", plugin.id)
        assertEquals(
            listOf(
                "app_file_list",
                "app_file_read",
                "app_file_write",
                "app_file_append",
                "app_file_stat",
                "app_file_delete"
            ),
            plugin.tools().map { it.name }
        )
        assertEquals(listOf("app-private-files"), plugin.skills().map { it.id })
    }

    private fun tempRoot(): File {
        return createTempDirectory("app-private-files").toFile()
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(sessionId = "test")

    private fun call(name: String, vararg pairs: Pair<String, Any>): ToolCall {
        return ToolCall(
            id = "call-1",
            name = name,
            input = buildJsonObject {
                pairs.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> put(key, JsonPrimitive(value))
                        is String -> put(key, JsonPrimitive(value))
                        is Number -> put(key, JsonPrimitive(value))
                    }
                }
            }
        )
    }
}
