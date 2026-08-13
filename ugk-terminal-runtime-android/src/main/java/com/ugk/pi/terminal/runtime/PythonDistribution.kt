package com.ugk.pi.terminal.runtime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Materializes CPython's pure-data standard-library tree from an immutable APK
 * asset archive into this application's private data directory.
 *
 * Native code is intentionally excluded: libpython and its native dependencies
 * stay in nativeLibraryDir, where Android permits them to be loaded. The asset
 * manifest is part of the APK and is checked before every terminal launch, so a
 * stale or tampered standard-library tree is rebuilt on the next invocation.
 */
internal class PythonDistribution(context: Context) {
    private val appContext = context.applicationContext
    private val manifestEntries by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadManifest()
    }

    @Synchronized
    fun home(): File {
        val target = File(
            appContext.filesDir,
            "$RUNTIME_DATA_DIRECTORY/$DISTRIBUTION_DIRECTORY"
        )
        if (matchesManifest(target)) return target

        val parent = target.parentFile
            ?: throw IllegalStateException("Python distribution has no parent directory")
        if (!parent.exists()) check(parent.mkdirs()) {
            "Unable to create Python runtime directory: ${parent.absolutePath}"
        }

        val staging = File(parent, ".${DISTRIBUTION_DIRECTORY}.staging-${UUID.randomUUID()}")
        check(staging.mkdirs()) {
            "Unable to create Python distribution staging directory: ${staging.absolutePath}"
        }
        try {
            extractAssetArchive(staging)
            check(matchesManifest(staging)) {
                "Packaged Python standard library failed its SHA-256 verification"
            }

            if (target.exists() && !target.deleteRecursively()) {
                throw IllegalStateException("Unable to replace Python distribution: ${target.absolutePath}")
            }
            check(staging.renameTo(target)) {
                "Unable to publish Python distribution: ${target.absolutePath}"
            }
            return target
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun loadManifest(): List<ManifestEntry> {
        val entries = appContext.assets.open(PYTHON_MANIFEST_ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { line -> parseManifestEntry(line) }
                    .toList()
            }

        require(entries.isNotEmpty()) { "Packaged Python standard-library manifest is empty" }
        require(entries.map(ManifestEntry::relativePath).distinct().size == entries.size) {
            "Packaged Python standard-library manifest contains duplicate paths"
        }
        return entries
    }

    private fun parseManifestEntry(line: String): ManifestEntry {
        val separator = line.indexOf("  ")
        require(separator == SHA256_HEX_LENGTH && separator < line.lastIndex) {
            "Malformed Python standard-library manifest entry: $line"
        }
        val sha256 = line.substring(0, separator)
        val relativePath = line.substring(separator + 2)
        require(sha256.matches(SHA256_HEX_REGEX)) {
            "Malformed Python standard-library SHA-256: $sha256"
        }
        require(isSafeRelativePath(relativePath)) {
            "Unsafe Python standard-library manifest path: $relativePath"
        }
        return ManifestEntry(sha256, relativePath)
    }

    private fun matchesManifest(root: File): Boolean {
        if (!root.isDirectory) return false
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        return manifestEntries.all { entry ->
            val candidate = File(canonicalRoot, entry.relativePath)
            val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull()
                ?: return@all false
            if (!canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)) {
                return@all false
            }
            canonicalCandidate.isFile && sha256(canonicalCandidate) == entry.sha256
        }
    }

    private fun extractAssetArchive(targetDirectory: File) {
        val expectedEntries = manifestEntries.associateBy(ManifestEntry::relativePath)
        val copiedEntries = mutableSetOf<String>()
        val canonicalRoot = targetDirectory.canonicalFile

        appContext.assets.open(PYTHON_ARCHIVE_ASSET_PATH).buffered().use { input ->
            ZipInputStream(input).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    try {
                        check(!entry.isDirectory) {
                            "Python standard-library archive must not contain directory entries: ${entry.name}"
                        }
                        check(isSafeRelativePath(entry.name)) {
                            "Unsafe Python standard-library archive path: ${entry.name}"
                        }
                        check(expectedEntries.containsKey(entry.name)) {
                            "Unexpected Python standard-library archive path: ${entry.name}"
                        }
                        check(copiedEntries.add(entry.name)) {
                            "Duplicate Python standard-library archive path: ${entry.name}"
                        }

                        val target = File(canonicalRoot, entry.name).canonicalFile
                        check(target.path.startsWith(canonicalRoot.path + File.separator)) {
                            "Python standard-library archive escaped its target directory: ${entry.name}"
                        }
                        val parent = target.parentFile
                            ?: throw IllegalStateException("Python asset target has no parent: ${target.absolutePath}")
                        if (!parent.exists()) check(parent.mkdirs()) {
                            "Unable to create Python asset directory: ${parent.absolutePath}"
                        }
                        target.outputStream().buffered().use { output ->
                            archive.copyTo(output)
                        }
                    } finally {
                        archive.closeEntry()
                    }
                }
            }
        }

        check(copiedEntries == expectedEntries.keys) {
            "Python standard-library archive does not match its manifest"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            updateDigest(digest, input)
        }
        return digest.digest().toHex()
    }

    private fun updateDigest(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            digest.update(buffer, 0, count)
        }
    }

    private fun ByteArray.toHex(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        return path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            path.split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
    }

    private data class ManifestEntry(
        val sha256: String,
        val relativePath: String
    )

    companion object {
        const val PYTHON_VERSION = "3.14"
        const val PYTHON_DISTRIBUTION_VERSION = "3.14.6"
        const val PYTHON_LIBRARY_FILE_NAME = "libpython3.14.so"

        private const val RUNTIME_DATA_DIRECTORY = "ugk-terminal-runtime/python"
        private const val DISTRIBUTION_DIRECTORY = PYTHON_DISTRIBUTION_VERSION
        private const val PYTHON_ASSET_DIRECTORY =
            "ugk-terminal-runtime/python/$PYTHON_DISTRIBUTION_VERSION"
        private const val PYTHON_MANIFEST_ASSET_PATH =
            "$PYTHON_ASSET_DIRECTORY/manifest.sha256"
        private const val PYTHON_ARCHIVE_ASSET_PATH = "$PYTHON_ASSET_DIRECTORY/stdlib.zip"
        private const val SHA256_HEX_LENGTH = 64
        private const val HASH_BUFFER_BYTES = 16 * 1024
        private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
    }
}
