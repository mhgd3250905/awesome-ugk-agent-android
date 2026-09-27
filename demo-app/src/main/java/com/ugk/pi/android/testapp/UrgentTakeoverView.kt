package com.ugk.pi.android.testapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.ugk.pi.attention.UrgentAccent
import com.ugk.pi.attention.UrgentAction
import com.ugk.pi.attention.UrgentBlockType
import com.ugk.pi.attention.UrgentContentBlock
import com.ugk.pi.attention.UrgentForm
import com.ugk.pi.attention.UrgentMessage

/** App-owned, full-screen presentation of the SDK's bounded urgent content. */
internal object UrgentTakeoverView {
    fun build(
        context: Context,
        message: UrgentMessage,
        onClose: () -> Unit,
        onOpenApp: () -> Unit,
        onAction: (UrgentAction) -> Boolean,
        onFormSubmit: (UrgentForm, String) -> Boolean
    ): View {
        val accent = accentColor(message.accent)
        val requiresResponse = message.actions.isNotEmpty() || message.form != null
        val root = FrameLayout(context).apply {
            setBackgroundColor(TaskNoteUi.Paper)
            isClickable = true // The takeover must not pass touches to the app underneath.
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Leave a safe fallback even if an OEM overlay window reports no system insets.
            setPadding(context.dp(24), context.dp(48), context.dp(24), context.dp(40))
        }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else 0
            column.setPadding(
                context.dp(24),
                maxOf(context.dp(48), insets.systemWindowInsetTop + context.dp(12)),
                context.dp(24),
                maxOf(context.dp(40), insets.systemWindowInsetBottom + context.dp(16), imeBottom + context.dp(16))
            )
            insets
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val identity = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        identity.addView(TaskNoteUi.label(context, "重要提醒").apply { rotation = -3f })
        identity.addView(View(context).apply {
            background = Ui.rounded(context, accent, 4)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(7), context.dp(7)).apply {
            marginStart = context.dp(14)
        })
        header.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_note_close)
            imageTintList = ColorStateList.valueOf(TaskNoteUi.Ink)
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumWidth = context.dp(48)
            minimumHeight = context.dp(48)
            setPadding(context.dp(13), context.dp(13), context.dp(13), context.dp(13))
            backgroundTintList = null
            background = Ui.clickableRounded(context, TaskNoteUi.Paper, TaskNoteUi.Sticker, 24, TaskNoteUi.Rule)
            stateListAnimator = null
            contentDescription = "关闭全屏提醒"
            isClickable = true
            isFocusable = true
            setOnClickListener { onClose() }
        }
        header.addView(close, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        column.addView(header)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setVerticalScrollBarEnabled(false)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // A short reminder reads as one composed note; longer messages and
            // controls grow naturally into the same scrollable content area.
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(26), 0, context.dp(24))
        }
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val compactHero = context.resources.configuration.screenWidthDp < 380 ||
            context.resources.configuration.fontScale > 1.2f
        val owlSize = if (compactHero) 104 else 160
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(if (compactHero) 144 else 160)
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(context).apply {
            text = message.title
            textSize = 32f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TaskNoteUi.Ink)
            includeFontPadding = false
            setLineSpacing(context.dp(3).toFloat(), 1f)
            ViewCompat.setAccessibilityHeading(this, true)
        }, fullWidth(context))
        heading.addView(TaskNoteUi.marker(context), LinearLayout.LayoutParams(context.dp(112), context.dp(20)).apply {
            topMargin = context.dp(8)
            marginStart = context.dp(2)
        })
        hero.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(TaskNoteUi.owl(context, owlSize).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(
            context.dp(owlSize), context.dp(owlSize)
        ).apply { marginStart = context.dp(8) })
        content.addView(hero, fullWidth(context))

        if (message.blocks.isEmpty()) {
            addParagraph(context, content, message.body, 20)
        } else {
            message.blocks.forEachIndexed { index, block ->
                addBlock(context, content, block, accent, if (index == 0) 20 else 16)
            }
        }

        content.addView(TaskNoteUi.divider(context), fullWidth(context, top = 26).apply {
            height = context.dp(3)
        })
        val reason = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        reason.addView(TextView(context).apply {
            text = "为什么现在提醒"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
        })
        reason.addView(TextView(context).apply {
            text = message.reason
            textSize = 15f
            setTextColor(TaskNoteUi.Secondary)
            setLineSpacing(context.dp(4).toFloat(), 1f)
        }, fullWidth(context, top = 7))
        content.addView(reason, fullWidth(context, top = 18))

        if (requiresResponse) {
            content.addView(TaskNoteUi.label(context, "等你回应"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(28) })
        }
        if (message.actions.isNotEmpty()) {
            addActions(context, content, message.actions, onAction)
        }
        message.form?.let { form ->
            addForm(context, content, form, onFormSubmit)
        }

        column.addView(TaskNoteUi.button(context, "打开对话", primary = !requiresResponse, onClick = onOpenApp).apply {
            contentDescription = "打开 Agent 对话查看提醒"
        }, fullWidth(context, top = 12))
        return root
    }

    private fun addActions(
        context: Context,
        content: LinearLayout,
        actions: List<UrgentAction>,
        onAction: (UrgentAction) -> Boolean
    ) {
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val error = TextView(context).apply {
            textSize = 13f
            setTextColor(Ui.Danger)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        actions.forEachIndexed { index, action ->
            group.addView(TaskNoteUi.button(context, action.label, primary = index == 0) {
                if (!onAction(action)) {
                    error.text = "暂时无法提交，请稍后再试"
                    error.visibility = View.VISIBLE
                }
            }.apply {
                contentDescription = "${action.label}，点击后告诉 Agent"
            }, fullWidth(context, top = if (index == 0) 0 else 10))
        }
        group.addView(error, fullWidth(context, top = 8))
        content.addView(group, fullWidth(context, top = 14))
    }

    private fun addForm(
        context: Context,
        content: LinearLayout,
        form: UrgentForm,
        onFormSubmit: (UrgentForm, String) -> Boolean
    ) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val label = TextView(context).apply {
            text = form.label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TaskNoteUi.Ink)
        }
        card.addView(label)
        val field = EditText(context).apply {
            id = View.generateViewId()
            hint = form.placeholder.ifBlank { "输入内容" }
            textSize = 16f
            setTextColor(TaskNoteUi.Ink)
            setHintTextColor(TaskNoteUi.Secondary)
            backgroundTintList = null
            background = Ui.stateListDrawable(
                normal = Ui.rounded(context, TaskNoteUi.Paper, 14, TaskNoteUi.Rule),
                focused = Ui.rounded(context, TaskNoteUi.Paper, 14, TaskNoteUi.Primary, 2)
            )
            setPadding(context.dp(16), context.dp(13), context.dp(16), context.dp(13))
            minHeight = context.dp(54)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(MAX_FORM_VALUE_CHARS))
            contentDescription = form.label
        }
        label.labelFor = field.id
        card.addView(field, fullWidth(context, top = 12))
        val error = TextView(context).apply {
            textSize = 13f
            setTextColor(Ui.Danger)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        fun submit() {
            val value = field.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                error.text = "请先输入内容"
                error.visibility = View.VISIBLE
            } else if (!onFormSubmit(form, value)) {
                error.text = "暂时无法提交，请稍后再试"
                error.visibility = View.VISIBLE
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        card.addView(TaskNoteUi.button(context, form.submitLabel, primary = true) { submit() }.apply {
            contentDescription = "${form.submitLabel}，提交给 Agent"
        }, fullWidth(context, top = 12))
        card.addView(error, fullWidth(context, top = 8))
        content.addView(card, fullWidth(context, top = 18))
    }

    private fun addBlock(
        context: Context,
        content: LinearLayout,
        block: UrgentContentBlock,
        accent: Int,
        topMarginDp: Int
    ) {
        when (block.type) {
            UrgentBlockType.HEADING -> content.addView(TextView(context).apply {
                text = block.text
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TaskNoteUi.Ink)
                setLineSpacing(context.dp(3).toFloat(), 1f)
                ViewCompat.setAccessibilityHeading(this, true)
            }, fullWidth(context, top = topMarginDp))
            UrgentBlockType.PARAGRAPH -> addParagraph(context, content, block.text, topMarginDp)
            UrgentBlockType.CALLOUT -> {
                val callout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                callout.addView(View(context).apply {
                    background = Ui.rounded(context, accent, 2)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(context.dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
                callout.addView(TextView(context).apply {
                    text = block.text
                    textSize = 19f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(TaskNoteUi.Ink)
                    setPadding(context.dp(14), context.dp(3), 0, context.dp(3))
                    setLineSpacing(context.dp(4).toFloat(), 1f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(callout, fullWidth(context, top = topMarginDp))
            }
            UrgentBlockType.BULLET -> {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                row.addView(TextView(context).apply {
                    text = "●"
                    textSize = 12f
                    setTextColor(accent)
                    setPadding(0, context.dp(3), context.dp(12), 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                row.addView(TextView(context).apply {
                    text = block.text
                    textSize = 18f
                    setTextColor(TaskNoteUi.Ink)
                    setLineSpacing(context.dp(4).toFloat(), 1f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(row, fullWidth(context, top = topMarginDp))
            }
        }
    }

    private fun addParagraph(context: Context, content: LinearLayout, value: String, topMarginDp: Int) {
        content.addView(TextView(context).apply {
            text = value
            textSize = 19f
            setTextColor(TaskNoteUi.Ink)
            setLineSpacing(context.dp(5).toFloat(), 1f)
        }, fullWidth(context, top = topMarginDp))
    }

    private fun fullWidth(context: Context, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(top) }

    private fun accentColor(accent: UrgentAccent): Int = when (accent) {
        UrgentAccent.AMBER -> Ui.Warning
        UrgentAccent.GREEN -> Ui.Primary
        UrgentAccent.BLUE -> Ui.Info
        UrgentAccent.RED -> Ui.Danger
    }

    private const val MAX_FORM_VALUE_CHARS = 500
}
