package com.ugk.pi.android

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillToolsTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun skillListReportsValidAndInvalidSkills() = runBlocking {
        val root = skillRoot()
        writeSkill(root, "good", "description: Good skill.\nx-ugk-load: always\n", "Body.")
        writeSkill(root, "bad", "", "Body.")

        val result = SkillListTool(SkillRepository(root)).execute(call("skill_list"), context())

        assertFalse(result.isError)
        val entries = kotlinx.serialization.json.Json.parseToJsonElement(result.content).jsonArray
        assertEquals(2, entries.size)
        val good = entries.first { it.jsonObject["name"]!!.jsonPrimitive.content == "good" }.jsonObject
        val bad = entries.first { it.jsonObject["name"]!!.jsonPrimitive.content == "bad" }.jsonObject
        assertEquals("always", good["loadPolicy"]!!.jsonPrimitive.content)
        assertEquals("valid", good["status"]!!.jsonPrimitive.content)
        assertEquals("invalid", bad["status"]!!.jsonPrimitive.content)
        assertTrue(bad["error"]!!.jsonPrimitive.content.contains("description"))
    }

    @Test
    fun skillReadReturnsBodyAndEmbedList() = runBlocking {
        val root = skillRoot()
        writeSkill(
            root,
            "guide",
            "description: Guide.\nx-ugk-embed-files: present.md, absent.md\n",
            "Guide body."
        )
        writeEmbed(root, "guide", "present.md", "present")

        val result = SkillReadTool(SkillRepository(root))
            .execute(call("skill_read", "name" to "guide"), context())

        assertFalse(result.isError)
        assertTrue(result.content.contains("Guide body."))
        assertTrue(result.content.contains("### Embedded files"))
        assertTrue(result.content.contains("- present.md"))
        assertTrue(result.content.contains("- absent.md (missing)"))
    }

    @Test
    fun skillReadMarksNamedRootEmbedAvailability() = runBlocking {
        val root = skillRoot()
        val memoryRoot = tempFolder.newFolder("agent-memory")
        File(memoryRoot, "preferences.md").writeText("- live preference")
        writeSkill(
            root,
            "agent-memory",
            "description: Memory.\n" +
                "x-ugk-embed-files: memory:preferences.md, memory:rules.md, other:x.md\n",
            "Memory body."
        )

        val result = SkillReadTool(
            SkillRepository(root),
            embedRoots = mapOf("memory" to memoryRoot)
        ).execute(call("skill_read", "name" to "agent-memory"), context())

        assertFalse(result.isError)
        assertTrue(result.content.contains("- memory:preferences.md"))
        assertFalse(result.content.contains("- memory:preferences.md ("))
        assertTrue(result.content.contains("- memory:rules.md (missing)"))
        assertTrue(result.content.contains("- other:x.md (unknown root: other)"))
    }

    @Test
    fun skillReadErrorsForUnknownAndInvalidSkills() = runBlocking {
        val root = skillRoot()
        writeSkill(root, "broken", "", "Body.")
        val tool = SkillReadTool(SkillRepository(root))

        val unknown = tool.execute(call("skill_read", "name" to "ghost"), context())
        val invalid = tool.execute(call("skill_read", "name" to "broken"), context())

        assertEquals("SKILL_NOT_FOUND", unknown.metadata["code"]!!.jsonPrimitive.content)
        assertEquals("INVALID_SKILL", invalid.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memoryListReturnsEmptyWhenDirectoryMissing() = runBlocking {
        val result = MemoryListTool(File(tempFolder.root, "agent-memory"))
            .execute(call("memory_list"), context())

        assertFalse(result.isError)
        assertEquals("[]", result.content)
    }

    @Test
    fun memoryListReturnsCategoryEntries() = runBlocking {
        val memoryRoot = tempFolder.newFolder("agent-memory")
        File(memoryRoot, "preferences.md").writeText("- prefer Chinese")
        File(memoryRoot, "rules.md").writeText("- no camera")
        File(memoryRoot, "stray-notes.md").writeText("- not a category")

        val result = MemoryListTool(memoryRoot).execute(call("memory_list"), context())

        val entries = kotlinx.serialization.json.Json.parseToJsonElement(result.content).jsonArray
        assertEquals(2, entries.size)
        val names = entries.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("preferences.md", "rules.md"), names)
        assertTrue(entries.first().jsonObject.containsKey("bytes"))
        assertTrue(entries.first().jsonObject.containsKey("lastModifiedMillis"))
    }

    @Test
    fun memoryReadEnforcesCategoryWhitelist() = runBlocking {
        val tool = MemoryReadTool(tempFolder.newFolder("agent-memory"))

        val unknown = tool.execute(call("memory_read", "category" to "secrets"), context())

        assertEquals("UNKNOWN_CATEGORY", unknown.metadata["code"]!!.jsonPrimitive.content)
        assertTrue(unknown.content.contains("preferences"))
    }

    @Test
    fun memoryReadReturnsNotFoundThenContent() = runBlocking {
        val memoryRoot = tempFolder.newFolder("agent-memory")
        val tool = MemoryReadTool(memoryRoot)
        MemoryWriteTool(memoryRoot).execute(
            call("memory_write", "category" to "preferences", "content" to "- prefer Chinese"),
            context()
        )

        val notFound = tool.execute(call("memory_read", "category" to "rules"), context())
        val found = tool.execute(call("memory_read", "category" to "preferences"), context())

        assertEquals("NOT_FOUND", notFound.metadata["code"]!!.jsonPrimitive.content)
        assertFalse(found.isError)
        assertEquals("- prefer Chinese", found.content)
    }

    @Test
    fun memoryWriteRequiresOverwriteForExistingCategory() = runBlocking {
        val memoryRoot = tempFolder.newFolder("agent-memory")
        val tool = MemoryWriteTool(memoryRoot)

        val created = tool.execute(
            call("memory_write", "category" to "rules", "content" to "- first"),
            context()
        )
        val rejected = tool.execute(
            call("memory_write", "category" to "rules", "content" to "- second"),
            context()
        )
        val overwritten = tool.execute(
            call("memory_write", "category" to "rules", "content" to "- first\n- second", "overwrite" to true),
            context()
        )

        assertFalse(created.isError)
        assertTrue(created.content.contains("bytes"))
        assertEquals("FILE_EXISTS", rejected.metadata["code"]!!.jsonPrimitive.content)
        assertFalse(overwritten.isError)
        assertEquals("- first\n- second", File(memoryRoot, "rules.md").readText())
    }

    @Test
    fun memoryWriteRejectsUnknownCategoryAndTooLargeContent() = runBlocking {
        val tool = MemoryWriteTool(tempFolder.newFolder("agent-memory"))

        val unknownCategory = tool.execute(
            call("memory_write", "category" to "diary", "content" to "x"),
            context()
        )
        val tooLarge = tool.execute(
            call(
                "memory_write",
                "category" to "facts",
                "content" to "a".repeat(16 * 1024 + 1)
            ),
            context()
        )

        assertEquals("UNKNOWN_CATEGORY", unknownCategory.metadata["code"]!!.jsonPrimitive.content)
        assertEquals("CONTENT_TOO_LARGE", tooLarge.metadata["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun memoryDeleteRemovesCategoryFileAndValidatesInput() = runBlocking {
        val memoryRoot = tempFolder.newFolder("agent-memory")
        val memoryFile = File(memoryRoot, "facts.md").apply { writeText("- fact") }
        val tool = MemoryDeleteTool(memoryRoot)

        val unknown = tool.execute(call("memory_delete", "category" to "diary"), context())
        val notFound = tool.execute(call("memory_delete", "category" to "rules"), context())
        val deleted = tool.execute(call("memory_delete", "category" to "facts"), context())

        assertEquals("UNKNOWN_CATEGORY", unknown.metadata["code"]!!.jsonPrimitive.content)
        assertEquals("NOT_FOUND", notFound.metadata["code"]!!.jsonPrimitive.content)
        assertFalse(deleted.isError)
        assertFalse(memoryFile.exists())
    }

    private fun skillRoot(): File = tempFolder.newFolder("agent-skills")

    private fun writeSkill(root: File, name: String, frontmatter: String, body: String) {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $name\n$frontmatter---\n$body")
    }

    private fun writeEmbed(root: File, skillName: String, fileName: String, content: String) {
        File(File(root, skillName), fileName).writeText(content)
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
