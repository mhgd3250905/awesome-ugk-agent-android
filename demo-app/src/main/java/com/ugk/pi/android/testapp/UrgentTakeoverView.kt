package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ugk.pi.attention.UrgentAccent
import com.ugk.pi.attention.UrgentBlockType
import com.ugk.pi.attention.UrgentContentBlock
import com.ugk.pi.attention.UrgentMessage

/** App-owned, full-screen presentation of the SDK's bounded urgent content. */
internal object UrgentTakeoverView {
    fun build(context: Context, message: UrgentMessage, onClose: () -> Unit, onOpenApp: () -> Unit): View {
        val accent = accentColor(message.accent)
        val accentSurface = accentSurface(message.accent)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Ui.Background)
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
            column.setPadding(
                context.dp(24),
                maxOf(context.dp(48), insets.systemWindowInsetTop + context.dp(12)),
                context.dp(24),
                maxOf(context.dp(40), insets.systemWindowInsetBottom + context.dp(16))
            )
            insets
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val identity = TextView(context).apply {
            text = "UGK  /  AGENT"
            textSize = 12f
            letterSpacing = 0.13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextSecondary)
        }
        header.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(context).apply {
            text = "×"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Ui.TextPrimary)
            background = Ui.clickableRounded(context, Ui.SurfaceSoft, Ui.SurfaceSubtle, 24)
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
            setPadding(0, context.dp(48), 0, context.dp(28))
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

        content.addView(TextView(context).apply {
            text = "●  重要提醒"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
        })
        content.addView(TextView(context).apply {
            text = message.title
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
            setLineSpacing(context.dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(18) })

        if (message.blocks.isEmpty()) {
            addParagraph(context, content, message.body, context.dp(26))
        } else {
            message.blocks.forEachIndexed { index, block ->
                addBlock(context, content, block, accent, accentSurface, if (index == 0) 28 else 16)
            }
        }

        val reasonCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SurfaceSoft, 18)
            setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
        }
        reasonCard.addView(TextView(context).apply {
            text = "为什么现在提醒"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
        })
        reasonCard.addView(TextView(context).apply {
            text = message.reason
            textSize = 14f
            setTextColor(Ui.TextSecondary)
            setLineSpacing(context.dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(8) })
        content.addView(reasonCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(32) })

        column.addView(TextView(context).apply {
            text = "打开对话"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Ui.OnPrimary)
            background = Ui.clickableRounded(context, Ui.Primary, Ui.PrimaryPressed, 16)
            contentDescription = "打开 Agent 对话查看提醒"
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenApp() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(56)
        ))
        return root
    }

    private fun addBlock(
        context: Context,
        content: LinearLayout,
        block: UrgentContentBlock,
        accent: Int,
        accentSurface: Int,
        topMarginDp: Int
    ) {
        when (block.type) {
            UrgentBlockType.HEADING -> content.addView(TextView(context).apply {
                text = block.text
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Ui.TextPrimary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(topMarginDp) })
            UrgentBlockType.PARAGRAPH -> addParagraph(context, content, block.text, context.dp(topMarginDp))
            UrgentBlockType.CALLOUT -> content.addView(TextView(context).apply {
                text = block.text
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accent)
                background = Ui.rounded(context, accentSurface, 18)
                setPadding(context.dp(18), context.dp(17), context.dp(18), context.dp(17))
                setLineSpacing(context.dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(topMarginDp) })
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
                })
                row.addView(TextView(context).apply {
                    text = block.text
                    textSize = 16f
                    setTextColor(Ui.TextPrimary)
                    setLineSpacing(context.dp(3).toFloat(), 1f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(topMarginDp) })
            }
        }
    }

    private fun addParagraph(context: Context, content: LinearLayout, value: String, topMargin: Int) {
        content.addView(TextView(context).apply {
            text = value
            textSize = 17f
            setTextColor(Ui.TextSecondary)
            setLineSpacing(context.dp(5).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin })
    }

    private fun accentColor(accent: UrgentAccent): Int = when (accent) {
        UrgentAccent.AMBER -> Ui.Warning
        UrgentAccent.GREEN -> Ui.Primary
        UrgentAccent.BLUE -> Ui.Info
        UrgentAccent.RED -> Ui.Danger
    }

    private fun accentSurface(accent: UrgentAccent): Int = when (accent) {
        UrgentAccent.AMBER -> Ui.WarningSoft
        UrgentAccent.GREEN -> Ui.PrimaryContainer
        UrgentAccent.BLUE -> Ui.InfoSoft
        UrgentAccent.RED -> Ui.DangerSoft
    }
}
