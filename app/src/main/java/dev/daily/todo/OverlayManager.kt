package dev.daily.todo

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.roundToInt

internal class OverlayManager(service: Context) {
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val context = if (Build.VERSION.SDK_INT >= 30) {
        val display = service.getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
        service.createDisplayContext(display).createWindowContext(type, null)
    } else service
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar)
    private var root: ScrollView? = null
    private var onError: ((String) -> Unit)? = null
    private var editorTouchAt: Long? = null
    val visible get() = root != null

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    // With window-content access disabled Android strips source window IDs. Identify our
    // own window by the view class it announces. Never inspect classes of other packages.
    private class PromptWindow(context: Context) : ScrollView(context) {
        override fun getAccessibilityClassName(): CharSequence = PromptWindow::class.java.name
        override fun onRequestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.className = accessibilityClassName
            return super.onRequestSendAccessibilityEvent(child, event)
        }
    }

    fun ownsEvent(event: AccessibilityEvent): Boolean {
        if (event.className == PromptWindow::class.java.name) return true
        // A tap into a populated EditText can create a separate insertion-handle window.
        // It is announced as a FrameLayout rather than as a child of PromptWindow.
        val touch = editorTouchAt ?: return false
        val view = root ?: return false
        val editor = view.findFocus() as? EditText ?: return false
        return !event.isFullScreen && event.className == "android.widget.FrameLayout" &&
            event.eventTime - touch in 0L..1_000L && editor.isShown && view.hasWindowFocus()
    }

    fun isEditing(): Boolean {
        val window = root ?: return false
        val editor = window.findFocus() as? EditText ?: return false
        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return window.hasWindowFocus() && editor.isShown && ime.isActive(editor)
    }

    fun hide() {
        val old = root ?: return
        root = null
        editorTouchAt = null
        onError = null
        runCatching { wm.removeViewImmediate(old) }
    }

    fun error(message: String) { onError?.invoke(message) }

    fun show(label: String, draft: GuardSessions.Draft, minChars: Int,
             onSubmit: (String) -> Unit, onPin: (String) -> Unit) {
        hide()
        val scroll = PromptWindow(themed).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(239, 244, 241))
            isFocusableInTouchMode = true
        }
        val layout = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        scroll.addView(layout)
        fun label(text: String, size: Float): TextView = TextView(themed).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(26, 49, 41))
            setPadding(0, dp(4), 0, dp(4))
            layout.addView(this)
        }
        fun button(caption: String): Button = Button(themed).apply { text = caption; layout.addView(this) }

        label(label, 16f)
        val title = label(draft.question, 24f).apply { setTypeface(typeface, Typeface.BOLD) }
        val hint = label("长按问题标题 5 秒可使用紧急 PIN，暂停所有提问 30 分钟。", 14f)
        val editor = EditText(themed).apply {
            this.hint = "写下这次使用的目的"
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(draft.text)
            setSelection(length())
            layout.addView(this)
        }
        val count = label("", 14f)
        val pin = EditText(themed).apply {
            this.hint = "紧急 PIN"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            visibility = View.GONE
            layout.addView(this)
        }
        for (field in listOf(editor, pin)) field.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) editorTouchAt = event.eventTime
            false
        }
        val message = label("", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val submit = button("保存并继续")
        val cancel = button("返回回答")
        var emergency = false
        var busy = false
        fun update() {
            val length = answerLength(editor.text.toString())
            count.text = context.getString(R.string.answered_count, length, minChars)
            submit.isEnabled = !busy && if (emergency) pin.length() > 0 else length >= minChars
            cancel.isEnabled = !busy
            editor.isEnabled = !busy
            pin.isEnabled = !busy
        }
        fun mode(usePin: Boolean) {
            emergency = usePin
            message.text = ""
            submit.text = if (usePin) "验证并暂停 30 分钟" else "保存并继续"
            listOf(editor, count, hint).forEach { it.visibility = if (usePin) View.GONE else View.VISIBLE }
            listOf(pin, cancel).forEach { it.visibility = if (usePin) View.VISIBLE else View.GONE }
            pin.text.clear()
            if (usePin) pin.requestFocus()
            update()
        }
        val unlock = Runnable { if (!busy && root === scroll && title.isPressed) mode(true) }
        title.isFocusable = true
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; view.postDelayed(unlock, 5000) }
                MotionEvent.ACTION_MOVE -> if (event.x !in 0f..view.width.toFloat() || event.y !in 0f..view.height.toFloat()) {
                    view.isPressed = false
                    view.removeCallbacks(unlock)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.removeCallbacks(unlock)
                }
            }
            true
        }
        title.setOnKeyListener { view, code, event ->
            if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_DPAD_CENTER) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    view.isPressed = true
                    view.postDelayed(unlock, 5000)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    view.isPressed = false
                    view.removeCallbacks(unlock)
                }
                true
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { draft.text = editor.text.toString(); update() }
        }
        editor.addTextChangedListener(watcher)
        pin.addTextChangedListener(watcher)
        mode(false)
        submit.setOnClickListener {
            if (!busy && submit.isEnabled) {
                busy = true
                update()
                if (emergency) {
                    val typed = pin.text.toString()
                    pin.text.clear()
                    onPin(typed)
                } else onSubmit(editor.text.toString())
            }
        }
        cancel.setOnClickListener { if (!busy) mode(false) }
        val params = WindowManager.LayoutParams(-1, -1, type, WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        wm.addView(scroll, params)
        root = scroll
        onError = { busy = false; message.text = it; update() }
    }
}
