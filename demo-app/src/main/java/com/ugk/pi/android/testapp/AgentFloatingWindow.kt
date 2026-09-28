package com.ugk.pi.android.testapp

import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.attention.UrgentMessage
import com.ugk.pi.attention.UrgentPresentationStatus
import com.ugk.pi.attention.UrgentAction
import com.ugk.pi.attention.UrgentForm
import java.util.ArrayDeque

/**
 * Cross-app, user-controlled Agent surface.
 *
 * The overlay is deliberately a renderer and interaction shell. Agent
 * execution is process-owned, while this class exposes only snapshots and
 * user intents through callbacks.
 */
class AgentFloatingWindow(private val context: Context) : ConfirmationOverlayHost {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private var expandedView: View? = null
    private var collapsedView: View? = null
    private var takeoverView: View? = null
    private var activeTakeoverPresentationId: String? = null
    private var takeoverInteractionConsumed = false
    private var contentContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var statusText: TextView? = null
    private var titleText: TextView? = null
    private var inputField: EditText? = null
    private var sendButton: SendActionButton? = null
    private var stopButton: SendActionButton? = null
    private var transcriptView: AgentOverlayTranscriptView? = null
    private var confirmationContainer: LinearLayout? = null
    private var renderedConfirmation: AgentOverlayConfirmation? = null
    private var queueView: TextView? = null
    private var emptyView: TextView? = null
    private var activityToggle: TextView? = null
    private var activityDetails: TextView? = null
    private var activityExpanded = false
    private var assistantPreview: String? = null
    private var imagePreview: android.app.Dialog? = null
    private var firstTranscriptRender = true
    private var collapsedIconView: ImageView? = null
    private var collapsedStatusText: TextView? = null

    private var snapshot = AgentOverlaySnapshot(
        title = "Agent",
        statusLabel = "就绪"
    )
    private val legacyLogs = ArrayDeque<String>()
    private var composerDraft = ""
    private var sendErrorText: String? = null
    private var pendingConfirmation: AgentOverlayConfirmation? = null
    private var confirmationResult: ((String) -> Unit)? = null
    private var urgentPreviousSurface: UrgentPreviousSurface = UrgentPreviousSurface.HIDDEN
    private var expandedX = dp(16)
    private var expandedY = dp(160)
    private var collapsedX = dp(16)
    private var collapsedY = dp(180)
    private var teachingActive = false
    private var surfaceTransition: android.animation.AnimatorSet? = null
    private var transitionOpening: Boolean? = null
    private var queuedOpening: Boolean? = null
    private var transitionGeneration = 0L
    private var transitionReadyCleanup: (() -> Unit)? = null
    private var transitionGeometryCleanup: (() -> Unit)? = null
    private var slideFromLeft = true
    private var preparingScreenOperation = false
    private var surfaceTransitionFinished: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var restoreBubbleAfterScreen = false

    /** Let the visible close finish before hiding pixels or dispatching a screen tool. */
    suspend fun prepareScreenOperation() {
        preparingScreenOperation = true
        queuedOpening = null
        check(pendingConfirmation == null && takeoverView == null) { "请先完成当前确认或提醒" }
        kotlinx.coroutines.withTimeout(5000) {
            surfaceTransitionFinished?.await()
            if (expandedView != null) collapseToBubble()
            surfaceTransitionFinished?.await()
            check(pendingConfirmation == null) { "请先完成当前确认" }
        }
        check(expandedView == null) { "悬浮窗尚未完成收起，未执行屏幕操作" }
        restoreBubbleAfterScreen = restoreBubbleAfterScreen || collapsedView != null
        // Remove the input window, not just its pixels/flags. A detached view cannot eat a tap.
        hideCollapsed(strict = true)
        check(collapsedView == null && expandedView == null) { "悬浮窗未移除，未执行屏幕操作" }
        kotlinx.coroutines.delay(120) // Allow the removed surface/input window to settle before capture.
    }

    fun finishScreenOperation() {
        preparingScreenOperation = false
        if (restoreBubbleAfterScreen) {
            restoreBubbleAfterScreen = false
            showCollapsed()
        }
    }

    /** Cancellation never runs completion actions or resurrects a hidden surface. */
    private fun cancelSurfaceTransition(cleanup: () -> Unit = {}) {
        transitionGeneration++
        queuedOpening = null
        transitionReadyCleanup?.invoke()
        transitionReadyCleanup = null
        val animator = surfaceTransition
        surfaceTransition = null
        transitionOpening = null
        val finished = surfaceTransitionFinished
        animator?.removeAllListeners()
        animator?.cancel()
        transitionGeometryCleanup?.invoke()
        transitionGeometryCleanup = null
        try { cleanup() } finally { finished?.complete(Unit) }
    }

    private fun outsideScreenX(width: Int): Int = if (slideFromLeft) -width - dp(16)
        else context.resources.displayMetrics.widthPixels + dp(16)

