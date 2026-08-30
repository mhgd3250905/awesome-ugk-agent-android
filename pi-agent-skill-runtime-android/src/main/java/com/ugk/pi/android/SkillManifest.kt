package com.ugk.pi.android

/**
 * Load strategy for a file-backed skill, declared by the `x-ugk-load` frontmatter key.
 *
 * - [ALWAYS]: the full instructions (plus embedded files) are injected on every run.
 * - [INDEXED]: only metadata (id + description + a stub) is injected; the model must
 *   call `skill_read` to load the full body.
 * - [TRIGGERED]: injected only when the resolver matches the user message.
 */
enum class SkillLoadPolicy {
    ALWAYS,
    INDEXED,
    TRIGGERED;

    companion object {
        fun fromFrontmatterValue(value: String): SkillLoadPolicy? {
            return entries.firstOrNull { it.name.lowercase() == value.lowercase() }
        }
    }
}

/**
 * Parsed SKILL.md frontmatter. Standard keys are `name` and `description`; the
 * `x-ugk-*` and `triggers` keys are extensions understood by this runtime.
 * Unknown keys are ignored for forward compatibility.
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val loadPolicy: SkillLoadPolicy = SkillLoadPolicy.TRIGGERED,
    val embedFiles: List<String> = emptyList(),
    val triggers: List<String> = emptyList()
)

sealed class SkillManifestParseResult {
    data class Valid(val manifest: SkillManifest, val body: String) : SkillManifestParseResult()
    data class Invalid(val reason: String) : SkillManifestParseResult()
}

/**
 * One `x-ugk-embed-files` entry. Bare entries resolve against the skill
 * directory; `alias:path` entries resolve against the host-registered embed
 * root named [alias] (see `FileBackedSkillProvider`'s `embedRoots`), so live
 * files outside the skill directory can be embedded on every skills() call.
 */
data class SkillEmbedReference(val alias: String?, val path: String) {

    /** Canonical entry text used in headers and stored in the manifest. */
    val display: String
        get() = if (alias == null) path else "$alias:$path"

    companion object {
        private val aliasPattern = Regex("^[a-z][a-z0-9-]*$")

        /**
         * Splits an entry on the first ':'. When the prefix is a valid alias
         * (`[a-z][a-z0-9-]*`) the entry is a named-root reference; otherwise
         * the entry must contain no ':' at all to stay a bare skill-directory
         * path. Returns null for entries that are neither valid form.
         */
        fun parse(entry: String): SkillEmbedReference? {
            val separator = entry.indexOf(':')
            if (separator < 0) return SkillEmbedReference(alias = null, path = entry)
            val alias = entry.substring(0, separator)
            val path = entry.substring(separator + 1).trim()
            if (!aliasPattern.matches(alias) || path.isEmpty()) return null
            return SkillEmbedReference(alias = alias, path = path)
        }
    }
}

/**
 * Hand-written flat frontmatter parser for SKILL.md files.
 *
 * Format: the file starts with a `---` line, followed by flat `key: value`
 * lines, closed by another `---` line. Everything after the closing delimiter
 * is the markdown body. No nested YAML structures are supported.
 */
object SkillManifestParser {
    const val FRONTMATTER_DELIMITER = "---"
    const val KEY_NAME = "name"
    const val KEY_DESCRIPTION = "description"
    const val KEY_LOAD_POLICY = "x-ugk-load"
    const val KEY_EMBED_FILES = "x-ugk-embed-files"
    const val KEY_TRIGGERS = "triggers"

    const val MAX_DESCRIPTION_CHARS = 1024
    const val MAX_BODY_BYTES = 64L * 1024L

    private val skillNamePattern = Regex("^[a-z0-9-]+$")

    /** Returns whether [name] is a legal file-backed skill directory name. */
    fun isValidSkillName(name: String): Boolean = skillNamePattern.matches(name)

    fun parse(text: String): SkillManifestParseResult {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != FRONTMATTER_DELIMITER) {
            return SkillManifestParseResult.Invalid(
                "SKILL.md must start with a '---' frontmatter block."
            )
        }

