package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ApiContextSettingsTest {

    @Test
    fun testFormatSpec() {
        val cfg1 = ApiProviderConfig(
            id = "1",
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-1",
            model = "deepseek-chat",
            name = "DeepSeek",
            contextWindow = "64K",
            maxOutputTokens = 8192,
            autoCompaction = true,
            compactionThreshold = 0.70
        )
        assertEquals("64K · 8K输出 · 70%压缩", cfg1.formatSpec())

        val cfg2 = ApiProviderConfig(
            id = "2",
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-2",
            model = "claude-3-7-sonnet",
            contextWindow = "200K",
            maxOutputTokens = 32768,
            autoCompaction = false
        )
        assertEquals("200K · 32K输出", cfg2.formatSpec())

        val cfg3 = ApiProviderConfig(
            id = "3",
            baseUrl = "https://open.bigmodel.cn",
            apiKey = "sk-3",
            model = "glm-5.3",
            name = "GLM-5.3",
            contextWindow = "2M",
            maxOutputTokens = 131072,
            autoCompaction = true,
            compactionThreshold = 0.80
        )
        assertEquals("2M · 128K输出 · 80%压缩", cfg3.formatSpec())
    }

    @Test
    fun testSerializationRoundTripWithContextAndMaxTokens() {
        val state = ApiProviderSettingsState(
            activeId = "cfg-1",
            configs = listOf(
                ApiProviderConfig(
                    id = "cfg-1",
                    baseUrl = "https://api.deepseek.com",
                    apiKey = "sk-deepseek",
                    model = "deepseek-v4",
                    name = "DeepSeek-V4",
                    contextWindow = "128K",
                    maxOutputTokens = 131072,
                    autoCompaction = true,
                    compactionThreshold = 0.70
                ),
                ApiProviderConfig(
                    id = "cfg-2",
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-claude",
                    model = "claude-3-5-sonnet",
                    name = "Claude 3.5",
                    contextWindow = "200K",
                    maxOutputTokens = 16384,
                    autoCompaction = false,
                    compactionThreshold = 0.65
                )
            )
        )

        val encoded = ApiProviderSettingsJson.encode(state)
        val decoded = ApiProviderSettingsJson.decode(encoded)

        assertEquals("cfg-1", decoded.activeId)
        assertEquals(2, decoded.configs.size)

        val c1 = decoded.configs[0]
        assertEquals("128K", c1.contextWindow)
        assertEquals(131072, c1.maxOutputTokens)
        assertEquals(true, c1.autoCompaction)
        assertEquals(0.70, c1.compactionThreshold ?: 0.0, 0.001)
        assertEquals("128K · 128K输出 · 70%压缩", c1.formatSpec())

        val c2 = decoded.configs[1]
        assertEquals("200K", c2.contextWindow)
        assertEquals(16384, c2.maxOutputTokens)
        assertEquals(false, c2.autoCompaction)
        assertEquals(0.65, c2.compactionThreshold ?: 0.0, 0.001)
        assertEquals("200K · 16K输出", c2.formatSpec())
    }

    @Test
    fun testLegacyJsonDefaults() {
        val legacyJson = """
            {
                "activeId": "old-1",
                "configs": [
                    {
                        "id": "old-1",
                        "baseUrl": "https://api.deepseek.com",
                        "apiKey": "sk-legacy",
                        "model": "deepseek-chat",
                        "name": "旧配置"
                    }
                ]
            }
        """.trimIndent()

        val decoded = ApiProviderSettingsJson.decode(legacyJson)
        val config = decoded.activeConfig()
        assertNotNull(config)
        assertEquals("200K", config!!.contextWindow)
        assertEquals(8192, config.maxOutputTokens)
        assertEquals(true, config.autoCompaction)
        assertEquals(0.70, config.compactionThreshold ?: 0.0, 0.001)
        assertEquals("200K · 8K输出 · 70%压缩", config.formatSpec())
    }

    @Test
    fun testBudgetForContextWindow() {
        val runtime = DemoConversationRuntime()
        val b2M = runtime.budgetForContextWindow("2M")
        assertEquals(800 to 80_000, b2M)

        val b1M = runtime.budgetForContextWindow("1M")
        assertEquals(400 to 50_000, b1M)

        val b200K = runtime.budgetForContextWindow("200K")
        assertEquals(220 to 30_000, b200K)

        val b128K = runtime.budgetForContextWindow("128K")
        assertEquals(160 to 20_000, b128K)

        val b64K = runtime.budgetForContextWindow("64K")
        assertEquals(100 to 12_000, b64K)

        val b32K = runtime.budgetForContextWindow("32K")
        assertEquals(60 to 8_000, b32K)
    }
}
