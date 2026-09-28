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
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
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
import com.ugk.pi.attention.AndroidNotificationPublisher
import com.ugk.pi.attention.UrgentMessagePresenter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
        httpTransport: DemoHttpTransport = JavaNetDemoHttpTransport(),
        additionalAgentInstructions: String? = null,
        enableTeachingExperience: Boolean = true
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
        // The ids contributed by the statically registered plugins are passed
        // as reserved so `skill_save` refuses to write a file that assembly
        // would then shadow: the file-backed side yields on an id collision
        // (round 7), so such a save would silently never reach the model while
        // still listing as installed.
        val importedFilePlugin = DemoImportedFilePlugin(
            DemoFileImportStore(appContext).workspaceRoot
        )
        val attentionPlugin = AgentAttentionPlugin(
            notificationPublisher = AndroidNotificationPublisher(
                appContext,
                DemoNotificationSettings.config()
            ),
            urgentPresenter = urgentMessagePresenter
        )
        val delayPlugin = delayedTaskController?.let { DemoDelayAgentPlugin(it) }
        val schedulePlugin = if (delayPlugin == null &&
            scheduleStore != null && scheduleScheduler != null) {
            // Compatibility for the retired background executor only. The
            // interactive Demo exposes the single-conversation delay instead.
            ScheduleTaskAgentPlugin(
                store = scheduleStore,
                scheduler = scheduleScheduler,
                supportsBackgroundPromptExecution = supportsBackgroundPromptExecution
            )
        } else {
            null
        }
        val automationDecorator = if (!isBackgroundRun) screenOverlayDecorator(
            delegateDecorator = toolDecorator,
            prepare = {
                withContext(Dispatchers.Main.immediate) {
                    DemoProcessScope.get(appContext).overlayController.window.prepareScreenOperation()
                }
            },
            finish = {
                withContext(Dispatchers.Main.immediate) {
                    DemoProcessScope.get(appContext).overlayController.window.finishScreenOperation()
                }
            }
        ) else toolDecorator
        val automationPlugin = AndroidAutomationAgentPlugin(
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
            toolDecorator = automationDecorator
        )
        val terminalPlugin = TerminalAgentPlugin(
            context = appContext,
            shouldBypassConfirmation = shouldBypassConfirmation,
            toolDecorator = toolDecorator
        )
        val reservedSkillIds = listOfNotNull(
            importedFilePlugin,
            attentionPlugin,
            delayPlugin,
            schedulePlugin,
            automationPlugin,
            terminalPlugin
        ).flatMap { it.skills() }.map { it.id }.toSet()
        val skillRepository = SkillRepository(
            rootDir = File(appContext.filesDir, "agent-skills"),
            reservedSkillIds = reservedSkillIds
        )
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
            .register(importedFilePlugin)
            .register(attentionPlugin)
        if (enableTeachingExperience && !isBackgroundRun) {
            builder.register(DemoTeachingExperiencePlugin(DemoProcessScope.get(appContext).teachingStore) { request ->
                val presenter = confirmationPresenter as? ActivityUserConfirmationDialogPresenter
                    ?: error("当前界面无法确认使用教学经验")
                presenter.showExplicitConfirmationDialog(request)
            })
        }
        if (delayPlugin != null) {
            builder.register(delayPlugin)
        } else if (schedulePlugin != null) {
            builder.register(schedulePlugin)
        }
        builder.register(automationPlugin)
            .register(terminalPlugin)
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
        additionalAgentInstructions?.takeIf { it.isNotBlank() }?.let(builder::agentInstructions)
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

/** One overlay lifecycle encloses ordinary tools and teaching before/after evidence alike. */
internal fun screenOverlayDecorator(
    delegateDecorator: AgentToolDecorator,
    prepare: suspend () -> Unit,
    finish: suspend () -> Unit
): AgentToolDecorator = AgentToolDecorator { tool ->
    val decorated = delegateDecorator.decorate(tool)
    object : AgentTool by decorated {
        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            // Launching from the background requires the existing visible overlay on some OEMs.
            // Launch is not coordinate input/capture: detaching its window can silently block startActivity.
            if (!DemoScreenAutomationPolicy.isScreenWorkflowTool(call.name) ||
                call.name == "launch_android_app" || call.name == "launch_android_app_intent") {
                return decorated.execute(call, context)
            }
            try {
                prepare()
                return decorated.execute(call, context)
            } finally {
                // Preparation may have partially detached the surface before throwing/cancellation.
                withContext(NonCancellable) { finish() }
            }
        }
    }
}
