package com.ugk.pi.android.testapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button

object Ui {
    // Dynamic theme state. The app intentionally keeps its own brand palette
    // instead of inheriting a device Monet palette, so the owl identity stays
    // consistent across hosts and Android versions.
    val isDark: Boolean get() = ThemeManager.isDark

    // Brand and interaction roles. BrandPrimary is reserved for identity and
    // small emphasis; Primary is the contrast-safe action color.
    val BrandPrimary: Int get() = Color.rgb(34, 160, 107) // #22A06B
    val Primary: Int get() = if (isDark) Color.rgb(88, 201, 149) else Color.rgb(25, 122, 82) // #58C995 / #197A52
    val PrimaryPressed: Int get() = if (isDark) Color.rgb(66, 183, 132) else Color.rgb(22, 107, 73) // #42B784 / #166B49
    val PrimaryContainer: Int get() = if (isDark) Color.rgb(18, 63, 45) else Color.rgb(221, 244, 232) // #123F2D / #DDF4E8
    val OnPrimary: Int get() = if (isDark) Color.rgb(18, 19, 22) else Color.WHITE // #121316 / #FFFFFF
    val OnPrimaryContainer: Int get() = if (isDark) Color.rgb(184, 232, 208) else Color.rgb(11, 59, 40) // #B8E8D0 / #0B3B28

    // Surface hierarchy: the conversation canvas is intentionally neutral so
    // role and reading order are carried by placement, not a green wash.
    val Background: Int get() = if (isDark) Color.rgb(18, 19, 22) else Color.rgb(251, 249, 245) // #121316 / #FBF9F5
    val ConversationCanvas: Int get() = if (isDark) Color.rgb(18, 19, 22) else Color.rgb(242, 241, 238) // #121316 / #F2F1EE
    val Surface: Int get() = if (isDark) Color.rgb(28, 30, 32) else Color.WHITE // #1C1E20 / #FFFFFF
    val SurfaceElevated: Int get() = if (isDark) Color.rgb(36, 39, 42) else Color.WHITE // #24272A / #FFFFFF
    val SurfaceSubtle: Int get() = if (isDark) Color.rgb(28, 30, 32) else Color.rgb(241, 238, 232) // #1C1E20 / #F1EEE8
    val SurfaceSoft: Int get() = if (isDark) Color.rgb(45, 48, 50) else Color.rgb(247, 245, 241) // #2D3032 / #F7F5F1

    // Text and outline roles. Outline is kept for high-information controls;
    // ordinary surfaces use OutlineSubtle or no stroke.
    val TextPrimary: Int get() = if (isDark) Color.rgb(251, 249, 245) else Color.rgb(28, 26, 23) // #FBF9F5 / #1C1A17
    val TextSecondary: Int get() = if (isDark) Color.rgb(185, 181, 174) else Color.rgb(100, 97, 92) // #B9B5AE / #64615C
    val TextMuted: Int get() = if (isDark) Color.rgb(143, 139, 132) else Color.rgb(142, 138, 131) // #8F8B84 / #8E8A83
    val OutlineSubtle: Int get() = if (isDark) Color.rgb(59, 62, 64) else Color.rgb(227, 222, 213) // #3B3E40 / #E3DED5
    val Outline: Int get() = if (isDark) Color.rgb(143, 139, 132) else Color.rgb(142, 138, 131) // #8F8B84 / #8E8A83
    val Divider: Int get() = if (isDark) Color.rgb(59, 62, 64) else Color.rgb(227, 222, 213) // #3B3E40 / #E3DED5
    val FocusRing: Int get() = if (isDark) Primary else BrandPrimary
    val OutlineFocus: Int get() = FocusRing

    // Conversation roles follow the familiar chat convention: the user's
    // message is the only large branded surface; assistant content is neutral.
    val UserBubble: Int get() = if (isDark) Color.rgb(88, 201, 149) else Color.rgb(34, 160, 107) // #58C995 / #22A06B
    val OnUserBubble: Int get() = Color.rgb(28, 26, 23) // #1C1A17
    val UserStroke: Int get() = Color.TRANSPARENT
    val UserAvatarSurface: Int get() = if (isDark) Color.rgb(45, 48, 50) else Color.rgb(232, 229, 223) // #2D3032 / #E8E5DF
    val OnUserAvatar: Int get() = TextSecondary
    val AssistantBubble: Int get() = if (isDark) Color.rgb(36, 39, 42) else Color.WHITE // #24272A / #FFFFFF
    val OnAssistantBubble: Int get() = if (isDark) Color.rgb(251, 249, 245) else Color.rgb(28, 26, 23) // #FBF9F5 / #1C1A17
    val AssistantStroke: Int get() = Color.TRANSPARENT
    val AssistantAvatarSurface: Int get() = if (isDark) Color.rgb(45, 48, 50) else Color.rgb(232, 229, 223) // #2D3032 / #E8E5DF

