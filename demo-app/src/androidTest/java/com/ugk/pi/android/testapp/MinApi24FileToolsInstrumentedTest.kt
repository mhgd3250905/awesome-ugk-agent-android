package com.ugk.pi.android.testapp

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AppFileListTool
import com.ugk.pi.android.AppFileWriteTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinApi24FileToolsInstrumentedTest {
    @Test
    fun appFileWriteWorksOnMinimumSupportedApi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-min-api-file-tools")
        root.deleteRecursively()

        try {
            val result = AppFileWriteTool(root).execute(
                ToolCall(
                    id = "min-api-file-write",
                    name = "app_file_write",
                    input = buildJsonObject {
                        put("path", "notes/today.md")
                        put("content", "hello")
                    }
                ),
                ToolExecutionContext(sessionId = "min-api-file-tools")
            )

            assertFalse("app_file_write failed: $result", result.isError)
            assertEquals("hello", File(root, "notes/today.md").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun appFileListSkipsSymlinkOutsideWorkspace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-min-api-file-list")
        val sibling = File(context.filesDir, "review-min-api-file-list-sibling")
        val link = File(root, "sibling-link")
        root.deleteRecursively()
        sibling.deleteRecursively()

        try {
            root.mkdirs()
            File(sibling, "escape.md").apply {
                parentFile?.mkdirs()
                writeText("outside")
            }
            try {
                Os.symlink(sibling.absolutePath, link.absolutePath)
            } catch (error: Exception) {
                assumeNoException("Symbolic links are not available in this test environment.", error)
                return@runBlocking
            }

            val result = AppFileListTool(root).execute(
                ToolCall(
                    id = "min-api-file-list",
                    name = "app_file_list",
                    input = buildJsonObject { put("path", "") }
                ),
                ToolExecutionContext(sessionId = "min-api-file-list")
            )

            assertFalse("app_file_list failed: $result", result.isError)
            assertTrue(result.content.isEmpty())
            assertFalse(result.metadata.toString().contains("sibling"))
        } finally {
            link.delete()
            sibling.deleteRecursively()
            root.deleteRecursively()
        }
    }
}
