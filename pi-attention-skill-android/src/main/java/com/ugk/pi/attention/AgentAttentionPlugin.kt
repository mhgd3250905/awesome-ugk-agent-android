package com.ugk.pi.attention

import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillMethod
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CancellationException
import java.util.UUID

/** Optional SDK capability for immediate notifications and host-rendered urgent messages. */
class AgentAttentionPlugin(
    private val notificationPublisher: AndroidNotificationPublisher,
    private val urgentPresenter: UrgentMessagePresenter? = null
) : AgentCapabilityPlugin {
    override val id: String = "android-attention"

    override fun tools(): List<AgentTool> = buildList {
        add(SendNotificationTool(notificationPublisher))
        urgentPresenter?.let { add(ShowUrgentMessageTool(notificationPublisher, it)) }
    }

    override fun agentInstructions(): List<String> = listOf(buildString {
        appendLine("This host can alert the user immediately outside the chat:")
        appendLine("- agent_send_notification(title, body) posts an Android notification. The host chooses the channel importance; Android and user settings decide whether a heads-up banner appears. Use it when the user asks to be notified or a background result needs an alert. An ordinary foreground chat reply does not need a duplicate notification.")
        if (urgentPresenter != null) {
            val urgentArguments = if (urgentPresenter is InteractiveUrgentMessagePresenter) {
                "title, body, reason, accent?, blocks?, actions?, form?"
            } else {
                "title, body, reason, accent?, blocks?"
            }
            appendLine("- agent_show_urgent_message($urgentArguments) posts that same notification and asks the host to present an interruptive screen. Use it only when the user expects an interruption for important, time-sensitive information. Optional accent is amber/green/blue/red; blocks is up to 8 ordered heading/paragraph/callout/bullet text elements. body is the notification summary and overlay fallback. The host always owns a close control. Do not also call agent_send_notification for the same event.")
            if (urgentPresenter is InteractiveUrgentMessagePresenter) {
                appendLine("- This host also supports optional actions (up to 3 real buttons with id and label) and one optional form (id, label, placeholder, submitLabel) on the urgent screen. Use these when the user should acknowledge or enter a short answer. The tool returns immediately; a click or form submission arrives later as a new SDK_EVENT turn in the same conversation. Never treat closing the screen as completing an action, and never claim a button was clicked until that event arrives. Do not request passwords or other secrets in the form.")
            }
        }
        appendLine("These methods act now; they do not schedule future work. For a future alert, use a separate scheduling capability only if the host registered one. Decide from the user's intent and the situation, not from keywords. Report the notification and overlay statuses returned by the tool; do not claim delivery or display when a status says otherwise.")
    }.trim())

    override fun skills(): List<AndroidSkill> = listOf(
        AndroidSkill(
            id = "android-attention",
            description = "通过宿主应用通知用户，必要时显示重要信息",
            instructions = """
                Send a notification when the user asked to be notified or a background result needs an alert. A foreground chat reply ordinarily needs no duplicate notification.
                Use the urgent message only for time-sensitive information the user would reasonably expect to interrupt their screen. For a richer urgent screen, choose an accent and up to 8 ordered text blocks (heading, paragraph, callout or bullet). The body remains a concise notification summary and fallback. The host renders the elements and always provides a close control.
                ${if (urgentPresenter is InteractiveUrgentMessagePresenter) "You may add up to 3 real action buttons and one plain-text form to an urgent screen. Give each control a stable short id. A user click or submission returns later as a new turn in the original conversation; the display tool itself never waits for it. Closing the screen is not an acknowledgement. Do not ask for secrets in this form." else ""}
                An urgent message also attempts a regular notification so the information can be reopened later.
                Do not call both methods for the same event.
                Never report delivery as successful when a tool returns a denied, blocked, unavailable or failed status.
                These tools publish now; they do not schedule a future run.
            """.trimIndent(),
            methods = buildList {
                add(AndroidSkillMethod(
                    toolName = "agent_send_notification",
                    purpose = "立即发送一条系统通知",
                    whenToUse = "用户要求提醒或需要在应用外保留可回看的执行结果",
                    resultSemantics = "posted 表示 Android 接受投递；具体显示形式仍由系统和用户设置决定"
                ))
                if (urgentPresenter != null) add(AndroidSkillMethod(
                    toolName = "agent_show_urgent_message",
                    purpose = if (urgentPresenter is InteractiveUrgentMessagePresenter)
                        "发送通知并显示重要提醒；可选按钮与单行表单，点击/提交后由宿主送回原对话"
                    else "发送通知，并尝试让宿主全屏展示重要消息；可选颜色与有限的内容元素由 Agent 指定",
                    whenToUse = "时间敏感且用户合理预期被打断时",
                    resultSemantics = "分别返回通知与悬浮展示状态；不可用时不要声称已展示"
                ))
            },
            triggers = listOf("通知", "提醒", "重要消息", "notification", "alert", "urgent")
        )
    )
}

