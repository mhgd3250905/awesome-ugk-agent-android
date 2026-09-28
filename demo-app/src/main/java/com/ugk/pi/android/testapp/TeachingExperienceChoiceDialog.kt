package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ugk.pi.android.UserConfirmationDialogRequest

internal fun teachingExperienceChoiceDialog(activity: Activity, request: UserConfirmationDialogRequest, choose: (String) -> Unit): Dialog {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.asymmetricRounded(activity, TaskNoteUi.Paper, 28, 28, 0, 0)
        setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(28))
    }
    val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    titles.addView(TaskNoteUi.label(activity, "教学经验"))
    titles.addView(TextView(activity).apply {
        text = request.title; textSize = 22f; setTextColor(TaskNoteUi.Ink)
        setTypeface(null, Typeface.BOLD); setPadding(0, activity.dp(18), 0, activity.dp(12))
    })
    heading.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
    heading.addView(TaskNoteUi.owl(activity, 92), LinearLayout.LayoutParams(activity.dp(92), activity.dp(92)))
    root.addView(heading)
    root.addView(TaskNoteUi.divider(activity), LinearLayout.LayoutParams(-1, activity.dp(3)))
    root.addView(object : ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .4f).toInt(), MeasureSpec.AT_MOST))
        }
    }.apply {
        addView(TextView(activity).apply {
            text = request.message; textSize = 15f; setTextColor(TaskNoteUi.Ink)
            setPadding(0, activity.dp(16), 0, activity.dp(20))
        })
    }, LinearLayout.LayoutParams(-1, -2))
    request.buttons.reversed().forEach { button ->
        root.addView(TaskNoteUi.button(activity, button.label, button.id != "cancel") { choose(button.id) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
    }
    return Dialog(activity, Ui.dialogTheme()).apply {
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.44f)
            setWindowAnimations(0)
        }
        setOnShowListener {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            root.startAnimation(android.view.animation.AnimationUtils.loadAnimation(activity, R.anim.demo_dialog_enter_bottom))
        }
    }
}
