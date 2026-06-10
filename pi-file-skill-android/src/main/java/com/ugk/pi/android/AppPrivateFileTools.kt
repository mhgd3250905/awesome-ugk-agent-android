package com.ugk.pi.android

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L

private val supportedTextExtensions = setOf(
    "txt",
    "md",
    "json",
    "jsonl",
    "csv",
    "log",
    "xml",
    "html",
    "yaml",
    "yml"
)

fun appPrivateFileTools(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
): List<AgentTool> {
    return listOf(
        AppFileListTool(rootDir, maxFileBytes),
        AppFileReadTool(rootDir, maxFileBytes),
        AppFileWriteTool(rootDir, maxFileBytes),
        AppFileAppendTool(rootDir, maxFileBytes),
        AppFileStatTool(rootDir, maxFileBytes),
        AppFileDeleteTool(rootDir, maxFileBytes)
    )
}

class AppFileAgentPlugin(
    private val rootDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val requireDeleteConfirmation: Boolean = true
) : AgentCapabilityPlugin {
    override val id: String = "app-private-files"

    override fun tools(): List<AgentTool> {
        return appPrivateFileTools(rootDir, maxFileBytes).map { tool ->
            if (requireDeleteConfirmation && tool.name == "app_file_delete") {
                UserConfirmationRequiredTool(tool)
            } else {
                tool
            }
        }
    }

    override fun skills(): List<AndroidSkill> = listOf(appPrivateFilesSkill())
}

fun appPrivateFilesSkill(): AndroidSkill {
    return AndroidSkill(
        id = "app-private-files",
        description = "Use when the user asks to create, read, update, append, list, inspect, or delete text files in the app-private agent workspace.",
        triggers = listOf(
            "file",
            "files",
            "read file",
            "write file",
            "append file",
            "delete file",
            "list files",
            "note",
            "notes",
            "文件",
            "读文件",
            "写文件",
            "追加",
            "删除文件",
            "笔记"
        ),
        instructions = """
            This Android-Skill describes app-private text file tools exposed by the host app.
            These tools operate only inside the host app's private agent file workspace.
            They are not general Android filesystem tools and they do not grant external storage access.

            Use relative paths such as notes/today.md, reports/status.json, or logs/session.jsonl.
            Do not use absolute paths, parent-directory traversal, external storage paths, shared preferences, databases, cache files, API keys, or system files.
            Files are text-only and each file can be at most 10 MB.

            In this demo host, app_file_delete requires a prior user confirmation through show_user_confirmation_dialog.
        """.trimIndent(),
        methods = listOf(
            AndroidSkillMethod(
                toolName = "app_file_list",
                purpose = "Lists files and directories under a relative directory path in the app-private agent workspace.",
                whenToUse = "Use before reading or updating workspace files when the user asks what files exist or when you need to inspect directory contents.",
                resultSemantics = "Returns entries with name, path, type, and sizeBytes metadata for files."
            ),
            AndroidSkillMethod(
                toolName = "app_file_read",
                purpose = "Reads a text file from the app-private agent workspace.",
                whenToUse = "Use when the user asks to open, inspect, summarize, or continue from a workspace text file.",
                resultSemantics = "The content is the complete text when the file exists, is a supported text type, and is not larger than 10 MB."
            ),
            AndroidSkillMethod(
                toolName = "app_file_write",
                purpose = "Writes a text file in the app-private agent workspace.",
                whenToUse = "Use when creating a new workspace text file or replacing an existing one with overwrite=true.",
                resultSemantics = "overwrite defaults to false; parent directories are created automatically."
            ),
            AndroidSkillMethod(
                toolName = "app_file_append",
                purpose = "Appends text to a workspace file, creating it if missing.",
                whenToUse = "Use for logs, notes, JSONL fragments, and ongoing records where preserving existing content matters.",
                resultSemantics = "The append is rejected if the resulting file would exceed 10 MB."
            ),
            AndroidSkillMethod(
                toolName = "app_file_stat",
                purpose = "Returns metadata for a workspace file or directory.",
                whenToUse = "Use to check whether a path exists, whether it is a file or directory, and how large it is.",
                resultSemantics = "Returns exists/type/path/name/sizeBytes/lastModifiedMillis metadata when available."
            ),
            AndroidSkillMethod(
                toolName = "app_file_delete",
                purpose = "Deletes one file from the app-private agent workspace.",
                whenToUse = "Use only after the user confirms deletion. Do not use for directories or recursive deletion.",
                resultSemantics = "Deletes files only; directory deletion is unsupported."
            )
        )
    )
}

