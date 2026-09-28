package com.ugk.pi.android.testapp

internal enum class DemoOperationPhase { IDLE, RECORDING, PAUSED, SAVING }

internal enum class DemoOperationGuidePhase { READY, PREPARING, ACTING, CAPTURING, ANALYZING, REVIEW }

internal data class DemoOperationStep(
    val id: Int,
    val eventIds: List<Int> = emptyList(),
    val preFrameId: String? = null,
    val postFrameId: String? = null,
    val localSummary: String = "",
    val aiSummary: String? = null,
    val userCorrection: String = "",
    val confirmed: Boolean = false,
    val preparation: Boolean = false,
    val discarded: Boolean = false
)

internal data class DemoOperationSnapshot(
    val phase: DemoOperationPhase = DemoOperationPhase.IDLE,
    val draftId: String? = null,
    val title: String = "",
    val elapsedMillis: Long = 0,
    val eventCount: Int = 0,
    val frameCount: Int = 0,
    val message: String? = null,
    val guidePhase: DemoOperationGuidePhase = DemoOperationGuidePhase.READY,
    val stepNumber: Int = 1,
    val reviewStep: DemoOperationStep? = null,
    val guidedAiEnabled: Boolean = false
)

internal data class DemoOperationEvent(
    val id: Int, val at: Long, val type: Int, val packageName: String,
    val className: String?, val viewId: String?, val label: String?,
    val bounds: List<Int>, val preFrameId: String? = null,
    val postFrameId: String? = null,
    val scrollDeltaX: Int? = null, val scrollDeltaY: Int? = null,
    val scrollX: Int? = null, val scrollY: Int? = null,
    val fromIndex: Int? = null, val toIndex: Int? = null
)

internal data class DemoOperationFrame(
    val id: String, val at: Long, val fileName: String,
    val packageName: String, val width: Int, val height: Int,
    val bytes: Int,
    val nodes: List<DemoOperationNode> = emptyList(),
    val treeTruncated: Boolean = false
)

internal data class DemoOperationNode(
    val path: String, val viewId: String?, val className: String?,
    val text: String?, val description: String?, val bounds: List<Int>,
    val clickable: Boolean, val scrollable: Boolean, val checked: Boolean,
    val checkable: Boolean? = null
)

internal data class DemoOperationDraft(
    val id: String, val title: String, val startedAt: Long,
    val endedAt: Long? = null, val status: String = "recording",
    val events: List<DemoOperationEvent> = emptyList(),
    val frames: List<DemoOperationFrame> = emptyList(),
    val gaps: List<String> = emptyList(),
    val steps: List<DemoOperationStep> = emptyList(),
    val guided: Boolean = false
)

internal object DemoOperationLimits {
    const val MAX_EVENTS = 500
    const val MAX_FRAMES = 40
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_JSON_BYTES = 8 * 1024 * 1024
    const val MAX_DRAFTS = 20
    const val MAX_DURATION_MILLIS = 10 * 60 * 1000L
}
