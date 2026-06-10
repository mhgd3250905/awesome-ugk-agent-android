package com.ugk.pi.android.testapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AgentFloatingWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var expandedView: View? = null
    private var collapsedView: View? = null

    private var logContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var statusText: TextView? = null
    private var inputField: EditText? = null
    private var sendBtn: View? = null

    var onSendMessage: ((String) -> Unit)? = null

    private val expandedParams = WindowManager.LayoutParams().apply {
        width = dp(300)
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = overlayType
        flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 200
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    private val collapsedParams = WindowManager.LayoutParams(
        dp(48), dp(48),
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 20
        y = 200
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
    }

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun show() {
        if (expandedView != null || collapsedView != null) return
        showCollapsed()
    }

    fun showExpanded() {
        if (expandedView != null) return
        hideCollapsed()
        val view = buildExpandedView()
        expandedView = view
        windowManager.addView(view, expandedParams)
    }

    fun hide() {
        hideExpanded()
        hideCollapsed()
    }

    fun isShowing(): Boolean = expandedView != null || collapsedView != null

    fun setStatus(text: String) {
        statusText?.text = text
    }

    fun addLog(text: String) {
        val container = logContainer ?: return
        val tv = TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.rgb(210, 210, 210))
            setPadding(0, dp(1), 0, dp(1))
        }
        container.addView(tv)
        trimLog(container)
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    fun clear() {
        logContainer?.removeAllViews()
        statusText?.text = "Agent 就绪"
    }

    fun setSending(sending: Boolean) {
        sendBtn?.let { btn ->
            if (sending) {
                btn.alpha = 0.4f
                btn.isClickable = false
            } else {
                btn.alpha = 1f
                btn.isClickable = true
            }
        }
    }

    private fun showCollapsed() {
        if (collapsedView != null) return
        val view = buildCollapsedView()
        collapsedView = view
        windowManager.addView(view, collapsedParams)
    }

    private fun hideCollapsed() {
        collapsedView?.let { windowManager.removeView(it) }
        collapsedView = null
    }

    private fun hideExpanded() {
        expandedView?.let { windowManager.removeView(it) }
        expandedView = null
        logContainer = null
        scrollView = null
        statusText = null
        inputField = null
        sendBtn = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCollapsedView(): View {
        val size = dp(48)
        return TextView(context).apply {
            text = "AI"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Ui.rounded(context, Ui.Mint, 24)
            setOnClickListener {
                hideCollapsed()
                showExpanded()
            }
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var dragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = collapsedParams.x
                            initialY = collapsedParams.y
                            touchX = event.rawX
                            touchY = event.rawY
                            dragging = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - touchX
                            val dy = event.rawY - touchY
                            if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) dragging = true
                            if (dragging) {
                                collapsedParams.x = initialX + dx.toInt()
                                collapsedParams.y = initialY + dy.toInt()
                                windowManager.updateViewLayout(v, collapsedParams)
                            }
                        }
                    }
                    return if (dragging) true else v.onTouchEvent(event)
                }
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExpandedView(): View {
        val widthPx = dp(300)

        statusText = TextView(context).apply {
            text = "Agent 就绪"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Ui.Mint)
        }

        val collapseBtn = TextView(context).apply {
            text = "×"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER
            setOnClickListener {
                hideExpanded()
                showCollapsed()
            }
        }

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.Mint)
            addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(collapseBtn, LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.MATCH_PARENT))
        }

        logContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        scrollView = ScrollView(context).apply {
            addView(logContainer)
        }

        inputField = EditText(context).apply {
            hint = "输入..."
            setHintTextColor(Color.rgb(120, 120, 120))
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.argb(255, 50, 50, 50))
        }

        sendBtn = TextView(context).apply {
            text = "发送"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Ui.rounded(context, Ui.Mint, 6)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { doSend() }
        }

        val inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(255, 40, 40, 40))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(sendBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Color.argb(245, 35, 35, 35), 14)
            clipChildren = true
            addView(titleBar, LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(scrollView, LinearLayout.LayoutParams(widthPx, dp(17) * 10))
            addView(inputBar, LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        setupDrag(titleBar, root, expandedParams)

        return root
    }

    private fun doSend() {
        val field = inputField ?: return
        val text = field.text.toString().trim()
        if (text.isBlank()) return
        field.setText("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        onSendMessage?.invoke(text)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(dragTarget: View, rootView: View, params: WindowManager.LayoutParams) {
        dragTarget.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) dragging = true
                        if (dragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(rootView, params)
                        }
                    }
                }
                return dragging
            }
        })
    }

    private fun trimLog(container: LinearLayout) {
        while (container.childCount > 80) {
            container.removeViewAt(0)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
