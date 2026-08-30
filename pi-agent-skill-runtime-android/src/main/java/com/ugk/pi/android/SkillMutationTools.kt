package com.ugk.pi.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Creates or updates one single-file skill in the repository. The plugin
 * wraps this tool with [UserConfirmationRequiredTool] by default because a
 * successful save changes the Agent's behavior on the next run.
 */
class SkillSaveTool(
    private val repository: SkillRepository,
    override val name: String = "skill_save"
) : AgentTool {
    override val description: String =
        "Creates or updates a file-backed skill from structured name, description, " +
            "load policy, optional triggers/embeds, and markdown body. New skills " +
            "must use overwrite=false; update an existing skill only after reading it."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Skill name matching [a-z0-9-]+.")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Short skill description, at most 1024 characters.")
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "Non-empty markdown instructions, at most 64 KB in UTF-8.")
            }
            putJsonObject("loadPolicy") {
                put("type", "string")
                put("description", "always, indexed, or triggered; defaults to triggered.")
                putJsonArray("enum") {
                    add(JsonPrimitive("always"))
                    add(JsonPrimitive("indexed"))
                    add(JsonPrimitive("triggered"))
                }
            }
            putJsonObject("triggers") {
                put("type", "array")
                put("description", "Optional trigger keywords; a comma-separated string is also accepted.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("embedFiles") {
                put("type", "array")
                put("description", "Optional .md entries, bare or alias:path.md.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("overwrite") {
                put("type", "boolean")
                put("description", "Replace an existing skill after reading it; defaults to false.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("name"))
            add(JsonPrimitive("description"))
            add(JsonPrimitive("body"))
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val skillName = call.input.requiredString("name")
            ?: return errorResult(call, this.name, "MISSING_NAME", "name is required.")
        val description = call.input.requiredString("description")
            ?: return errorResult(call, this.name, "MISSING_DESCRIPTION", "description is required.")
        val body = call.input.requiredString("body")
            ?: return errorResult(call, this.name, "MISSING_BODY", "body is required.")

        val loadPolicyElement = call.input["loadPolicy"]
        val loadPolicyValue = when {
            loadPolicyElement == null -> "triggered"
            loadPolicyElement is JsonPrimitive && loadPolicyElement.isString ->
                loadPolicyElement.contentOrNull.orEmpty()
            else -> return errorResult(
                call,
                this.name,
                "INVALID_LOAD_POLICY",
                "loadPolicy must be a string: always, indexed, or triggered."
            )
        }
        val loadPolicy = SkillLoadPolicy.fromFrontmatterValue(loadPolicyValue)
            ?: return errorResult(
                call,
                this.name,
                "INVALID_LOAD_POLICY",
                "loadPolicy must be one of always, indexed, or triggered."
            )

        val triggers = call.input.stringList("triggers")
            ?: return errorResult(call, this.name, "INVALID_TRIGGERS", "triggers must be strings.")
        val embedFiles = call.input.stringList("embedFiles")
            ?: return errorResult(call, this.name, "INVALID_EMBED_FILES", "embedFiles must be strings.")
        val overwriteElement = call.input["overwrite"]
        val overwrite = when {
            overwriteElement == null -> false
            overwriteElement is JsonPrimitive && !overwriteElement.isString ->
                overwriteElement.contentOrNull?.toBooleanStrictOrNull()
            else -> null
        } ?: return errorResult(call, this.name, "INVALID_OVERWRITE", "overwrite must be a boolean.")

        return when (
            val outcome = repository.saveSkill(
                SkillSaveRequest(
                    name = skillName,
                    description = description,
                    body = body,
                    loadPolicy = loadPolicy,
                    triggers = triggers,
                    embedFiles = embedFiles,
                    overwrite = overwrite
                )
            )
        ) {
            is SkillSaveOutcome.Failed -> errorResult(call, this.name, outcome.code, outcome.message)
            is SkillSaveOutcome.Saved -> {
                val manifest = outcome.skill.manifest!!
                ToolResult(
                    toolCallId = call.id,
                    name = this.name,
                    content = if (outcome.overwritten) {
                        "Updated skill '${manifest.name}'. It is valid and available on the next Agent run."
                    } else {
                        "Created skill '${manifest.name}'. It is valid and available on the next Agent run."
                    },
                    metadata = skillMetadata(
                        manifest = manifest,
                        bodyBytes = outcome.skill.body.toByteArray(Charsets.UTF_8).size,
                        overwritten = outcome.overwritten
                    )
                )
            }
        }
    }
}

/** Deletes one custom skill directory; built-in skills are protected. */
class SkillDeleteTool(
    private val repository: SkillRepository,
    override val name: String = "skill_delete"
) : AgentTool {
    override val description: String =
        "Deletes one custom file-backed skill by exact name. Built-in skills cannot be deleted."
    override val inputSchema: JsonObject = skillNameSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val skillName = call.input.requiredString("name")
            ?: return errorResult(call, name, "MISSING_NAME", "name is required.")
        return when (val outcome = repository.deleteSkill(skillName)) {
            is SkillDeleteOutcome.Failed -> errorResult(call, name, outcome.code, outcome.message)
            is SkillDeleteOutcome.Deleted -> ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Deleted skill '${outcome.name}'.",
                metadata = buildJsonObject {
                    put("name", outcome.name)
                    put("status", "deleted")
                    put("deleted", true)
                    put("path", "agent-skills/${outcome.name}/SKILL.md")
                }
            )
        }
    }
}

private fun skillNameSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("name") {
            put("type", "string")
            put("description", "Exact skill name from skill_list.")
        }
    }
    putJsonArray("required") { add(JsonPrimitive("name")) }
}

private fun skillMetadata(
    manifest: SkillManifest,
    bodyBytes: Int,
    overwritten: Boolean
): JsonObject = buildJsonObject {
    put("name", manifest.name)
    put("description", manifest.description)
    put("loadPolicy", manifest.loadPolicy.name.lowercase())
    putJsonArray("triggers") {
        manifest.triggers.forEach { add(JsonPrimitive(it)) }
    }
    putJsonArray("embedFiles") {
        manifest.embedFiles.forEach { add(JsonPrimitive(it)) }
    }
    put("status", "valid")
    put("overwritten", overwritten)
    put("bodyBytes", bodyBytes)
    put("path", "agent-skills/${manifest.name}/SKILL.md")
}

private fun JsonObject.requiredString(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.contentOrNull?.takeIf { it.isNotBlank() }
}

/** Accept structured string arrays and the flat comma-separated form. */
private fun JsonObject.stringList(key: String): List<String>? {
    val element = this[key] ?: return emptyList()
    return when (element) {
        is JsonArray -> {
            val values = mutableListOf<String>()
            element.forEach { item: JsonElement ->
                val primitive = item as? JsonPrimitive ?: return null
                if (!primitive.isString) return null
                primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            }
            values
        }
        is JsonPrimitive -> {
            if (!element.isString) return null
            element.contentOrNull
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: emptyList()
        }
        else -> null
    }
}
