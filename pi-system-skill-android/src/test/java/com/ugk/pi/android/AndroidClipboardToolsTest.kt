package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidClipboardToolsTest {
    @Test
    fun readReturnsMetadataAndForwardsRawTextOnlyAsTransientModelContent() = runBlocking {
        val rawText = "secret-value"
        val backend = FakeClipboardBackend().apply {
            readResult = ClipboardReadResult(
                code = ClipboardErrorCodes.OK,
                text = rawText,
                originalLength = rawText.length,
                itemCount = 1,
                mimeTypes = listOf("text/plain")
            )
        }

        val result = ClipboardReadTextTool(backend).execute(
            ToolCall(
                id = "read-1",
                name = "clipboard_read_text",
                input = buildJsonObject { put("maxChars", 100) }
            ),
            ToolExecutionContext(sessionId = "clipboard-test")
        )

        assertFalse(result.isError)
        assertFalse(result.content.contains(rawText))
        assertFalse(result.metadata.toString().contains(rawText))
        assertTrue(result.transientModelContent?.contains(rawText) == true)
        assertTrue(result.transientModelContent?.contains("clipboardText") == true)
    }

    @Test
    fun failedReadNeverForwardsBackendText() = runBlocking {
        val backend = FakeClipboardBackend().apply {
            readResult = ClipboardReadResult(
                code = ClipboardErrorCodes.READ_UNAVAILABLE,
                text = "must-not-leak",
                message = "focus required"
            )
        }

        val result = ClipboardReadTextTool(backend).execute(
            ToolCall("read-failed", "clipboard_read_text", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "clipboard-test")
        )

        assertTrue(result.isError)
        assertNull(result.transientModelContent)
        assertFalse(result.content.contains("must-not-leak"))
    }

    @Test
    fun writePassesExactTextAndDefaultsToSensitive() = runBlocking {
        val rawText = "token-123"
        val backend = FakeClipboardBackend()
        val result = ClipboardWriteTextTool(backend).execute(
            ToolCall(
                id = "write-1",
                name = "clipboard_write_text",
                input = buildJsonObject {
                    put("text", rawText)
                    put("label", "Test")
                }
            ),
            ToolExecutionContext(sessionId = "clipboard-test")
        )

        assertFalse(result.isError)
        assertEquals(rawText, backend.writtenText)
        assertEquals("Test", backend.writtenLabel)
        assertTrue(backend.writtenSensitive)
        assertFalse(result.content.contains(rawText))
    }

    @Test
    fun missingWriteTextFailsWithoutCallingBackend() = runBlocking {
        val backend = FakeClipboardBackend()
        val result = ClipboardWriteTextTool(backend).execute(
            ToolCall("write-invalid", "clipboard_write_text", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "clipboard-test")
        )

        assertTrue(result.isError)
        assertEquals(ClipboardErrorCodes.INVALID_INPUT, result.metadata["code"]?.toString()?.trim('"'))
        assertNull(backend.writtenText)
    }

    @Test
    fun clearDelegatesToBackend() = runBlocking {
        val backend = FakeClipboardBackend()
        val result = ClipboardClearTool(backend).execute(
            ToolCall("clear-1", "clipboard_clear", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "clipboard-test")
        )

        assertFalse(result.isError)
        assertTrue(backend.clearCalled)
        assertEquals(
            ClipboardErrorCodes.OK,
            Json.parseToJsonElement(result.content).jsonObject["code"]?.toString()?.trim('"')
        )
    }

    private class FakeClipboardBackend : ClipboardBackend {
        var readResult = ClipboardReadResult(ClipboardErrorCodes.READ_UNAVAILABLE)
        var writtenText: String? = null
        var writtenLabel: String? = null
        var writtenSensitive = false
        var clearCalled = false

        override fun readText(maxChars: Int): ClipboardReadResult = readResult

        override fun writeText(
            text: String,
            label: String,
            sensitive: Boolean
        ): ClipboardOperationResult {
            writtenText = text
            writtenLabel = label
            writtenSensitive = sensitive
            return ClipboardOperationResult(ClipboardErrorCodes.OK)
        }

        override fun clear(): ClipboardOperationResult {
            clearCalled = true
            return ClipboardOperationResult(ClipboardErrorCodes.OK)
        }
    }
}
