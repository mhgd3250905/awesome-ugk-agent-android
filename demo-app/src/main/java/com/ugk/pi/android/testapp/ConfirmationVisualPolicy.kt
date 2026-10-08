package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationButtonIntent
import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.userConfirmationButtonIntent
import java.util.Locale

/**
 * Visual semantics for a confirmation action. This is intentionally separate
 * from the SDK's authorization decision: it only controls how the host makes
 * the decision legible to a user.
 */
enum class ConfirmationVisualRole {
    CANCEL,
    PRIMARY,
    WARNING,
    DANGER
}

/**
 * Shared, complete-request classification used before a request is reduced to
 * the bounded overlay model. The full target input is inspected here and is
 * never copied into [AgentOverlayConfirmation].
 */
object ConfirmationVisualPolicy {
    private val cancellationLabels = setOf(
        "取消",
        "拒绝",
        "否",
        "停止",
        "关闭",
        "稍后",
        "cancel",
        "deny",
        "no",
        "reject",
        "stop",
        "close",
        "later"
    )

    private val dangerousToolNames = setOf(
        "clipboard_clear",
        "send_sms",
        "send_sms_message",
        "delete_file",
        "delete_directory",
        "delete_folder",
        "publish_content",
        "payment",
        "make_payment"
    )

    private val dangerMarkers = listOf(
        "删除",
        "覆盖",
        "发布",
        "支付",
        "敏感",
        "机密",
        "短信",
        "不可逆",
        "危险",
        "清空剪贴板",
        "清除剪贴板",
        "clipboard_clear",
        "clipboard clear",
        "send_sms",
        "send sms",
        "sensitive",
        "secret",
        "credential",
        "password",
        "private_key",
        "api_key",
        "irreversible",
        "dangerous",
        "high impact",
        "delete",
        "overwrite",
        "publish",
        "payment",
        "release",
        "deploy",
        "force push",
        "rm ",
        "rm-",
        "drop ",
        "truncate "
    )

    private val ordinaryToolNames = setOf(
        "launch_android_app",
        "open_android_app",
        "screen_read",
        "screen_find",
        "screen_query",
        "screen_snapshot",
        "read_current_screen"
    )

    private val ordinaryMarkers = listOf(
        "打开应用",
        "打开页面",
        "查看",
        "读取屏幕",
        "读取当前屏幕",
        "预览",
        "可撤销",
        "撤销",
        "重试",
        "open app",
        "open page",
        "view",
        "read-only",
        "read only",
        "preview",
        "reversible",
        "undo",
        "retry"
    )

    private val dangerousTerminalScriptPattern = Regex(
        """(?i)(rm\s+-[rRfF]*|sudo\s+|mkfs(?:\s|["}]|$)|dd\s+if=|shutdown(?:\s|["}]|$)|reboot(?:\s|["}]|$)|poweroff(?:\s|["}]|$)|curl[^\n]*\|\s*(?:ba)?sh|wget[^\n]*\|\s*(?:ba)?sh|chmod\s+777|chown\s+|drop\s+table|truncate\s+)"""
    )

    fun classify(
        request: UserConfirmationDialogRequest,
        button: UserConfirmationDialogButton
    ): ConfirmationVisualRole {
        if (button.isCancellationButton()) return ConfirmationVisualRole.CANCEL

        val toolName = request.target?.toolName.orEmpty().lowercase(Locale.ROOT)
        val fullText = buildString {
            append(request.title)
            append('\n')
            append(request.message)
            append('\n')
            append(toolName)
            append('\n')
            append(request.target?.input?.toString().orEmpty())
            append('\n')
            append(button.label)
        }.lowercase(Locale.ROOT)

        if (toolName in dangerousToolNames || dangerMarkers.any(fullText::contains)) {
            return ConfirmationVisualRole.DANGER
        }
        if (toolName.contains("terminal") && dangerousTerminalScriptPattern.containsMatchIn(fullText)) {
            return ConfirmationVisualRole.DANGER
        }
        val contextText = buildString {
            append(request.title)
            append('\n')
            append(request.message)
            append('\n')
            append(toolName)
            append('\n')
            append(button.label)
        }.lowercase(Locale.ROOT)
        if (toolName in ordinaryToolNames || ordinaryMarkers.any(contextText::contains)) {
            return ConfirmationVisualRole.PRIMARY
        }
        // Confirmation requests without a reliable reversible-operation signal
        // must remain visibly cautious instead of looking like a recommendation.
        return ConfirmationVisualRole.WARNING
    }

    fun classifyButtons(
        request: UserConfirmationDialogRequest,
        buttons: List<UserConfirmationDialogButton> = request.buttons
    ): List<ConfirmationVisualRole> = buttons.map { classify(request, it) }

    /**
     * Whether this button should be drawn as the refusal.
     *
     * The id is asked of the SDK's own vocabulary instead of a copy kept here. That copy
     * also compared differently: the button a user taps because it is drawn as Cancel has to
     * be the very id the protected Tool reports back as their decline - including when the
     * model spelled it `Not_Now` or `" cancel "` - or the answer is a dialog that comes back
     * on top of the decision they already made. The label vocabulary stays the host's own,
     * because it is about what a user reads, not about what the SDK will honour.
     */
    fun isCancellation(button: UserConfirmationDialogButton): Boolean {
        when (userConfirmationButtonIntent(button.id)) {
            UserConfirmationButtonIntent.DECLINED -> return true
            // An id the SDK will honour as the user's approval cannot be drawn as the
            // refusal, whatever the label says. The protected Tool reads the id and nothing
            // else, so painting it as the "no" button would promise the user a refusal and
            // then run the operation: {"id":"OK","label":"取消"} is exactly that, and before
            // the id comparison was normalised it only took the exact lowercase "ok" to hit
            // it. A button may be drawn DANGER for the same operation; it may not be drawn
            // as the answer the SDK will not obey.
            UserConfirmationButtonIntent.ACCEPTED -> return false
            UserConfirmationButtonIntent.UNRECOGNIZED -> Unit
        }
        val label = button.label.trim().lowercase(Locale.ROOT)
        return label in cancellationLabels
    }
}

fun UserConfirmationDialogButton.isCancellationButton(): Boolean =
    ConfirmationVisualPolicy.isCancellation(this)

fun UserConfirmationDialogRequest.confirmationVisualRole(
    button: UserConfirmationDialogButton
): ConfirmationVisualRole = ConfirmationVisualPolicy.classify(this, button)

fun UserConfirmationDialogRequest.confirmationVisualRoles(
    buttons: List<UserConfirmationDialogButton> = this.buttons
): List<ConfirmationVisualRole> = ConfirmationVisualPolicy.classifyButtons(this, buttons)
