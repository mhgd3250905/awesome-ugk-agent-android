package com.ugk.pi.android

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Raw (unwrapped) tool set of the agent skill runtime. The plugin wraps
 * `skill_save`, `skill_delete`, and `memory_delete` with
 * [UserConfirmationRequiredTool] by default because they change Agent
 * behavior or destroy user data; hosts that want the raw tool set can build it
 * from this list. [embedRoots] are the named roots that `x-ugk-embed-files`
 * `alias:file.md` entries resolve against; they should match the map given to
 * `FileBackedSkillProvider` so `skill_read` reports availability correctly.
 */
fun agentSkillRuntimeTools(
    repository: SkillRepository,
    memoryRoot: File,
    embedRoots: Map<String, File> = emptyMap()
): List<AgentTool> {
    return listOf(
        SkillListTool(repository),
        SkillReadTool(repository, embedRoots),
        SkillSaveTool(repository),
        SkillDeleteTool(repository),
        MemoryListTool(memoryRoot),
        MemoryReadTool(memoryRoot),
        MemoryWriteTool(memoryRoot),
        MemoryDeleteTool(memoryRoot)
    )
}

/** Whitelisted agent-memory categories; each category maps to `<category>.md`. */
val AGENT_MEMORY_CATEGORIES: List<String> = listOf(
    "user-profile",
    "preferences",
    "facts",
    "rules"
)

private const val MAX_MEMORY_FILE_BYTES = 16L * 1024L

class SkillListTool(
    private val repository: SkillRepository,
    override val name: String = "skill_list"
) : AgentTool {
    override val description: String =
        "Lists file-backed skills with name, description, load policy, and status. " +
            "Invalid skills are listed with the reason they were rejected."
    override val inputSchema: JsonObject = noPropertiesSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val skills = buildJsonArray {
            repository.load().forEach { scanned ->
                addJsonObject {
                    put("name", scanned.manifest?.name ?: scanned.directoryName)
                    put("description", scanned.manifest?.description ?: "")
                    put(
                        "loadPolicy",
                        scanned.manifest?.loadPolicy?.name?.lowercase() ?: "triggered"
                    )
                    put(
                        "status",
                        if (scanned.status == ScannedSkillStatus.VALID) "valid" else "invalid"
                    )
                    scanned.error?.let { put("error", it) }
                }
            }
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = skills.toString(),
            metadata = buildJsonObject { put("count", JsonPrimitive(skills.size)) }
        )
    }
}

class SkillReadTool(
    private val repository: SkillRepository,
    private val embedRoots: Map<String, File> = emptyMap(),
    override val name: String = "skill_read"
) : AgentTool {
    override val description: String =
        "Loads the full markdown body of a file-backed skill by name. " +
            "Use it for indexed (metadata-only) skills before relying on them."
    override val inputSchema: JsonObject = nameSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val skillName = call.input.string("name")
            ?: return errorResult(call, name, "MISSING_NAME", "name is required.")
        val scanned = repository.load()
            .firstOrNull { it.manifest?.name == skillName || it.directoryName == skillName }
            ?: return errorResult(call, name, "SKILL_NOT_FOUND", "No skill named '$skillName'.")

        if (scanned.status != ScannedSkillStatus.VALID || scanned.manifest == null) {
            return errorResult(
                call,
                name,
                "INVALID_SKILL",
                "Skill '$skillName' is invalid and cannot be read: ${scanned.error}"
            )
        }

        val manifest = scanned.manifest
        val content = buildString {
            appendLine("Skill: ${manifest.name}")
            appendLine("Description: ${manifest.description}")
            appendLine("Load policy: ${manifest.loadPolicy.name.lowercase()}")
            appendLine(
                "Triggers: " +
                    if (manifest.triggers.isEmpty()) "(none)" else manifest.triggers.joinToString(", ")
            )
            appendLine()
            append(scanned.body.trimEnd())
            appendLine()
            appendLine()
            appendLine("### Embedded files (x-ugk-embed-files)")
            if (manifest.embedFiles.isEmpty()) {
                appendLine("- (none)")
            } else {
                manifest.embedFiles.forEach { embedPath ->
                    appendLine("- $embedPath${embedAvailabilityNote(scanned, embedPath)}")
                }
            }
        }.trimEnd()

        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = content,
            metadata = buildJsonObject {
                put("name", manifest.name)
                put("description", manifest.description)
                put("loadPolicy", manifest.loadPolicy.name.lowercase())
                putJsonArray("triggers") {
                    manifest.triggers.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("embedFiles") {
                    manifest.embedFiles.forEach { add(JsonPrimitive(it)) }
                }
            }
        )
    }

    /**
     * Availability note for one embed entry. Bare entries are checked inside
     * the skill directory; `alias:path` entries are checked inside the
     * registered embed root, with an explicit note when the alias itself is
     * unknown so the model does not mistake unregistered roots for missing
     * memory.
     */
    private fun embedAvailabilityNote(scanned: ScannedSkill, entry: String): String {
        val reference = SkillEmbedReference.parse(entry)
            ?: return " (invalid)"
        if (reference.alias == null) {
            return if (resolveInsideRoot(scanned.directory, reference.path)?.isFile == true) {
                ""
            } else {
                " (missing)"
            }
        }
        val root = embedRoots[reference.alias]
            ?: return " (unknown root: ${reference.alias})"
        return if (resolveInsideRoot(root, reference.path)?.isFile == true) {
            ""
        } else {
            " (missing)"
        }
    }
}