    /** Source slides completely out, then the destination slides in from that same edge. */
    private fun animateSurface(view: View, opening: Boolean, bubbleBounds: Rect, onEnd: () -> Unit = {}) {
        val generation = ++transitionGeneration
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        surfaceTransitionFinished = finished
        transitionOpening = opening
        val bubble = collapsedView
        val destination = if (opening) view else bubble
        val panelX = expandedX
        val bubbleX = bubbleBounds.left
        val screenWidth = context.resources.displayMetrics.widthPixels
        if (opening) slideFromLeft = bubbleBounds.exactCenterX() <= screenWidth / 2f
        val panelOutside = outsideScreenX(expandedParams.width)
        val bubbleOutside = outsideScreenX(collapsedParams.width)
        expandedParams.x = if (opening) panelOutside else panelX
        collapsedParams.x = if (opening) bubbleX else bubbleOutside
        expandedParams.flags = expandedWindowFlags(pendingConfirmation != null)
        runCatching { windowManager.updateViewLayout(view, expandedParams) }
        collapsedParams.flags = collapsedParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        bubble?.let { runCatching { windowManager.updateViewLayout(it, collapsedParams) } }
        // Destination is attached outside the screen, fully opaque from its first frame.
        transitionGeometryCleanup = {
            expandedParams.x = panelX
            collapsedParams.x = bubbleX
            collapsedParams.flags = collapsedParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            expandedParams.flags = expandedParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        }
        var completed = false
        fun complete() {
            if (completed) return
            completed = true
            if (generation != transitionGeneration) { finished.complete(Unit); return }
            try {
                onEnd()
            } finally {
                surfaceTransition = null
                transitionOpening = null
                transitionGeometryCleanup?.invoke()
                transitionGeometryCleanup = null
                collapsedView?.let { runCatching { windowManager.updateViewLayout(it, collapsedParams) } }
                updateExpandedWindowFlags(pendingConfirmation != null)
                if (opening) expandedView?.requestApplyInsets()
                val next = queuedOpening
                queuedOpening = null
                try {
                    if (pendingConfirmation != null) showExpanded()
                    else if (!preparingScreenOperation && next != null) {
                        if (next) showExpanded() else collapseToBubble()
                    }
                } finally { finished.complete(Unit) }
            }
        }
        fun startAnimation() {
            if (generation != transitionGeneration) return
            fun slide(target: View?, params: WindowManager.LayoutParams, from: Int, to: Int, time: Long) =
                android.animation.ValueAnimator.ofInt(from, to).apply {
                    duration = time
                    interpolator = android.view.animation.PathInterpolator(.22f, 1f, .36f, 1f)
                    addUpdateListener {
                        if (completed || generation != transitionGeneration || target == null) return@addUpdateListener
                        params.x = it.animatedValue as Int
                        runCatching { windowManager.updateViewLayout(target, params) }
                    }
                }
            val exit = if (opening) slide(bubble, collapsedParams, bubbleX, bubbleOutside, 180L)
                else slide(view, expandedParams, panelX, panelOutside, 200L)
            val enter = if (opening) slide(view, expandedParams, panelOutside, panelX, 240L)
                else slide(bubble, collapsedParams, bubbleOutside, bubbleX, 180L)
            val animator = android.animation.AnimatorSet().apply {
                playSequentially(exit, enter)
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = complete()
                })
            }
            surfaceTransition = animator
            animator.start()
        }
        if (destination == null) { complete(); return }
        var started = false
        val ready = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (started || generation != transitionGeneration) return true
                started = true
                transitionReadyCleanup?.invoke()
                transitionReadyCleanup = null
                startAnimation()
                return true
            }
        }
        // addView can precede attachment: Android transfers listeners from the floating
        // observer to the attached observer and kills the former. Remove from the current
        // observer, otherwise each frame would restart the movement.
        transitionReadyCleanup = {
            destination.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(ready)
        }
        destination.viewTreeObserver.addOnPreDrawListener(ready)
        destination.invalidate()
    }

    private var teachingCompletedSegments = 0
    private var teachingActions: LinearLayout? = null
    var onFinishTeaching: (() -> Unit)? = null
    private var externalAutomationMode = false
    private var preImeY: Int? = null
    private var preImeHeight: Int? = null
    private var imeBaseVisibleBottom: Int? = null
    private var imeLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var imeApplyPending: Runnable? = null
    private var overlayGestureActive = false

    var onSendMessage: ((String) -> Boolean)? = null
    var onStopAgent: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null
    var onDraftChanged: ((String) -> Unit)? = null
    internal var onUrgentInteraction: ((DemoUrgentInteraction) -> Boolean)? = null

    private val expandedParams = WindowManager.LayoutParams().apply {
        width = expandedWidth()
        height = expandedHeight()
        type = overlayType
        windowAnimations = 0
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.LEFT
        x = expandedX
        y = expandedY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

    private val collapsedParams = WindowManager.LayoutParams().apply {
        width = dp(112)
        height = dp(48)
        type = overlayType
        windowAnimations = 0
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.LEFT
        x = collapsedX
        y = collapsedY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
    }

    private val takeoverParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        type = overlayType
        windowAnimations = 0
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    fun show() {
        if (preparingScreenOperation) { restoreBubbleAfterScreen = true; return }
        if (takeoverView != null) {
            // MainActivity may have moved to the background while the takeover is open.
            if (urgentPreviousSurface == UrgentPreviousSurface.HIDDEN) {
                urgentPreviousSurface = UrgentPreviousSurface.COLLAPSED
            }
            return
        }
        if (!Settings.canDrawOverlays(context) || isShowing()) return
        showCollapsed()
    }

    fun showExpanded() {
        if (preparingScreenOperation && pendingConfirmation == null) { restoreBubbleAfterScreen = true; return }
        if (transitionOpening != null) {
            if (transitionOpening == false) queuedOpening = true
            return
        }
        if (!Settings.canDrawOverlays(context) || takeoverView != null) return
        if (expandedView != null) return

        val openingBounds = collapsedView?.let { bubble ->
            Rect(collapsedParams.x, collapsedParams.y,
                collapsedParams.x + (bubble.width.takeIf { it > 0 } ?: collapsedParams.width),
                collapsedParams.y + (bubble.height.takeIf { it > 0 } ?: collapsedParams.height))
        }
        collapsedX = collapsedParams.x
        collapsedY = collapsedParams.y
        preImeY = null
        preImeHeight = null
        expandedParams.width = clampExpandedWidth(expandedParams.width)
        expandedParams.height = clampExpandedHeight(expandedParams.height)
        expandedParams.x = clampX(collapsedX, expandedParams.width)
        expandedParams.y = clampY(collapsedY, expandedParams.height)
        expandedX = expandedParams.x
        expandedY = expandedParams.y
        expandedParams.flags = expandedWindowFlags(forceFocusable = pendingConfirmation != null)

        val view = buildExpandedView()
        if (openingBounds != null) {
            slideFromLeft = openingBounds.exactCenterX() <= context.resources.displayMetrics.widthPixels / 2f
            expandedParams.x = outsideScreenX(expandedParams.width)
            expandedParams.flags = expandedParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else slideFromLeft = expandedParams.x + expandedParams.width / 2 <= context.resources.displayMetrics.widthPixels / 2
        if (addViewSafely(view, expandedParams)) {
            expandedView = view
            attachImeAvoidance(view)
            renderSnapshot()
            openingBounds?.let { animateSurface(view, true, it) { hideCollapsed(strict = true) } }
        } else {
            expandedParams.x = expandedX
            expandedParams.flags = expandedWindowFlags(forceFocusable = pendingConfirmation != null)
            collapsedX = expandedX
            collapsedY = expandedY
            showCollapsed()
        }
    }

    fun hide() {
        restoreBubbleAfterScreen = false
        // Returning to the Activity cancels restoration, not an in-flight tool guard.
        cancelSurfaceTransition {
            urgentPreviousSurface = UrgentPreviousSurface.HIDDEN
            hideTakeover()
            hideExpanded()
            hideCollapsed()
        }
    }

    /** Keep an urgent takeover visible when the main Activity returns to the foreground. */
    fun hideOrdinaryForActivity() {
        if (takeoverView != null) {
            urgentPreviousSurface = UrgentPreviousSurface.HIDDEN
        } else {
            hide()
        }
    }

    fun isShowing(): Boolean = expandedView != null || collapsedView != null || takeoverView != null

    internal fun isTeachingChatShowing(): Boolean = teachingActive && expandedView != null

    internal fun hasBlockingPresentation(): Boolean = takeoverView != null || pendingConfirmation != null

    /** Temporarily replaces the ordinary bubble/chat surface with an app-owned screen. */
    fun showUrgentMessage(message: UrgentMessage, conversationId: String? = null): UrgentPresentationStatus {
        if (!Settings.canDrawOverlays(context)) return UrgentPresentationStatus.PERMISSION_DENIED
        if (pendingConfirmation != null || externalAutomationMode || takeoverView != null) {
            return UrgentPresentationStatus.BUSY
        }
        if ((message.actions.isNotEmpty() || message.form != null) &&
            (message.binding == null || conversationId == null || onUrgentInteraction == null)
        ) return UrgentPresentationStatus.UNAVAILABLE
        val previous = when {
            expandedView != null -> UrgentPreviousSurface.EXPANDED
            collapsedView != null -> UrgentPreviousSurface.COLLAPSED
            else -> UrgentPreviousSurface.HIDDEN
        }
        val view = UrgentTakeoverView.build(
            context,
            message,
            onClose = { dismissUrgentMessage() },
            onOpenApp = {
                dismissUrgentMessage()
                onOpenApp?.invoke()
            },
            onAction = { action: UrgentAction ->
                deliverUrgentInteraction(message, conversationId, DemoUrgentInteraction.Kind.BUTTON, action.id, action.label)
            },
            onFormSubmit = { form: UrgentForm, value: String ->
                deliverUrgentInteraction(message, conversationId, DemoUrgentInteraction.Kind.FORM, form.id, form.label, value)
            }
        )
        cancelSurfaceTransition {
            hideExpanded()
            hideCollapsed()
        }
        takeoverParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                if (message.form == null) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0
        takeoverParams.softInputMode = if (message.form == null) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if (!addViewSafely(view, takeoverParams)) {
            restoreSurface(previous)
            return UrgentPresentationStatus.FAILED
        }
        urgentPreviousSurface = previous
        takeoverView = view
        activeTakeoverPresentationId = message.binding?.presentationId
        takeoverInteractionConsumed = false
        return UrgentPresentationStatus.SHOWN
    }

    private fun deliverUrgentInteraction(
        message: UrgentMessage,
        conversationId: String?,
        kind: DemoUrgentInteraction.Kind,
        controlId: String,
        controlLabel: String,
        value: String? = null
    ): Boolean {
        val binding = message.binding ?: return false
        val target = conversationId ?: return false
        if (takeoverView == null || takeoverInteractionConsumed ||
            activeTakeoverPresentationId != binding.presentationId
        ) return false
        val controlBelongsToScreen = when (kind) {
            DemoUrgentInteraction.Kind.BUTTON -> message.actions.any {
                it.id == controlId && it.label == controlLabel
            }
            DemoUrgentInteraction.Kind.FORM -> message.form?.let {
                it.id == controlId && it.label == controlLabel
            } == true
        }
        if (!controlBelongsToScreen) return false
        val callback = onUrgentInteraction ?: return false
        takeoverInteractionConsumed = true
        val accepted = runCatching {
            callback(DemoUrgentInteraction(
                binding = binding,
                conversationId = target,
                title = message.title,
                kind = kind,
                controlId = controlId,
                controlLabel = controlLabel,
                value = value
            ))
        }.getOrDefault(false)
        if (!accepted) {
            takeoverInteractionConsumed = false
            return false
        }
        if (takeoverView != null) dismissUrgentMessage()
        return true
    }

    private fun dismissUrgentMessage() {
        val previous = urgentPreviousSurface
        hideTakeover()
        urgentPreviousSurface = UrgentPreviousSurface.HIDDEN
        restoreSurface(previous)
    }

    private fun hideTakeover() {
        takeoverView?.let { view ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            removeViewSafely(view)
        }
        takeoverView = null
        activeTakeoverPresentationId = null
        takeoverInteractionConsumed = false
    }

    private fun restoreSurface(previous: UrgentPreviousSurface) {
        when (previous) {
            UrgentPreviousSurface.HIDDEN -> Unit
            UrgentPreviousSurface.COLLAPSED -> showCollapsed()
            UrgentPreviousSurface.EXPANDED -> {
                collapsedX = expandedX
                collapsedY = expandedY
                showExpanded()
            }
        }
    }

    private enum class UrgentPreviousSurface { HIDDEN, COLLAPSED, EXPANDED }

    override fun showConfirmation(
        request: UserConfirmationDialogRequest,
        onResult: (String) -> Unit
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (takeoverView != null) dismissUrgentMessage()
        confirmationResult = onResult
        pendingConfirmation = request.toOverlayConfirmation()
        snapshot = snapshot.copy(pendingConfirmation = pendingConfirmation)
        if (transitionOpening == false) {
            // A confirmation cannot wait behind a queued hide/collapse.
            cancelSurfaceTransition {
                hideCollapsed()
                expandedView?.let {
                    it.scaleX = 1f; it.scaleY = 1f
                    it.translationX = 0f; it.translationY = 0f
                    it.requestApplyInsets()
                }
            }
        }
        if (expandedView == null) showExpanded()
        updateExpandedWindowFlags(forceFocusable = true)
        renderSnapshot()
        return expandedView != null
    }

    override fun hideConfirmation() {
        pendingConfirmation = null
        confirmationResult = null
        snapshot = snapshot.copy(pendingConfirmation = null)
        if (externalAutomationMode && expandedView != null) {
            collapseToBubble()
        } else {
            renderSnapshot()
        }
    }

    /** Render a stable, complete snapshot with bounded confirmation summaries. */
    fun bindSnapshot(value: AgentOverlaySnapshot) {
        if (teachingActive) return
        applySnapshot(value)
    }

    fun bindTeachingSnapshot(value: AgentOverlaySnapshot) {
        if (!teachingActive) return
        applySnapshot(value)
        // Teaching owns its session even while idle; only an active run needs automation mode.
        if (externalAutomationMode != value.isBusy) setExternalAutomationMode(value.isBusy)
    }

    private fun applySnapshot(value: AgentOverlaySnapshot) {
        if (snapshot.conversationId != value.conversationId ||
            (snapshot.runId != null && snapshot.runId != value.runId)) {
            assistantPreview = null
            activityExpanded = false
        }
        if (!value.isBusy) assistantPreview = null
        val confirmation = pendingConfirmation ?: value.pendingConfirmation
        pendingConfirmation = confirmation
        snapshot = value.copy(
            steps = value.steps.toList(),
            messages = value.messages.map { it.copy(imagePaths = it.imagePaths.toList()) },
            process = value.process?.copy(steps = value.process.steps.toList()),
            pendingConfirmation = confirmation
        )
        renderSnapshot()
    }

    /** Matches the main chat's throttled assistant stream without persisting a second message. */
    fun setAssistantPreview(text: String?) {
        if (teachingActive) return
        if (assistantPreview == text) return
        assistantPreview = text
        renderSnapshot()
    }

    /** Legacy bridge retained for callers that update only a status label. */
    fun setStatus(text: String) {
        if (teachingActive) return
        snapshot = snapshot.copy(statusLabel = text, statusDetail = text)
        renderSnapshot()
    }

    /** Legacy bridge retained for callers that append an activity line. */
    fun addLog(text: String) {
        if (teachingActive) return
        if (text.isNotBlank()) {
            legacyLogs.addLast(text)
            while (legacyLogs.size > 40) legacyLogs.removeFirst()
        }
        renderSnapshot()
    }

    fun clear() {
        if (takeoverView != null) dismissUrgentMessage()
        legacyLogs.clear()
        activityExpanded = false
        assistantPreview = null
        sendErrorText = null
        composerDraft = ""
        inputField?.setText("")
        onDraftChanged?.invoke("")
        pendingConfirmation = null
        confirmationResult = null
        urgentPreviousSurface = UrgentPreviousSurface.HIDDEN
        snapshot = snapshot.copy(
            statusLabel = "Agent 就绪",
            statusDetail = null,
            latestMessage = null,
            latestMessageRole = null,
            messages = emptyList(),
            process = null,
            runId = null,
            steps = emptyList(),
            isBusy = false,
            queuedMessages = 0,
            pendingConfirmation = null
        )
        renderSnapshot()
    }

    /** Keep input available while busy so new messages can be queued. */
    fun setSending(sending: Boolean) {
        snapshot = snapshot.copy(isBusy = sending)
        renderSnapshot()
    }

    /** Select teaching conversation content and controls; surface behavior stays shared. */
    fun setTeachingState(active: Boolean, completedSegments: Int = 0) {
        if (active && !teachingActive) {
            externalAutomationMode = false
            composerDraft = ""
            inputField?.setText("")
            assistantPreview = null
        }
        teachingActive = active
        teachingCompletedSegments = completedSegments
        teachingActions?.visibility = if (active) View.VISIBLE else View.GONE
        expandedView?.findViewWithTag<View>("overlay-open-app")?.visibility = View.VISIBLE
        expandedView?.findViewWithTag<View>("overlay-collapse")?.visibility = View.VISIBLE
        expandedView?.findViewWithTag<View>("overlay-hide")?.contentDescription = "隐藏 Agent 悬浮窗"
        renderSnapshot()
    }

    fun setExternalAutomationMode(active: Boolean) {
        val effectiveActive = if (teachingActive) snapshot.isBusy else active
        val enteringAutomation = effectiveActive && !externalAutomationMode
        if (effectiveActive && takeoverView != null) dismissUrgentMessage()
        externalAutomationMode = effectiveActive
        if (enteringAutomation && expandedView != null && pendingConfirmation == null) {
            collapseToBubble()
        }
        if (expandedView == null) {
            preImeY = null
            preImeHeight = null
        }
        updateExpandedWindowFlags(forceFocusable = pendingConfirmation != null)
    }

    /** Synchronize the hidden overlay composer with the main Activity draft. */
    fun setComposerDraft(value: String) {
        if (teachingActive) return
        if (composerDraft == value && inputField?.text?.toString() == value) return
        composerDraft = value
        inputField?.let { field ->
            if (field.text?.toString() != value) field.setText(value)
            field.setSelection(field.length())
        }
        onDraftChanged?.invoke(composerDraft)
    }

    private fun updateCollapsedDimensions() {
        collapsedParams.width = dp(112)
        collapsedParams.height = dp(48)
    }

    private fun showCollapsed(startOutside: Boolean = false) {
        if (collapsedView != null) return
        updateCollapsedDimensions()
        collapsedParams.x = if (startOutside) outsideScreenX(collapsedParams.width) else clampX(collapsedX, collapsedParams.width)
        collapsedParams.y = clampY(collapsedY, collapsedParams.height)
        collapsedParams.flags = collapsedParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        if (startOutside) collapsedParams.flags = collapsedParams.flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val view = buildCollapsedView()
        if (addViewSafely(view, collapsedParams)) collapsedView = view else hideCollapsed()
    }

    private fun hideCollapsed(strict: Boolean = false) {
        collapsedView?.let {
            if (strict) {
                windowManager.removeViewImmediate(it)
                check(!it.isAttachedToWindow) { "悬浮球尚未移除" }
            } else removeViewSafely(it)
        }
        collapsedView = null
        collapsedIconView = null
        collapsedStatusText = null
    }

    private fun hideExpanded(strict: Boolean = false) {
        imagePreview?.dismiss()
        imagePreview = null
        inputField?.let { field ->
            composerDraft = field.text?.toString().orEmpty()
            onDraftChanged?.invoke(composerDraft)
        }
        inputField?.let { field ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(field.windowToken, 0)
        }
        // Persist the user-intended position and height: the window may
        // currently sit at an IME avoidance offset or a compressed height
        // that must not leak into later expands.
        expandedY = preImeY ?: expandedY
        expandedParams.height = preImeHeight ?: expandedParams.height
        preImeY = null
        preImeHeight = null
        detachImeAvoidance()
        // A programmatic removal ends any in-flight touch gesture: no UP/CANCEL
        // will arrive for a detached view, so the suppression flag must reset here.
        overlayGestureActive = false
        expandedView?.let { view ->
            if (strict) windowManager.removeViewImmediate(view) else removeViewSafely(view)
        }
        expandedView = null
        contentContainer = null
        scrollView = null
        statusText = null
        titleText = null
        inputField = null
        sendButton = null
        stopButton = null
        teachingActions = null
        transcriptView = null
        confirmationContainer = null
        renderedConfirmation = null
        queueView = null
        emptyView = null
        activityToggle = null
        activityDetails = null
        firstTranscriptRender = true
    }

    private fun collapseToBubble() {
        if (pendingConfirmation != null) return
        if (transitionOpening != null) {
            if (transitionOpening == true) queuedOpening = false
            return
        }
        if (expandedView == null) return
        expandedX = expandedParams.x
        // Collapse from the user-intended position, not the IME-avoided one.
        // Only the y anchor is consumed here; the height anchor is left for
        // hideExpanded() so the compressed IME height does not leak into the
        // next expand.
        expandedY = preImeY ?: expandedParams.y
        preImeY = null
        // Resolve the shared bubble size before anchoring the common animation.
        updateCollapsedDimensions()
        collapsedX = clampX(if (slideFromLeft) expandedX else expandedX + expandedParams.width - collapsedParams.width,
            collapsedParams.width)
        collapsedY = clampY(expandedY, collapsedParams.height)
        val closingBounds = Rect(collapsedX, collapsedY,
            collapsedX + collapsedParams.width, collapsedY + collapsedParams.height)
        val outgoing = expandedView
        if (outgoing != null) {
            inputField?.let { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0) }
            showCollapsed(startOutside = true)
            if (collapsedView == null) return
            animateSurface(outgoing, false, closingBounds) { hideExpanded(strict = true) }
        } else {
            hideExpanded()
            showCollapsed()
        }
    }

    enum class CollapsedDisplayState(
        val label: String,
        val colorProvider: () -> Int
    ) {
        RUNNING("运行中", { Ui.Primary }),
        CONFIRMING("待确认", { Ui.Warning }),
        COMPLETED("完成", { Ui.Success }),
        FAILED("失败", { Ui.Danger }),
        IDLE("就绪", { Ui.TextMuted })
    }

    private fun resolveCollapsedState(): CollapsedDisplayState {
        if (snapshot.pendingConfirmation != null || snapshot.statusLabel.contains("确认")) {
            return CollapsedDisplayState.CONFIRMING
        }
        if (snapshot.isBusy) {
            return CollapsedDisplayState.RUNNING
        }
        val label = snapshot.statusLabel
        return when {
            label.contains("失败") || label.contains("错误") || label.contains("停止") || label.contains("取消") ->
                CollapsedDisplayState.FAILED
            label.contains("完成") || label.contains("成功") ->
                CollapsedDisplayState.COMPLETED
            else -> CollapsedDisplayState.IDLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCollapsedView(): View {
        val state = resolveCollapsedState()
        // A NOT_TOUCHABLE overlay is made translucent by WindowManager. Consume animation
        // touches locally instead; outside this small window remains normally interactive.
        val root = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                if (transitionOpening != null) true else super.dispatchTouchEvent(event)
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ui.rounded(context, Ui.SurfaceElevated, 19, Ui.OutlineSubtle)
            elevation = dp(3).toFloat()
            contentDescription = "Agent 悬浮窗 (${state.label})，点击展开"
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.brand_owl_avatar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            background = null
            contentDescription = "绿色猫头鹰助手，${state.label}"
        }
        collapsedIconView = icon

        val label = TextView(context).apply {
            text = state.label
            textSize = 12f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(state.colorProvider())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, 0, 0)
        }
        collapsedStatusText = label

        root.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
        root.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setupDrag(root, root, collapsedParams) {
            showExpanded()
        }
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExpandedView(): View {
        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                if (transitionOpening != null && pendingConfirmation == null) true
                else super.dispatchTouchEvent(event)
        }.apply {
            background = Ui.rounded(context, Ui.ConversationCanvas, 20, Ui.OutlineSubtle)
            clipChildren = true
            clipToOutline = true
        }
        val contentRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val resizeHandle = ResizeCornerHandle(context).apply {
            contentDescription = "从右下角拖动调整 Agent 悬浮窗大小"
            isClickable = true
            isFocusable = true
        }
        root.addView(resizeHandle, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END or Gravity.BOTTOM))
        setupResize(resizeHandle, root)
        root.addView(contentRoot, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(8), 0, 0, dp(4))
        }
        val headerText = LinearLayout(context).apply {
            tag = "overlay-header-drag"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, 0, dp(4), 0)
            contentDescription = "拖动标题移动悬浮窗"
        }
        titleText = TextView(context).apply {
            tag = "overlay-title"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Ui.TextPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusText = TextView(context).apply {
            textSize = 11f
            setTextColor(Ui.TextSecondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        headerText.addView(titleText, LinearLayout.LayoutParams(-1, -2))
        headerText.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(headerIcon(R.drawable.ic_overlay_open_in_new, "打开完整 Agent 主界面", "overlay-open-app") {
            onOpenApp?.invoke()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(headerIcon(R.drawable.ic_note_close, "隐藏 Agent 悬浮窗", "overlay-hide") {
            onHide?.invoke()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(headerIcon(R.drawable.ic_process_expand_more, "收起 Agent 悬浮窗", "overlay-collapse") {
            collapseToBubble()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        contentRoot.addView(header, LinearLayout.LayoutParams(-1, -2))
        teachingActions = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = if (teachingActive) View.VISIBLE else View.GONE
            addView(TextView(context).apply {
                text = "停止本段"; textSize = 13f; setTextColor(Ui.TextPrimary)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { onStopAgent?.invoke() }
            })
            addView(TextView(context).apply {
                text = "结束教学"; textSize = 13f; setTextColor(Ui.Primary)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { onFinishTeaching?.invoke() }
            })
        }
        contentRoot.addView(teachingActions, LinearLayout.LayoutParams(-1, -2))
        setupDrag(headerText, root, expandedParams) { }

        scrollView = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Ui.ConversationCanvas)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }
        contentContainer = container
        confirmationContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "overlay-confirmation"
        }
        container.addView(confirmationContainer, LinearLayout.LayoutParams(-1, -2))
        transcriptView = AgentOverlayTranscriptView(context).apply {
            onImageOpen = { path ->
                imagePreview?.dismiss()
                imagePreview = showFullImageDialog(context, path, overlay = true)
            }
        }
        container.addView(transcriptView, LinearLayout.LayoutParams(-1, -2))
        emptyView = TextView(context).apply {
            text = "在这里继续对话"
            textSize = 13f
            setTextColor(Ui.TextSecondary)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        container.addView(emptyView, LinearLayout.LayoutParams(-1, -2))
        queueView = TextView(context).apply {
            tag = "overlay-queue"
            textSize = 11f
            setTextColor(Ui.TextSecondary)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        container.addView(queueView, LinearLayout.LayoutParams(-1, -2))
        activityToggle = TextView(context).apply {
            tag = "overlay-activity-toggle"
            textSize = 11f
            setTextColor(Ui.TextSecondary)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.clickableRounded(context, Color.TRANSPARENT, Ui.SurfaceSoft, 8)
            isFocusable = true
            setOnClickListener { activityExpanded = !activityExpanded; renderSnapshot() }
        }
        container.addView(activityToggle, LinearLayout.LayoutParams(-1, -2))
        activityDetails = TextView(context).apply {
            tag = "overlay-activity-details"
            textSize = 11f
            setTextColor(Ui.TextSecondary)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.1f)
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        container.addView(activityDetails, LinearLayout.LayoutParams(-1, -2))
        scrollView?.addView(container, ViewGroup.LayoutParams(-1, -2))
        contentRoot.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.BOTTOM
            background = Ui.rounded(context, Ui.SurfaceElevated, 24)
            setPadding(dp(6), dp(4), dp(4), dp(4))
        }
        inputField = EditText(context).apply {
            tag = "overlay-input"
            hint = "发消息"
            setHintTextColor(Ui.TextMuted)
            setTextColor(Ui.TextPrimary)
            textSize = 13f
            minLines = 1
            maxLines = 4
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setPadding(dp(10), dp(8), dp(6), dp(8))
            contentDescription = "给 Agent 输入消息"
            setText(composerDraft)
            setSelection(length())
        }
        inputField?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                composerDraft = text?.toString().orEmpty()
                sendErrorText = null
                onDraftChanged?.invoke(composerDraft)
                renderSnapshot()
            }
            override fun afterTextChanged(editable: android.text.Editable?) = Unit
        })
        sendButton = SendActionButton(context).apply {
            tag = "overlay-send"
            contentDescription = "发送悬浮窗消息"
            installTapAction(this) { sendInput() }
        }
        stopButton = SendActionButton(context).apply {
            tag = "overlay-stop"
            buttonState = SendActionButton.State.BUSY
            contentDescription = "停止 Agent 当前任务"
            installTapAction(this) { onStopAgent?.invoke() }
        }
        composer.addView(inputField, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        composer.addView(stopButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        contentRoot.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return root
    }

    private fun headerIcon(resource: Int, description: String, viewTag: String, action: () -> Unit) =
        ImageView(context).apply {
            tag = viewTag
            setImageResource(resource)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextSecondary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            contentDescription = description
            if (Build.VERSION.SDK_INT >= 26) tooltipText = description
            background = Ui.clickableRounded(context, Color.TRANSPARENT, Ui.SurfaceSoft, 12)
            installTapAction(this, action)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTapAction(target: View, action: () -> Unit) {
        target.isClickable = true
        target.isFocusable = true
        target.setOnClickListener { if (target.isEnabled) action() }
        target.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var moved = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                if (!view.isEnabled) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY; moved = false; view.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.rawX - downX) > touchSlop() ||
                        kotlin.math.abs(event.rawY - downY) > touchSlop()) {
                        moved = true; view.isPressed = false
                    }
                    MotionEvent.ACTION_UP -> { view.isPressed = false; if (!moved) view.performClick() }
                    MotionEvent.ACTION_CANCEL -> { moved = true; view.isPressed = false }
                }
                return true
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun actionButton(
        label: String,
        description: String,
        foregroundColor: Int = Ui.TextSecondary,
        pressedForegroundColor: Int = foregroundColor,
        backgroundColor: Int = Ui.SurfaceSoft,
        pressedBackgroundColor: Int = Ui.SurfaceSoft,
        strokeColor: Int = Ui.OutlineSubtle,
        disabledForegroundColor: Int = Ui.DisabledContent,
        disabledBackgroundColor: Int? = null,
        disabledStrokeColor: Int? = null,
        minHeightDp: Int = 48,
        action: () -> Unit
    ): TextView =
        TextView(context).apply {
            text = label
            textSize = 11.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.stateColorList(
                normal = foregroundColor,
                pressed = pressedForegroundColor,
                disabled = disabledForegroundColor
            ))
            gravity = Gravity.CENTER
            minWidth = dp(42)
            minHeight = dp(minHeightDp)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = Ui.clickableRounded(
                context,
                backgroundColor,
                pressedBackgroundColor,
                10,
                strokeColor,
                disabledColor = disabledBackgroundColor,
                disabledStrokeColor = disabledStrokeColor
            )
            contentDescription = description
            setOnClickListener { action() }
            // A swipe that starts on a button must stay a window drag, not
            // accidentally invoke Hide/Collapse when the finger is lifted.
            setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var moved = false

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            moved = false
                            view.isPressed = true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!moved && (
                                kotlin.math.abs(event.rawX - downX) > touchSlop() ||
                                    kotlin.math.abs(event.rawY - downY) > touchSlop()
                                )
                            ) {
                                moved = true
                                view.isPressed = false
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val shouldClick = !moved
                            view.isPressed = false
                            if (shouldClick) view.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            moved = true
                            view.isPressed = false
                        }
                    }
                    return true
                }
            })
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize(handle: View, windowRoot: View) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0
            private var initialHeight = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = expandedParams.width
                        initialHeight = expandedParams.height
                        touchX = event.rawX
                        touchY = event.rawY
                        view.isPressed = true
                        overlayGestureActive = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val nextWidth = clampExpandedWidth(
                            initialWidth + (event.rawX - touchX).toInt()
                        )
                        val nextHeight = clampExpandedHeight(
                            initialHeight + (event.rawY - touchY).toInt()
                        )
                        if (nextWidth != expandedParams.width || nextHeight != expandedParams.height) {
                            // Only an actual resize is a user-intent position change; a bare
                            // tap on the handle must not drop the pre-IME restore anchor.
                            // Dropping the height anchor too makes the resized height the
                            // accepted baseline; the post-gesture re-apply re-runs avoidance
                            // against it.
                            preImeY = null
                            preImeHeight = null
                            expandedParams.width = nextWidth
                            expandedParams.height = nextHeight
                            expandedParams.x = clampX(expandedParams.x, nextWidth)
                            expandedParams.y = clampY(expandedParams.y, nextHeight)
                            expandedX = expandedParams.x
                            expandedY = expandedParams.y
                            runCatching {
                                windowManager.updateViewLayout(windowRoot, expandedParams)
                            }.onFailure {
                                Log.w(TAG, "Unable to resize Agent overlay window", it)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        overlayGestureActive = false
                        reapplyImeAvoidanceAfterUserGesture()
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun sendInput() {
        val field = inputField ?: return
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val previousLog = legacyLogs.lastOrNull()
        val accepted = onSendMessage?.invoke(text) == true
        if (!accepted) {
            sendErrorText = legacyLogs.lastOrNull()?.takeIf { it != previousLog }
                ?: "消息未发送，请稍后重试"
            renderSnapshot()
            return
        }
        composerDraft = ""
        field.setText("")
        onDraftChanged?.invoke("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun expandedWindowFlags(forceFocusable: Boolean): Int {
        val focusFlag = if (externalAutomationMode && !forceFocusable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            0
        }
        val touchFlag = if (transitionOpening != null)
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0
        return WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or focusFlag or touchFlag
    }

    private fun updateExpandedWindowFlags(forceFocusable: Boolean) {
        expandedParams.flags = expandedWindowFlags(forceFocusable)
        expandedView?.let { view ->
            runCatching {
                windowManager.updateViewLayout(view, expandedParams)
            }.onFailure {
                Log.w(TAG, "Unable to update Agent overlay focus mode", it)
            }
        }
    }

    /**
     * Keeps the expanded panel readable while typing. Overlay windows do
     * not receive reliable system resize behavior, so the IME is detected
     * here and the window is shifted locally instead.
     *
     * API 30+ reads dispatched IME window insets, which the system sends to
     * this overlay because its own composer EditText is the IME target.
     * API 24-29 falls back to the visible display frame difference, which
     * does not reflect the IME for this window type on every shell and is
     * best effort only.
     */
    private fun attachImeAvoidance(root: View) {
        detachImeAvoidance()
        if (Build.VERSION.SDK_INT >= 30) {
            attachImeAvoidanceModern(root)
        } else {
            val listener = ViewTreeObserver.OnGlobalLayoutListener { handleImeGlobalLayout() }
            imeLayoutListener = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
    }

    /**
     * Every onApply dispatch merely caches the insets and re-arms a debounced
     * apply: during an IME animation the per-frame insets are computed from a
     * stale window frame for this overlay and would collapse the keyboard-top
     * estimate if applied directly (the shift is not idempotent against a
     * stale inset). Once dispatches settle (no new frame for
     * [IME_SETTLE_DELAY_MS]), the cached insets match the current window
     * frame, so the single apply lands on the true IME top; the window's own
     * move re-dispatches and the next settle converges the target. No
     * WindowInsetsAnimation.Callback may be attached here: registering one
     * makes the system strip the ime inset values from this overlay's
     * dispatches entirely.
     */
    @RequiresApi(30)
    private fun attachImeAvoidanceModern(root: View) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            scheduleImeAvoidanceApply(root, insets)
            // Return the insets unconsumed so child dispatch continues
            // with the default behavior.
            insets
        }
    }

    @RequiresApi(30)
    private fun scheduleImeAvoidanceApply(root: View, insets: WindowInsets) {
        imeApplyPending?.let { root.removeCallbacks(it) }
        val task = Runnable {
            imeApplyPending = null
            handleImeInsets(insets)
        }
        imeApplyPending = task
        root.postDelayed(task, IME_SETTLE_DELAY_MS)
    }

    private fun detachImeAvoidance() {
        if (Build.VERSION.SDK_INT >= 30) {
            imeApplyPending?.let { task -> expandedView?.removeCallbacks(task) }
            imeApplyPending = null
            expandedView?.setOnApplyWindowInsetsListener(null)
        } else {
            val listener = imeLayoutListener
            if (listener != null) {
                expandedView?.viewTreeObserver?.takeIf { it.isAlive }
                    ?.removeOnGlobalLayoutListener(listener)
            }
        }
        imeLayoutListener = null
        imeBaseVisibleBottom = null
    }

    /** API 30+ path: IME state read from the insets dispatched to this overlay. */
    private fun handleImeInsets(insets: WindowInsets) {
        // A drag or resize gesture owns the position until the finger lifts.
        if (overlayGestureActive || transitionOpening != null) return
        if (!insets.isVisible(WindowInsets.Type.ime())) {
            restoreFromImeAvoidance()
            return
        }
        val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
        if (imeBottom <= 0) {
            // IME visible but the inset value is stripped for this overlay on
            // most dispatches (only the visibility flag is reliable). A zero
            // inset also covers a panel already sitting fully above the IME,
            // where the estimate simply computes no shift needed.
            applyImeAvoidance(estimatedImeTopParent())
            return
        }
        applyImeAvoidance(
            ImeAvoidance.imeTopParent(expandedParams.y, expandedParams.height, imeBottom)
        )
    }

    /**
     * Fallback IME top when the system strips the ime inset values: typical
     * IMEs occupy a stable fraction of the display, so a proportional
     * estimate keeps the composer visible instead of freezing the panel
     * under the keyboard.
     */
    private fun estimatedImeTopParent(): Int =
        context.resources.displayMetrics.heightPixels * IME_HEIGHT_FRACTION_NUMERATOR /
            IME_HEIGHT_FRACTION_DENOMINATOR

    /** API 24-29 fallback path; depends on visibleDisplayFrame reflecting the IME. */
    private fun handleImeGlobalLayout() {
        val root = expandedView ?: return
        // A drag or resize gesture owns the position until the finger lifts.
        if (overlayGestureActive || transitionOpening != null) return
        val rect = Rect()
        root.getWindowVisibleDisplayFrame(rect)
        val base = imeBaseVisibleBottom
        if (base == null) {
            imeBaseVisibleBottom = rect.bottom
            return
        }
        if (base - rect.bottom > dp(IME_VISIBLE_THRESHOLD_DP)) {
            applyImeAvoidance(rect.bottom)
        } else {
            // Refresh the baseline while no IME is considered visible so
            // system bar changes do not register as a keyboard.
            imeBaseVisibleBottom = rect.bottom
            restoreFromImeAvoidance()
        }
    }

    private fun applyImeAvoidance(imeTop: Int) {
        if (transitionOpening != null) return
        val root = expandedView ?: return
        val decision = ImeAvoidance.avoidanceDecision(
            windowTop = expandedParams.y,
            windowHeight = expandedParams.height,
            imeTop = imeTop,
            minY = dp(48),
            margin = dp(IME_AVOIDANCE_MARGIN_DP),
            minHeight = minExpandedHeight(),
            maxHeight = maxExpandedHeight()
        )
        if (decision.targetY == expandedParams.y &&
            decision.targetHeight == expandedParams.height
        ) return
        if (preImeY == null) preImeY = expandedY
        if (preImeHeight == null) preImeHeight = expandedParams.height
        expandedY = decision.targetY
        expandedParams.y = decision.targetY
        expandedParams.height = decision.targetHeight
        runCatching {
            windowManager.updateViewLayout(root, expandedParams)
        }.onFailure {
            Log.w(TAG, "Unable to shift Agent overlay above the IME", it)
        }
    }

    private fun restoreFromImeAvoidance() {
        if (transitionOpening != null) return
        val root = expandedView ?: return
        val restoreY = preImeY
        val restoreHeight = preImeHeight
        if (restoreY == null && restoreHeight == null) return
        preImeY = null
        preImeHeight = null
        // Clamp the height first: the y clamp bound depends on it.
        val targetHeight = clampExpandedHeight(restoreHeight ?: expandedParams.height)
        val targetY = clampY(restoreY ?: expandedParams.y, targetHeight)
        if (targetY == expandedParams.y && targetHeight == expandedParams.height) return
        expandedY = targetY
        expandedParams.y = targetY
        expandedParams.height = targetHeight
        runCatching {
            windowManager.updateViewLayout(root, expandedParams)
        }.onFailure {
            Log.w(TAG, "Unable to restore Agent overlay position after the IME", it)
        }
    }

    /**
     * Re-runs avoidance right after a drag or resize gesture releases the
     * window: the gesture suspends automatic shifts so the finger keeps
     * full control, and this closes the gap when the released position
     * still overlaps a visible IME.
     */
    private fun reapplyImeAvoidanceAfterUserGesture() {
        val root = expandedView ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            // Same decisions as handleImeInsets, polled from the latest
            // dispatched insets: exact value when present, estimate when the
            // system strips the ime inset value for this overlay.
            val insets = root.rootWindowInsets ?: return
            if (!insets.isVisible(WindowInsets.Type.ime())) {
                // The IME closed mid-gesture: its restore dispatch was
                // swallowed by the gesture guard, so restore here.
                restoreFromImeAvoidance()
                return
            }
            val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (imeBottom <= 0) {
                applyImeAvoidance(estimatedImeTopParent())
                return
            }
            applyImeAvoidance(
                ImeAvoidance.imeTopParent(expandedParams.y, expandedParams.height, imeBottom)
            )
            return
        }
        val base = imeBaseVisibleBottom
        val rect = Rect()
        root.getWindowVisibleDisplayFrame(rect)
        if (base == null || base - rect.bottom <= dp(IME_VISIBLE_THRESHOLD_DP)) {
            restoreFromImeAvoidance()
            return
        }
        applyImeAvoidance(rect.bottom)
    }

    private fun renderSnapshot() {
        val collapsedState = resolveCollapsedState()
        collapsedIconView?.apply {
            contentDescription = "绿色猫头鹰助手，${collapsedState.label}"
            background = null
        }
        collapsedStatusText?.apply {
            text = collapsedState.label
            setTextColor(collapsedState.colorProvider())
        }
        collapsedView?.contentDescription = "Agent 悬浮窗 (${collapsedState.label})，点击展开"
        expandedView?.findViewWithTag<View>("overlay-open-app")?.visibility = View.VISIBLE
        expandedView?.findViewWithTag<View>("overlay-collapse")?.visibility = View.VISIBLE
        expandedView?.findViewWithTag<View>("overlay-hide")?.contentDescription = "隐藏 Agent 悬浮窗"
        titleText?.apply { text = if (teachingActive) "教学中 · 已完成 $teachingCompletedSegments 段" else snapshot.title; contentDescription = text }
        statusText?.apply {
            text = snapshot.statusLabel
            setTextColor(if (snapshot.statusLabel.contains("失败")) Ui.Danger else Ui.TextSecondary)
        }
        stopButton?.visibility = if (snapshot.isBusy) View.VISIBLE else View.GONE
        sendButton?.let { button ->
            val canSend = !inputField?.text?.toString()?.trim().isNullOrEmpty() && onSendMessage != null
            button.isEnabled = canSend
            button.buttonState = if (canSend) SendActionButton.State.ACTIVE else SendActionButton.State.DISABLED
            button.contentDescription = if (snapshot.isBusy) "发送并排队" else "发送悬浮窗消息"
        }
        inputField?.hint = if (teachingActive) "描述下一步，或告诉我怎么纠正" else if (snapshot.isBusy) "发消息（排队）" else "发消息"
        if (expandedView == null) return
        val scroll = scrollView ?: return
        val wasAtBottom = !scroll.canScrollVertically(1)
        val confirmationChanged = renderedConfirmation != snapshot.pendingConfirmation
        if (confirmationChanged) {
            confirmationContainer?.removeAllViews()
            snapshot.pendingConfirmation?.let { value -> confirmationContainer?.let { addConfirmation(it, value) } }
            renderedConfirmation = snapshot.pendingConfirmation
        }
        confirmationContainer?.visibility = if (snapshot.pendingConfirmation != null) View.VISIBLE else View.GONE
        val messagesChanged = transcriptView?.render(snapshot, assistantPreview) == true
        emptyView?.visibility = if (transcriptView?.childCount == 0 && snapshot.pendingConfirmation == null) View.VISIBLE else View.GONE
        queueView?.apply {
            val textValue = sendErrorText ?: if (snapshot.queuedMessages > 0) {
                "已排队 ${snapshot.queuedMessages} 条，当前任务结束后继续"
            } else ""
            if (text.toString() != textValue) text = textValue
            setTextColor(if (sendErrorText != null) Ui.Warning else Ui.TextSecondary)
            visibility = if (textValue.isNotEmpty()) View.VISIBLE else View.GONE
        }
        activityToggle?.apply {
            visibility = if (legacyLogs.isNotEmpty()) View.VISIBLE else View.GONE
            text = if (activityExpanded) "收起活动记录" else "活动记录 · ${legacyLogs.size}"
            val arrow = context.getDrawable(R.drawable.ic_process_expand_more)?.mutate()
            arrow?.setTint(Ui.TextSecondary)
            arrow?.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawables(null, null, arrow, null)
            contentDescription = if (activityExpanded) "活动记录已展开，点击收起" else "活动记录已收起，点击展开"
        }
        activityDetails?.apply {
            visibility = if (activityExpanded && legacyLogs.isNotEmpty()) View.VISIBLE else View.GONE
            if (activityExpanded) {
                val logText = legacyLogs.joinToString("\n")
                if (text.toString() != logText) text = logText
            }
        }
        val scrollToConfirmation = confirmationChanged && snapshot.pendingConfirmation != null
        if (scrollToConfirmation || firstTranscriptRender || (messagesChanged && wasAtBottom)) {
            scroll.post {
                if (scrollToConfirmation) scroll.scrollTo(0, 0)
                else scroll.scrollTo(0, ((contentContainer?.height ?: 0) - scroll.height).coerceAtLeast(0))
            }
        }
        firstTranscriptRender = false
    }

    private fun addConfirmation(
        container: LinearLayout,
        confirmation: AgentOverlayConfirmation
    ) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SurfaceSoft, 12, Ui.Warning)
            setPadding(dp(10), dp(10), dp(10), dp(8))
            contentDescription = "需要确认：${confirmation.title}"
        }
        card.addView(TextView(context).apply {
            text = "需要你的确认"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.WarningOnContainer)
        })
        card.addView(TextView(context).apply {
            text = confirmation.title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
            setPadding(0, dp(4), 0, dp(4))
        })
        card.addView(TextView(context).apply {
            text = confirmation.message
            textSize = 12f
            setTextColor(Ui.TextPrimary)
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(8))
        })
        confirmation.target?.let { target ->
            card.addView(TextView(context).apply {
                text = "目标 Tool：${target.toolName}"
                textSize = 12f
                setTextColor(Ui.TextPrimary)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
            card.addView(TextView(context).apply {
                text = "输入摘要：${target.inputSummary}"
                textSize = 11f
                setTextColor(Ui.TextSecondary)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(8))
            })
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = if (confirmation.buttons.size <= 2) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            gravity = Gravity.END
        }
        confirmation.buttons.forEachIndexed { index, button ->
            val visualRole = button.visualRole
            val action = actionButton(
                label = button.label,
                description = "确认：${button.label}",
                foregroundColor = when (visualRole) {
                    ConfirmationVisualRole.CANCEL -> Ui.TextSecondary
                    ConfirmationVisualRole.PRIMARY -> Ui.OnPrimary
                    ConfirmationVisualRole.WARNING -> Ui.WarningOnContainer
                    ConfirmationVisualRole.DANGER -> Ui.DangerOnContainer
                },
                pressedForegroundColor = when (visualRole) {
                    ConfirmationVisualRole.CANCEL -> Ui.TextPrimary
                    ConfirmationVisualRole.PRIMARY -> Ui.OnPrimary
                    ConfirmationVisualRole.WARNING -> Ui.WarningOnContainer
                    ConfirmationVisualRole.DANGER -> Ui.OnDanger
                },
                backgroundColor = when (visualRole) {
                    ConfirmationVisualRole.CANCEL -> Ui.SurfaceSubtle
                    ConfirmationVisualRole.PRIMARY -> Ui.Primary
                    ConfirmationVisualRole.WARNING -> Ui.WarningSoft
                    ConfirmationVisualRole.DANGER -> Ui.DangerSoft
                },
                pressedBackgroundColor = when (visualRole) {
                    ConfirmationVisualRole.CANCEL -> Ui.SurfaceSoft
                    ConfirmationVisualRole.PRIMARY -> Ui.PrimaryPressed
                    ConfirmationVisualRole.WARNING -> Ui.SurfaceSoft
                    ConfirmationVisualRole.DANGER -> Ui.Danger
                },
                strokeColor = when (visualRole) {
                    ConfirmationVisualRole.CANCEL -> Ui.OutlineSubtle
                    ConfirmationVisualRole.PRIMARY -> Ui.Primary
                    ConfirmationVisualRole.WARNING -> Ui.Warning
                    ConfirmationVisualRole.DANGER -> Ui.Danger
                },
                minHeightDp = 48
            ) {
                selectConfirmation(button.id)
            }
            val params = if (confirmation.buttons.size <= 2) {
                LinearLayout.LayoutParams(0, dp(48), 1f)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                )
            }
            if (index > 0) {
                if (confirmation.buttons.size <= 2) params.marginStart = dp(4)
                else params.topMargin = dp(4)
            }
            buttonRow.addView(action, params)
        }
        card.addView(buttonRow)
        container.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
    }

    private fun selectConfirmation(buttonId: String) {
        val callback = confirmationResult
        confirmationResult = null
        pendingConfirmation = null
        snapshot = snapshot.copy(pendingConfirmation = null)
        renderSnapshot()
        callback?.invoke(buttonId)
        if (externalAutomationMode) {
            collapseToBubble()
        }
    }

    /**
     * A transparent touch target that strengthens the panel's existing
     * rounded bottom-right corner instead of adding an icon or a tile.
     */
    private inner class ResizeCornerHandle(context: Context) : View(context) {
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Ui.PrimaryPressed
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = dp(4).toFloat()
            val radius = dp(14).toFloat()
            val inset = dp(1).toFloat()
            cornerPaint.strokeWidth = stroke
            canvas.drawArc(
                RectF(
                    width - radius * 2 - inset,
                    height - radius * 2 - inset,
                    width - inset,
                    height - inset
                ),
                0f,
                90f,
                false,
                cornerPaint
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(
        dragTarget: View,
        windowRoot: View,
        params: WindowManager.LayoutParams,
        onClick: () -> Unit
    ) {
        dragTarget.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        if (params === expandedParams) overlayGestureActive = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop() || kotlin.math.abs(dy) > touchSlop())) {
                            dragging = true
                            // A confirmed drag makes the finger the intent for
                            // the position only: the finger never touches the
                            // height, so the pre-IME height baseline survives
                            // and the keyboard-closing restore still returns
                            // the user's original height. The post-gesture
                            // re-apply re-runs avoidance from the released
                            // geometry and records the released position.
                            if (params === expandedParams) {
                                preImeY = null
                            }
                        }
                        if (dragging) {
                            params.x = clampX(initialX + dx.toInt(), params.width)
                            params.y = clampY(initialY + dy.toInt(), params.height)
                            if (params === expandedParams) {
                                expandedX = params.x
                                expandedY = params.y
                            } else {
                                collapsedX = params.x
                                collapsedY = params.y
                            }
                            runCatching {
                                windowManager.updateViewLayout(windowRoot, params)
                            }.onFailure {
                                Log.w(TAG, "Unable to move Agent overlay window", it)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (params === expandedParams) {
                            overlayGestureActive = false
                            reapplyImeAvoidanceAfterUserGesture()
                        }
                        if (!dragging) onClick()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (params === expandedParams) {
                            overlayGestureActive = false
                            reapplyImeAvoidanceAfterUserGesture()
                        }
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun addViewSafely(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching {
            windowManager.addView(view, params)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to add Agent overlay window", it)
            false
        }

    private fun removeViewSafely(view: View) {
        runCatching { windowManager.removeView(view) }
    }

    private fun statusColor(status: String): Int = when {
        status.contains("失败") -> Ui.Danger
        status.contains("确认") || status.contains("等待") -> Ui.Warning
        status.contains("完成") -> Ui.Success
        else -> Ui.PrimaryPressed
    }

    private fun expandedWidth(): Int = clampExpandedWidth(dp(360))

    private fun expandedHeight(): Int = clampExpandedHeight(dp(520))

    private fun clampExpandedWidth(value: Int): Int = value.coerceIn(
        minExpandedWidth(),
        maxExpandedWidth()
    )

    private fun clampExpandedHeight(value: Int): Int = value.coerceIn(
        minExpandedHeight(),
        maxExpandedHeight()
    )

    private fun minExpandedWidth(): Int = minOf(dp(280), availableWidth())

    private fun maxExpandedWidth(): Int = availableWidth().coerceAtLeast(minExpandedWidth())

    private fun minExpandedHeight(): Int = minOf(dp(240), availableHeight())

    private fun maxExpandedHeight(): Int = availableHeight().coerceAtLeast(minExpandedHeight())

    private fun availableWidth(): Int = (context.resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(1))

    private fun availableHeight(): Int = (context.resources.displayMetrics.heightPixels - dp(56)).coerceAtLeast(dp(1))

    private fun clampX(value: Int, width: Int): Int = value.coerceIn(
        dp(8),
        (context.resources.displayMetrics.widthPixels - width - dp(8)).coerceAtLeast(dp(8))
    )

    private fun clampY(value: Int, height: Int): Int = value.coerceIn(
        dp(48),
        (context.resources.displayMetrics.heightPixels - height - dp(8)).coerceAtLeast(dp(48))
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun touchSlop(): Int = ViewConfiguration.get(context).scaledTouchSlop

    private companion object {
        const val TAG = "AgentFloatingWindow"
        const val IME_AVOIDANCE_MARGIN_DP = 8
        const val IME_VISIBLE_THRESHOLD_DP = 96
        const val IME_SETTLE_DELAY_MS = 150L
        const val IME_HEIGHT_FRACTION_NUMERATOR = 58
        const val IME_HEIGHT_FRACTION_DENOMINATOR = 100
    }
}
