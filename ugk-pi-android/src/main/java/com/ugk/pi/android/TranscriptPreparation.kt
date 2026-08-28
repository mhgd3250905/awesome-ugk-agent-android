package com.ugk.pi.android

/**
 * The result of preparing a session transcript for one model request or
 * explicit run-completion boundary.
 */
data class TranscriptPreparation(
    val messages: List<AgentMessage>
)

/**
 * Pure transcript preparation seam. Implementations receive an immutable
 * snapshot and must return the messages that may replace it.
 */
fun interface TranscriptPreparationPolicy {
    fun prepare(snapshot: List<AgentMessage>): TranscriptPreparation
}

/** Keeps the existing runtime behavior when a host does not configure a policy. */
object NoOpTranscriptPreparationPolicy : TranscriptPreparationPolicy {
    override fun prepare(snapshot: List<AgentMessage>): TranscriptPreparation =
        TranscriptPreparation(snapshot)
}
