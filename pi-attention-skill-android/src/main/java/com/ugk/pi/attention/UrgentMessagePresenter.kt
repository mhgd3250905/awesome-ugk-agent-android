package com.ugk.pi.attention

/** The host implements this with its own UI. The SDK does not create an overlay window. */
fun interface UrgentMessagePresenter {
    suspend fun show(message: UrgentMessage): UrgentPresentation
}

data class UrgentMessage(
    val title: String,
    val body: String,
    val reason: String
)

enum class UrgentPresentationStatus {
    SHOWN,
    PERMISSION_DENIED,
    BUSY,
    UNAVAILABLE,
    FAILED
}

data class UrgentPresentation(val status: UrgentPresentationStatus)