        val frontmatterLines = mutableListOf<String>()
        var closed = false
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.trim() == FRONTMATTER_DELIMITER) {
                closed = true
                break
            }
            frontmatterLines += line
        }
        if (!closed) {
            return SkillManifestParseResult.Invalid(
                "Unterminated frontmatter: missing closing '---'."
            )
        }

        val values = mutableMapOf<String, String>()
        for (line in frontmatterLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val separator = trimmed.indexOf(':')
            if (separator <= 0) {
                return SkillManifestParseResult.Invalid(
                    "Malformed frontmatter line (expected 'key: value'): '$trimmed'."
                )
            }
            val key = trimmed.substring(0, separator).trim()
            // A repeated key would silently flip the effective value (for
            // example two x-ugk-load lines), so duplicates are rejected with
            // a diagnostic instead of last-one-wins.
            if (values.containsKey(key)) {
                return SkillManifestParseResult.Invalid(
                    "Duplicate frontmatter key '$key' in SKILL.md."
                )
            }
            values[key] = trimmed.substring(separator + 1).trim()
        }

        val name = values[KEY_NAME]
        if (name.isNullOrBlank()) {
            return SkillManifestParseResult.Invalid("Missing required frontmatter key 'name'.")
        }
        if (!isValidSkillName(name)) {
            return SkillManifestParseResult.Invalid(
                "Skill name must match [a-z0-9-]+ but was '$name'."
            )
        }

        val description = values[KEY_DESCRIPTION]
        if (description.isNullOrBlank()) {
            return SkillManifestParseResult.Invalid("Missing required frontmatter key 'description'.")
        }
        if (description.length > MAX_DESCRIPTION_CHARS) {
            return SkillManifestParseResult.Invalid(
                "description exceeds the $MAX_DESCRIPTION_CHARS character limit."
            )
        }

        val loadPolicy = when (val rawPolicy = values[KEY_LOAD_POLICY]) {
            null -> SkillLoadPolicy.TRIGGERED
            else -> SkillLoadPolicy.fromFrontmatterValue(rawPolicy) ?: return SkillManifestParseResult.Invalid(
                "x-ugk-load must be one of always, indexed, triggered but was '$rawPolicy'."
            )
        }

        val embedFiles = when (val rawEmbeds = values[KEY_EMBED_FILES]) {
            null -> emptyList()
            else -> parseEmbedFiles(rawEmbeds) ?: return SkillManifestParseResult.Invalid(
                "x-ugk-embed-files must be a comma-separated list of '.md' entries: " +
                    "a bare file inside the skill directory or 'alias:file.md' " +
                    "pointing at a host-registered embed root."
            )
        }

        val triggers = values[KEY_TRIGGERS]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: emptyList()

        val body = lines.drop(frontmatterLines.size + 2).joinToString("\n")
        if (body.isBlank()) {
            return SkillManifestParseResult.Invalid("Skill body must not be empty.")
        }
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            return SkillManifestParseResult.Invalid(
                "Skill body exceeds the ${MAX_BODY_BYTES / 1024} KB limit."
            )
        }

        return SkillManifestParseResult.Valid(
            SkillManifest(
                name = name,
                description = description,
                loadPolicy = loadPolicy,
                embedFiles = embedFiles,
                triggers = triggers
            ),
            body = body
        )
    }

    /**
     * Embed entries must be markdown files reached without traversal, so bare
     * entries stay inside the skill directory and `alias:path` entries (see
     * [SkillEmbedReference.parse]) apply the same path rules to the part
     * after the alias. Separators, traversal, absolute-looking names, hidden
     * or non-.md names, and leftover ':' characters are rejected.
     */
    private fun parseEmbedFiles(rawValue: String): List<String>? {
        val entries = rawValue
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (entries.isEmpty()) return emptyList()

        val unique = LinkedHashSet<String>()
        for (entry in entries) {
            val reference = SkillEmbedReference.parse(entry) ?: return null
            if (!isValidEmbedPath(reference.path)) return null
            unique += reference.display
        }
        return unique.toList()
    }

    private fun isValidEmbedPath(path: String): Boolean {
        val invalid = path.contains('/') ||
            path.contains('\\') ||
            path.contains(':') ||
            path == "." ||
            path == ".." ||
            path.startsWith(".") ||
            !path.endsWith(".md", ignoreCase = true)
        return !invalid
    }
}
