package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button

object Ui {
    // Dynamic theme state
    val isDark: Boolean get() = ThemeManager.isDark

    val Surface: Int get() = if (isDark) Color.rgb(18, 19, 22) else Color.rgb(251, 249, 245)
    val SurfaceElevated: Int get() = if (isDark) Color.rgb(26, 27, 31) else Color.rgb(255, 255, 255)
    val SurfaceSoft: Int get() = if (isDark) Color.rgb(36, 38, 43) else Color.rgb(240, 236, 229)
    val SurfaceSubtle: Int get() = if (isDark) Color.rgb(30, 32, 36) else Color.rgb(245, 242, 236)

    // 主品牌与核心强调色：活力温暖橙红
    val Mint: Int get() = if (isDark) Color.rgb(255, 110, 74) else Color.rgb(234, 84, 52)
    val MintLight: Int get() = if (isDark) Color.rgb(58, 36, 30) else Color.rgb(253, 238, 233)
    val MintDark: Int get() = if (isDark) Color.rgb(255, 131, 98) else Color.rgb(206, 62, 31)
    val MintStroke: Int get() = if (isDark) Color.rgb(104, 58, 47) else Color.rgb(247, 195, 182)

    // 文字体系：浅色暖炭黑，深色通透灰白（绝不发绿）
    val TextPrimary: Int get() = if (isDark) Color.rgb(240, 242, 245) else Color.rgb(28, 26, 23)
    val TextSecondary: Int get() = if (isDark) Color.rgb(156, 161, 174) else Color.rgb(107, 102, 94)
    val TextMuted: Int get() = if (isDark) Color.rgb(101, 106, 118) else Color.rgb(158, 152, 142)

    // 描边体系：浅色米灰，深色冷灰
    val Outline: Int get() = if (isDark) Color.rgb(47, 50, 56) else Color.rgb(229, 224, 216)
    val OutlineFocus: Int get() = if (isDark) Color.rgb(255, 110, 74) else Color.rgb(234, 84, 52)

    // 气泡色彩：用户暖橙粉底，助手纯白/纯炭黑底
    val UserBubble: Int get() = if (isDark) Color.rgb(46, 34, 30) else Color.rgb(253, 238, 233)
    val UserStroke: Int get() = if (isDark) Color.rgb(78, 52, 43) else Color.rgb(247, 195, 182)
    val AssistantBubble: Int get() = if (isDark) Color.rgb(26, 27, 31) else Color.rgb(255, 255, 255)
    val AssistantStroke: Int get() = if (isDark) Color.rgb(47, 50, 56) else Color.rgb(234, 229, 220)

    // 代码卡片与文本：深浅双模精致配色
    val CodeBg: Int get() = if (isDark) Color.rgb(21, 22, 25) else Color.rgb(245, 242, 236)
    val CodeText: Int get() = if (isDark) Color.rgb(110, 231, 183) else Color.rgb(45, 106, 79)

    // 清爽点缀色：淡青绿/草木绿
    val Success: Int get() = if (isDark) Color.rgb(110, 231, 183) else Color.rgb(46, 125, 94)
    val SuccessSoft: Int get() = if (isDark) Color.rgb(22, 46, 36) else Color.rgb(232, 245, 238)

    // 警告与危险提示色
    val Warning: Int get() = if (isDark) Color.rgb(251, 191, 36) else Color.rgb(196, 126, 24)
    val WarningSoft: Int get() = if (isDark) Color.rgb(51, 39, 17) else Color.rgb(254, 247, 233)
    val WarningStroke: Int get() = if (isDark) Color.rgb(94, 72, 29) else Color.rgb(245, 224, 180)
    val Danger: Int get() = if (isDark) Color.rgb(248, 113, 113) else Color.rgb(209, 57, 57)
    val DangerSoft: Int get() = if (isDark) Color.rgb(54, 25, 25) else Color.rgb(254, 238, 238)

    // 新增语义化别名 Tokens
    val Accent: Int get() = Mint
    val AccentLight: Int get() = MintLight
    val AccentDark: Int get() = MintDark
    val AccentStroke: Int get() = MintStroke
    val Sage: Int get() = Success
    val SageSoft: Int get() = SuccessSoft

    fun dialogTheme(): Int = if (ThemeManager.isDark) {
        android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(context.dp(strokeDp), strokeColor)
        }

    fun asymmetricRounded(
        context: Context,
        color: Int,
        topLeftDp: Int,
        topRightDp: Int,
        bottomRightDp: Int,
        bottomLeftDp: Int,
        strokeColor: Int = 0,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val tl = context.dp(topLeftDp).toFloat()
        val tr = context.dp(topRightDp).toFloat()
        val br = context.dp(bottomRightDp).toFloat()
        val bl = context.dp(bottomLeftDp).toFloat()
        cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        if (strokeColor != 0) setStroke(context.dp(strokeDp), strokeColor)
    }

    fun stateListDrawable(
        normal: Drawable,
        pressed: Drawable? = null,
        focused: Drawable? = null,
        disabled: Drawable? = null
    ): StateListDrawable = StateListDrawable().apply {
        if (disabled != null) addState(intArrayOf(-android.R.attr.state_enabled), disabled)
        if (pressed != null) addState(intArrayOf(android.R.attr.state_pressed), pressed)
        if (focused != null) addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }

    fun clickableRounded(
        context: Context,
        normalColor: Int,
        pressedColor: Int,
        radiusDp: Int,
        strokeColor: Int = 0,
        strokeDp: Int = 1
    ): StateListDrawable {
        val normal = rounded(context, normalColor, radiusDp, strokeColor, strokeDp)
        val pressed = rounded(context, pressedColor, radiusDp, strokeColor, strokeDp)
        return stateListDrawable(normal = normal, pressed = pressed)
    }

    fun styleSecondaryButton(button: Button) {
        button.setAllCaps(false)
        button.setTextColor(MintDark)
        button.background = clickableRounded(button.context, SurfaceElevated, SurfaceSoft, 12, Outline)
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
