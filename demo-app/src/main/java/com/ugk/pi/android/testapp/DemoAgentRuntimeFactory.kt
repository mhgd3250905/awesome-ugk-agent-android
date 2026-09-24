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
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.LoadPolicySkillResolver
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.SkillRepository
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.ScheduleTaskAgentPlugin
import com.ugk.pi.terminal.skill.TerminalAgentPlugin
import com.ugk.pi.attention.AgentAttentionPlugin
import com.ugk.pi.attention.AgentNotificationConfig
import com.ugk.pi.attention.AndroidNotificationPublisher
import com.ugk.pi.attention.UrgentMessagePresenter
import java.io.File

/**
 * Composition root for the Demo conversation runtime. A legacy headless
 * caller remains for compatibility, but the interactive app now registers
 * its own one-slot delayed-conversation tool.
 */
internal object DemoAgentRuntimeFactory {
    fun create(
        context: Context,
        scheduleStore: AgentTaskStore? = null,
        scheduleScheduler: AgentTaskScheduler? = null,
        delayedTaskController: DemoDelayedTaskController? = null,
        urgentMessagePresenter: UrgentMessagePresenter? = null,
        confirmationPresenter: UserConfirmationDialogPresenter,
        shouldBypassConfirmation: () -> Boolean,
        toolDecorator: AgentToolDecorator = AgentToolDecorator.Identity,
        supportsBackgroundPromptExecution: Boolean = true,
        maxIterations: Int = DEFAULT_DEMO_MAX_ITERATIONS,
        isBackgroundRun: Boolean = false,
        httpTransport: DemoHttpTransport = JavaNetDemoHttpTransport()
    ): AgentRuntime {
        val appContext = context.applicationContext
        val config = ApiProviderSettingsStore(appContext).activeConfig()
        val profile = config?.let(ProviderProfile::from)
        val baseProvider: LLMProvider = profile?.createRuntimeProvider(httpTransport) ?: MissingApiProvider
        val provider: LLMProvider = if (profile != null && delayedTaskController != null && !isBackgroundRun) {
            DemoModelIntentRouter(baseProvider, profile)
        } else {
            baseProvider
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

        val builder = AgentRuntime.Builder()
            .llmProvider(provider)
            .maxIterations(maxIterations)
            .transcriptPreparationPolicy(
                DemoContextCompactionPolicy(
                    contextWindow = config?.let { ContextProfile.configValueOrDefault(it.contextWindow) },
                    thresholdRatio = config?.compactionThreshold ?: ContextCompactor.DEFAULT_THRESHOLD,
                    autoCompaction = config?.autoCompaction ?: true
                )
            )
            .register(
                DemoImportedFilePlugin(
                    DemoFileImportStore(appContext).workspaceRoot
                )
            )
            .register(
                AgentAttentionPlugin(
                    notificationPublisher = AndroidNotificationPublisher(
                        appContext,
                        AgentNotificationConfig(
                            // Channel importance is immutable after creation; use a new ID for heads-up eligibility.
                            channelId = "ugk_agent_alerts_high_v1",
                            channelName = "Agent 醒目提醒",
                            channelDescription = "Agent 请求发送的通知与重要提醒",
                            smallIconResId = R.drawable.ic_agent_notification,
                            importance = android.app.NotificationManager.IMPORTANCE_HIGH
                        )
                    ),
                    urgentPresenter = urgentMessagePresenter
                )
            )
        if (delayedTaskController != null) {
            builder.register(DemoDelayAgentPlugin(delayedTaskController))
        } else if (scheduleStore != null && scheduleScheduler != null) {
            // Compatibility for the retired background executor only. The
            // interactive Demo exposes the single-conversation delay instead.
            builder.register(ScheduleTaskAgentPlugin(
                store = scheduleStore,
                scheduler = scheduleScheduler,
                supportsBackgroundPromptExecution = supportsBackgroundPromptExecution
            ))
        }
        builder.register(
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
                    ),
                    toolDecorator = toolDecorator
                )
            )
            .register(
                TerminalAgentPlugin(
                    context = appContext,
                    shouldBypassConfirmation = shouldBypassConfirmation,
                    toolDecorator = toolDecorator
                )
            )
            .register(
                AgentSkillRuntimePlugin(
                    repository = skillRepository,
                    memoryRoot = memoryRoot,
                    shouldBypassConfirmation = shouldBypassConfirmation,
                    embedRoots = embedRoots
                )
            )

        if (isBackgroundRun) {
            builder.agentInstructions(BACKGROUND_AGENT_INSTRUCTIONS)
        }
        return builder
            .skillResolver(LoadPolicySkillResolver(skillRepository))
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