class AppFileListTool(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    override val name: String = "app_file_list"
) : AppPrivateFileTool(rootDir, maxFileBytes) {
    override val description: String = "Lists files and directories in the app-private agent workspace."
    override val inputSchema: JsonObject = pathSchema(requiredPath = false)

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.input.string("path") ?: ""
        val resolved = resolvePath(path, requireTextFile = false)
        if (resolved is FileResolveResult.Error) return errorResult(call, resolved.code, resolved.message)
        val target = (resolved as FileResolveResult.Success).file
        if (!target.exists()) return errorResult(call, "FILE_NOT_FOUND", "Directory does not exist: $path")
        if (!target.isDirectory) return errorResult(call, "NOT_DIRECTORY", "Path is not a directory: $path")

        val entries = target.listFiles().orEmpty().sortedBy { it.name.lowercase() }
        val metadata = buildJsonObject {
            put("path", normalizedRelativePath(target))
            put("type", "directory")
            putJsonArray("entries") {
                entries.forEach { entry ->
                    add(fileMetadata(entry))
                }
            }
        }
        val content = entries.joinToString(separator = "\n") { entry ->
            val type = if (entry.isDirectory) "dir" else "file"
            "$type ${normalizedRelativePath(entry)}"
        }
        return ToolResult(call.id, name, content, metadata = metadata)
    }
}

class AppFileReadTool(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    override val name: String = "app_file_read"
) : AppPrivateFileTool(rootDir, maxFileBytes) {
    override val description: String = "Reads a text file in the app-private agent workspace."
    override val inputSchema: JsonObject = pathSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val resolved = resolvePath(call.input.string("path"), requireTextFile = true)
        if (resolved is FileResolveResult.Error) return errorResult(call, resolved.code, resolved.message)
        val file = (resolved as FileResolveResult.Success).file
        if (!file.exists()) return errorResult(call, "FILE_NOT_FOUND", "File does not exist: ${resolved.path}")
        if (!file.isFile) return errorResult(call, "NOT_FILE", "Path is not a file: ${resolved.path}")
        if (file.length() > maxFileBytes) return errorResult(call, "FILE_TOO_LARGE", "File exceeds the 10 MB limit: ${resolved.path}")
        return try {
            ToolResult(call.id, name, file.readText(), metadata = fileMetadata(file))
        } catch (error: IOException) {
            errorResult(call, "IO_ERROR", error.message ?: error::class.java.name)
        }
    }
}

class AppFileWriteTool(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    override val name: String = "app_file_write"
) : AppPrivateFileTool(rootDir, maxFileBytes) {
    override val description: String = "Writes a text file in the app-private agent workspace."
    override val inputSchema: JsonObject = writeSchema(includeOverwrite = true)

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val content = call.input.string("content") ?: return errorResult(call, "MISSING_CONTENT", "content is required.")
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        if (contentBytes.size > maxFileBytes) return errorResult(call, "FILE_TOO_LARGE", "Content exceeds the 10 MB limit.")
        val resolved = resolvePath(call.input.string("path"), requireTextFile = true)
        if (resolved is FileResolveResult.Error) return errorResult(call, resolved.code, resolved.message)
        val file = (resolved as FileResolveResult.Success).file
        val overwrite = call.input.boolean("overwrite") ?: false
        if (file.exists() && !overwrite) return errorResult(call, "FILE_EXISTS", "File already exists: ${resolved.path}")
        if (file.exists() && !file.isFile) return errorResult(call, "NOT_FILE", "Path is not a file: ${resolved.path}")

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Wrote ${resolved.path}.",
                metadata = fileMetadata(file).plus("overwritten", JsonPrimitive(overwrite))
            )
        } catch (error: IOException) {
            errorResult(call, "IO_ERROR", error.message ?: error::class.java.name)
        }
    }
}

class AppFileAppendTool(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    override val name: String = "app_file_append"
) : AppPrivateFileTool(rootDir, maxFileBytes) {
    override val description: String = "Appends text to a file in the app-private agent workspace."
    override val inputSchema: JsonObject = writeSchema(includeOverwrite = false)

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val content = call.input.string("content") ?: return errorResult(call, "MISSING_CONTENT", "content is required.")
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val resolved = resolvePath(call.input.string("path"), requireTextFile = true)
        if (resolved is FileResolveResult.Error) return errorResult(call, resolved.code, resolved.message)
        val file = (resolved as FileResolveResult.Success).file
        if (file.exists() && !file.isFile) return errorResult(call, "NOT_FILE", "Path is not a file: ${resolved.path}")
        val nextSize = file.length() + contentBytes.size
        if (nextSize > maxFileBytes) return errorResult(call, "FILE_TOO_LARGE", "Append would exceed the 10 MB limit: ${resolved.path}")

        return try {
            file.parentFile?.mkdirs()
            file.appendText(content)
            ToolResult(call.id, name, "Appended ${resolved.path}.", metadata = fileMetadata(file))
        } catch (error: IOException) {
            errorResult(call, "IO_ERROR", error.message ?: error::class.java.name)
        }
    }
}

class AppFileStatTool(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    override val name: String = "app_file_stat"
) : AppPrivateFileTool(rootDir, maxFileBytes) {
    override val description: String = "Returns metadata for a file or directory in the app-private agent workspace."
    override val inputSchema: JsonObject = pathSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val resolved = resolvePath(call.input.string("path"), requireTextFile = false)
        if (resolved is FileResolveResult.Error) return errorResult(call, resolved.code, resolved.message)
        val file = (resolved as FileResolveResult.Success).file
        if (!file.exists()) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Path does not exist: ${resolved.path}",
                metadata = buildJsonObject {
                    put("path", resolved.path)
                    put("exists", false)
                }
            )
        }
        return ToolResult(call.id, name, fileMetadata(file).toString(), metadata = fileMetadata(file))
    }
}

