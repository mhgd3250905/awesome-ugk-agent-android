package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class DemoFileImportPolicyTest {
    @Test
    fun supportsConfiguredTextFormatsOnly() {
        assertTrue(DemoFileImportPolicy.isSupported("notes.md", "text/markdown"))
        assertTrue(DemoFileImportPolicy.isSupported("data.json", "application/octet-stream"))
        assertFalse(DemoFileImportPolicy.isSupported("photo.png", "image/png"))
        assertFalse(DemoFileImportPolicy.isSupported("report.pdf", "application/pdf"))
    }

    @Test
    fun sanitizesDisplayNameBeforeItBecomesAWorkspaceFileName() {
        assertEquals("hello_world.md", DemoFileImportPolicy.safeFileName("hello world.md"))
        assertEquals("imported.txt", DemoFileImportPolicy.safeFileName("../"))
    }

    @Test
    fun importedFilePluginIsReadOnly() {
        val plugin = DemoImportedFilePlugin(createTempDirectory("imported-files").toFile())

        assertEquals(
            listOf("app_file_stat", "app_file_read"),
            plugin.tools().map { it.name }
        )
        assertTrue(plugin.agentInstructions().single().contains("untrusted data"))
    }
}
