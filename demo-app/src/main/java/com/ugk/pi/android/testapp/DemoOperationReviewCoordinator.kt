package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AnthropicRetryPolicy
import kotlinx.coroutines.*

/** Process-owned step review. One API configuration is fixed for the entire recording. */
internal class DemoOperationReviewCoordinator(
    private val context: Context,
    private val recorder: () -> DemoOperationRecorder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var draftId: String? = null
    private var config: ApiProviderConfig? = null
    private var evidence: DemoOperationDraft? = null
    private var job: Job? = null
    private var requestGeneration = 0L

    fun onSnapshot(state: DemoOperationSnapshot) {
        if (state.phase == DemoOperationPhase.RECORDING && state.draftId != draftId) {
            cancel()
            draftId = state.draftId
            config = ApiProviderSettingsStore(context).activeConfig()
            evidence = null
        }
        if (state.phase != DemoOperationPhase.RECORDING ||
            state.guidePhase !in setOf(DemoOperationGuidePhase.REVIEW, DemoOperationGuidePhase.ANALYZING)) {
            cancel()
        }
        if (state.phase == DemoOperationPhase.IDLE) {
            draftId = null
            config = null
            evidence = null
        }
    }

    fun onStepReady(draft: DemoOperationDraft, step: DemoOperationStep) {
        evidence = draft
        requestReview(step.userCorrection)
    }

    fun requestReview(correction: String): Result<Unit> = runCatching {
        val recording = recorder()
        val state = recording.snapshot()
        val step = state.reviewStep ?: error("当前没有待核对的步骤")
        val draft = evidence?.takeIf { it.id == state.draftId } ?: error("本步证据尚未准备好")
        check(job == null && state.guidePhase == DemoOperationGuidePhase.REVIEW) { "请等待当前整理完成" }
        recording.beginStepAnalysis(step.id, correction).getOrThrow()
        val selected = config
        if (selected == null || selected.apiKey.isBlank() || selected.model.isBlank() || selected.baseUrl.isBlank()) {
            recording.finishStepAnalysis(step.id, null, "模型配置不可用，请结束录制并在设置中配置图片模型")
            return@runCatching
        }
        val generation = ++requestGeneration
        job = scope.launch {
            var summary: String? = null
            var failure: String? = null
            try {
                summary = withContext(Dispatchers.IO) {
                    val provider = ProviderProfile.from(selected).createRuntimeProvider(
                        JavaNetDemoHttpTransport(), AnthropicRetryPolicy(maxAttempts = 1)
                    )
                    DemoOperationStepReviewer(provider).review(draft, step, correction.trim()) { frame ->
                        recording.frameFile(draft.id, frame.fileName)
                            ?.takeIf { it.length() in 1..(2L * 1024 * 1024) }?.readBytes()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                failure = "本步整理超时，证据已保留，请点击重新整理"
            } catch (error: DemoOperationStepReviewException) {
                failure = error.message
            } catch (_: Exception) {
                failure = "本步整理未完成，证据已保留，请检查图片模型配置后重试"
            } finally {
                if (generation == requestGeneration) job = null
            }
            if (generation == requestGeneration && recording.snapshot().draftId == draft.id) {
                recording.finishStepAnalysis(step.id, summary, failure)
            }
        }
    }

    private fun cancel() {
        requestGeneration++
        job?.cancel()
        job = null
    }
}
