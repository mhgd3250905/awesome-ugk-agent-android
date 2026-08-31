package com.ugk.pi.android

import java.io.File
import java.io.IOException

enum class ScannedSkillStatus {
    VALID,
    INVALID
}

/**
 * One scanned `<filesDir>/agent-skills/<skill-name>/SKILL.md` entry.
 *
 * Invalid skills are never injected, but they stay visible in `skill_list`
 * with an [error] reason instead of being silently dropped. The manifest and
 * body are null/empty for invalid entries; [directoryName] is always the
 * on-disk directory name so invalid entries can still be listed.
 */
data class ScannedSkill(
    val directoryName: String,
    val directory: File,
    val manifest: SkillManifest?,
    val body: String,
    val status: ScannedSkillStatus,
    val error: String? = null
)

/** Structured input for the single-file skill authoring MVP. */
data class SkillSaveRequest(
    val name: String,
    val description: String,
    val body: String,
    val loadPolicy: SkillLoadPolicy = SkillLoadPolicy.TRIGGERED,
    val triggers: List<String> = emptyList(),
    val embedFiles: List<String> = emptyList(),
    val overwrite: Boolean = false
)

sealed class SkillSaveOutcome {
    data class Saved(
        val skill: ScannedSkill,
        val overwritten: Boolean
    ) : SkillSaveOutcome()

    data class Failed(
        val code: String,
        val message: String
    ) : SkillSaveOutcome()
}

sealed class SkillDeleteOutcome {
    data class Deleted(val name: String) : SkillDeleteOutcome()

    data class Failed(
        val code: String,
        val message: String
    ) : SkillDeleteOutcome()
}

private data class NormalizedSkillRequest(
    val request: SkillSaveRequest,
    val expectedManifest: SkillManifest,
    val expectedBody: String
)

/**
 * Scans `<rootDir>/agent-skills` on every [load] call. The skill set is
 * expected to stay small, so results are not cached; each call re-reads the
 * directory from disk.
 */
class SkillRepository(private val rootDir: File) {

    private val mutationLock = Any()

    fun load(): List<ScannedSkill> = synchronized(mutationLock) {
        val root = try {
            rootDir.canonicalFile
        } catch (error: IOException) {
            // Fail closed: without a resolvable root boundary no skill can be trusted.
            return@synchronized emptyList()
        }
        val skillDirectories = rootDir
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
        skillDirectories.map { directory -> scanDirectory(directory, root) }
    }

