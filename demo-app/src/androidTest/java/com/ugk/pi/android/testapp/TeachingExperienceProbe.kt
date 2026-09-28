package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeachingExperienceProbe {
    @Test fun fullAuthorizationStillWaitsForExplicitChoice() = runBlocking {
        var click: ((String) -> Unit)? = null
        val shown = CompletableDeferred<Unit>()
        val presenter = ActivityUserConfirmationDialogPresenter(isFullAuthorizationEnabled = { true }, overlayHost = object : ConfirmationOverlayHost {
            override fun showConfirmation(request: UserConfirmationDialogRequest, onResult: (String) -> Unit): Boolean {
                click = onResult; shown.complete(Unit); return true
            }
            override fun hideConfirmation() {}
        })
        val pending = async { presenter.showExplicitConfirmationDialog(UserConfirmationDialogRequest("使用经验？", "验证", listOf(
            UserConfirmationDialogButton("use", "使用"), UserConfirmationDialogButton("cancel", "不用")))) }
        withTimeout(5000) { shown.await() }
        assertFalse(pending.isCompleted)
        withContext(Dispatchers.Main) { presenter.onActivityPaused(); click!!.invoke("cancel") }
        assertEquals("cancel", pending.await().selectedButtonId)
    }

    @Test fun realModelFindsParaphraseAndAsksBeforeLoading() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("experienceProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = checkNotNull(ApiProviderSettingsStore(context).activeConfig())
        val root = File(context.cacheDir, "experience-probe-${UUID.randomUUID()}")
        val store = DemoTeachingStore(root)
        val id = UUID.randomUUID().toString()
        store.create(id, "检查谷歌商店应用更新")
        store.update(id) { it.copy(status = "finished") }
        store.saveGuide(id, DemoTeachingGuide("检查谷歌商店应用更新", "查看Google Play待更新应用，不执行安装", emptyList(),
            listOf("观察当前商店页面并进入应用管理，检查待更新列表"), emptyList(), listOf("显示待更新应用或已全部最新"), emptyList(),
            listOf("看看哪些应用有更新", "检查应用新版本"), listOf("Google Play", "谷歌商店"), listOf("安装全部更新")))
        var choices = 0
        val plugin = DemoTeachingExperiencePlugin(store) { request ->
            assertTrue(request.message.contains("检查谷歌商店应用更新"))
            choices++
            UserConfirmationDialogResult("cancel")
        }
        val provider = ProviderProfile.from(config).createRuntimeProvider(JavaNetDemoHttpTransport(), AnthropicRetryPolicy(maxAttempts = 1))
        val runtime = AgentRuntime.Builder().llmProvider(provider).register(plugin).maxIterations(6).build()
        val names = mutableListOf<String>()
        val trace = mutableListOf<String>()
        try {
            withTimeout(120_000) {
                runtime.run(AgentSession(UUID.randomUUID().toString()), "帮我看看谷歌商店里哪些应用有新版本。").collect { event ->
                    if (event is AgentEvent.ToolStarted) names += event.call.name
                    if (event is AgentEvent.ToolStarted) trace += event.call.toString()
                    if (event is AgentEvent.ToolFinished) trace += event.result.content
                    if (event is AgentEvent.Failed) trace += event.toString()
                }
            }
            assertTrue(names.contains("teaching_experience_search"))
            assertTrue(trace.joinToString("\n"), names.contains("teaching_experience_use"))
            assertEquals(1, choices)
            assertTrue(store.read(id)!!.usageHistory.isEmpty())
        } finally {
            runtime.close()
            runCatching {
                val cache = context.cacheDir.canonicalFile
                val probeRoot = root.canonicalFile
                check(probeRoot.parentFile == cache && probeRoot.name.startsWith("experience-probe-"))
                probeRoot.deleteRecursively()
            }
        }
    }
}