abstract class AgentMemoryTool(
    protected val memoryRoot: File
) : AgentTool {

    protected fun memoryFile(category: String): File = File(memoryRoot, "$category.md")

    protected fun categoryError(call: ToolCall, category: String): ToolResult {
        return errorResult(
            call,
            name,
            "UNKNOWN_CATEGORY",
            "Unknown memory category '$category'. " +
                "Valid categories: ${AGENT_MEMORY_CATEGORIES.joinToString(", ")}."
        )
    }

    protected fun memoryMetadata(file: File): JsonObject {
        return buildJsonObject {
            put("name", file.name)
            put("bytes", JsonPrimitive(file.length()))
            put("lastModifiedMillis", JsonPrimitive(file.lastModified()))
        }
    }
}

class MemoryListTool(
    memoryRoot: File,
    override val name: String = "memory_list"
) : AgentMemoryTool(memoryRoot) {
    override val description: String =
        "Lists the agent-memory category files with name, size in bytes, and last modified time."
    override val inputSchema: JsonObject = noPropertiesSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val entries = memoryRoot
            .takeIf { it.isDirectory }
            ?.listFiles { file ->
                file.isFile && file.name.removeSuffix(".md") in AGENT_MEMORY_CATEGORIES
            }
            .orEmpty()
            .sortedBy { it.name }
        val entriesJson = buildJsonArray {
            entries.forEach { file ->
                addJsonObject {
                    put("name", file.name)
                    put("category", file.name.removeSuffix(".md"))
                    put("bytes", JsonPrimitive(file.length()))
                    put("lastModifiedMillis", JsonPrimitive(file.lastModified()))
                }
            }
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = entriesJson.toString(),
            metadata = buildJsonObject { put("count", JsonPrimitive(entries.size)) }
        )
    }
}

class MemoryReadTool(
    memoryRoot: File,
    override val name: String = "memory_read"
) : AgentMemoryTool(memoryRoot) {
    override val description: String =
        "Reads the full content of one agent-memory category file " +
            "(user-profile, preferences, facts, or rules)."
    override val inputSchema: JsonObject = categorySchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val category = call.input.string("category")
            ?: return errorResult(call, name, "MISSING_CATEGORY", "category is required.")
        if (category !in AGENT_MEMORY_CATEGORIES) return categoryError(call, category)
        val file = memoryFile(category)
        if (!file.isFile) {
            return errorResult(call, name, "NOT_FOUND", "No memory stored for category '$category'.")
        }
        if (file.length() > MAX_MEMORY_FILE_BYTES) {
            return errorResult(call, name, "FILE_TOO_LARGE", "Memory file exceeds the 16 KB limit: ${file.name}")
        }
        return try {
            ToolResult(call.id, name, file.readText(), metadata = memoryMetadata(file))
        } catch (error: IOException) {
            errorResult(call, name, "IO_ERROR", error.message ?: error::class.java.name)
        }
    }
}

