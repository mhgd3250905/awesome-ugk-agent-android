package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Native presentation for saved operations; authorization stays in the explicit button callbacks. */
internal object DemoWorkflowUi {
    fun isVerified(plan: DemoWorkflowPlan, records: List<DemoWorkflowRunRecord>): Boolean {
        val digest = plan.digest()
        return records.any { it.isTrial && it.status == "succeeded" && it.draftId == plan.draftId &&
            it.version == plan.version && it.planDigest == digest }
    }

    fun actionLabel(action: String): String = when (action) {
        "launch" -> "打开 App"
        "click" -> "点击"
        "long_click" -> "长按"
        "scroll_forward", "scroll_backward" -> "滚动页面"
        "back" -> "返回"
        "check" -> "检查画面"
        else -> "待核对操作"
    }

    fun conditionText(condition: DemoWorkflowCondition): String {
        val descriptions = condition.selectors.map { selector ->
            val label = selector.text ?: selector.description ?: "目标控件"
            val state = when (selector.checked) { true -> "已选中"; false -> "未选中"; null -> "可见" }
            "${if (label == "目标控件") label else "“$label”"}$state"
        }
        return (descriptions + listOfNotNull(condition.visualQuestion?.takeIf { it.isNotBlank() }))
            .joinToString("；")
    }