private class SendNotificationTool(
    private val publisher: AndroidNotificationPublisher
) : AgentTool {
    override val name: String = "agent_send_notification"
    override val description: String = "Send an immediate Android notification through the host app."
    override val inputSchema: JsonObject = messageSchema()

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val message = call.input.readMessage() ?: return invalidInput(call)
        val delivery = publisher.publish(message)
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = "notification=${delivery.status.name.lowercase()}",
            isError = delivery.status != NotificationDeliveryStatus.POSTED,
            metadata = buildJsonObject {
                put("notification", delivery.status.name.lowercase())
                delivery.notificationId?.let { put("notificationId", it) }
            }
        )
    }
}

private class ShowUrgentMessageTool(
    private val publisher: AndroidNotificationPublisher,
    private val presenter: UrgentMessagePresenter
) : AgentTool {
    private val supportsInteractions = presenter is InteractiveUrgentMessagePresenter
    override val name: String = "agent_show_urgent_message"
    override val description: String = "Post a notification and ask the host to show an important message on screen now."
    override val inputSchema: JsonObject = messageSchema(withReason = true, withInteractions = supportsInteractions)

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val message = call.input.readMessage() ?: return invalidInput(call)
        if (message.body.length > MAX_URGENT_BODY_CHARS) return invalidInput(call)
        val reason = call.input.stringValue("reason")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_REASON_CHARS }
            ?: return invalidInput(call)
        val accent = call.input.stringValue("accent")?.let { value ->
            UrgentAccent.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: return invalidInput(call)
        } ?: UrgentAccent.AMBER
        val blocks = call.input.readUrgentBlocks() ?: return invalidInput(call)
        if (!supportsInteractions &&
            (call.input.declaresControl("actions") || call.input.declaresControl("form"))
        ) {
            return invalidInput(call)
        }
        val actions = call.input.readUrgentActions() ?: return invalidInput(call)
        val form = if (call.input.declaresControl("form")) {
            call.input.readUrgentForm() ?: return invalidInput(call)
        } else {
            null
        }
        if (form != null && actions.any { it.id == form.id }) return invalidInput(call)
        val presentationId = UUID.randomUUID().toString()
        val delivery = publisher.publish(message)
        val presentation = try {
            presenter.show(UrgentMessage(
                message.title,
                message.body,
                reason,
                accent,
                blocks,
                actions,
                form,
                UrgentPresentationBinding(presentationId, context.sessionId)
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            UrgentPresentation(UrgentPresentationStatus.FAILED)
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildString {
                append("notification=${delivery.status.name.lowercase()}; overlay=${presentation.status.name.lowercase()}")
                if (presentation.status == UrgentPresentationStatus.SHOWN) {
                    append("; presentationId=$presentationId")
                    if (actions.isNotEmpty() || form != null) append("; interaction=awaiting_user_event")
                }
            },
            isError = delivery.status != NotificationDeliveryStatus.POSTED ||
                presentation.status != UrgentPresentationStatus.SHOWN,
            metadata = buildJsonObject {
                put("notification", delivery.status.name.lowercase())
                delivery.notificationId?.let { put("notificationId", it) }
                put("overlay", presentation.status.name.lowercase())
                if (presentation.status == UrgentPresentationStatus.SHOWN) put("presentationId", presentationId)
            }
        )
    }
}