class AppFileDeleteTool(
    rootDir: File,
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    override val name: String = "app_file_delete"
) : AppPrivateFileTool(rootDir, maxFileBytes) {
    override val description: String = "Deletes one file from the app-private agent workspace."
    override val inputSchema: JsonObject = pathSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val resolved = resolvePath(call.input.string("path"), requireTextFile = true)
        if (resolved is FileResolveResult.Error) return errorResult(call, resolved.code, resolved.message)
        val file = (resolved as FileResolveResult.Success).file
        if (!file.exists()) return errorResult(call, "FILE_NOT_FOUND", "File does not exist: ${resolved.path}")
        if (file.isDirectory) return errorResult(call, "DIRECTORY_DELETE_UNSUPPORTED", "Directory deletion is unsupported: ${resolved.path}")
        if (!file.isFile) return errorResult(call, "NOT_FILE", "Path is not a regular file: ${resolved.path}")
        return try {
            val deleted = file.delete()
            if (!deleted) {
                errorResult(call, "IO_ERROR", "Failed to delete file: ${resolved.path}")
            } else {
                ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "Deleted ${resolved.path}.",
                    metadata = buildJsonObject {
                        put("path", resolved.path)
                        put("deleted", true)
                    }
                )
            }
        } catch (error: SecurityException) {
            errorResult(call, "IO_ERROR", error.message ?: error::class.java.name)
        }
    }
}

abstract class AppPrivateFileTool(
    private val rootDir: File,
    protected val maxFileBytes: Long
) : AgentTool {
    private val canonicalRoot: File by lazy {
        rootDir.mkdirs()
        rootDir.canonicalFile
    }

    protected fun resolvePath(path: String?, requireTextFile: Boolean): FileResolveResult {
        val rawPath = path?.trim()
        if (rawPath.isNullOrBlank()) {
            return if (requireTextFile) {
                FileResolveResult.Error("INVALID_PATH", "path is required.")
            } else {
                FileResolveResult.Success(canonicalRoot, "")
            }
        }
        if (File(rawPath).isAbsolute || rawPath.startsWith('/') || rawPath.contains('\\')) {
            return FileResolveResult.Error("INVALID_PATH", "Path must be relative.")
        }
        val segments = rawPath.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "." || it == ".." }) {
            return FileResolveResult.Error("INVALID_PATH", "Path must not contain . or ..")
        }
        val file = File(canonicalRoot, segments.joinToString(File.separator)).canonicalFile
        if (!file.isInside(canonicalRoot)) {
            return FileResolveResult.Error("OUTSIDE_ROOT", "Path resolves outside the app-private workspace.")
        }
        val normalizedPath = normalizedRelativePath(file)
        if (requireTextFile && !isSupportedTextPath(file.name)) {
            return FileResolveResult.Error("UNSUPPORTED_FILE_TYPE", "Only supported text files can be used: $normalizedPath")
        }
        return FileResolveResult.Success(file, normalizedPath)
    }

    protected fun normalizedRelativePath(file: File): String {
        val relative = canonicalRoot.toPath().relativize(file.canonicalFile.toPath()).toString()
        return relative.replace(File.separatorChar, '/')
    }

    protected fun fileMetadata(file: File): JsonObject {
        return buildJsonObject {
            put("path", normalizedRelativePath(file))
            put("name", file.name)
            put("exists", file.exists())
            put("type", if (file.isDirectory) "directory" else "file")
            if (file.isFile) put("sizeBytes", file.length())
            put("lastModifiedMillis", file.lastModified())
        }
    }

    protected fun errorResult(call: ToolCall, code: String, message: String): ToolResult {
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

    private fun File.isInside(root: File): Boolean {
        return toPath().startsWith(root.toPath())
    }

    private fun isSupportedTextPath(fileName: String): Boolean {
        val lastDot = fileName.lastIndexOf('.')
        if (lastDot < 0) return true
        if (lastDot == fileName.lastIndex) return false
        return fileName.substring(lastDot + 1).lowercase() in supportedTextExtensions
    }
}

sealed class FileResolveResult {
    data class Success(val file: File, val path: String) : FileResolveResult()
    data class Error(val code: String, val message: String) : FileResolveResult()
}

private fun pathSchema(requiredPath: Boolean = true): JsonObject {
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Relative path inside the app-private agent file workspace.")
            }
        }
        if (requiredPath) {
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }
    }
}

private fun writeSchema(includeOverwrite: Boolean): JsonObject {
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Relative path inside the app-private agent file workspace.")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Text content to write or append.")
            }
            if (includeOverwrite) {
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put("description", "Set true to replace an existing file. Defaults to false.")
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("content"))
        }
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