class MemoryWriteTool(
    memoryRoot: File,
    override val name: String = "memory_write"
) : AgentMemoryTool(memoryRoot) {
    override val description: String =
        "Writes the full content of one agent-memory category file. Read the category " +
            "first with memory_read, merge entries without losing existing ones, then " +
            "write the whole file with overwrite=true. Requires prior user consent."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("category") {
                put("type", "string")
                put("description", "Memory category: user-profile, preferences, facts, or rules.")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Full merged file content to write.")
            }
            putJsonObject("overwrite") {
                put("type", "boolean")
                put("description", "Set true to replace the existing category file. Defaults to false.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("category"))
            add(JsonPrimitive("content"))
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val category = call.input.string("category")
            ?: return errorResult(call, name, "MISSING_CATEGORY", "category is required.")
        if (category !in AGENT_MEMORY_CATEGORIES) return categoryError(call, category)
        val content = call.input.string("content")
            ?: return errorResult(call, name, "MISSING_CONTENT", "content is required.")
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        if (contentBytes.size > MAX_MEMORY_FILE_BYTES) {
            return errorResult(call, name, "CONTENT_TOO_LARGE", "Content exceeds the 16 KB limit.")
        }

        val file = memoryFile(category)
        val overwrite = call.input.boolean("overwrite") ?: false
        if (file.exists() && !overwrite) {
            return errorResult(
                call,
                name,
                "FILE_EXISTS",
                "Memory file '${file.name}' already exists; read it with memory_read, " +
                    "merge the entries, then write with overwrite=true."
            )
        }

        return try {
            memoryRoot.mkdirs()
            writeTextAtomically(file, content)
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Wrote ${contentBytes.size} bytes to ${file.name}.",
                metadata = memoryMetadata(file).plus("overwritten", JsonPrimitive(overwrite))
            )
        } catch (error: IOException) {
            errorResult(call, name, "IO_ERROR", error.message ?: error::class.java.name)
        }
    }
}

class MemoryDeleteTool(
    memoryRoot: File,
    override val name: String = "memory_delete"
) : AgentMemoryTool(memoryRoot) {
    override val description: String =
        "Deletes one agent-memory category file after the user confirmed the exact " +
            "content that will be removed."
    override val inputSchema: JsonObject = categorySchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val category = call.input.string("category")
            ?: return errorResult(call, name, "MISSING_CATEGORY", "category is required.")
        if (category !in AGENT_MEMORY_CATEGORIES) return categoryError(call, category)
        val file = memoryFile(category)
        if (!file.isFile) {
            return errorResult(call, name, "NOT_FOUND", "No memory stored for category '$category'.")
        }
        val deleted = try {
            file.delete()
        } catch (error: SecurityException) {
            false
        }
        return if (deleted) {
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Deleted ${file.name}.",
                metadata = buildJsonObject {
                    put("category", category)
                    put("deleted", true)
                }
            )
        } else {
            errorResult(call, name, "IO_ERROR", "Failed to delete file: ${file.name}")
        }
    }
}

internal fun errorResult(call: ToolCall, name: String, code: String, message: String): ToolResult {
    return ToolResult(
        toolCallId = call.id,
        name = name,
        content = message,
        isError = true,
        metadata = buildJsonObject {
            put("code", code)
            put("message", message)
        }
    )
}

private fun noPropertiesSchema(): JsonObject {
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
}

private fun nameSchema(): JsonObject {
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Skill name from skill_list.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }
}

private fun categorySchema(): JsonObject {
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("category") {
                put("type", "string")
                put("description", "Memory category: user-profile, preferences, facts, or rules.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("category")) }
    }
}

private fun JsonObject.string(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.boolean(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

private fun JsonObject.plus(key: String, value: JsonPrimitive): JsonObject {
    return JsonObject(this + (key to value))
}
