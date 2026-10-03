package com.ugk.pi.terminal.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged CPython payload is the part of the version facts that a constant
 * cannot carry.
 *
 * `TerminalPythonProfile`'s KDoc promised that bumping the constants makes
 * "every derived string follow". It does not: `PythonDistribution` builds the
 * asset path it opens from `PYTHON_DISTRIBUTION_VERSION`, while the shipped
 * standard library lives in a directory literally named after that version, and
 * `libpython<version>.so` plus the `cpython-<digits>` extension names are file
 * names in `jniLibs`. Bumping the constant past the payload was measured green
 * across the whole JVM gate (867/0/0, mut-m6-python-version-bump-fullgate.log)
 * and only fails at runtime on a device, when `assets.open()` cannot find
 * `python/3.14.7/stdlib.zip`. `verify-runtime.ps1 -CheckPackages` does not close
 * this either: it compares `runtime-lock.json` against the files on disk and
 * never reads these constants.
 *
 * Reads the source tree because unit tests do not see src/main/assets or
 * src/main/jniLibs on their classpath; Gradle runs JVM tests with the module
 * directory as the working directory.
 */
class TerminalPythonProfilePayloadTest {
    @Test
    fun packagedPayloadCarriesTheSingleSourcedVersionFacts() {
        val moduleDirectory = File(".").canonicalFile
        val problems = mutableListOf<String>()

        val assetRoot = File(moduleDirectory, "src/main/assets/ugk-terminal-runtime/python")
        if (!assetRoot.isDirectory) {
            problems += "packaged Python asset directory is missing: ${assetRoot.path}"
        } else {
            val shippedVersions = assetRoot.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
                .sorted()
            assertEquals(
                "the asset tree must carry exactly the distribution version the constants name " +
                    "(a bump that leaves an old directory behind, or moves only one of the two, " +
                    "breaks Python materialization on every device)",
                listOf(TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION),
                shippedVersions
            )
            val distribution = File(assetRoot, TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION)
            listOf("manifest.sha256", "stdlib.zip").forEach { name ->
                if (!File(distribution, name).isFile) {
                    problems += "packaged Python payload file is missing: ${File(distribution, name).path}"
                }
            }
            problems += manifestTreeProblems(File(distribution, "manifest.sha256"))
        }

        assertEquals(
            "the shared library name must follow PYTHON_VERSION",
            "libpython${TerminalPythonProfile.PYTHON_VERSION}.so",
            TerminalPythonProfile.PYTHON_LIBRARY_FILE_NAME
        )

        val jniLibs = File(moduleDirectory, "src/main/jniLibs")
        val abis = jniLibs.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
        assertTrue("no ABIs found under ${jniLibs.path}", abis.isNotEmpty())
        val expectedTag = "cpython-${TerminalPythonProfile.PYTHON_VERSION.replace(".", "")}"
        abis.forEach { abi ->
            val directory = File(jniLibs, abi)
            if (!File(directory, TerminalPythonProfile.PYTHON_LIBRARY_FILE_NAME).isFile) {
                problems += "$abi is missing ${TerminalPythonProfile.PYTHON_LIBRARY_FILE_NAME}"
            }
            val extensionNames = directory.listFiles().orEmpty().map { it.name }
            val tagged = extensionNames.filter { expectedTag in it }
            if (tagged.isEmpty()) {
                problems += "$abi carries no extension module named for $expectedTag " +
                    "(e.g. libugk_pyext_math.$expectedTag-$abi-linux-android.so)"
            }
        }

        assertEquals(emptyList<String>(), problems)
    }

    /**
     * Every manifest entry the Runtime extracts is addressed as
     * `lib/python<version>/...`; the version in that prefix is the same fact the
     * constants carry, so a `PYTHON_VERSION` bump that leaves the stdlib archive
     * and manifest untouched must be visible here.
     */
    private fun manifestTreeProblems(manifest: File): List<String> {
        if (!manifest.isFile) return emptyList()
        val prefix = "lib/python${TerminalPythonProfile.PYTHON_VERSION}/"
        val offenders = manifest.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line -> line.substringAfter("  ", missingDelimiterValue = line) }
            .filterNot { it.startsWith(prefix) }
        return if (offenders.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "${manifest.name}: ${offenders.size} entr${if (offenders.size == 1) "y" else "ies"} " +
                    "outside $prefix, first three: ${offenders.take(3)}"
            )
        }
    }
}
