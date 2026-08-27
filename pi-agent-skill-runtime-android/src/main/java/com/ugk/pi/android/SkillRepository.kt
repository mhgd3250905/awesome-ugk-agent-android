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

/**
 * Scans `<rootDir>/agent-skills` on every [load] call. The skill set is
 * expected to stay small, so results are not cached; each call re-reads the
 * directory from disk.
 */
class SkillRepository(private val rootDir: File) {

    fun load(): List<ScannedSkill> {
        val skillDirectories = rootDir
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
        return skillDirectories.map { directory -> scanDirectory(directory) }
    }

    private fun scanDirectory(directory: File): ScannedSkill {
        fun invalid(reason: String): ScannedSkill = ScannedSkill(
            directoryName = directory.name,
            directory = directory,
            manifest = null,
            body = "",
            status = ScannedSkillStatus.INVALID,
            error = reason
        )

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

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_FILE_BYTES = 128L * 1024L
    }
}
