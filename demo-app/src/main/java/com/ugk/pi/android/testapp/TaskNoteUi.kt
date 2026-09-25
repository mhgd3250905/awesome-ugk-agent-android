package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/** The shared paper-and-ink language of decisions, timers and important notes. */
internal object TaskNoteUi {
    val Paper: Int get() = if (Ui.isDark) Color.rgb(38, 42, 32) else Color.rgb(255, 247, 224)
    val Ink: Int get() = if (Ui.isDark) Color.rgb(232, 242, 214) else Color.rgb(11, 59, 40)
    val Secondary: Int get() = if (Ui.isDark) Color.rgb(185, 196, 173) else Color.rgb(99, 108, 88)
    val Rule: Int get() = if (Ui.isDark) Color.rgb(86, 96, 73) else Color.rgb(211, 202, 177)
    val Sticker: Int get() = if (Ui.isDark) Color.rgb(85, 66, 28) else Color.rgb(255, 228, 167)
    val StickerInk: Int get() = if (Ui.isDark) Color.rgb(255, 218, 144) else Color.rgb(117, 77, 18)
    val Primary: Int get() = if (Ui.isDark) Color.rgb(166, 219, 185) else Color.rgb(25, 122, 82)
    val PrimaryPressed: Int get() = if (Ui.isDark) Color.rgb(140, 197, 160) else Color.rgb(17, 99, 65)
    val OnPrimary: Int get() = if (Ui.isDark) Color.rgb(16, 51, 34) else Color.WHITE

    fun owl(context: Context, sizeDp: Int): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.brand_owl_note)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        minimumWidth = context.dp(sizeDp)
        minimumHeight = context.dp(sizeDp)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun divider(context: Context): View = View(context).apply {
        minimumHeight = context.dp(3)
        background = GradientDrawable().apply {
            shape = GradientDrawable.LINE
            setStroke(context.dp(1), Rule, context.dp(4).toFloat(), context.dp(4).toFloat())
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** Render the generated ink as a mask so it sits on either paper theme. */
    fun marker(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.note_marker_underline)
        scaleType = ImageView.ScaleType.CENTER_CROP
        val ink = if (Ui.isDark) StickerInk else Color.rgb(255, 211, 124)
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, Color.red(ink).toFloat(),
            0f, 0f, 0f, 0f, Color.green(ink).toFloat(),
            0f, 0f, 0f, 0f, Color.blue(ink).toFloat(),
            0f, 0f, -3f, 0f, 672f
        )))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(StickerInk)
        includeFontPadding = false
        setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
        background = Ui.rounded(context, Sticker, 6)
        rotation = -4f
    }

    fun button(context: Context, label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (primary) OnPrimary else Ink)
            background = Ui.clickableRounded(
                context,
                if (primary) Primary else Paper,
                if (primary) PrimaryPressed else Sticker,
                16,
                if (primary) Color.TRANSPARENT else Rule
            )
            stateListAnimator = null
            minimumWidth = 0
            minWidth = 0
            minHeight = context.dp(54)
            minimumHeight = context.dp(54)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
}
