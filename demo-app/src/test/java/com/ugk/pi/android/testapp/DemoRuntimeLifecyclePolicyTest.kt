package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DemoRuntimeLifecyclePolicyTest {

    @Test
    fun resumeCreatesThenReusesRuntimeUntilRuntimeConfigurationChanges() {
        val initial = config()
        val tracer = RuntimeRefreshTracer()

        tracer.resume(initial)
        val firstRuntime = tracer.runtimeIdentity

        tracer.resume(initial.copy(name = "仅更新展示名称"))

        assertEquals(listOf("create", "reuse"), tracer.events)
        assertEquals(firstRuntime, tracer.runtimeIdentity)

        tracer.resume(
            initial.copy(
                contextWindow = "32K",
                autoCompaction = false,
                compactionThreshold = 0.85
            )
        )

        assertEquals(listOf("create", "reuse", "stop", "close", "create"), tracer.events)
        val secondRuntime = tracer.runtimeIdentity

        tracer.resume(initial.copy(model = "changed-model"))

        assertEquals(
            listOf("create", "reuse", "stop", "close", "create", "stop", "close", "create"),
            tracer.events
        )
        assertNotEquals(firstRuntime, secondRuntime)
        assertNotEquals(secondRuntime, tracer.runtimeIdentity)
    }

    @Test
    fun firstResumeCreatesRuntimeWhenItDoesNotExist() {
        val tracer = RuntimeRefreshTracer()

        tracer.resume(config())

        assertEquals(listOf("create"), tracer.events)
        assertEquals(1, tracer.runtimeIdentity)
    }

    @Test
    fun outputLimitChangeAffectsRuntimeButNullUsesFactoryDefault() {
        val initial = config(maxOutputTokens = null)
        val tracer = RuntimeRefreshTracer()

        tracer.resume(initial)
        tracer.resume(initial.copy(maxOutputTokens = 8192))

        assertEquals(listOf("create", "reuse"), tracer.events)

        tracer.resume(initial.copy(maxOutputTokens = 16384))

        assertEquals(listOf("create", "reuse", "stop", "close", "create"), tracer.events)
    }

    @Test
    fun protocolChangeRebuildsRuntimeEvenWhenOtherResolvedSettingsStayTheSame() {
        val initial = config(protocol = ProviderProtocol.AUTO)
        val tracer = RuntimeRefreshTracer()

        tracer.resume(initial)
        tracer.resume(initial.copy(protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS))

        assertEquals(listOf("create", "stop", "close", "create"), tracer.events)
    }

    /**
     * Activity recreation scenario. When the AgentRuntime is process-owned,
     * a recreated Activity observes runtimeExists=true with the installed
     * config, so an unchanged provider config must map to REUSE — the
     * in-flight run keeps executing and queued overlay messages survive.
     * (With an Activity-owned runtime the field would be null after
     * recreation and decide() would return CREATE, killing the run.)
     */
    @Test
    fun activityRecreationWithUnchangedConfigReusesProcessOwnedRuntimeWithoutStoppingIt() {
        val config = config()
        val processState = ProcessOwnedRuntimeState()

        ProcessBackedActivity(processState).resume(config)
        val runtimeFromFirstActivity = processState.agentRuntimeIdentity
        // Simulate a config-change recreation: the second Activity instance
        // has no fields of its own and only sees the process-level state.
        val recreated = ProcessBackedActivity(processState)

        recreated.resume(config)

        assertEquals(listOf("create", "reuse"), processState.events)
        assertEquals(runtimeFromFirstActivity, processState.agentRuntimeIdentity)
        // A genuinely changed config on the recreated Activity still rebuilds
        // (user edited API settings): stop + close semantics are preserved.
        recreated.resume(config.copy(apiKey = "rotated-credential"))
        assertEquals(listOf("create", "reuse", "stop", "close", "create"), processState.events)
    }

    private fun config(
        baseUrl: String = "https://provider.example",
        apiKey: String = "test-credential",
        model: String = "stable-model",
        name: String? = "展示名称",
        contextWindow: String? = "200K",
        maxOutputTokens: Int? = 8192,
        autoCompaction: Boolean? = true,
        compactionThreshold: Double? = 0.70,
        protocol: ProviderProtocol = ProviderProtocol.AUTO
    ) = ApiProviderConfig(
        id = "provider-1",
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        name = name,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        autoCompaction = autoCompaction,
        compactionThreshold = compactionThreshold,
        protocol = protocol
    )

    private class RuntimeRefreshTracer {
        private var runtimeExists = false
        private var installedConfig: DemoRuntimeConfig? = null
        var runtimeIdentity: Int = 0
            private set
        val events = mutableListOf<String>()

        fun resume(config: ApiProviderConfig?) {
            when (
                DemoRuntimeLifecyclePolicy.decide(
                    runtimeExists = runtimeExists,
                    installedConfig = installedConfig,
                    requestedConfig = DemoRuntimeConfig.from(config)
                )
            ) {
                DemoRuntimeRefreshAction.CREATE -> create(config)
                DemoRuntimeRefreshAction.REUSE -> events += "reuse"
                DemoRuntimeRefreshAction.REBUILD -> {
                    events += "stop"
                    events += "close"
                    create(config)
                }
            }
        }

        private fun create(config: ApiProviderConfig?) {
            runtimeExists = true
            installedConfig = DemoRuntimeConfig.from(config)
            runtimeIdentity++
            events += "create"
        }
    }

    /** Mirrors DemoConversationRuntime: the process-level state an Activity reads. */
    private class ProcessOwnedRuntimeState {
        var agentRuntimeExists = false
        var appliedRuntimeConfig: DemoRuntimeConfig? = null
        var agentRuntimeIdentity = 0
            private set
        val events = mutableListOf<String>()

        fun createRuntime(config: ApiProviderConfig?) {
            agentRuntimeExists = true
            appliedRuntimeConfig = DemoRuntimeConfig.from(config)
            agentRuntimeIdentity++
            events += "create"
        }
    }

    /** A MainActivity instance: owns no runtime fields, only process state. */
    private class ProcessBackedActivity(private val process: ProcessOwnedRuntimeState) {
        fun resume(config: ApiProviderConfig?) {
            when (
                DemoRuntimeLifecyclePolicy.decide(
                    runtimeExists = process.agentRuntimeExists,
                    installedConfig = process.appliedRuntimeConfig,
                    requestedConfig = DemoRuntimeConfig.from(config)
                )
            ) {
                DemoRuntimeRefreshAction.CREATE,
                DemoRuntimeRefreshAction.REBUILD -> {
                    if (process.agentRuntimeExists) {
                        // rebuildRuntime(): stopAgent(clearQueuedMessages = true) + close()
                        process.events += "stop"
                        process.events += "close"
                    }
                    process.createRuntime(config)
                }
                DemoRuntimeRefreshAction.REUSE -> process.events += "reuse"
            }
        }
    }
}
