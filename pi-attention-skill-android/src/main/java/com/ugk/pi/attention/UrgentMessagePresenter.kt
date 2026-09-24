package com.ugk.pi.attention

/** The host implements this with its own UI. The SDK does not create an overlay window. */
fun interface UrgentMessagePresenter {
    suspend fun show(message: UrgentMessage): UrgentPresentation
}

data class UrgentMessage(
    val title: String,
    val body: String,
    val reason: String,
    val accent: UrgentAccent = UrgentAccent.AMBER,
    val blocks: List<UrgentContentBlock> = emptyList()
)

/** A bounded presentation vocabulary. Hosts may render it in their own visual language. */
enum class UrgentAccent { AMBER, GREEN, BLUE, RED }

enum class UrgentBlockType { HEADING, PARAGRAPH, CALLOUT, BULLET }

data class UrgentContentBlock(val type: UrgentBlockType, val text: String)

enum class UrgentPresentationStatus {
    SHOWN,
    PERMISSION_DENIED,
    BUSY,
    UNAVAILABLE,
    FAILED
}

data class UrgentPresentation(val status: UrgentPresentationStatus)