    // Neutral evidence cards. Status color is applied only to compact state
    // indicators, never to a whole result body.
    val EvidenceSurface: Int get() = Surface
    val EvidenceRaised: Int get() = SurfaceElevated
    val EvidenceOutline: Int get() = OutlineSubtle
    val CodeBg: Int get() = SurfaceSubtle
    val CodeHeader: Int get() = SurfaceSoft
    val CodeText: Int get() = TextPrimary

    // Status roles. Green success intentionally shares the interaction family,
    // but status containers and explicit labels keep it distinct from branding.
    val Success: Int get() = Primary
    val SuccessSoft: Int get() = PrimaryContainer
    val SuccessStroke: Int get() = Success
    val Warning: Int get() = if (isDark) Color.rgb(242, 184, 75) else Color.rgb(168, 95, 0) // #F2B84B / #A85F00
    val WarningSoft: Int get() = if (isDark) Color.rgb(74, 51, 15) else Color.rgb(255, 241, 214) // #4A330F / #FFF1D6
    val WarningOnContainer: Int get() = if (isDark) Color.rgb(242, 184, 75) else Color.rgb(91, 53, 0) // #F2B84B / #5B3500
    val WarningStroke: Int get() = Warning
    val Danger: Int get() = if (isDark) Color.rgb(255, 138, 128) else Color.rgb(198, 62, 62) // #FF8A80 / #C63E3E
    val DangerSoft: Int get() = if (isDark) Color.rgb(79, 37, 35) else Color.rgb(251, 226, 224) // #4F2523 / #FBE2E0
    val DangerOnContainer: Int get() = if (isDark) Color.rgb(255, 180, 172) else Color.rgb(104, 28, 28) // #FFB4AC / #681C1C
    val OnDanger: Int get() = if (isDark) Background else Color.WHITE
    val Info: Int get() = if (isDark) Color.rgb(117, 167, 232) else Color.rgb(40, 106, 181) // #75A7E8 / #286AB5
    val InfoSoft: Int get() = if (isDark) Color.rgb(25, 58, 96) else Color.rgb(228, 239, 251) // #193A60 / #E4EFFB
    val InfoOnContainer: Int get() = if (isDark) Color.rgb(184, 213, 246) else Color.rgb(21, 59, 104) // #B8D5F6 / #153B68
    val NoticeSurface: Int get() = WarningSoft
    val NoticeContent: Int get() = WarningOnContainer
    val NoticeStroke: Int get() = WarningStroke
    val DisabledContainer: Int get() = if (isDark) Color.rgb(45, 48, 50) else Divider // #2D3032 / #E3DED5
    val DisabledContent: Int get() = Outline

    /** Shared enabled/pressed/checked/disabled state mapping for native Views. */
    fun stateColorList(
        normal: Int,
        pressed: Int = normal,
        disabled: Int = DisabledContent,
        checked: Int = normal,
        disabledChecked: Int = disabled
    ): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        ),
        intArrayOf(disabledChecked, disabled, checked, pressed, normal)
    )

    fun switchThumbTint(): ColorStateList = stateColorList(
        normal = SurfaceElevated,
        pressed = SurfaceElevated,
        disabled = DisabledContent,
        checked = Primary,
        disabledChecked = DisabledContent
    )

    fun switchTrackTint(): ColorStateList = stateColorList(
        normal = SurfaceSoft,
        pressed = SurfaceSoft,
        disabled = DisabledContainer,
        checked = PrimaryContainer,
        disabledChecked = DisabledContainer
    )

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
        strokeDp: Int = 1,
        disabledColor: Int? = null,
        disabledStrokeColor: Int? = null
    ): StateListDrawable {
        val normal = rounded(context, normalColor, radiusDp, strokeColor, strokeDp)
        val pressed = rounded(context, pressedColor, radiusDp, strokeColor, strokeDp)
        val disabled = disabledColor?.let {
            rounded(
                context,
                it,
                radiusDp,
                disabledStrokeColor ?: strokeColor,
                strokeDp
            )
        }
        return stateListDrawable(normal = normal, pressed = pressed, disabled = disabled)
    }

    fun styleSecondaryButton(button: Button) {
        button.setAllCaps(false)
        button.setTextColor(TextSecondary)
        button.background = clickableRounded(button.context, SurfaceElevated, SurfaceSoft, 12, OutlineSubtle)
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