    /**
     * Validates and atomically installs one `<name>/SKILL.md` file below this
     * repository. The public method keeps mutation rules in the repository so
     * callers cannot bypass path, parser, or protected-skill checks.
     */
    fun saveSkill(request: SkillSaveRequest): SkillSaveOutcome = synchronized(mutationLock) {
        if (!SkillManifestParser.isValidSkillName(request.name)) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "INVALID_NAME",
                message = "Skill name must match [a-z0-9-]+ and contain no path separators."
            )
        }
        if (request.name in PROTECTED_SKILL_NAMES) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "PROTECTED_SKILL",
                message = "Skill '${request.name}' is built in and cannot be saved or overwritten."
            )
        }

        val normalized = try {
            normalizeRequest(request)
        } catch (error: IllegalArgumentException) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "INVALID_SKILL",
                message = "Skill '${request.name}' failed validation: ${error.message}"
            )
        }
        val rendered = renderSkill(normalized.request)
        val parsed = when (val result = SkillManifestParser.parse(rendered)) {
            is SkillManifestParseResult.Valid -> result
            is SkillManifestParseResult.Invalid -> {
                return@synchronized SkillSaveOutcome.Failed(
                    code = "INVALID_SKILL",
                    message = "Skill '${request.name}' failed validation: ${result.reason}"
                )
            }
        }
        if (parsed.manifest != normalized.expectedManifest ||
            parsed.body != normalized.expectedBody
        ) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "INVALID_SKILL",
                message = "Rendered SKILL.md did not preserve the complete manifest and body."
            )
        }

        val renderedBytes = rendered.toByteArray(Charsets.UTF_8)
        if (renderedBytes.size > MAX_SKILL_FILE_BYTES) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "CONTENT_TOO_LARGE",
                message = "SKILL.md exceeds the ${MAX_SKILL_FILE_BYTES / 1024} KB limit."
            )
        }

        val root = try {
            rootDir.canonicalFile
        } catch (error: IOException) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to resolve skill repository: ${error.message ?: error::class.java.name}"
            )
        }
        if (rootDir.exists() && !rootDir.isDirectory) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "IO_ERROR",
                message = "Skill repository is not a directory: ${rootDir.name}"
            )
        }
        if (!rootDir.exists() && !rootDir.mkdirs() && !rootDir.isDirectory) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to create skill repository directory."
            )
        }

        val directory = File(rootDir, request.name)
        val existed = directory.exists()
        if (existed && !directory.isDirectory) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "INVALID_TARGET",
                message = "Skill target is not a directory: ${request.name}."
            )
        }
        if (!isDirectChildOfRoot(directory, root)) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "INVALID_TARGET",
                message = "Skill target is outside the skill repository."
            )
        }
        if (existed && !request.overwrite) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "SKILL_EXISTS",
                message = "Skill '${request.name}' already exists; use overwrite=true after reading it."
            )
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to create skill directory '${request.name}'."
            )
        }

        val skillFile = File(directory, SKILL_FILE_NAME)
        if (skillFile.exists() && !isDirectChildOfDirectory(skillFile, directory)) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "INVALID_TARGET",
                message = "Existing SKILL.md target is outside the skill directory."
            )
        }
        val previousContent = if (skillFile.isFile) {
            try {
                skillFile.readBytes()
            } catch (error: IOException) {
                return@synchronized SkillSaveOutcome.Failed(
                    code = "IO_ERROR",
                    message = "Failed to read existing SKILL.md: ${error.message ?: error::class.java.name}"
                )
            }
        } else {
            null
        }

        val temporary = try {
            File.createTempFile(".skill-", ".tmp", directory)
        } catch (error: IOException) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to create a temporary skill file: ${error.message ?: error::class.java.name}"
            )
        }
        try {
            temporary.outputStream().use { output -> output.write(renderedBytes) }
            val temporaryText = temporary.readText(Charsets.UTF_8)
            when (val temporaryParsed = SkillManifestParser.parse(temporaryText)) {
                is SkillManifestParseResult.Invalid -> {
                    return@synchronized SkillSaveOutcome.Failed(
                        code = "INVALID_SKILL",
                        message = "Temporary SKILL.md failed validation: ${temporaryParsed.reason}"
                    )
                }
                is SkillManifestParseResult.Valid -> {
                    if (temporaryParsed.manifest != normalized.expectedManifest ||
                        temporaryParsed.body != normalized.expectedBody
                    ) {
                        return@synchronized SkillSaveOutcome.Failed(
                            code = "INVALID_SKILL",
                            message = "Temporary SKILL.md did not preserve the complete manifest and body."
                        )
                    }
                }
            }
            if (!replaceOnto(temporary, skillFile)) {
                return@synchronized SkillSaveOutcome.Failed(
                    code = "IO_ERROR",
                    message = "Failed to replace SKILL.md for '${request.name}'."
                )
            }

            val verified = load().firstOrNull { it.directoryName == request.name }
            if (verified == null ||
                verified.status != ScannedSkillStatus.VALID ||
                verified.manifest != normalized.expectedManifest ||
                verified.body != normalized.expectedBody
            ) {
                restorePreviousSkillFile(skillFile, previousContent)
                return@synchronized SkillSaveOutcome.Failed(
                    code = "WRITE_FAILED",
                    message = "Written skill '${request.name}' did not pass repository validation."
                )
            }
            return@synchronized SkillSaveOutcome.Saved(verified, overwritten = existed)
        } catch (error: IOException) {
            return@synchronized SkillSaveOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to write SKILL.md: ${error.message ?: error::class.java.name}"
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Deletes exactly one custom skill directory under this repository. */
    fun deleteSkill(name: String): SkillDeleteOutcome = synchronized(mutationLock) {
        if (!SkillManifestParser.isValidSkillName(name)) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "INVALID_NAME",
                message = "Skill name must match [a-z0-9-]+ and contain no path separators."
            )
        }
        if (name in PROTECTED_SKILL_NAMES) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "PROTECTED_SKILL",
                message = "Skill '$name' is built in and cannot be deleted."
            )
        }

        val root = try {
            rootDir.canonicalFile
        } catch (error: IOException) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to resolve skill repository: ${error.message ?: error::class.java.name}"
            )
        }
        val directory = File(rootDir, name)
        if (!directory.exists()) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "NOT_FOUND",
                message = "No skill named '$name'."
            )
        }
        if (!directory.isDirectory || !isDirectChildOfRoot(directory, root)) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "INVALID_TARGET",
                message = "Skill target '$name' is not a repository child directory."
            )
        }

        val validationError = validateDeleteTree(directory, root, mutableSetOf())
        if (validationError != null) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "INVALID_TARGET",
                message = validationError
            )
        }
        if (!deleteTree(directory) || directory.exists()) {
            return@synchronized SkillDeleteOutcome.Failed(
                code = "IO_ERROR",
                message = "Failed to delete skill '$name'."
            )
        }
        SkillDeleteOutcome.Deleted(name)
    }

    private fun scanDirectory(directory: File, root: File): ScannedSkill {
        fun invalid(reason: String): ScannedSkill = ScannedSkill(
            directoryName = directory.name,
            directory = directory,
            manifest = null,
            body = "",
            status = ScannedSkillStatus.INVALID,
            error = reason
        )

        scanBoundaryError(directory, root)?.let { return invalid(it) }

        val skillFile = File(directory, SKILL_FILE_NAME)
        if (!skillFile.isFile) {
            return invalid("SKILL.md not found in skill directory '${directory.name}'.")
        }
        if (skillFile.length() > MAX_SKILL_FILE_BYTES) {
            return invalid(
                "SKILL.md exceeds the ${MAX_SKILL_FILE_BYTES / 1024} KB limit " +
                    "(${skillFile.length()} bytes)."
            )
        }

        val text = try {
            skillFile.readText(Charsets.UTF_8)
        } catch (error: IOException) {
            return invalid("Failed to read SKILL.md: ${error.message}")
        }

        return when (val parsed = SkillManifestParser.parse(text)) {
            is SkillManifestParseResult.Invalid -> invalid(parsed.reason)
            is SkillManifestParseResult.Valid -> {
                if (parsed.manifest.name != directory.name) {
                    invalid(
                        "Skill name '${parsed.manifest.name}' does not match " +
                            "directory name '${directory.name}'."
                    )
                } else {
                    ScannedSkill(
                        directoryName = directory.name,
                        directory = directory,
                        manifest = parsed.manifest,
                        body = parsed.body,
                        status = ScannedSkillStatus.VALID
                    )
                }
            }
        }
    }

    /**
     * Rejects scan candidates that would read skill content from outside the
     * skill root through a link, matching the boundary checks of save and
     * delete. An `agent-skills/<name>` directory that is itself a link, or
     * whose SKILL.md resolves outside its directory, would otherwise be
     * parsed and injected (always policy) on every run. Returns the invalid
     * reason, or null when the candidate is a plain repository child.
     */
    private fun scanBoundaryError(directory: File, root: File): String? {
        return try {
            val canonical = directory.canonicalFile
            val canonicalSkillFile = File(directory, SKILL_FILE_NAME).canonicalFile
            when {
                canonical.parentFile?.path != root.path ->
                    "Skill directory '${directory.name}' is not a direct child of the skill repository."
                canonical.name != directory.name ->
                    "Skill directory '${directory.name}' resolves through a link " +
                        "and is not an exact repository child."
                canonicalSkillFile.parentFile?.path != canonical.path ->
                    "SKILL.md in '${directory.name}' resolves outside its skill directory."
                else -> null
            }
        } catch (error: IOException) {
            "Failed to resolve skill directory '${directory.name}': ${error.message}"
        }
    }

    private fun normalizeRequest(request: SkillSaveRequest): NormalizedSkillRequest {
        if (request.description.contains('\r') || request.description.contains('\n')) {
            throw IllegalArgumentException("description must not contain CR/LF.")
        }
        fun normalizeFlatEntries(field: String, entries: List<String>): List<String> {
            return entries.mapIndexed { index, value ->
                if (value.contains('\r') || value.contains('\n')) {
                    throw IllegalArgumentException("$field[$index] must not contain CR/LF.")
                }
                if (value.contains(',')) {
                    throw IllegalArgumentException("$field[$index] must not contain ','.")
                }
                value.trim()
            }.filter(String::isNotEmpty)
        }

        val normalizedRequest = request.copy(
            description = request.description.trim(),
            body = normalizeLineEndings(request.body),
            triggers = normalizeFlatEntries("triggers", request.triggers),
            embedFiles = normalizeFlatEntries("embedFiles", request.embedFiles)
        )
        return NormalizedSkillRequest(
            request = normalizedRequest,
            expectedManifest = SkillManifest(
                name = normalizedRequest.name,
                description = normalizedRequest.description,
                loadPolicy = normalizedRequest.loadPolicy,
                embedFiles = normalizedRequest.embedFiles.distinct(),
                triggers = normalizedRequest.triggers
            ),
            expectedBody = normalizedRequest.body
        )
    }

    private fun normalizeLineEndings(value: String): String {
        return value.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun renderSkill(request: SkillSaveRequest): String = buildString {
        appendLine(SkillManifestParser.FRONTMATTER_DELIMITER)
        appendLine("${SkillManifestParser.KEY_NAME}: ${request.name}")
        appendLine("${SkillManifestParser.KEY_DESCRIPTION}: ${request.description}")
        appendLine("${SkillManifestParser.KEY_LOAD_POLICY}: ${request.loadPolicy.name.lowercase()}")
        if (request.embedFiles.isNotEmpty()) {
            appendLine(
                "${SkillManifestParser.KEY_EMBED_FILES}: " +
                    request.embedFiles.joinToString(", ")
            )
        }
        if (request.triggers.isNotEmpty()) {
            appendLine("${SkillManifestParser.KEY_TRIGGERS}: " + request.triggers.joinToString(", "))
        }
        appendLine(SkillManifestParser.FRONTMATTER_DELIMITER)
        append(request.body)
    }

    private fun isDirectChildOfRoot(directory: File, root: File): Boolean {
        return runCatching {
            val canonical = directory.canonicalFile
            directory.name.isNotEmpty() &&
                canonical.name == directory.name &&
                canonical.parentFile?.path == root.path
        }.getOrDefault(false)
    }

    private fun isDirectChildOfDirectory(file: File, directory: File): Boolean {
        return runCatching {
            val canonical = file.canonicalFile
            file.name == SKILL_FILE_NAME &&
                canonical.name == file.name &&
                canonical.parentFile?.path == directory.canonicalFile.path
        }.getOrDefault(false)
    }

    private fun restorePreviousSkillFile(skillFile: File, previousContent: ByteArray?) {
        if (previousContent == null) {
            skillFile.delete()
            return
        }
        val directory = skillFile.parentFile ?: return
        val restoreFile = runCatching {
            File.createTempFile(".skill-restore-", ".tmp", directory)
        }.getOrNull() ?: return
        try {
            restoreFile.outputStream().use { it.write(previousContent) }
            replaceOnto(restoreFile, skillFile)
        } catch (_: IOException) {
            // Keep the best available state; the normal path is prevalidated,
            // so this is only a defensive rollback after an unexpected race.
        } finally {
            if (restoreFile.exists()) restoreFile.delete()
        }
    }

    private fun validateDeleteTree(
        file: File,
        root: File,
        visitedDirectories: MutableSet<String>
    ): String? {
        val canonical = try {
            file.canonicalFile
        } catch (error: IOException) {
            return "Failed to resolve deletion target '${file.name}': ${error.message}"
        }
        if (canonical.path == root.path) {
            return "Refusing to delete the skill repository root."
        }
        if (canonical.parentFile == null ||
            (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator))
        ) {
            return "Deletion target '${file.name}' resolves outside the skill repository."
        }
        if (canonical.name != file.name) {
            return "Deletion target '${file.name}' resolves through a link and is not an exact repository child."
        }
        if (file !== root && file.absoluteFile.parentFile?.canonicalFile?.path != canonical.parentFile?.path) {
            return "Deletion target '${file.name}' contains a symbolic-link-like path outside its own directory."
        }
        if (file.isDirectory) {
            if (!visitedDirectories.add(canonical.path)) {
                return "Deletion target contains a directory cycle."
            }
            val children = file.listFiles()
                ?: return "Failed to enumerate deletion target '${file.name}'."
            children.forEach { child ->
                validateDeleteTree(child, root, visitedDirectories)?.let { return it }
            }
        }
        return null
    }

    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            if (!children.all(::deleteTree)) return false
        }
        return file.delete()
    }

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_FILE_BYTES = 128L * 1024L
    }
}

/** Built-in skills are seeded by the SDK and are never agent-writable. */
val PROTECTED_SKILL_NAMES: Set<String> = setOf(
    "agent-memory",
    "android-skill-creator"
)
