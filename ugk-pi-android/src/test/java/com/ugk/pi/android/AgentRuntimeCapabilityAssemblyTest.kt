package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class AgentRuntimeCapabilityAssemblyTest {
    @Test
    fun assemblyTransportAndAccumulatorAreNotPublicJvmSurfaces() {
        assertJvmClassAbsent("com.ugk.pi.android.AndroidSkillAssembly")
        assertJvmClassAbsent("com.ugk.pi.android.AndroidSkillAssemblyProvider")
        assertJvmClassAbsent("com.ugk.pi.android.AndroidSkillAssemblyAccumulator")
        listOf(
            "com.ugk.pi.android.RuntimeSkillAssembly",
            "com.ugk.pi.android.RuntimeSkillAccumulator",
            "com.ugk.pi.android.CompositeAndroidSkillProvider"
        ).forEach { className ->
            assertTrue(
                "$className must not be public",
                !Modifier.isPublic(Class.forName(className).modifiers)
            )
        }
    }

    @Test
    fun `plugin declared skills are rechecked per run after a confirmation state toggle`() = runBlocking {
        val resolver = RecordingSkillResolver()
        var bypassConfirmation = false
        var skillCalls = 0
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "stateful-plugin"

                    override fun tools(): List<AgentTool> = emptyList()

                    override fun skills(): List<AndroidSkill> {
                        skillCalls++
                        val state = if (bypassConfirmation) "FULL" else "CONFIRM"
                        return listOf(
                            AndroidSkill(
                                id = "stateful-confirmation",
                                description = "Stateful confirmation skill",
                                instructions = "CONFIRMATION_$state",
                                methods = listOf(
                                    AndroidSkillMethod(
                                        toolName = "stateful_tool",
                                        purpose = "METHOD_$state",
                                        whenToUse = "Use in $state mode.",
                                        resultSemantics = "Returns the current mode."
                                    )
                                )
                            )
                        )
                    }
                }
            )
            .build()

        runtime.run(AgentSession("stateful-v1"), "stateful").toList()
        bypassConfirmation = true
        runtime.run(AgentSession("stateful-v2"), "stateful").toList()

        assertEquals("CONFIRMATION_CONFIRM", resolver.receivedSkills[0].single().instructions)
        assertEquals("METHOD_CONFIRM", resolver.receivedSkills[0].single().methods.single().purpose)
        assertEquals("CONFIRMATION_FULL", resolver.receivedSkills[1].single().instructions)
        assertEquals("METHOD_FULL", resolver.receivedSkills[1].single().methods.single().purpose)
        assertEquals(2, skillCalls)
    }

    @Test
    fun `registered plugin and custom provider are merged in dynamic then plugin declared order`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val provider = RecordingLLMProvider()
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .skillResolver(resolver)
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "assembly-plugin"

                    override fun tools(): List<AgentTool> = emptyList()

                    override fun skills(): List<AndroidSkill> = listOf(
                        skill("plugin-declared", "PLUGIN_DECLARED")
                    )

                    override fun skillProviders(): List<AndroidSkillProvider> = listOf(
                        StaticAndroidSkillProvider(listOf(skill("plugin-dynamic", "PLUGIN_DYNAMIC")))
                    )
                }
            )
            .skillProvider(
                StaticAndroidSkillProvider(listOf(skill("custom", "CUSTOM")))
            )
            .build()

        runtime.run(AgentSession("assembly"), "assemble").toList()

        assertEquals(
            listOf("plugin-dynamic", "custom", "plugin-declared"),
            resolver.receivedSkills.single().map { it.id }
        )
    }

    @Test
    fun `custom provider can be set before plugin registration without changing the result`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val provider = RecordingLLMProvider()
        val plugin = object : AgentCapabilityPlugin {
            override val id: String = "assembly-plugin"

            override fun tools(): List<AgentTool> = emptyList()

            override fun skills(): List<AndroidSkill> = listOf(
                skill("plugin-declared", "PLUGIN_DECLARED")
            )

            override fun skillProviders(): List<AndroidSkillProvider> = listOf(
                StaticAndroidSkillProvider(listOf(skill("plugin-dynamic", "PLUGIN_DYNAMIC")))
            )
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .skillResolver(resolver)
            .skillProvider(
                StaticAndroidSkillProvider(listOf(skill("custom", "CUSTOM")))
            )
            .register(plugin)
            .build()

        runtime.run(AgentSession("assembly-reversed"), "assemble").toList()

        assertEquals(
            listOf("plugin-dynamic", "custom", "plugin-declared"),
            resolver.receivedSkills.single().map { it.id }
        )
    }

    @Test
    fun `dynamic providers are rechecked per run and the last custom provider replaces the first`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val provider = RecordingLLMProvider()
        val dynamicProvider = MutableDynamicSkillProvider()
        val secondCustomProvider = MutableDynamicSkillProvider(prefix = "custom")
        var skillProviderListCalls = 0
        val plugin = object : AgentCapabilityPlugin {
            override val id: String = "assembly-plugin"

            override fun tools(): List<AgentTool> = emptyList()

            override fun skills(): List<AndroidSkill> = listOf(
                skill("plugin-declared", "PLUGIN_DECLARED")
            )

            override fun skillProviders(): List<AndroidSkillProvider> {
                skillProviderListCalls++
                return listOf(dynamicProvider)
            }
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .skillResolver(resolver)
            .register(plugin)
            .skillProvider(
                StaticAndroidSkillProvider(listOf(skill("first-custom", "FIRST_CUSTOM")))
            )
            .skillProvider(secondCustomProvider)
            .build()

        runtime.run(AgentSession("assembly-live-1"), "assemble").toList()
        runtime.run(AgentSession("assembly-live-2"), "assemble").toList()

        assertEquals(
            listOf("plugin-dynamic-v1", "custom-v1", "plugin-declared"),
            resolver.receivedSkills[0].map { it.id }
        )
        assertEquals(
            listOf("plugin-dynamic-v2", "custom-v2", "plugin-declared"),
            resolver.receivedSkills[1].map { it.id }
        )
        assertEquals(2, dynamicProvider.calls)
        assertEquals(2, secondCustomProvider.calls)
        assertEquals(2, skillProviderListCalls)
    }

    @Test
    fun `duplicate skill ids fail before resolver and model provider`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("assembly-duplicates")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "first-plugin"

                    override fun tools(): List<AgentTool> = emptyList()

                    override fun skills(): List<AndroidSkill> = listOf(
                        skill("shared", "PLUGIN_DECLARED"),
                        skill("declared-one", "DECLARED_ONE")
                    )

                    override fun skillProviders(): List<AndroidSkillProvider> = listOf(
                        StaticAndroidSkillProvider(
                            listOf(
                                skill("shared", "DYNAMIC_ONE_DUPLICATE"),
                                skill("dynamic-one", "DYNAMIC_ONE")
                            )
                        ),
                        StaticAndroidSkillProvider(
                            listOf(
                                skill("shared", "DYNAMIC_TWO_DUPLICATE"),
                                skill("dynamic-two", "DYNAMIC_TWO")
                            )
                        )
                    )
                }
            )
            .skillProvider(
                StaticAndroidSkillProvider(
                    listOf(
                        skill("shared", "CUSTOM_DUPLICATE"),
                        skill("custom-one", "CUSTOM_ONE")
                    )
                )
            )
            .build()

        val events = runtime.run(session, "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("Duplicate skill id 'shared'"))
        assertTrue(failure.message.contains("skillProviders"))
        assertTrue(resolver.receivedSkills.isEmpty())
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun `duplicate skill ids within one provider fail`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .skillProvider(
                StaticAndroidSkillProvider(
                    listOf(
                        skill("same-provider", "ONE"),
                        skill("same-provider", "TWO")
                    )
                )
            )
            .build()

        val events = runtime.run(AgentSession("same-provider"), "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("Duplicate skill id 'same-provider'"))
        assertTrue(failure.message.contains("custom skillProvider()"))
        assertEquals(emptyList<List<AndroidSkill>>(), resolver.receivedSkills)
    }

    @Test
    fun `duplicate ids across plugin dynamic providers fail`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(dynamicPlugin("first-dynamic", "cross-plugin"))
            .register(dynamicPlugin("second-dynamic", "cross-plugin"))
            .build()

        val events = runtime.run(AgentSession("cross-plugin"), "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("Duplicate skill id 'cross-plugin'"))
        assertTrue(failure.message.contains("first-dynamic"))
        assertTrue(failure.message.contains("second-dynamic"))
        assertEquals(emptyList<List<AndroidSkill>>(), resolver.receivedSkills)
    }

    @Test
    fun duplicatePluginDeclaredSkillsAcrossTwoPluginsFailClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("cross-declared")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "declared-first"
                    override fun tools(): List<AgentTool> = emptyList()
                    override fun skills(): List<AndroidSkill> =
                        listOf(skill("cross-declared", "FIRST"))
                }
            )
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "declared-second"
                    override fun tools(): List<AgentTool> = emptyList()
                    override fun skills(): List<AndroidSkill> =
                        listOf(skill("cross-declared", "SECOND"))
                }
            )
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events = events,
            expectedMessage = "Duplicate skill id 'cross-declared'",
            resolver = resolver,
            llm = llm,
            session = session
        )
        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("declared-first"))
        assertTrue(failure.message.contains("declared-second"))
    }

    @Test
    fun `duplicate ids between dynamic custom and plugin declared sources fail`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(
                dynamicPlugin(
                    id = "dynamic-source",
                    skillId = "dynamic-custom"
                )
            )
            .skillProvider(
                StaticAndroidSkillProvider(listOf(skill("dynamic-custom", "CUSTOM")))
            )
            .build()

        val dynamicCustomEvents = runtime.run(
            AgentSession("dynamic-custom"),
            "assemble"
        ).toList()

        val dynamicCustomFailure = dynamicCustomEvents.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(dynamicCustomFailure.message.contains("dynamic-custom"))
        assertTrue(dynamicCustomFailure.message.contains("dynamic-source"))
        assertTrue(dynamicCustomFailure.message.contains("custom skillProvider()"))
        assertEquals(emptyList<List<AndroidSkill>>(), resolver.receivedSkills)

        val pluginCollisionRuntime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "plugin-declared-source"
                    override fun tools(): List<AgentTool> = emptyList()
                    override fun skills(): List<AndroidSkill> = listOf(
                        skill("plugin-declared", "PLUGIN")
                    )
                }
            )
            .skillProvider(
                StaticAndroidSkillProvider(listOf(skill("plugin-declared", "CUSTOM")))
            )
            .build()

        val pluginEvents = pluginCollisionRuntime.run(
            AgentSession("plugin-declared"),
            "assemble"
        ).toList()

        val pluginFailure = pluginEvents.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(pluginFailure.message.contains("Duplicate skill id 'plugin-declared'"))
        assertTrue(pluginFailure.message.contains("plugin-declared-source"))
        assertTrue(pluginFailure.message.contains("custom skillProvider()"))
    }

    @Test
    fun `blank skill ids fail and case remains exact and case sensitive`() = runBlocking {
        val blankRuntime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(RecordingSkillResolver())
            .skillProvider(StaticAndroidSkillProvider(listOf(skill("   ", "BLANK"))))
            .build()

        val blankEvents = blankRuntime.run(AgentSession("blank-id"), "assemble").toList()

        val blankFailure = blankEvents.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(blankFailure.message.contains("Skill id must not be blank"))

        val resolver = RecordingSkillResolver()
        val caseRuntime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .skillProvider(
                StaticAndroidSkillProvider(
                    listOf(skill("Foo", "UPPER"), skill("foo", "LOWER"))
                )
            )
            .build()

        val caseEvents = caseRuntime.run(AgentSession("case-id"), "assemble").toList()

        assertEquals(emptyList<AgentEvent.Failed>(), caseEvents.filterIsInstance<AgentEvent.Failed>())
        assertEquals(listOf("Foo", "foo"), resolver.receivedSkills.single().map { it.id })
    }

    @Test
    fun `skill provider exceptions become failed events without calling resolver or model`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("provider-failure")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .skillProvider(
                object : AndroidSkillProvider {
                    override fun skills(): List<AndroidSkill> =
                        error("dynamic provider exploded")
                }
            )
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertEquals(
            AgentEvent.Failed("Skill assembly failed: dynamic provider exploded"),
            events.last()
        )
        assertEquals(emptyList<List<AndroidSkill>>(), resolver.receivedSkills)
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun javaProviderNullListFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-null-provider-list")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .skillProvider(JavaNullSkillListProvider())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "null skills list",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaProviderNullElementFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-null-provider-element")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .skillProvider(JavaNullSkillElementProvider())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "null skill",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaPluginSkillProvidersNullListFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-null-plugin-provider-list")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(JavaNullSkillProvidersPlugin())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "null skillProviders list",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaPluginSkillProviderNullElementFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-null-plugin-provider-element")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(JavaNullSkillProviderElementPlugin())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "null skill provider at index 0",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaPluginSkillsNullListFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-null-declared-list")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(JavaNullDeclaredSkillsPlugin())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "null skills list",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaPluginSkillsNullElementFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-null-declared-element")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(JavaNullDeclaredSkillElementPlugin())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "null skill",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaPluginSkillProvidersExceptionFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-plugin-provider-exception")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(JavaThrowingSkillProvidersPlugin())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "java skillProviders exploded",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun javaPluginSkillsExceptionFailsClosed() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("java-plugin-skills-exception")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(JavaThrowingDeclaredSkillsPlugin())
            .build()

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "java plugin.skills exploded",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun publicConstructorRejectsDuplicateSkillsWithSameAssemblyRules() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("direct-duplicate")
        val runtime = AgentRuntime(
            llmProvider = llm,
            toolRegistry = ToolRegistry(),
            skillProvider = StaticAndroidSkillProvider(
                listOf(
                    skill("same-direct", "ONE"),
                    skill("same-direct", "TWO")
                )
            ),
            skillResolver = resolver
        )

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "Duplicate skill id 'same-direct'",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun publicConstructorRejectsBlankSkillsWithSameAssemblyRules() = runBlocking {
        val resolver = RecordingSkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("direct-blank")
        val runtime = AgentRuntime(
            llmProvider = llm,
            toolRegistry = ToolRegistry(),
            skillProvider = StaticAndroidSkillProvider(listOf(skill("   ", "BLANK"))),
            skillResolver = resolver
        )

        val events = runtime.run(session, "assemble").toList()

        assertAssemblyFailedBeforeResolution(
            events,
            "Skill id must not be blank",
            resolver,
            llm,
            session
        )
    }

    @Test
    fun publicConstructorRejectsJavaNullSkillElementWithSameAssemblyRules() = runBlocking {
        val resolver = RecordingEmptySkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("direct-null-element")
        val runtime = AgentRuntime(
            llmProvider = llm,
            toolRegistry = ToolRegistry(),
            skillProvider = JavaNullSkillElementProvider(),
            skillResolver = resolver
        )

        val events = runtime.run(session, "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("null skill"))
        assertEquals(0, resolver.calls)
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun publicConstructorRejectsJavaNullSkillListWithSameAssemblyRules() = runBlocking {
        val resolver = RecordingEmptySkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("direct-null-list")
        val runtime = AgentRuntime(
            llmProvider = llm,
            toolRegistry = ToolRegistry(),
            skillProvider = JavaNullSkillListProvider(),
            skillResolver = resolver
        )

        val events = runtime.run(session, "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("null skills list"))
        assertEquals(0, resolver.calls)
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun publicConstructorRejectsJavaNullProviderSourceWithSameAssemblyRules() = runBlocking {
        val resolver = RecordingEmptySkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("direct-null-source")
        val runtime = AgentRuntime(
            llmProvider = llm,
            toolRegistry = ToolRegistry(),
            skillProvider = JavaNullSkillSourceProvider(),
            skillResolver = resolver
        )

        val events = runtime.run(session, "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("null source"))
        assertEquals(0, resolver.calls)
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun publicConstructorRejectsJavaThrowingProviderSourceWithSameAssemblyRules() = runBlocking {
        val resolver = RecordingEmptySkillResolver()
        val llm = RecordingLLMProvider()
        val session = AgentSession("direct-throwing-source")
        val runtime = AgentRuntime(
            llmProvider = llm,
            toolRegistry = ToolRegistry(),
            skillProvider = JavaThrowingSkillSourceProvider(),
            skillResolver = resolver
        )

        val events = runtime.run(session, "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("java provider source exploded"))
        assertEquals(0, resolver.calls)
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun `empty providers and empty skill lists produce no skills`() = runBlocking {
        val resolver = RecordingSkillResolver()
        val runtime = AgentRuntime.Builder()
            .llmProvider(RecordingLLMProvider())
            .skillResolver(resolver)
            .register(
                object : AgentCapabilityPlugin {
                    override val id: String = "empty-plugin"
                    override fun tools(): List<AgentTool> = emptyList()
                    override fun skills(): List<AndroidSkill> = emptyList()
                    override fun skillProviders(): List<AndroidSkillProvider> = listOf(
                        EmptyAndroidSkillProvider
                    )
                }
            )
            .skillProvider(EmptyAndroidSkillProvider)
            .build()

        runtime.run(AgentSession("assembly-empty"), "assemble").toList()

        assertEquals(emptyList<AndroidSkill>(), resolver.receivedSkills.single())
    }

    private fun assertAssemblyFailedBeforeResolution(
        events: List<AgentEvent>,
        expectedMessage: String,
        resolver: RecordingSkillResolver,
        llm: RecordingLLMProvider,
        session: AgentSession
    ) {
        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains(expectedMessage))
        assertTrue(resolver.receivedSkills.isEmpty())
        assertEquals(0, llm.requestCount)
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    private fun assertJvmClassAbsent(className: String) {
        assertEquals(null, runCatching { Class.forName(className) }.getOrNull())
    }

    private fun skill(id: String, marker: String): AndroidSkill = AndroidSkill(
        id = id,
        description = "Skill $marker",
        instructions = marker
    )

    private fun dynamicPlugin(id: String, skillId: String): AgentCapabilityPlugin =
        object : AgentCapabilityPlugin {
            override val id: String = id
            override fun tools(): List<AgentTool> = emptyList()
            override fun skills(): List<AndroidSkill> = emptyList()
            override fun skillProviders(): List<AndroidSkillProvider> = listOf(
                StaticAndroidSkillProvider(listOf(skill(skillId, id.uppercase())))
            )
        }

    private class RecordingSkillResolver : AndroidSkillResolver {
        val receivedSkills = mutableListOf<List<AndroidSkill>>()

        override fun resolve(
            userMessage: String,
            skills: List<AndroidSkill>,
            availableToolNames: Set<String>
        ): List<AndroidSkill> {
            receivedSkills += skills
            return skills
        }
    }

    private class RecordingEmptySkillResolver : AndroidSkillResolver {
        var calls: Int = 0

        override fun resolve(
            userMessage: String,
            skills: List<AndroidSkill>,
            availableToolNames: Set<String>
        ): List<AndroidSkill> {
            calls++
            return emptyList()
        }
    }

    private class RecordingLLMProvider : LLMProvider {
        var requestCount: Int = 0

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requestCount++
            return ModelResponse("done")
        }
    }

    private class MutableDynamicSkillProvider(
        private val prefix: String = "plugin-dynamic"
    ) : AndroidSkillProvider {
        var calls: Int = 0
            private set

        override fun skills(): List<AndroidSkill> {
            calls++
            return listOf(skill("$prefix-v$calls", "${prefix.uppercase().replace('-', '_')}_V$calls"))
        }

        private fun skill(id: String, marker: String): AndroidSkill = AndroidSkill(
            id = id,
            description = "Skill $marker",
            instructions = marker
        )
    }
}
