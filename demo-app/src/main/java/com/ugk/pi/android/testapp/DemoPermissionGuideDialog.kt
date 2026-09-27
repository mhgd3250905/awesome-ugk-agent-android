package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class DemoPermissionGuideDialog(private val activity: Activity) {
    private var dialog: Dialog? = null
    private var shown: PermissionGuideStep? = null
    private var shownNotificationChannelBlocked = false
    private var shownRuntimeSettingsFallback = false
    private var shownPrimaryLabel: String? = null

    fun dismiss() { dialog?.dismiss(); dialog = null; shown = null }

    fun show(step: PermissionGuideStep, index: Int, total: Int,
             onPrimary: () -> Unit, onSkip: () -> Unit, onClose: () -> Unit,
             primaryLabel: String = if (step == PermissionGuideStep.ACCESSIBILITY) "同意并去开启" else "去开启",
             notificationChannelBlocked: Boolean = false,
             runtimeSettingsFallback: Boolean = false,
             onBackgroundBattery: () -> Unit = {},
             onBackgroundAutostart: () -> Unit = {}) {
        if (shown == step && shownNotificationChannelBlocked == notificationChannelBlocked &&
            shownRuntimeSettingsFallback == runtimeSettingsFallback && shownPrimaryLabel == primaryLabel && dialog?.isShowing == true) return
        dismiss()
        val root = LinearLayout(activity).apply {
            tag = "permission_guide_card"
            orientation = LinearLayout.VERTICAL
            background = Ui.asymmetricRounded(activity, TaskNoteUi.Paper, 28, 28, 0, 0)
            clipToOutline = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(18), activity.dp(24), activity.dp(16))
        }
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(20))
        }
        fun text(value: String, size: Float, id: String) = TextView(activity).apply {
            text = value; textSize = size; tag = id
            setTextColor(TaskNoteUi.Ink)
            setPadding(0, activity.dp(8), 0, activity.dp(8))
        }
        fun quiet(label: String, id: String, action: () -> Unit) = text(label, 14f, id).apply {
            gravity = Gravity.CENTER
            minHeight = activity.dp(48)
            setTextColor(TaskNoteUi.Secondary)
            setOnClickListener { action() }
        }
        val badge = TaskNoteUi.label(activity, if (step == PermissionGuideStep.BACKGROUND) "可选建议" else "使用准备")
        val progress = text("$index / $total", 13f, "permission_guide_progress").apply {
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        val close = quiet("以后再说", "permission_guide_close", onClose)
        val metadata = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge)
            addView(progress, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val top = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val naturalWidth = listOf(badge, progress, close).sumOf {
                    it.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    it.measuredWidth
                } + activity.dp(24)
                val stacked = naturalWidth > View.MeasureSpec.getSize(widthMeasureSpec)
                orientation = if (stacked) VERTICAL else HORIZONTAL
                val width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
                val weight = if (stacked) 0f else 1f
                val params = metadata.layoutParams as LinearLayout.LayoutParams
                if (params.width != width || params.weight != weight) {
                    metadata.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(metadata)
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        content.addView(top)
        val title = when (step) {
            PermissionGuideStep.ACCESSIBILITY -> "让助手帮你操作屏幕"
            PermissionGuideStep.OVERLAY -> "离开 App，也能看到进度"
            PermissionGuideStep.CAMERA -> "随手拍照，交给助手"
            PermissionGuideStep.NOTIFICATIONS -> "及时收到任务提醒"
            PermissionGuideStep.BACKGROUND -> "让后台任务少些中断"
        }
        content.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 25f, "permission_guide_title").apply { typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TaskNoteUi.owl(activity, 80), LinearLayout.LayoutParams(activity.dp(80), activity.dp(80)))
        })
        val explanation = when (step) {
            PermissionGuideStep.ACCESSIBILITY -> "开启无障碍后，助手可以读取屏幕，并按你的安排点击、输入和滑动。\n相关屏幕内容会发送至你配置的模型服务，用于处理任务。"
            PermissionGuideStep.OVERLAY -> "开启悬浮窗后，切换到其他 App 时也能查看任务进度，并继续对话。"
            PermissionGuideStep.CAMERA -> "允许使用相机后，可以拍照并把照片交给助手。"
            PermissionGuideStep.NOTIFICATIONS -> if (notificationChannelBlocked) {
                "系统通知已允许，但“Agent 醒目提醒”已关闭。开启后，可以收到任务进度和完成提醒。"
            } else "开启通知后，可以收到任务进度和完成提醒。"
            PermissionGuideStep.BACKGROUND -> "这些设置可减少后台中断，可能增加耗电，但不能保证任务一直运行。"
        }
        content.addView(text(explanation, 16f, "permission_guide_body").apply { setLineSpacing(0f, 1.3f) })
        if (runtimeSettingsFallback) content.addView(text("当前未获得权限，请在系统设置中开启。", 16f, "permission_guide_recovery"))
        val hint = when (step) {
            PermissionGuideStep.ACCESSIBILITY -> "在系统页面找到 UGK Agent，开启服务后返回。"
            PermissionGuideStep.OVERLAY -> "在系统页面找到 UGK Agent，打开开关后返回。"
            PermissionGuideStep.CAMERA -> "选图和导入文件无需额外存储授权。"
            PermissionGuideStep.NOTIFICATIONS -> null
            PermissionGuideStep.BACKGROUND -> null
        }
        hint?.let { content.addView(text(it, 13f, "permission_guide_hint")) }
        if (step == PermissionGuideStep.BACKGROUND) {
            content.addView(text("后台耗电\n在应用详情中进入电池或耗电管理，选择“无限制”或“允许后台运行”。", 15f, "background_battery_hint"))
            content.addView(TaskNoteUi.button(activity, "查看后台耗电设置", false, onBackgroundBattery).apply { tag = "background_battery_settings" })
            val autostartHint = if (Build.MANUFACTURER.equals("Huawei", ignoreCase = true)) {
                "在应用启动管理中找到 UGK Agent，关闭自动管理，并允许自启动及后台运行。"
            } else "在设置中搜索“自启动”或“应用启动管理”，找到 UGK Agent 并允许自启动及后台运行。"
            content.addView(text("自启动 / 启动管理\n$autostartHint", 15f, "background_autostart_hint"))
            content.addView(TaskNoteUi.button(activity, "查看自启动设置", false, onBackgroundAutostart).apply { tag = "background_autostart_settings" })
            content.addView(text("最近任务卡片锁定\n若手机支持，在最近任务中长按 UGK Agent 卡片并锁定，减少被一键清理。", 15f, "background_recents_hint"))
        }
        footer.addView(TaskNoteUi.button(activity, if (step == PermissionGuideStep.BACKGROUND) "我已了解，不再提醒" else primaryLabel, true, onPrimary).apply {
            tag = "permission_guide_primary"; minHeight = activity.dp(54)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        footer.addView(quiet("暂时跳过", "permission_guide_skip", onSkip),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(6) })
        val visible = Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        val budget = ((visible.height().takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels) * .84f).toInt()
        root.addView(object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                footer.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec((budget - footer.measuredHeight).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(content) })
        root.addView(footer)
        dialog = Dialog(activity, Ui.dialogTheme()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { onClose() }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(.44f)
                setGravity(Gravity.BOTTOM)
                attributes = attributes.apply { windowAnimations = 0 }
            }
            show()
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        shown = step
        shownNotificationChannelBlocked = notificationChannelBlocked
        shownRuntimeSettingsFallback = runtimeSettingsFallback
        shownPrimaryLabel = primaryLabel
        root.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.demo_dialog_enter_bottom))
    }
}
