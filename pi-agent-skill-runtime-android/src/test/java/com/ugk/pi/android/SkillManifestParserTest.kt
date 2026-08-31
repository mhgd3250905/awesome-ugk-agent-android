package com.ugk.pi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManifestParserTest {

    @Test
    fun parsesRequiredFieldsWithDefaults() {
        val result = SkillManifestParser.parse(
            """
                ---
                name: my-skill
                description: Does something useful.
                ---
                # Body
                Instructions here.
            """.trimIndent()
        )

        val valid = result as SkillManifestParseResult.Valid
        assertEquals("my-skill", valid.manifest.name)
        assertEquals("Does something useful.", valid.manifest.description)
        assertEquals(SkillLoadPolicy.TRIGGERED, valid.manifest.loadPolicy)
        assertTrue(valid.manifest.embedFiles.isEmpty())
        assertTrue(valid.manifest.triggers.isEmpty())
        assertTrue(valid.body.contains("# Body"))
        assertTrue(valid.body.contains("Instructions here."))
    }

    @Test
    fun parsesExtensionKeysAndIgnoresUnknownKeys() {
        val result = SkillManifestParser.parse(
            """
                ---
                name: memory
                description: Memory skill.
                x-ugk-load: always
                x-ugk-embed-files: preferences.md, rules.md
                triggers: 记一下, remember, 帮我记住
                x-future-key: whatever
                unknown: ignored
                ---
                Body content.
            """.trimIndent()
        )

        val valid = result as SkillManifestParseResult.Valid
        assertEquals(SkillLoadPolicy.ALWAYS, valid.manifest.loadPolicy)
        assertEquals(listOf("preferences.md", "rules.md"), valid.manifest.embedFiles)
        assertEquals(listOf("记一下", "remember", "帮我记住"), valid.manifest.triggers)
    }

    @Test
    fun rejectsMissingName() {
        val result = SkillManifestParser.parse(
            """
                ---
                description: No name here.
                ---
                Body.
            """.trimIndent()
        )

        val invalid = result as SkillManifestParseResult.Invalid
        assertTrue(invalid.reason.contains("name"))
    }

    @Test
    fun rejectsNameOutsideAllowedPattern() {
        val upperCase = SkillManifestParser.parse(
            "---\nname: MySkill\ndescription: d.\n---\nBody."
        )
        val underscore = SkillManifestParser.parse(
            "---\nname: my_skill\ndescription: d.\n---\nBody."
        )

        assertTrue((upperCase as SkillManifestParseResult.Invalid).reason.contains("[a-z0-9-]+"))
        assertTrue(underscore is SkillManifestParseResult.Invalid)
    }

    @Test
    fun rejectsMissingOrBlankDescription() {
        val missing = SkillManifestParser.parse("---\nname: a\n---\nBody.")
        val blank = SkillManifestParser.parse("---\nname: a\ndescription:   \n---\nBody.")

        assertTrue((missing as SkillManifestParseResult.Invalid).reason.contains("description"))
        assertTrue(blank is SkillManifestParseResult.Invalid)
    }

    @Test
    fun rejectsTooLongDescription() {
        val result = SkillManifestParser.parse(
            "---\nname: a\ndescription: ${"x".repeat(SkillManifestParser.MAX_DESCRIPTION_CHARS + 1)}\n---\nBody."
        )

        assertTrue((result as SkillManifestParseResult.Invalid).reason.contains("1024"))
    }

    @Test
    fun rejectsMissingOrUnterminatedFrontmatter() {
        val noFrontmatter = SkillManifestParser.parse("Just a body.")
        val unterminated = SkillManifestParser.parse("---\nname: a\ndescription: d.\nBody only.")

        assertTrue((noFrontmatter as SkillManifestParseResult.Invalid).reason.contains("---"))
        assertTrue((unterminated as SkillManifestParseResult.Invalid).reason.contains("Unterminated"))
    }

    @Test
    fun rejectsEmptyBody() {
        val result = SkillManifestParser.parse("---\nname: a\ndescription: d.\n---\n   \n")

        assertTrue((result as SkillManifestParseResult.Invalid).reason.contains("body"))
    }

    @Test
    fun rejectsTooLargeBody() {
        val largeBody = "a".repeat((SkillManifestParser.MAX_BODY_BYTES + 1).toInt())
        val result = SkillManifestParser.parse("---\nname: a\ndescription: d.\n---\n$largeBody")

        assertTrue((result as SkillManifestParseResult.Invalid).reason.contains("64 KB"))
    }

    @Test
    fun rejectsInvalidLoadPolicyValue() {
        val result = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-load: sometimes\n---\nBody."
        )

        assertTrue((result as SkillManifestParseResult.Invalid).reason.contains("x-ugk-load"))
    }

    @Test
    fun rejectsEmbedEntriesOutsideSkillDirectory() {
        val nested = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: sub/file.md\n---\nBody."
        )
        val nonMarkdown = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: notes.txt\n---\nBody."
        )
        val bareTraversal = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: ../secret.md\n---\nBody."
        )

        assertTrue(nested is SkillManifestParseResult.Invalid)
        assertTrue(nonMarkdown is SkillManifestParseResult.Invalid)
        assertTrue(bareTraversal is SkillManifestParseResult.Invalid)
    }

    @Test
    fun parsesNamedRootEmbedEntriesAlongsideBareEntries() {
        val result = SkillManifestParser.parse(
            "---\nname: agent-memory\n" +
                "description: Memory.\n" +
                "x-ugk-embed-files: memory:preferences.md, rules.md, memory: user-profile.md\n" +
                "---\nBody."
        )

        val valid = result as SkillManifestParseResult.Valid
        assertEquals(
            listOf("memory:preferences.md", "rules.md", "memory:user-profile.md"),
            valid.manifest.embedFiles
        )
    }

    @Test
    fun rejectsNamedRootEmbedTraversalAndAbsolutePaths() {
        val traversal = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: memory:../secret.md\n---\nBody."
        )
        val absolute = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: memory:/etc/hosts.md\n---\nBody."
        )
        val windowsDrive = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: memory:C:\\x.md\n---\nBody."
        )
        val backslash = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: memory:..\\x.md\n---\nBody."
        )

        assertTrue(traversal is SkillManifestParseResult.Invalid)
        assertTrue(absolute is SkillManifestParseResult.Invalid)
        assertTrue(windowsDrive is SkillManifestParseResult.Invalid)
        assertTrue(backslash is SkillManifestParseResult.Invalid)
    }

    @Test
    fun rejectsInvalidEmbedAliases() {
        val upperCase = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: Memory:x.md\n---\nBody."
        )
        val underscore = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: mem_x:a.md\n---\nBody."
        )
        val emptyPath = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: memory:\n---\nBody."
        )
        val numericFirst = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-embed-files: 1memory:x.md\n---\nBody."
        )

        assertTrue(upperCase is SkillManifestParseResult.Invalid)
        assertTrue(underscore is SkillManifestParseResult.Invalid)
        assertTrue(emptyPath is SkillManifestParseResult.Invalid)
        assertTrue(numericFirst is SkillManifestParseResult.Invalid)
    }

    @Test
    fun rejectsMalformedFrontmatterLine() {
        val result = SkillManifestParser.parse("---\nname a\ndescription: d.\n---\nBody.")

        assertTrue((result as SkillManifestParseResult.Invalid).reason.contains("Malformed"))
    }

    @Test
    fun rejectsDuplicateFrontmatterKeys() {
        val duplicateName = SkillManifestParser.parse(
            "---\nname: a\nname: b\ndescription: d.\n---\nBody."
        )
        val duplicateLoadPolicy = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-load: always\nx-ugk-load: indexed\n---\nBody."
        )
        val duplicateUnknownKey = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-future-key: 1\nx-future-key: 2\n---\nBody."
        )

        assertTrue((duplicateName as SkillManifestParseResult.Invalid).reason.contains("name"))
        assertTrue((duplicateLoadPolicy as SkillManifestParseResult.Invalid).reason.contains("x-ugk-load"))
        assertTrue((duplicateUnknownKey as SkillManifestParseResult.Invalid).reason.contains("x-future-key"))
    }

    @Test
    fun bodyLinesWithColonsAreNotFrontmatterDuplicates() {
        val result = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\n---\nkey: value\nname: shadow"
        )

        val valid = result as SkillManifestParseResult.Valid
        assertEquals("a", valid.manifest.name)
        assertTrue(valid.body.contains("name: shadow"))
    }

    @Test
    fun acceptsIndexedPolicyCaseInsensitively() {
        val result = SkillManifestParser.parse(
            "---\nname: a\ndescription: d.\nx-ugk-load: INDEXED\n---\nBody."
        )

        assertEquals(
            SkillLoadPolicy.INDEXED,
            (result as SkillManifestParseResult.Valid).manifest.loadPolicy
        )
    }
}
