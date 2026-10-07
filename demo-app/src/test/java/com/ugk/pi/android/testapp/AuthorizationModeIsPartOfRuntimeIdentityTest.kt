package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Full authorization is an input to what an `AgentRuntime` *is*, not only to how a call is
 * answered.
 *
 * The plugins add `show_user_confirmation_dialog` only when confirmation is not bypassed
 * (`if (!shouldBypassConfirmation())`), and that decision runs once, when the tool list is
 * built. The protected Tools read the same flag live, on every call. So a runtime built
 * while the switch was on holds protected Tools with no dialog Tool in their reach - and the
 * refusal they answer with tells the model to call that missing Tool and retry. Because the
 * installed identity did not carry the flag, turning it off mapped to REUSE, and that dead
 * end outlived every later resume: the user had to restart the app to get their confirmations
 * back.
 */
class AuthorizationModeIsPartOfRuntimeIdentityTest {

    @Test
    fun anUnchangedProviderConfigReusesTheRuntime() {
        val tracer = AuthorizationAwareTracer()

        tracer.resume(authEnabled = true)
        tracer.resume(authEnabled = true)

        assertEquals(listOf("create", "reuse"), tracer.actions)
        assertEquals(1, tracer.runtimeIdentity)
    }

    @Test
    fun turningFullAuthorizationOffRebuildsTheRuntimeThatRegisteredWithoutTheDialogTool() {
        val tracer = AuthorizationAwareTracer()

        tracer.resume(authEnabled = true)
        tracer.resume(authEnabled = false)

        assertEquals(
            "a runtime built with full authorization has no confirmation dialog Tool " +
                "registered; keeping it after the switch is turned off strands every " +
                "protected Tool on a refusal that names a Tool the model was never given",
            listOf("create", "stop", "close", "create"),
            tracer.actions
        )
        assertEquals(2, tracer.runtimeIdentity)
    }

    @Test
    fun turningFullAuthorizationOnAlsoRebuilds() {
        val tracer = AuthorizationAwareTracer()

        tracer.resume(authEnabled = false)
        tracer.resume(authEnabled = true)

        assertEquals(listOf("create", "stop", "close", "create"), tracer.actions)
    }

    /**
     * The other direction of the same rule: the flag must not make every resume rebuild. The
     * installed identity has to be written from the *same reading* the decision used, which is
     * what `MainActivity.refreshRuntime()` does by resolving the flag once and passing it to
     * both sides.
     */
    @Test
    fun aRebuiltRuntimeIsNotRebuiltAgainByTheNextResume() {
        val tracer = AuthorizationAwareTracer()

        tracer.resume(authEnabled = true)
        tracer.resume(authEnabled = false)
        tracer.resume(authEnabled = false)
        tracer.resume(authEnabled = true)
        tracer.resume(authEnabled = true)

        assertEquals(
            listOf(
                "create",
                "stop", "close", "create",
                "reuse",
                "stop", "close", "create",
                "reuse"
            ),
            tracer.actions
        )
        assertEquals(3, tracer.runtimeIdentity)
    }

    @Test
    fun aProviderConfigChangeStillRebuildsWithTheAuthorizationFlagUnchanged() {
        val tracer = AuthorizationAwareTracer()

        tracer.resume(authEnabled = false)
        tracer.resume(authEnabled = false, model = "second-model")

        assertEquals(listOf("create", "stop", "close", "create"), tracer.actions)
    }

    /** Mirrors `MainActivity.refreshRuntime()` / `rebuildRuntime()` including the shared read. */
    private class AuthorizationAwareTracer {
        private var runtimeExists = false
        private var installedConfig: DemoRuntimeConfig? = null
        val actions = mutableListOf<String>()
        var runtimeIdentity = 0
            private set

        fun resume(authEnabled: Boolean, model: String = "stable-model") {
            val config = ApiProviderConfig(
                id = "provider-1",
                baseUrl = "https://provider.example",
                apiKey = "test-credential",
                model = model,
                name = "展示名称",
                contextWindow = "200K",
                maxOutputTokens = 8192,
                autoCompaction = true,
                compactionThreshold = 0.70,
                protocol = ProviderProtocol.AUTO
            )
            // One read of the stored preference, used for both sides of the comparison.
            val requested = DemoRuntimeConfig.from(config, authEnabled)
            when (
                DemoRuntimeLifecyclePolicy.decide(
                    runtimeExists = runtimeExists,
                    installedConfig = installedConfig,
                    requestedConfig = requested
                )
            ) {
                DemoRuntimeRefreshAction.CREATE -> create(requested)
                DemoRuntimeRefreshAction.REUSE -> actions += "reuse"
                DemoRuntimeRefreshAction.REBUILD -> {
                    actions += "stop"
                    actions += "close"
                    create(requested)
                }
            }
        }

        private fun create(requested: DemoRuntimeConfig?) {
            runtimeExists = true
            installedConfig = requested
            runtimeIdentity++
            actions += "create"
        }
    }
}
