package com.ugk.pi.android.testapp

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Debug-only fixture. Every visible message is synthetic; no conversation runtime is created. */
class DemoDialogTestHostActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        val scale = fontScaleOverride
        val configured = if (scale == null) newBase else {
            newBase.createConfigurationContext(Configuration(newBase.resources.configuration).apply {
                fontScale = scale
            })
        }
        super.attachBaseContext(configured)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Ui.isDark
            isAppearanceLightNavigationBars = !Ui.isDark
        }
        showSyntheticConversation()
    }

    fun showSyntheticConversation() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.ConversationCanvas)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(Ui.Background)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_owl_avatar)
            contentDescription = "UGK 猫头鹰"
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("今天，也照顾好自己", 18f, Ui.TextPrimary, bold = true))
            addView(label("UGK / AGENT", 11f, Ui.TextSecondary).apply {
                letterSpacing = 0.1f
            }, fullWidth().apply { topMargin = dp(5) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        root.addView(header, fullWidth())
        root.addView(View(this).apply { setBackgroundColor(Ui.OutlineSubtle) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        messages.addView(label("10 秒以后提醒我去喝水", 16f, Ui.OnUserBubble).apply {
            background = Ui.rounded(this@DemoDialogTestHostActivity, Ui.UserBubble, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        val assistant = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        assistant.addView(ImageView(this).apply {
            setImageResource(R.drawable.brand_owl_avatar)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(9) })
        assistant.addView(label("收到，我来帮你记着。\n确认后就开始计时。", 16f, Ui.OnAssistantBubble).apply {
            background = Ui.rounded(this@DemoDialogTestHostActivity, Ui.AssistantBubble, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        messages.addView(assistant, fullWidth().apply { topMargin = dp(24) })
        root.addView(messages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(label("发消息", 16f, Ui.TextMuted).apply {
            background = Ui.rounded(this@DemoDialogTestHostActivity, Ui.SurfaceElevated, 24)
            setPadding(dp(18), dp(17), dp(18), dp(17))
        }, fullWidth().apply {
            marginStart = dp(18)
            marginEnd = dp(18)
            bottomMargin = dp(18)
        })
        setContentView(root)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        /** Set only before instrumentation launches this host; never changes system font settings. */
        @Volatile
        var fontScaleOverride: Float? = null
    }
}