private fun JsonObject.readMessage(): AttentionMessage? {
    val title = stringValue("title")?.trim() ?: return null
    val body = stringValue("body")?.trim() ?: return null
    if (title.isEmpty() || title.length > MAX_TITLE_CHARS || body.isEmpty() || body.length > MAX_BODY_CHARS) {
        return null
    }
    return AttentionMessage(title, body)
}

/**
 * Reads a string field. A `null` is not screened here: every field reachable
 * through this reader is required by the schema, and an explicitly null
 * optional is refused one level up by [declaresControl] before it gets here.
 */
private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * An optional argument a gateway serialized as JSON `null` means the same thing
 * as one the model left out. POJO/Jackson-style endpoints emit `"blocks":null`
 * and `"offset":null` for fields they do not fill in, and the providers in this
 * SDK already read that shape as absent - a presence test written against the
 * raw map treats it as a request instead, and rejects the whole call.
 */
private fun JsonObject.optionalElement(key: String): JsonElement? =
    this[key]?.takeUnless { it is JsonNull }

/**
 * Optional ordered content for the urgent screen.
 *
 * Internal so the argument table can be executed by a host-side unit test: the
 * Tools that read it need an [AndroidNotificationPublisher], and that needs an
 * Android [android.content.Context].
 */
internal fun JsonObject.readUrgentBlocks(): List<UrgentContentBlock>? {
    val value = optionalElement("blocks") ?: return emptyList()
    val array = value as? JsonArray ?: return null
    if (array.size > MAX_URGENT_BLOCKS) return null
    return array.map { element ->
        val item = element as? JsonObject ?: return null
        if (item.keys.any { it != "type" && it != "text" }) return null
        val typeName = item.stringValue("type") ?: return null
        val type = UrgentBlockType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
            ?: return null
        val blockText = item.stringValue("text")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_URGENT_BLOCK_CHARS }
            ?: return null
        UrgentContentBlock(type, blockText)
    }
}

/** See [readUrgentBlocks] for why this is internal. */
internal fun JsonObject.readUrgentActions(): List<UrgentAction>? {
    val value = optionalElement("actions") ?: return emptyList()
    val array = value as? JsonArray ?: return null
    if (array.size > MAX_URGENT_ACTIONS) return null
    val actions = array.map { element ->
        val item = element as? JsonObject ?: return null
        if (item.keys.any { it != "id" && it != "label" }) return null
        val id = item.stringValue("id")?.takeIf(::validControlId) ?: return null
        val label = item.stringValue("label")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_ACTION_LABEL_CHARS }
            ?: return null
        UrgentAction(id, label)
    }
    return actions.takeIf { items -> items.map { it.id }.toSet().size == items.size }
}

/**
 * See [readUrgentBlocks] for why this is internal.
 *
 * A `null` and a wrong-shaped value both read as "no form" here; which of the
 * two it was is the caller's decision, made with [declaresControl], so a
 * declared-but-unusable form is refused rather than quietly dropped.
 */
internal fun JsonObject.readUrgentForm(): UrgentForm? {
    val item = optionalElement("form") as? JsonObject ?: return null
    if (item.keys.any { it !in setOf("id", "label", "placeholder", "submitLabel") }) return null
    val id = item.stringValue("id")?.takeIf(::validControlId) ?: return null
    val label = item.stringValue("label")?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_FORM_LABEL_CHARS }
        ?: return null
    val placeholder = if (item.declaresControl("placeholder")) {
        item.stringValue("placeholder")?.trim()
            ?.takeIf { it.length <= MAX_FORM_PLACEHOLDER_CHARS } ?: return null
    } else ""
    val submitLabel = item.stringValue("submitLabel")?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_FORM_SUBMIT_LABEL_CHARS }
        ?: return null
    return UrgentForm(id, label, placeholder, submitLabel)
}

/**
 * True when the model actually asked for this optional control, as opposed to an
 * endpoint filling the key with `null`.
 */
internal fun JsonObject.declaresControl(key: String): Boolean = optionalElement(key) != null

/** See [readUrgentBlocks] for why this is internal. */
internal fun validControlId(id: String): Boolean =
    id.length in 1..MAX_CONTROL_ID_CHARS &&
        (id[0] in 'A'..'Z' || id[0] in 'a'..'z') &&
        id.drop(1).all { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-'
        }

