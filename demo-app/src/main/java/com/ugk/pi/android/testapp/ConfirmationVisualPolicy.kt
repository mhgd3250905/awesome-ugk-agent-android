package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
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
    private val cancellationIds = setOf(
        "cancel",
        "deny",
        "no",
        "reject",
        "stop",
        "close",
        "abort",
        "dismiss",
        "later",
        "not_now"
    )

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

    fun isCancellation(button: UserConfirmationDialogButton): Boolean {
        val id = button.id.lowercase(Locale.ROOT)
        val label = button.label.trim().lowercase(Locale.ROOT)
        return id in cancellationIds || label in cancellationLabels
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
