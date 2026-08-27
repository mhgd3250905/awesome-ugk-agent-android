package com.ugk.pi.android.testapp

import android.content.ComponentName
import android.content.Context
import com.ugk.pi.android.AccessibilityScreenAutomationBackend
import com.ugk.pi.android.AccessibilityServiceProvider
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentTaskScheduler
import com.ugk.pi.android.AgentTaskStore
import com.ugk.pi.android.AgentSkillRuntimePlugin
import com.ugk.pi.android.AgentSkillSeeder
import com.ugk.pi.android.AndroidAutomationAgentPlugin
import com.ugk.pi.android.AnthropicMessagesProvider
import com.ugk.pi.android.FileBackedSkillProvider
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.LoadPolicySkillResolver
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.SkillRepository
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.ScheduleTaskAgentPlugin
import com.ugk.pi.terminal.skill.TerminalAgentPlugin
import java.io.File

/**
 * Single composition root for both foreground and scheduled Agent runs.
 *
 * The background JobService never creates an Activity. It asks the host for
 * this same capability graph with a headless confirmation presenter and the
 * current persisted authorization setting.
 */
internal object DemoAgentRuntimeFactory {
    fun create(
        context: Context,
        scheduleStore: AgentTaskStore,
        scheduleScheduler: AgentTaskScheduler,
        confirmationPresenter: UserConfirmationDialogPresenter,
        shouldBypassConfirmation: () -> Boolean,
        shouldBlockForScreenAutomation: () -> Boolean = { false },
        supportsBackgroundPromptExecution: Boolean = true,
        maxIterations: Int = DEFAULT_DEMO_MAX_ITERATIONS,
        isBackgroundRun: Boolean = false
    ): AgentRuntime {
        val appContext = context.applicationContext
        val config = ApiProviderSettingsStore(appContext).activeConfig()
        val provider: LLMProvider = if (config != null) {
            AnthropicMessagesProvider(
                apiKey = config.apiKey,
                model = config.model,
                baseUrl = config.baseUrl
            )
        } else {
            MissingApiProvider
        }

        // File-backed skills live in the app-private agent-skills directory;
        // packaged skills are seeded once and never overwrite user changes.
        val skillRepository = SkillRepository(File(appContext.filesDir, "agent-skills"))
        val memoryRoot = File(appContext.filesDir, "agent-memory")
        // Named embed roots: `x-ugk-embed-files` entries like
        // `memory:preferences.md` resolve here, so the packaged agent-memory
        // skill embeds the live memory store on every skills() call instead
        // of static seed templates.
        val embedRoots = mapOf("memory" to memoryRoot)
        AgentSkillSeeder.seed(appContext)

        val capabilityPlugins = listOf(
            DemoImportedFilePlugin(
                DemoFileImportStore(appContext).workspaceRoot
            ),
            ScheduleTaskAgentPlugin(
                store = scheduleStore,
                scheduler = scheduleScheduler,
                supportsBackgroundPromptExecution = supportsBackgroundPromptExecution
            ),
            AndroidAutomationAgentPlugin(
                context = appContext,
                confirmationPresenter = confirmationPresenter,
                accessibilityServiceComponent = ComponentName(
                    appContext,
                    AgentAccessibilityService::class.java
                ),
                accessibilityStateProvider = AgentAccessibilityService.runtimeStateProvider,
                shouldBypassConfirmation = shouldBypassConfirmation,
                screenAutomationBackend = AccessibilityScreenAutomationBackend(
                    serviceProvider = AccessibilityServiceProvider {
                        AgentAccessibilityService.instance
                    },
                    ownPackageName = appContext.packageName
                )
            ),
            TerminalAgentPlugin(
                context = appContext,
                shouldBypassConfirmation = shouldBypassConfirmation,
                shouldBlockForScreenAutomation = shouldBlockForScreenAutomation
            ),
            AgentSkillRuntimePlugin(
                repository = skillRepository,
                memoryRoot = memoryRoot,
                shouldBypassConfirmation = shouldBypassConfirmation,
                embedRoots = embedRoots
            )
        )

        val builder = AgentRuntime.Builder()
            .llmProvider(provider)
            .maxIterations(maxIterations)
        capabilityPlugins.forEach { plugin -> builder.register(plugin) }

        if (isBackgroundRun) {
            builder.agentInstructions(BACKGROUND_AGENT_INSTRUCTIONS)
        }
        // Builder.skillProvider replaces the statically registered plugin
        // skills, so the merged provider must carry them along explicitly and
        // must be attached after every register() call.
        return builder
            .skillResolver(LoadPolicySkillResolver(skillRepository))
            .skillProvider(FileBackedSkillProvider(capabilityPlugins, skillRepository, embedRoots = embedRoots))
            .build()
    }

    private object MissingApiProvider : LLMProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse {
            return ModelResponse(content = "请先在设置中配置 API 源（URL、模型名称、API Key）。")
        }
    }

    private val BACKGROUND_AGENT_INSTRUCTIONS = """
        This is a scheduled background Agent run. There is no interactive Activity confirmation dialog.
        Read-only observations may be used when the required service is connected. Do not call protected actions that require confirmation unless the host's explicit full-authorization setting is enabled. If a protected Tool returns a confirmation-required error, stop the action and report that the scheduled task is blocked; do not retry blindly or simulate a successful action.
        Treat the scheduled task prompt as the user's requested work, but do not create another scheduled task unless the user explicitly asks for that in the prompt.
    """.trimIndent()

    private const val DEFAULT_DEMO_MAX_ITERATIONS = 500
}
