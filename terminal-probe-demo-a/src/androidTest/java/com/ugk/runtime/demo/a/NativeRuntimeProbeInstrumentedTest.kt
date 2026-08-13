package com.ugk.runtime.demo.a

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.terminal.skill.TerminalAgentPlugin
import com.ugk.pi.terminal.skill.TerminalToolPolicy
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashRuntime
import com.ugk.pi.terminal.runtime.NativeRuntimeProbe
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeProbeInstrumentedTest {
    @Test
    fun terminalAgentPluginRequiresImmediateUserConfirmationByDefault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plugin = TerminalAgentPlugin(context)
        val tool = plugin.tools().single()
        val marker = File(context.filesDir, "confirmation-must-not-run.txt")
        marker.delete()

        val result = tool.execute(
            ToolCall(
                id = "confirmation-required",
                name = tool.name,
                input = buildJsonObject {
                    put("script", "printf executed > '${marker.absolutePath}'")
                }
            ),
            ToolExecutionContext(sessionId = "gate3")
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("confirmation", ignoreCase = true))
        assertFalse("unconfirmed terminal script executed", marker.exists())
    }

    @Test
    fun terminalAgentPluginExplicitCancelTerminatesActiveProcessGroup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plugin = TerminalAgentPlugin(
            context,
            TerminalToolPolicy(requireUserConfirmation = false, maxConcurrentExecutions = 1)
        )
        val tool = plugin.tools().single()
        val workspace = File(context.filesDir, "ugk-terminal-workspace/gate3-explicit-cancel")
        val started = File(workspace, "started.txt")
        val leaked = File(workspace, "leaked.txt")
        started.delete()
        leaked.delete()
        val callId = "device-explicit-cancel"

        val invocation = async {
            tool.execute(
                ToolCall(
                    id = callId,
                    name = tool.name,
                    input = buildJsonObject {
                        put(
                            "script",
                            "printf started > ${started.name}; " +
                                "(trap '' TERM; sleep 2; printf leaked > ${leaked.name}) & wait"
                        )
                        put("workingDirectory", "gate3-explicit-cancel")
                        put("timeoutMillis", 30_000)
                    }
                ),
                ToolExecutionContext(sessionId = "gate3")
            )
        }
        withTimeout(5_000) {
            while (!started.isFile) delay(20)
        }

        assertTrue(plugin.cancel(callId))
        val result = withTimeout(5_000) { invocation.await() }
        assertTrue(result.isError)
        assertEquals("\"CANCELLED\"", result.metadata?.get("code").toString())
        assertFalse(plugin.cancel(callId))

        delay(2_500)
        assertFalse("explicit cancel left a process-group descendant", leaked.exists())
    }

    @Test
    fun executesPayloadFromThisApplicationsNativeLibraryDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("com.ugk.runtime.demo.a", context.packageName)

        val executable = NativeRuntimeProbe.executableFile(context)
        assertTrue("Probe payload is missing: $executable", executable.isFile)

        val result = NativeRuntimeProbe.execute(context, listOf("demo-a"))

        assertFalse("Probe timed out: $result", result.timedOut)
        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue(result.stdout.contains("ugk_runtime_probe=1"))
        assertTrue(result.stdout.contains("argv[0]=${executable.absolutePath}"))
        assertTrue(result.stdout.contains("argv[1]=demo-a"))
    }

    @Test
    fun executesPackagedBashInThisApplicationsPrivateWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = File(context.filesDir, "bash-test-a")
        val runtime = BashRuntime(context)

        val executable = BashRuntime.executableFile(context)
        assertTrue("Bash payload is missing: $executable", executable.isFile)

        val result = runtime.execute(
            BashCommandRequest(
                script = "printf 'sum=%s\\n' \"\$((20 + 22))\"; printf 'cwd=%s\\n' \"\$PWD\"; printf 'hello' > result.txt",
                workingDirectory = workspace,
                timeoutMillis = 5_000
            )
        )

        assertFalse("Bash timed out: $result", result.timedOut)
        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue(result.stdout.contains("sum=42"))
        assertTrue(result.stdout.contains("cwd=${workspace.absolutePath}"))
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
    fun invokesEmbeddedPythonWithNativeExtensionsAndRepairsItsStandardLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)
        val nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir)

        assertTrue(
            "Python launcher is missing",
            File(nativeLibraryDirectory, BashRuntime.pythonExecutableFileName).isFile
        )
        assertTrue("libpython is missing", File(nativeLibraryDirectory, "libpython3.14.so").isFile)

        val importPaths = runtime.execute(
            BashCommandRequest(
                script = "python -S -c \"import importlib.machinery, sys; print('path=' + '|'.join(sys.path)); print('suffixes=' + '|'.join(importlib.machinery.EXTENSION_SUFFIXES))\"",
                timeoutMillis = 15_000
            )
        )
        assertFalse("Python path probe timed out: $importPaths", importPaths.timedOut)
        assertEquals("Python path probe stderr: ${importPaths.stderr}", 0, importPaths.exitCode)
        assertTrue(
            "Python native library directory is absent from sys.path: ${importPaths.stdout}",
            importPaths.stdout.contains(nativeLibraryDirectory.absolutePath)
        )
        assertTrue(
            "Python extension bridge payload was not extracted; native files: " +
                nativeLibraryDirectory.list()?.sorted()?.joinToString(),
            nativeLibraryDirectory.listFiles()?.any { file ->
                file.name.startsWith("libugk_pyext__sqlite3.cpython-314-") &&
                    file.name.endsWith(".so")
            } == true
        )

        val first = runtime.execute(
            BashCommandRequest(
                script = """
                    type -t python
                    type -t python3
                    python -c "import hashlib, sqlite3, ssl, subprocess, sys; print('python=' + sys.version.split()[0]); print('sha256=' + hashlib.sha256(b'abc').hexdigest()); print('sqlite=' + sqlite3.sqlite_version); print('ssl=' + ssl.OPENSSL_VERSION.split()[0]); print(subprocess.run(['/system/bin/echo', 'subprocess=ok'], capture_output=True, text=True, check=True).stdout.strip())"
                """.trimIndent(),
                timeoutMillis = 15_000
            )
        )

        assertFalse("Python timed out: $first", first.timedOut)
        assertEquals("Python stderr: ${first.stderr}", 0, first.exitCode)
        assertTrue("stdout: ${first.stdout}", first.stdout.lines().count { it == "function" } >= 2)
        assertTrue("stdout: ${first.stdout}", first.stdout.contains("python=3.14.6"))
        assertTrue(
            "stdout: ${first.stdout}",
            first.stdout.contains("sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        )
        assertTrue("stdout: ${first.stdout}", first.stdout.contains("sqlite="))
        assertTrue("stdout: ${first.stdout}", first.stdout.contains("ssl=OpenSSL"))
        assertTrue("stdout: ${first.stdout}", first.stdout.contains("subprocess=ok"))

        val stdlibFile = File(
            context.filesDir,
            "ugk-terminal-runtime/python/3.14.6/lib/python3.14/encodings/__init__.py"
        )
        assertTrue("Python stdlib was not materialized: $stdlibFile", stdlibFile.isFile)
        stdlibFile.writeText("tampered")

        val repaired = runtime.execute(
            BashCommandRequest(
                script = "python -c \"import encodings; print('stdlib=repaired')\"",
                timeoutMillis = 15_000
            )
        )

        assertFalse("Python repair timed out: $repaired", repaired.timedOut)
        assertEquals("Python repair stderr: ${repaired.stderr}", 0, repaired.exitCode)
        assertTrue("stdout: ${repaired.stdout}", repaired.stdout.contains("stdlib=repaired"))
        assertFalse("Python stdlib was not repaired", stdlibFile.readText() == "tampered")
    }

    @Test
    fun permitsExecutableMemoryRequiredByEmbeddedJavaScriptEngines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)

        val result = runtime.execute(
            BashCommandRequest(
                script = """
                    python -c "import mmap; page = mmap.mmap(-1, mmap.PAGESIZE, prot=mmap.PROT_READ | mmap.PROT_WRITE | mmap.PROT_EXEC); page[:4] = b'ugk!'; print('execmem=ok'); page.close()"
                """.trimIndent(),
                timeoutMillis = 15_000
            )
        )

        assertFalse("Executable-memory probe timed out: $result", result.timedOut)
        assertEquals("Executable-memory probe stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("execmem=ok"))
    }

    @Test
    fun timeoutTerminatesBackgroundDescendantsInTheTerminalSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = File(context.filesDir, "timeout-test-a")
        val marker = File(workspace, "descendant-marker.txt")
        marker.delete()
        val runtime = BashRuntime(context)

        val result = runtime.execute(
            BashCommandRequest(
                script = "(sleep 2; printf leaked > ${marker.name}) & sleep 30",
                workingDirectory = workspace,
                timeoutMillis = 500
            )
        )

        assertTrue("result should be marked timed out: $result", result.timedOut)
        Thread.sleep(2_500)
        assertFalse("background descendant survived timeout: $marker", marker.exists())
    }

    @Test
    fun timeoutEscalatesToKillWhenDescendantIgnoresTerminateAfterBashExits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = File(context.filesDir, "timeout-resistant-test-a")
        val marker = File(workspace, "term-resistant-marker.txt")
        marker.delete()
        val runtime = BashRuntime(context)

        val result = runtime.execute(
            BashCommandRequest(
                script = "(trap '' TERM; sleep 2; printf leaked > ${marker.name}) & wait",
                workingDirectory = workspace,
                timeoutMillis = 500
            )
        )

        assertTrue("result should be marked timed out: $result", result.timedOut)
        Thread.sleep(2_500)
        assertFalse("TERM-resistant descendant survived timeout: $marker", marker.exists())
    }
}