    fun stepCard(context: Context, index: Int, step: DemoWorkflowStep, appName: String,
                 onMenu: (View) -> Unit): LinearLayout = column(context).apply {
        background = Ui.rounded(context, Ui.Surface, 18)
        setPadding(context.dp(16), context.dp(12), context.dp(12), context.dp(16))
        tag = "workflow_step_${step.id}"
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(context, (index + 1).toString().padStart(2, '0'), 12f, TaskNoteUi.Primary, true),
            LinearLayout.LayoutParams(context.dp(30), -2))
        heading.addView(text(context, step.title, 17f, Ui.TextPrimary, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_process_more_horiz)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextSecondary)
            contentDescription = "第 ${index + 1} 步，编辑或删除"
            tag = "workflow_step_menu_${step.id}"
            background = Ui.clickableRounded(context, Ui.Surface, Ui.SurfaceSubtle, 24)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            setOnClickListener { onMenu(this) }
        }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        addView(heading)
        addView(text(context, "$appName · ${actionLabel(step.action)}", 12f, Ui.TextSecondary), space(context, 2))
        if (!step.postcondition.visualQuestion.isNullOrBlank()) addView(pill(context, "这里需要 AI 判断"),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(10) })
        addView(text(context, "完成时 · ${conditionText(step.postcondition)}", 13f, Ui.TextSecondary), space(context, 10))
    }

    fun runRecord(context: Context, record: DemoWorkflowRunRecord, currentPlan: DemoWorkflowPlan): LinearLayout =
        column(context).apply {
            setPadding(0, context.dp(12), 0, context.dp(12))
            tag = "workflow_record_${record.id}"
            val label = when (record.status) {
                "succeeded" -> "已完成"
                "failed" -> "未完成"
                "cancelled" -> "已停止"
                "interrupted" -> "已中断"
                else -> "进行中"
            }
            val sameVersion = record.version == currentPlan.version && record.planDigest == currentPlan.digest()
            addView(text(context, "${if (record.isTrial) "试跑" else "运行"} · $label${if (sameVersion) "" else " · 旧版本"}",
                15f, if (record.status == "succeeded") TaskNoteUi.Primary else Ui.TextPrimary, true))
            addView(text(context, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(record.startedAt)), 12f, Ui.TextMuted), space(context, 4))
            val elapsed = record.endedAt?.let { operationDuration(it - record.startedAt) } ?: "尚未结束"
            addView(text(context, "$elapsed · ${record.completedSteps}/${record.totalSteps} 步 · ${record.modelCalls} 次模型调用 · ${record.imagesSent} 张图片",
                12f, Ui.TextSecondary), space(context, 6))
            if (record.message.isNotBlank()) addView(text(context, record.message, 13f, Ui.TextSecondary), space(context, 6))
        }

    fun completionSheet(activity: Activity, initialGoal: String, initialCriteria: String, recompile: Boolean = false,
                        onChanged: (String, String) -> Unit, onConfirm: (String, String) -> Boolean): Dialog {
        val body = column(activity)
        val goal = input(activity, initialGoal, "这次操作要做什么", 1000, false).apply {
            tag = "workflow_intent_goal"
        }
        val criteria = input(activity, initialCriteria, "例如：出现“今日已签到”\n或能看到电量图表和屏幕使用时间", 1000, false).apply {
            tag = "workflow_intent_criteria"
        }
        watch(goal) { onChanged(it, criteria.text.toString()) }
        watch(criteria) { onChanged(goal.text.toString(), it) }
        body.addView(text(activity, "要做什么", 13f, TaskNoteUi.Secondary))
        body.addView(goal, space(activity, 6))
        body.addView(text(activity, "完成时应该看到", 13f, TaskNoteUi.Secondary), space(activity, 14))
        body.addView(criteria, space(activity, 6))
        body.addView(text(activity, if (recompile) "先保存在本机，下一步由你确认是否重新整理。" else "只保存在本机，尚未发送给 AI。",
            13f, TaskNoteUi.Secondary), space(activity, 14))
        return sheet(activity, "怎样算完成？", if (recompile) "重新说明结果" else "演示已保存", body,
            if (recompile) "保存并继续" else "保存说明", if (recompile) "取消" else "稍后填写") {
            val goalValue = goal.text.toString().trim()
            val criteriaValue = criteria.text.toString().trim()
            when {
                goalValue.isBlank() -> { goal.error = "填写这次操作的目标"; false }
                criteriaValue.isBlank() -> { criteria.error = "说明看到什么才算完成"; false }
                else -> onConfirm(goalValue, criteriaValue)
            }
        }
    }

    fun compileSheet(activity: Activity, draft: DemoOperationDraft, initialGoal: String, initialCriteria: String,
                     onChanged: (String, String) -> Unit, onConfirm: (String, String) -> Boolean): Dialog {
        val body = column(activity)
        val goal = input(activity, initialGoal, "这次操作要做什么", 1000, false).apply {
            tag = "workflow_compile_goal"
        }
        val criteria = input(activity, initialCriteria, "例如：出现“今日已签到”", 1000, false).apply {
            tag = "workflow_compile_criteria"
        }
        watch(goal) { onChanged(it, criteria.text.toString()) }
        watch(criteria) { onChanged(goal.text.toString(), it) }
        body.addView(text(activity, "操作目标", 13f, TaskNoteUi.Secondary))
        body.addView(goal, space(activity, 6))
        body.addView(text(activity, "怎样算完成", 13f, TaskNoteUi.Secondary), space(activity, 14))
        body.addView(criteria, space(activity, 6))
        body.addView(text(activity, "${draft.events.size} 条事件 · ${draft.frames.size} 张关键画面", 13f, TaskNoteUi.Secondary), space(activity, 16))
        body.addView(text(activity, "确认后，目标、完成标准、页面文字与关键画面将发送至当前 API。整理后可审阅，再决定是否试跑。",
            14f, TaskNoteUi.Secondary), space(activity, 6))
        return sheet(activity, "整理成操作", "让演示变成步骤", body, "确认并整理") {
            val goalValue = goal.text.toString().trim()
            val criteriaValue = criteria.text.toString().trim()
            when {
                goalValue.isBlank() -> { goal.error = "填写这次操作的目标"; false }
                criteriaValue.isBlank() -> { criteria.error = "说明看到什么才算完成"; false }
                else -> onConfirm(goalValue, criteriaValue)
            }
        }
    }

    fun overviewEditor(activity: Activity, plan: DemoWorkflowPlan,
                       onSave: (DemoWorkflowPlan) -> Boolean): Dialog {
        val body = column(activity)
        val title = input(activity, plan.title, "操作名称", 120, true).apply { tag = "workflow_edit_title" }
        val goal = input(activity, plan.goal, "希望完成的目标", 1000, false).apply { tag = "workflow_edit_goal" }
        body.addView(text(activity, "名称", 13f, TaskNoteUi.Secondary))
        body.addView(title, space(activity, 6))
        body.addView(text(activity, "目标", 13f, TaskNoteUi.Secondary), space(activity, 14))
        body.addView(goal, space(activity, 6))
        body.addView(text(activity, "保存后生成新版本，需要重新试跑。", 13f, TaskNoteUi.Secondary), space(activity, 14))
        return sheet(activity, "编辑操作", "补充目标", body, "保存新版本") {
            val newTitle = title.text.toString().trim()
            val newGoal = goal.text.toString().trim()
            when {
                newTitle.isBlank() -> { title.error = "填写操作名称"; false }
                newGoal.isBlank() -> { goal.error = "填写操作目标"; false }
                else -> onSave(plan.copy(title = newTitle, goal = newGoal))
            }
        }
    }

    fun stepEditor(activity: Activity, plan: DemoWorkflowPlan, index: Int,
                   onSave: (DemoWorkflowPlan) -> Boolean): Dialog {
        val step = plan.steps[index]
        val body = column(activity)
        val title = input(activity, step.title, "这一步要做什么", 200, true).apply { tag = "workflow_edit_step_title" }
        body.addView(text(activity, "步骤名称", 13f, TaskNoteUi.Secondary))
        body.addView(title, space(activity, 6))
        val initialText = step.postcondition.selectors.mapNotNull { it.text ?: it.description }.joinToString("\n")
        val completion = input(activity, initialText, "每行一项，例如：今日已签到", 2400, false).apply {
            tag = "workflow_edit_condition_text"
        }
        body.addView(text(activity, "完成时出现的文字", 13f, TaskNoteUi.Secondary), space(activity, 14))
        body.addView(completion, space(activity, 6))
        body.addView(text(activity, if (step.postcondition.selectors.isNotEmpty())
            "修改文字会保留原有控件和选中状态要求；删行会移除对应检查。无文字的页面检查会继续保留。"
            else "每行一项，页面需要同时出现这些文字。", 12f, TaskNoteUi.Secondary), space(activity, 6))
        val visual = CheckBox(activity).apply {
            text = "需要 AI 判断完成画面"
            setTextColor(TaskNoteUi.Ink); textSize = 14f
            minHeight = activity.dp(48)
            isChecked = !step.postcondition.visualQuestion.isNullOrBlank()
            buttonTintList = android.content.res.ColorStateList.valueOf(TaskNoteUi.Primary)
            tag = "workflow_edit_visual_check"
        }
        val question = input(activity, step.postcondition.visualQuestion.orEmpty(), "让 AI 检查什么？", 500, false).apply {
            tag = "workflow_edit_visual_question"
            visibility = if (visual.isChecked) View.VISIBLE else View.GONE
        }
        visual.setOnCheckedChangeListener { _, checked -> question.visibility = if (checked) View.VISIBLE else View.GONE }
        body.addView(visual, space(activity, 8))
        body.addView(question, space(activity, 4))
        body.addView(text(activity, "只调整这一步的说明与完成条件，保存后重新试跑。", 13f, TaskNoteUi.Secondary), space(activity, 14))
        return sheet(activity, "编辑第 ${index + 1} 步", "检查完成条件", body, "保存新版本") {
            val newTitle = title.text.toString().trim()
            val newText = completion.text.toString().trim()
            val lines = newText.lines().map(String::trim).filter(String::isNotEmpty)
            val visualQuestion = question.text.toString().trim().takeIf { visual.isChecked && it.isNotBlank() }
            val selectors = if (newText == initialText.trim()) step.postcondition.selectors
                else editConditionText(step.postcondition.selectors, lines)
            when {
                newTitle.isBlank() -> { title.error = "填写步骤名称"; false }
                selectors.size > 8 || lines.any { it.length > 300 } -> { completion.error = "完成检查合计最多 8 项，每项文字不超过 300 字"; false }
                visual.isChecked && visualQuestion == null -> { question.error = "填写需要判断的问题"; false }
                selectors.isEmpty() && visualQuestion == null -> { completion.error = "至少保留一种完成检查"; false }
                else -> {
                    val updated = step.copy(title = newTitle, postcondition = step.postcondition.copy(
                        selectors = selectors, visualQuestion = visualQuestion))
                    onSave(plan.copy(steps = plan.steps.mapIndexed { i, value -> if (i == index) updated else value }))
                }
            }
        }
    }

    /** Text-only editing must not silently drop an existing identity or checked-state constraint. */
    private fun editConditionText(original: List<DemoWorkflowSelector>, lines: List<String>): List<DemoWorkflowSelector> {
        val fixed = original.filter { it.text == null && it.description == null }
        val remaining = original.filter { it.text != null || it.description != null }.toMutableList()
        // Match untouched lines first, so deleting/reordering a line keeps the other
        // conditions attached to their original controls rather than their row index.
        val unchanged = lines.map { line ->
            val index = remaining.indexOfFirst { (it.text ?: it.description) == line }
            if (index >= 0) remaining.removeAt(index) else null
        }
        return lines.mapIndexed { index, line ->
            val selector = unchanged[index] ?: remaining.firstOrNull()?.also { remaining.removeAt(0) }
            when {
                selector == null -> DemoWorkflowSelector(text = line)
                selector.text != null -> selector.copy(text = line)
                else -> selector.copy(description = line)
            }
        } + fixed
    }

    fun runConfirmation(activity: Activity, plan: DemoWorkflowPlan, isTrial: Boolean,
                        appLabel: (String) -> String, onConfirm: () -> Boolean): Dialog {
        val body = column(activity)
        body.addView(text(activity, plan.title, 20f, TaskNoteUi.Ink, true))
        body.addView(text(activity, plan.goal, 14f, TaskNoteUi.Secondary), space(activity, 6))
        val packages = plan.steps.flatMap { listOf(it.packageName, it.postcondition.packageName) }.distinct()
        fun row(label: String, value: String) {
            body.addView(text(activity, label, 12f, TaskNoteUi.Secondary), space(activity, 14))
            body.addView(text(activity, value, 15f, TaskNoteUi.Ink), space(activity, 4))
        }
        row("完成标准", plan.completionCriteria.ifBlank { "尚未填写" })
        row("目标 App", packages.joinToString("、", transform = appLabel))
        row("${plan.steps.size} 个步骤 · 动作范围", plan.steps.map { actionLabel(it.action) }.distinct().joinToString("、"))
        row("画面分析", "必要判断或偏差恢复时，将当前页面发送至当前 API。")
        row("本次范围", "恢复最多返回 1 次 · 最长 5 分钟")
        body.addView(text(activity, "保持设备解锁，可随时点悬浮条停止。部分系统页隐藏浮条时，可回本 App 停止。",
            13f, TaskNoteUi.Secondary), space(activity, 16))
        return sheet(activity, if (isTrial) "准备试跑" else "再次运行", "你来决定开始", body,
            if (isTrial) "开始试跑" else "开始运行", onConfirm = onConfirm)
    }

    fun pill(context: Context, value: String): TextView = text(context, value, 12f, TaskNoteUi.StickerInk).apply {
        background = Ui.rounded(context, TaskNoteUi.Sticker, 6)
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
    }

    private fun input(context: Context, value: String, placeholder: String, limit: Int, singleLine: Boolean) = EditText(context).apply {
        textSize = 16f; hint = placeholder
        setTextColor(TaskNoteUi.Ink); setHintTextColor(TaskNoteUi.Secondary)
        background = Ui.rounded(context, Ui.Surface, 12, TaskNoteUi.Rule)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        minHeight = context.dp(54)
        inputType = InputType.TYPE_CLASS_TEXT or if (singleLine) InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(singleLine)
        if (!singleLine) { minLines = 2; maxLines = 4; gravity = Gravity.TOP }
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setText(value)
    }

    private fun watch(input: EditText, onChanged: (String) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChanged(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun sheet(activity: Activity, title: String, label: String, body: LinearLayout,
                      primaryLabel: String, secondaryLabel: String = "取消", onConfirm: () -> Boolean): Dialog {
        val root = column(activity).apply {
            background = Ui.asymmetricRounded(activity, TaskNoteUi.Paper, 28, 28, 0, 0)
            setPadding(activity.dp(22), activity.dp(18), activity.dp(22), activity.dp(16))
        }
        val document = column(activity).apply {
            addView(TaskNoteUi.label(activity, label), LinearLayout.LayoutParams(-2, -2))
            addView(text(activity, title, 25f, TaskNoteUi.Ink, true), space(activity, 16))
            addView(body, space(activity, 18))
        }
        val footer = column(activity).apply { setPadding(0, activity.dp(14), 0, 0) }
        val dialog = Dialog(activity, Ui.dialogTheme())
        footer.addView(TaskNoteUi.button(activity, primaryLabel, true) {
            if (onConfirm()) dialog.dismiss()
        }.apply { tag = "workflow_sheet_confirm" }, LinearLayout.LayoutParams(-1, -2))
        footer.addView(TextView(activity).apply {
            text = secondaryLabel; textSize = 14f; setTextColor(TaskNoteUi.Secondary)
            minHeight = activity.dp(48); gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; tag = "workflow_sheet_later"
            setOnClickListener { dialog.dismiss() }
        })
        val budget = (activity.resources.displayMetrics.heightPixels * .88f).toInt()
        root.addView(object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                footer.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val visible = android.graphics.Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }
                val available = minOf(budget, visible.height().takeIf { it > 0 } ?: budget)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                    (available - footer.measuredHeight - activity.dp(34)).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(document) })
        root.addView(footer)
        dialog.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
                setDimAmount(.44f); setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            show(); window?.setLayout(-1, -2)
        }
        return dialog
    }

    private fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun space(context: Context, top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(top) }
    private fun text(context: Context, value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        includeFontPadding = false; setLineSpacing(0f, 1.12f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
}
