package com.ugk.pi.android.testapp

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Attachment entry points in the shared owl paper-note style. */
internal fun showAttachmentMenuSheet(context: Context, onSelect: (Int) -> Unit) {
    val dialog = Dialog(context, Ui.dialogTheme())
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.asymmetricRounded(context, TaskNoteUi.Paper, 28, 28, 0, 0)
        clipToOutline = true
    }
    val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(14), context.dp(24), context.dp(10))
        clipChildren = false; clipToPadding = false
    }
    val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
    }
    val headings = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
    }
    headings.addView(TaskNoteUi.label(context, "交给我吧"), LinearLayout.LayoutParams(-2, -2).apply {
        topMargin = context.dp(8)
    })
    headings.addView(TextView(context).apply {
        text = "添加与工具"; textSize = if (context.resources.configuration.screenWidthDp < 380) 22f else 24f
        setTextColor(TaskNoteUi.Ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(18) })
    header.addView(headings, LinearLayout.LayoutParams(0, -2, 1f))
    val owlSize = when {
        context.resources.configuration.fontScale > 1.25f -> 72
        context.resources.configuration.screenWidthDp < 380 -> 92
        else -> 104
    }
    header.addView(TaskNoteUi.owl(context, owlSize), LinearLayout.LayoutParams(context.dp(owlSize), context.dp(owlSize)).apply {
        marginStart = context.dp(4)
    })
    sheet.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
    sheet.addView(TaskNoteUi.divider(context), LinearLayout.LayoutParams(-1, context.dp(3)).apply {
        topMargin = context.dp(20); bottomMargin = context.dp(12)
    })
    val titles = listOf("拍照", "从相册选择图片", "导入文档 / 文件", "教我操作")
    val subtitles = listOf("拍下眼前的内容", "添加截图或已有照片", "让助手参考文件内容", "分段对话，整理可复用的经验")
    val icons = listOf(android.R.drawable.ic_menu_camera, android.R.drawable.ic_menu_gallery,
        android.R.drawable.ic_menu_upload, android.R.drawable.ic_menu_compass)
    titles.forEachIndexed { index, title ->
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(68)
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            background = Ui.clickableRounded(context, TaskNoteUi.Paper, TaskNoteUi.Sticker, 16)
            isClickable = true; isFocusable = true
            contentDescription = "$title，${subtitles[index]}"
            setOnClickListener { dialog.dismiss(); onSelect(index) }
        }
        row.addView(ImageView(context).apply {
            setImageResource(icons[index]); setColorFilter(TaskNoteUi.Primary)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(25), context.dp(25)).apply { marginEnd = context.dp(16) })
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(TaskNoteUi.Ink) })
        labels.addView(TextView(context).apply {
            text = subtitles[index]; textSize = 12f; setTextColor(TaskNoteUi.Secondary)
            setPadding(0, context.dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply {
            text = "›"; textSize = 23f; setTextColor(TaskNoteUi.Secondary)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        sheet.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    var closing = false
    fun close(action: () -> Unit = {}) {
        if (closing) return
        closing = true
        root.clearAnimation()
        root.animate().translationY(root.height.toFloat()).setDuration(240L)
            .setInterpolator(android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f))
            .withEndAction { dialog.dismiss(); action() }.start()
    }
    // Run navigation only after the same exit motion used by the timer sheet.
    for (index in titles.indices) {
        sheet.getChildAt(index + 2).setOnClickListener { close { onSelect(index) } }
    }
    val footer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(20))
        addView(TaskNoteUi.button(context, "取消", false) { close() }, LinearLayout.LayoutParams(-1, -2))
    }
    val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            footer.measure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            val budget = (context.resources.displayMetrics.heightPixels * .74f).toInt() - footer.measuredHeight
            super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(budget.coerceAtLeast(0), android.view.View.MeasureSpec.AT_MOST))
        }
    }.apply { isFillViewport = false; overScrollMode = android.view.View.OVER_SCROLL_NEVER; addView(sheet) }
    root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
    root.addView(footer, LinearLayout.LayoutParams(-1, -2))
    root.setOnApplyWindowInsetsListener { _, insets ->
        val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
            insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        footer.setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(20) + bottom)
        insets
    }
    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(.44f)
        setWindowAnimations(0)
    }
    dialog.show()
    dialog.window?.setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT
    )
    root.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.demo_dialog_enter_bottom))
}