private fun invalidInput(call: ToolCall) = ToolResult(
    toolCallId = call.id,
    name = call.name,
    content = "Invalid notification or urgent presentation arguments; check required text, optional blocks and interactive controls",
    isError = true,
    metadata = buildJsonObject { put("status", "invalid_input") }
)

private fun messageSchema(
    withReason: Boolean = false,
    withInteractions: Boolean = false
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("title", buildJsonObject {
            put("type", "string")
            put("maxLength", MAX_TITLE_CHARS)
        })
        put("body", buildJsonObject {
            put("type", "string")
            put("maxLength", if (withReason) MAX_URGENT_BODY_CHARS else MAX_BODY_CHARS)
        })
        if (withReason) put("reason", buildJsonObject {
            put("type", "string")
            put("description", "Why this must interrupt the user now")
            put("maxLength", MAX_REASON_CHARS)
        })
        if (withReason) put("accent", buildJsonObject {
            put("type", "string")
            put("description", "Optional screen accent; defaults to amber")
            put("enum", buildJsonArray { UrgentAccent.entries.forEach { add(JsonPrimitive(it.name.lowercase())) } })
        })
        if (withReason) put("blocks", buildJsonObject {
            put("type", "array")
            put("description", "Optional ordered content for the urgent screen; replaces body there. Plain text only.")
            put("maxItems", MAX_URGENT_BLOCKS)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { UrgentBlockType.entries.forEach { add(JsonPrimitive(it.name.lowercase())) } })
                    })
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("maxLength", MAX_URGENT_BLOCK_CHARS)
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("type")); add(JsonPrimitive("text")) })
                put("additionalProperties", false)
            })
        })
        if (withInteractions) put("actions", buildJsonObject {
            put("type", "array")
            put("description", "Up to 3 clickable buttons. IDs are stable ASCII identifiers; a click returns later as a new conversation turn.")
            put("maxItems", MAX_URGENT_ACTIONS)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("maxLength", MAX_CONTROL_ID_CHARS)
                    })
                    put("label", buildJsonObject {
                        put("type", "string")
                        put("maxLength", MAX_ACTION_LABEL_CHARS)
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive("label")) })
                put("additionalProperties", false)
            })
        })
        if (withInteractions) put("form", buildJsonObject {
            put("type", "object")
            put("description", "One short plain-text answer field with a submit button. The host limits user input to 500 characters.")
            put("properties", buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("maxLength", MAX_CONTROL_ID_CHARS)
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("maxLength", MAX_FORM_LABEL_CHARS)
                })
                put("placeholder", buildJsonObject {
                    put("type", "string")
                    put("maxLength", MAX_FORM_PLACEHOLDER_CHARS)
                })
                put("submitLabel", buildJsonObject {
                    put("type", "string")
                    put("maxLength", MAX_FORM_SUBMIT_LABEL_CHARS)
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("id"))
                add(JsonPrimitive("label"))
                add(JsonPrimitive("submitLabel"))
            })
            put("additionalProperties", false)
        })
    })
    put("required", kotlinx.serialization.json.buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive("title"))
        add(kotlinx.serialization.json.JsonPrimitive("body"))
        if (withReason) add(kotlinx.serialization.json.JsonPrimitive("reason"))
    })
    put("additionalProperties", false)
}

private const val MAX_TITLE_CHARS = 120
private const val MAX_BODY_CHARS = 2_048
private const val MAX_URGENT_BODY_CHARS = 600
private const val MAX_REASON_CHARS = 240
private const val MAX_URGENT_BLOCKS = 8
private const val MAX_URGENT_BLOCK_CHARS = 240
private const val MAX_URGENT_ACTIONS = 3
private const val MAX_CONTROL_ID_CHARS = 32
private const val MAX_ACTION_LABEL_CHARS = 40
private const val MAX_FORM_LABEL_CHARS = 80
private const val MAX_FORM_PLACEHOLDER_CHARS = 80
private const val MAX_FORM_SUBMIT_LABEL_CHARS = 24
