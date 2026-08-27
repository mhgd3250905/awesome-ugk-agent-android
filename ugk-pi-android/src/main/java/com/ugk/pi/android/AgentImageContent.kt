package com.ugk.pi.android

/**
 * 表示随用户消息一起传递给大模型的多模态图片内容。
 *
 * @property base64Data 图片经过 Base64 编码后的数据字符串（不带 data:... 前缀）
 * @property mimeType 图片的 MIME 类型，例如 "image/jpeg"、"image/png"、"image/webp"
 */
data class AgentImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg"
)
