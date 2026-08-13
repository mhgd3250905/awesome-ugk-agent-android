package com.example.runtime.demo.b

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashRuntime
import com.ugk.pi.terminal.runtime.NativeRuntimeProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeProbeInstrumentedTest {
    @Test
    fun executesPayloadFromThisApplicationsNativeLibraryDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("com.example.runtime.demo.b", context.packageName)

        val executable = NativeRuntimeProbe.executableFile(context)
        assertTrue("Probe payload is missing: $executable", executable.isFile)

        val result = NativeRuntimeProbe.execute(context, listOf("demo-b"))

        assertFalse("Probe timed out: $result", result.timedOut)
        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue(result.stdout.contains("ugk_runtime_probe=1"))
        assertTrue(result.stdout.contains("argv[0]=${executable.absolutePath}"))
        assertTrue(result.stdout.contains("argv[1]=demo-b"))
    }

    @Test
    fun executesPackagedBashInThisApplicationsPrivateWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = File(context.filesDir, "bash-test-b")
        val runtime = BashRuntime(context)

        val executable = BashRuntime.executableFile(context)
        assertTrue("Bash payload is missing: $executable", executable.isFile)

        val result = runtime.execute(
            BashCommandRequest(
                script = "printf 'id=%s\\n' \"${context.packageName}\"; printf 'product=%s\\n' \"\$((6 * 7))\"; printf 'hello' > result.txt",
                workingDirectory = workspace,
                timeoutMillis = 5_000
            )
        )

        assertFalse("Bash timed out: $result", result.timedOut)
        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("id=com.example.runtime.demo.b"))
        assertTrue(result.stdout.contains("product=42"))
        assertEquals("hello", File(workspace, "result.txt").readText())
    }

    @Test
    fun invokesPackagedSqliteThroughTheGeneratedBashProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)
        val sqlite = File(context.applicationInfo.nativeLibraryDir, BashRuntime.sqliteExecutableFileName)

        assertTrue("SQLite payload is missing: $sqlite", sqlite.isFile)

        val result = runtime.execute(
            BashCommandRequest(
                script = "type -t sqlite3; sqlite3 :memory: \"select 6 * 7, sqlite_compileoption_used('OMIT_LOAD_EXTENSION');\"",
                timeoutMillis = 5_000
            )
        )

        assertFalse("SQLite timed out: $result", result.timedOut)
        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.lines().any { it == "function" })
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("42|1"))
    }

    @Test
    fun invokesPackagedNetworkCommandsWithTheRuntimeManagedCaBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)
        val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir)

        assertTrue("curl payload is missing", File(nativeLibraryDirectory, BashRuntime.curlExecutableFileName).isFile)
        assertTrue("OpenSSL payload is missing", File(nativeLibraryDirectory, BashRuntime.opensslExecutableFileName).isFile)

        val tamperResult = runtime.execute(
            BashCommandRequest(
                script = "printf tampered > \"${'$'}BASH_ENV\"; printf tampered > \"${'$'}CURL_CA_BUNDLE\"",
                timeoutMillis = 5_000
            )
        )
        assertFalse("tamper setup timed out: $tamperResult", tamperResult.timedOut)
        assertEquals("tamper setup stderr: ${tamperResult.stderr}", 0, tamperResult.exitCode)

        val result = runtime.execute(
            BashCommandRequest(
                script = """
                    type -t curl
                    type -t openssl
                    test -r "${'$'}CURL_CA_BUNDLE"
                    printf abc | openssl dgst -sha256
                    curl --fail --silent --show-error --max-time 15 --output /dev/null --write-out 'https=%{http_code}\n' https://example.com
                """.trimIndent(),
                timeoutMillis = 25_000
            )
        )

        assertFalse("network commands timed out: $result", result.timedOut)
        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.lines().count { it == "function" } >= 2)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("https=200"))
    }

    @Test
    fun invokesEmbeddedPythonFromThisApplicationsPrivateRuntimeHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)
        val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir)

        assertTrue(
            "Python launcher is missing",
            File(nativeLibraryDirectory, BashRuntime.pythonExecutableFileName).isFile
        )
        assertTrue(
            "Python extension bridge payload is missing",
            nativeLibraryDirectory.listFiles()?.any { file ->
                file.name.startsWith("libugk_pyext__sqlite3.cpython-314-") &&
                    file.name.endsWith(".so")
            } == true
        )

        val result = runtime.execute(
            BashCommandRequest(
                script = """
                    python -c "import hashlib, sqlite3, ssl, subprocess, sys; print('python=' + sys.version.split()[0]); print('prefix=' + sys.prefix); print('sha256=' + hashlib.sha256(b'abc').hexdigest()); print('sqlite=' + sqlite3.sqlite_version); print('ssl=' + ssl.OPENSSL_VERSION.split()[0]); print(subprocess.run(['/system/bin/echo', 'subprocess=b'], capture_output=True, text=True, check=True).stdout.strip())"
                """.trimIndent(),
                timeoutMillis = 15_000
            )
        )

        assertFalse("Python timed out: $result", result.timedOut)
        assertEquals("Python stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("python=3.14.6"))
        assertTrue(
            "stdout: ${result.stdout}",
            result.stdout.contains("prefix=${context.filesDir.absolutePath}/ugk-terminal-runtime/python/3.14.6")
        )
        assertTrue(
            "stdout: ${result.stdout}",
            result.stdout.contains("sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        )
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("sqlite="))
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("ssl=OpenSSL"))
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("subprocess=b"))
    }

}
