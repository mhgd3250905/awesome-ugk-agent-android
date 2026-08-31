package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoConversationStoreTest {

    @Test
    fun legacySingleImagePathJsonMigratesToSingleElementList() {
        val legacyJson = """
            [
              {
                "id": "conv-legacy-1",
                "title": "旧单图会话",
                "createdAt": 1000,
                "updatedAt": 2000,
                "messages": [
                  {
                    "role": "user",
                    "content": "请分析并识别这张图片",
                    "createdAt": 1500,
                    "imagePath": "/data/user/0/photos/legacy_photo.jpg"
                  },
                  {
                    "role": "assistant",
                    "content": "这是一只猫。",
                    "createdAt": 1600
                  }
                ]
              }
            ]
        """.trimIndent()

        val decoded = decodeStoredConversations(legacyJson)

        assertEquals(1, decoded.size)
        val conversation = decoded[0]
        assertEquals("conv-legacy-1", conversation.id)
        assertEquals("旧单图会话", conversation.title)
        assertEquals(2, conversation.messages.size)

        val userMessage = conversation.messages[0]
        assertEquals("user", userMessage.role)
        assertEquals("请分析并识别这张图片", userMessage.content)
        assertEquals(listOf("/data/user/0/photos/legacy_photo.jpg"), userMessage.imagePaths)
        assertEquals("/data/user/0/photos/legacy_photo.jpg", userMessage.imagePath)

        val assistantMessage = conversation.messages[1]
        assertEquals("assistant", assistantMessage.role)
        assertEquals("这是一只猫。", assistantMessage.content)
        assertTrue(assistantMessage.imagePaths.isEmpty())
        assertNull(assistantMessage.imagePath)
    }

    @Test
    fun newMultiImagePathsJsonRoundTripsAndPreservesOrder() {
        val originalPaths = listOf(
            "/data/user/0/photos/img_1.jpg",
            "/data/user/0/photos/img_2.jpg",
            "/data/user/0/photos/img_3.jpg",
            "/data/user/0/photos/img_4.jpg"
        )
        val original = listOf(
            DemoConversation(
                id = "conv-multi-1",
                title = "多图分析任务",
                createdAt = 10000L,
                updatedAt = 20000L,
                messages = mutableListOf(
                    DemoStoredMessage(
                        role = "user",
                        content = "请按顺序对比这四张图片",
                        createdAt = 11000L,
                        imagePaths = originalPaths
                    ),
                    DemoStoredMessage(
                        role = "assistant",
                        content = "第一张是日出，第二张是正午，第三张是傍晚，第四张是夜景。",
                        createdAt = 12000L,
                        imagePaths = emptyList()
                    )
                )
            )
        )

        val encoded = encodeStoredConversations(original)
        val decoded = decodeStoredConversations(encoded)

        assertEquals(1, decoded.size)
        val conversation = decoded[0]
        assertEquals("conv-multi-1", conversation.id)
        assertEquals("多图分析任务", conversation.title)
        assertEquals(2, conversation.messages.size)

        val userMessage = conversation.messages[0]
        assertEquals("user", userMessage.role)
        assertEquals("请按顺序对比这四张图片", userMessage.content)
        assertEquals(originalPaths, userMessage.imagePaths)
        assertEquals("/data/user/0/photos/img_1.jpg", userMessage.imagePath)
    }

    @Test
    fun normalizeStoredMessageFiltersBlankAndTruncatesOverFourPaths() {
        val rawPaths = listOf(
            "   ",
            "/path/img_1.jpg",
            "",
            "  /path/img_2.jpg  ",
            "/path/img_3.jpg",
            "/path/img_4.jpg",
            "/path/img_5.jpg",
            "/path/img_6.jpg"
        )
        val message = DemoStoredMessage(
            role = "user",
            content = "测试超过4张图片",
            imagePaths = rawPaths
        )

        val normalized = normalizeStoredMessage(message)

        assertEquals(
            listOf(
                "/path/img_1.jpg",
                "/path/img_2.jpg",
                "/path/img_3.jpg",
                "/path/img_4.jpg"
            ),
            normalized.imagePaths
        )
        assertEquals("/path/img_1.jpg", normalized.imagePath)
    }

    @Test
    fun messageWithBlankContentAndNonEmptyImagePathsIsPreserved() {
        val conversation = DemoConversation(
            id = "conv-image-only",
            title = "纯图片会话",
            createdAt = 1000L,
            updatedAt = 1000L,
            messages = mutableListOf(
                DemoStoredMessage(
                    role = "user",
                    content = "",
                    imagePaths = listOf("/path/image_only.jpg")
                )
            )
        )

        val normalized = normalizeStoredConversation(conversation)

        assertEquals(1, normalized.messages.size)
        assertEquals(listOf("/path/image_only.jpg"), normalized.messages[0].imagePaths)
    }

    @Test
    fun messageWithBlankContentAndEmptyImagePathsIsFiltered() {
        val conversation = DemoConversation(
            id = "conv-empty-msg",
            title = "空消息测试",
            createdAt = 1000L,
            updatedAt = 1000L,
            messages = mutableListOf(
                DemoStoredMessage(
                    role = "user",
                    content = "   ",
                    imagePaths = listOf("  ", "")
                )
            )
        )

        val normalized = normalizeStoredConversation(conversation)

        assertTrue(normalized.messages.isEmpty())
    }

    @Test
    fun singleImagePathSecondaryConstructorAndGetterCompatibility() {
        val messageWithSingle = DemoStoredMessage(
            role = "user",
            content = "单图构造器",
            imagePath = "/path/legacy.jpg"
        )
        assertEquals(listOf("/path/legacy.jpg"), messageWithSingle.imagePaths)
        assertEquals("/path/legacy.jpg", messageWithSingle.imagePath)

        val messageWithNull = DemoStoredMessage(
            role = "user",
            content = "无图构造器",
            imagePath = null
        )
        assertEquals(emptyList<String>(), messageWithNull.imagePaths)
        assertNull(messageWithNull.imagePath)

        val messageWithBlank = DemoStoredMessage(
            role = "user",
            content = "空白路径构造器",
            imagePath = "   "
        )
        assertEquals(emptyList<String>(), messageWithBlank.imagePaths)
        assertNull(messageWithBlank.imagePath)
    }

    @Test
    fun malformedConversationOrMessageDoesNotDiscardNeighboringValidData() {
        val raw = """
            [
              {
                "id": "keep-before",
                "title": "前一个",
                "messages": [
                  {"role": "assistant", "content": "保留前一个"},
                  {"role": "unknown", "content": "跳过坏角色"},
                  {"role": "assistant", "content": {"wrong": true}},
                  {"role": "assistant", "content": "仍保留前一个"}
                ]
              },
              "不是会话对象",
              {"id": 12345, "messages": [{"role": "assistant", "content": "坏 id"}]},
              {
                "id": "keep-after",
                "title": "后一个",
                "messages": [{"role": "user", "content": "保留后一个"}]
              }
            ]
        """.trimIndent()

        val decoded = decodeStoredConversations(raw)

        assertEquals(listOf("keep-before", "keep-after"), decoded.map { it.id })
        assertEquals(
            listOf("保留前一个", "仍保留前一个"),
            decoded[0].messages.map { it.content }
        )
        assertEquals(listOf("保留后一个"), decoded[1].messages.map { it.content })
    }

    @Test
    fun malformedImagePathsFallsBackToLegacySingleImagePath() {
        val raw = """
            [{
              "id": "conv-image-fallback",
              "messages": [{
                "role": "user",
                "content": "兼容旧图片字段",
                "imagePaths": {"not": "an array"},
                "imagePath": "  /path/legacy-fallback.jpg  "
              }]
            }]
        """.trimIndent()

        val decoded = decodeStoredConversations(raw)

        assertEquals(1, decoded.size)
        assertEquals(
            listOf("/path/legacy-fallback.jpg"),
            decoded.single().messages.single().imagePaths
        )
    }
}
