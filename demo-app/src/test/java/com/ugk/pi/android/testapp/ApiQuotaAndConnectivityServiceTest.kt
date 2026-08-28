package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiQuotaAndConnectivityServiceTest {

    @Test
    fun testDetectProvider() {
        assertEquals(
            QuotaProviderType.DEEPSEEK,
            ApiQuotaAndConnectivityService.detectProvider("https://api.deepseek.com/anthropic")
        )
        assertEquals(
            QuotaProviderType.DEEPSEEK,
            ApiQuotaAndConnectivityService.detectProvider("https://api.deepseek.com/v1")
        )
        assertEquals(
            QuotaProviderType.SILICONFLOW,
            ApiQuotaAndConnectivityService.detectProvider("https://api.siliconflow.cn/v1")
        )
        assertEquals(
            QuotaProviderType.SILICONFLOW,
            ApiQuotaAndConnectivityService.detectProvider("https://api.siliconflow.com/v1")
        )
        assertEquals(
            QuotaProviderType.MOONSHOT,
            ApiQuotaAndConnectivityService.detectProvider("https://api.moonshot.cn/v1")
        )
        assertEquals(
            QuotaProviderType.MOONSHOT,
            ApiQuotaAndConnectivityService.detectProvider("https://api.moonshot.ai/v1")
        )
        assertEquals(
            QuotaProviderType.OPENROUTER,
            ApiQuotaAndConnectivityService.detectProvider("https://openrouter.ai/api/v1")
        )
        assertEquals(
            QuotaProviderType.UNKNOWN,
            ApiQuotaAndConnectivityService.detectProvider("https://api.example.com/v1")
        )
    }

    @Test
    fun testParseDeepSeekBalanceResponse() {
        val json = """
            {
                "is_available": true,
                "balance_infos": [
                    {
                        "currency": "CNY",
                        "total_balance": "18.50",
                        "granted_balance": "3.50",
                        "topped_up_balance": "15.00"
                    }
                ]
            }
        """.trimIndent()

        val result = ApiQuotaAndConnectivityService.parseDeepSeekBalanceResponse(json)
        assertTrue(result.supported)
        assertEquals(QuotaProviderType.DEEPSEEK, result.provider)
        assertNotNull(result.balanceText)
        assertTrue(result.balanceText!!.contains("￥18.50"))
        assertTrue(result.balanceText!!.contains("充值 ￥15.00"))
        assertTrue(result.balanceText!!.contains("赠金 ￥3.50"))
    }

    @Test
    fun testParseSiliconFlowBalanceResponse() {
        val json = """
            {
                "code": 20000,
                "message": "success",
                "data": {
                    "balance": "26.80",
                    "totalBalance": "26.80",
                    "chargeBalance": "20.00"
                }
            }
        """.trimIndent()

        val result = ApiQuotaAndConnectivityService.parseSiliconFlowBalanceResponse(json, isUsd = false)
        assertTrue(result.supported)
        assertEquals(QuotaProviderType.SILICONFLOW, result.provider)
        assertNotNull(result.balanceText)
        assertTrue(result.balanceText!!.contains("￥26.80"))
        assertTrue(result.balanceText!!.contains("已充值 ￥20.00"))
    }

    @Test
    fun testParseMoonshotBalanceResponse() {
        val json = """
            {
                "code": 0,
                "data": {
                    "available_balance": 50.0,
                    "cash_balance": 40.0,
                    "voucher_balance": 10.0
                }
            }
        """.trimIndent()

        val result = ApiQuotaAndConnectivityService.parseMoonshotBalanceResponse(json, isUsd = false)
        assertTrue(result.supported)
        assertEquals(QuotaProviderType.MOONSHOT, result.provider)
        assertNotNull(result.balanceText)
        assertTrue(result.balanceText!!.contains("￥50.0"))
        assertTrue(result.balanceText!!.contains("现金 ￥40.0"))
        assertTrue(result.balanceText!!.contains("代金券 ￥10.0"))
    }

    @Test
    fun testParseOpenRouterBalanceResponse() {
        val json = """
            {
                "data": {
                    "limit": 20.0,
                    "usage": 4.5,
                    "limit_remaining": 15.5,
                    "is_free_tier": false
                }
            }
        """.trimIndent()

        val result = ApiQuotaAndConnectivityService.parseOpenRouterBalanceResponse(json)
        assertTrue(result.supported)
        assertEquals(QuotaProviderType.OPENROUTER, result.provider)
        assertNotNull(result.balanceText)
        assertTrue(result.balanceText!!.contains("$15.5 可用"))
        assertTrue(result.balanceText!!.contains("限额 $20.0"))
        assertTrue(result.balanceText!!.contains("已用: $4.5"))
    }

    @Test
    fun testApiProviderConfigNameAndDisplayName() {
        val configWithName = ApiProviderConfig(
            id = "cfg-1",
            baseUrl = "https://api.deepseek.com/anthropic",
            apiKey = "sk-test",
            model = "deepseek-chat",
            name = "DeepSeek 官方主配置"
        )
        assertEquals("DeepSeek 官方主配置", configWithName.displayName())

        val configWithoutName = ApiProviderConfig(
            id = "cfg-2",
            baseUrl = "https://api.deepseek.com/anthropic",
            apiKey = "sk-test",
            model = "deepseek-chat"
        )
        assertEquals("deepseek-chat - api.deepseek.com", configWithoutName.displayName())
    }

    @Test
    fun testApiProviderSettingsJsonSerializationCompatibility() {
        // 验证带 name 的新版序列化与反序列化
        val state = ApiProviderSettingsState(
            activeId = "cfg-1",
            configs = listOf(
                ApiProviderConfig("cfg-1", "https://api.deepseek.com", "key1", "model1", "配置1"),
                ApiProviderConfig("cfg-2", "https://api.siliconflow.cn", "key2", "model2", null)
            )
        )
        val encoded = ApiProviderSettingsJson.encode(state)
        val decoded = ApiProviderSettingsJson.decode(encoded)

        assertEquals("cfg-1", decoded.activeId)
        assertEquals(2, decoded.configs.size)
        assertEquals("配置1", decoded.configs[0].name)
        assertNull(decoded.configs[1].name)

        // 验证旧版 JSON（不含 name 字段）能正常兼容反序列化
        val legacyJson = """
            {
                "activeId": "old-1",
                "configs": [
                    {
                        "id": "old-1",
                        "baseUrl": "https://api.openai.com",
                        "apiKey": "sk-old",
                        "model": "gpt-4"
                    }
                ]
            }
        """.trimIndent()
        val legacyDecoded = ApiProviderSettingsJson.decode(legacyJson)
        assertEquals("old-1", legacyDecoded.activeId)
        assertEquals(1, legacyDecoded.configs.size)
        assertNull(legacyDecoded.configs[0].name)
        assertEquals("gpt-4 - api.openai.com", legacyDecoded.configs[0].displayName())
    }
}
