package com.ugk.pi.android

import java.io.File
import java.io.IOException

/**
 * Dynamic [AndroidSkillProvider] for valid file-backed skills discovered by
 * [SkillRepository]. Runtime capability assembly owns the merge with
 * plugin-declared skills and other providers; this class only owns repository
 * scanning and named-root embed reads.
 *
 * [embedRoots] registers named root directories that `x-ugk-embed-files`
 * entries may reference as `alias:file.md` (alias matching `[a-z][a-z0-9-]*`).
 * The mechanism is generic: any skill can embed live `.md` files from any
 * host-registered root, which is what lets the agent-memory skill replay the
 * real memory store instead of static seed templates. Aliased entries whose
 * alias is not registered are skipped with an "unknown embed root" note.
 *
 * The public constructor intentionally accepts only [repository] and
 * [embedRoots] after D-024; the former plugin-list constructor has no
 * compatibility overload. Register [AgentSkillRuntimePlugin] when the tools,
 * instructions, and this file-backed source must be assembled together.
 */
class FileBackedSkillProvider(
    private val repository: SkillRepository,
    private val embedRoots: Map<String, File> = emptyMap()
) : AndroidSkillProvider {
    override val source: AndroidSkillProviderSource = AndroidSkillProviderSource.FILE_BACKED

    override fun skills(): List<AndroidSkill> {
        val fileSkills = repository.load()
            .filter { it.status == ScannedSkillStatus.VALID }
            .map { scanned ->
                val manifest = requireNotNull(scanned.manifest)
                AndroidSkill(
                    id = manifest.name,
                    description = manifest.description,
                    instructions = buildInstructions(manifest, scanned),
                    triggers = manifest.triggers
                )
            }
        return fileSkills
    }

    private fun buildInstructions(
        manifest: SkillManifest,
        scanned: ScannedSkill
    ): String {
        return when (manifest.loadPolicy) {
            SkillLoadPolicy.ALWAYS -> buildAlwaysInstructions(manifest, scanned)
            SkillLoadPolicy.INDEXED -> INDEXED_STUB_INSTRUCTIONS
            SkillLoadPolicy.TRIGGERED -> scanned.body
        }
    }

    /**
     * Always skills embed the referenced files behind the body so their full
     * guidance is present on every run. Bare entries resolve against the
     * skill directory; `alias:path` entries resolve against the embed root
     * registered under [embedRoots]. Each embed is capped at
     * [MAX_EMBED_FILE_BYTES]; oversized embeds are truncated with a note,
     * missing embed files and unknown embed roots are skipped with a note.
     * Files are re-read on every [skills] call, so embeds always reflect the
     * current on-disk content (live data, not a build-time snapshot).
     */
    private fun buildAlwaysInstructions(
        manifest: SkillManifest,
        scanned: ScannedSkill
    ): String {
        val builder = StringBuilder(scanned.body.trimEnd())
        manifest.embedFiles.forEach { entry ->
            val reference = SkillEmbedReference.parse(entry)
            if (reference == null) {
                // The parser already rejects invalid entries; this only
                // guards against regressions leaking through the manifest.
                builder.append("\n\n### Embedded: ").append(entry).append('\n')
                builder.append("(invalid embed entry; skipped)")
                return@forEach
            }
            builder.append("\n\n### Embedded: ").append(reference.display).append('\n')

            if (reference.alias != null && reference.alias !in embedRoots) {
                builder.append("(unknown embed root '${reference.alias}'; skipped)")
                return@forEach
            }
            val root = if (reference.alias == null) {
                scanned.directory
            } else {
                embedRoots.getValue(reference.alias)
            }
            val embedFile = resolveInsideRoot(root, reference.path)
            if (embedFile == null) {
                builder.append("(embed file resolves outside its root; skipped)")
                return@forEach
            }
            if (!embedFile.isFile) {
                builder.append("(embed file not found; skipped)")
                return@forEach
            }
            val bytes = try {
                embedFile.readBytes()
            } catch (error: IOException) {
                builder.append("(embed file could not be read; skipped)")
                return@forEach
            }
            if (bytes.size > MAX_EMBED_FILE_BYTES) {
                // Trim the trailing replacement char so a cut multi-byte sequence stays valid text.
                builder.append(
                    String(bytes.copyOf(MAX_EMBED_FILE_BYTES), Charsets.UTF_8).trimEnd('\uFFFD')
                )
                builder.append("\n(embed file truncated at ${MAX_EMBED_FILE_BYTES / 1024} KB)")
            } else {
                builder.append(String(bytes, Charsets.UTF_8))
            }
        }
        return builder.toString()
    }

    companion object {
        const val MAX_EMBED_FILE_BYTES = 16 * 1024

        /** Fixed instructions for indexed skills; `skill_read` loads the body. */
        const val INDEXED_STUB_INSTRUCTIONS =
            "Metadata-only skill. Invoke the `skill_read` tool with this skill's name " +
                "to load full instructions before relying on it."
    }
}
