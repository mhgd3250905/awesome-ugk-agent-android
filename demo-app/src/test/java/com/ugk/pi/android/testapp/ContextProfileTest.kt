package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextProfileTest {

    @Test
    fun supportedProfilesExposeCurrentCapacityAndSessionBudgets() {
        val expected = listOf(
            ProfileExpectation("32K", "32K", 32_768, 60, 8_000),
            ProfileExpectation("64K", "64K", 65_536, 100, 12_000),
            ProfileExpectation("128K", "128K", 131_072, 160, 20_000),
            ProfileExpectation("200K", "200K", 204_800, 220, 30_000),
            ProfileExpectation("1M", "1M", 1_048_576, 400, 50_000),
            ProfileExpectation("2M", "2M", 2_097_152, 800, 80_000)
        )

        val actual = ContextProfile.supported.map {
            ProfileExpectation(
                stableId = it.stableId,
                displayLabel = it.displayLabel,
                tokenCapacity = it.tokenCapacity,
                sessionMaxMessages = it.sessionMaxMessages,
                sessionMaxChars = it.sessionMaxChars
            )
        }

        assertEquals(expected, actual)
    }

    @Test
    fun uiOrderedProfilesKeepExistingChipOrderAndLabels() {
        assertEquals(
            listOf("64K", "128K", "200K", "1M", "2M", "32K"),
            ContextProfile.uiOrdered.map { it.displayLabel }
        )
    }

    @Test
    fun configDefaultsTo200KAndBlankJsonContextUsesTheSameDefault() {
        val config = ApiProviderConfig(
            id = "new",
            baseUrl = "https://example.com",
            apiKey = "key",
            model = "model"
        )
        assertEquals("200K", config.contextWindow)
        assertEquals("200K", ContextProfile.DEFAULT_CONFIG)
        assertEquals("200K · 8K输出 · 70%压缩", config.copy(contextWindow = "").formatSpec())

        val decoded = ApiProviderSettingsJson.decode(
            """
            {
                "configs": [
                    {
                        "id": "missing",
                        "baseUrl": "https://example.com",
                        "apiKey": "key",
                        "model": "model"
                    },
                    {
                        "id": "blank",
                        "baseUrl": "https://example.com",
                        "apiKey": "key",
                        "model": "model",
                        "contextWindow": ""
                    }
                ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("200K", "200K"), decoded.configs.map { it.contextWindow })
    }

    @Test
    fun contextConsumersSafelyFallBackTo128KForNullAndUnknownValues() {
        assertEquals("128K", ContextProfile.resolve(null).stableId)
        assertEquals("128K", ContextProfile.resolve("future-window").stableId)
        assertEquals(131_072, ContextCompactor.parseContextWindowTokens(null))
        assertEquals(131_072, ContextCompactor.parseContextWindowTokens("future-window"))
        assertEquals(160 to 20_000, DemoActivityState.budgetForContextWindow(null))
        assertEquals(160 to 20_000, DemoActivityState.budgetForContextWindow("future-window"))
    }

    private data class ProfileExpectation(
        val stableId: String,
        val displayLabel: String,
        val tokenCapacity: Int,
        val sessionMaxMessages: Int,
        val sessionMaxChars: Int
    )
}
