package com.ugk.pi.android.testapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class DemoImportedFile(
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val mimeType: String?
)

internal sealed class DemoFileImportResult {
    data class Success(val file: DemoImportedFile) : DemoFileImportResult()
    data class Failure(val message: String) : DemoFileImportResult()
}

internal object DemoFileImportPolicy {
    const val MAX_FILE_BYTES = 10L * 1024L * 1024L

    private val supportedExtensions = setOf(
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

    fun isSupported(displayName: String, mimeType: String?): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension in supportedExtensions) return true
        if (extension.isBlank() && mimeType?.lowercase()?.startsWith("text/") == true) return true
        return mimeType?.lowercase() in setOf(
            "application/json",
            "application/xml",
            "application/yaml",
            "text/yaml"
        )
    }

    fun safeFileName(displayName: String): String {
        val baseName = displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', ' ')
            .take(120)
        return baseName.ifBlank { "imported.txt" }
    }
}

internal class DemoFileImportStore(context: Context) {
    private val appContext = context.applicationContext
    val workspaceRoot: File = File(appContext.filesDir, "agent-workspace")
    private val importRoot = File(workspaceRoot, "imports")

    fun importFile(uri: Uri): DemoFileImportResult {
        val resolver = appContext.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: "imported.txt"
        val mimeType = resolver.getType(uri)
        if (!DemoFileImportPolicy.isSupported(displayName, mimeType)) {
            return DemoFileImportResult.Failure(
                "当前只支持文本文件：txt、md、json、jsonl、csv、log、xml、html、yaml、yml"
            )
        }

        val reportedSize = querySize(resolver, uri)
        if (reportedSize != null && reportedSize > DemoFileImportPolicy.MAX_FILE_BYTES) {
            return DemoFileImportResult.Failure("文件超过 10 MB 限制")
        }

        val storedName = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-" +
            DemoFileImportPolicy.safeFileName(displayName)
        val output = File(importRoot, storedName)
        val temporary = File(importRoot, ".$storedName.part")

        return try {
            importRoot.mkdirs()
            val input = resolver.openInputStream(uri)
                ?: return DemoFileImportResult.Failure("无法打开所选文件")
            var totalBytes = 0L
            input.use { source ->
                temporary.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        totalBytes += count
                        if (totalBytes > DemoFileImportPolicy.MAX_FILE_BYTES) {
                            temporary.delete()
                            return DemoFileImportResult.Failure("文件超过 10 MB 限制")
                        }
                        target.write(buffer, 0, count)
                    }
                }
            }
            if (!temporary.renameTo(output)) {
                temporary.delete()
                return DemoFileImportResult.Failure("无法保存导入文件")
            }
            DemoFileImportResult.Success(
                DemoImportedFile(
                    displayName = displayName,
                    relativePath = "imports/$storedName",
                    sizeBytes = totalBytes,
                    mimeType = mimeType
                )
            )
        } catch (error: SecurityException) {
            temporary.delete()
            DemoFileImportResult.Failure("没有读取该文件的权限")
        } catch (error: IOException) {
            temporary.delete()
            DemoFileImportResult.Failure(error.message ?: "读取文件失败")
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun querySize(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Long? = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()
}
