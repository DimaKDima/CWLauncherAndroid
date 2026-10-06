package ru.cw.launcher.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import ru.cw.launcher.engine.CwLog
import ru.cw.launcher.engine.PhoneVm
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GameActivity : Activity() {
    private val started = AtomicBoolean(false)
    private var lookX = 0f
    private var lookY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var typing = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width < 16 || height < 16) return
                if (!started.compareAndSet(false, true)) return
                val windowSurface = holder.surface
                thread(name = "cw-jvm", isDaemon = false) {
                    var code = -1
                    var message: String? = null
                    var windowReady = false
                    try {
                        code = PhoneVm.launch(this@GameActivity, windowSurface, width, height)
                        windowReady = true
                        CwLog.info("Игра завершилась с кодом $code")
                    } catch (t: Throwable) {
                        code = -1
                        message = t.message ?: "Игра не запустилась"
                        CwLog.error("Игра не запустилась: ${t.message}")
                    } finally {
                        if (windowReady) {
                            try {
                                JREUtils.releaseBridgeWindow()
                            } catch (_: Throwable) {
                            }
                        }
                        PhoneVm.mark(PhoneVm.Stage.EXITED, code, message)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        surface.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(controls(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    @Deprecated("Кнопка назад закрывает меню игры")
    override fun onBackPressed() {
        CallbackBridge.tapKey(256)
    }

    private fun handleTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                if (!CallbackBridge.isGrabbing()) CallbackBridge.sendCursorPos(x, y)
                CallbackBridge.sendMouseButton(0, 1, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (CallbackBridge.isGrabbing()) {
                    lookX += (x - lastX) * 1.5f
                    lookY += (y - lastY) * 1.5f
                    CallbackBridge.sendCursorPos(lookX, lookY)
                } else {
                    CallbackBridge.sendCursorPos(x, y)
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> CallbackBridge.sendMouseButton(0, 0, 0)
        }
    }

    private fun controls(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0x99000000.toInt())
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        hold(bar, "W", 87)
        hold(bar, "A", 65)
        hold(bar, "S", 83)
        hold(bar, "D", 68)
        hold(bar, "Прыжок", 32)
        hold(bar, "Шифт", 340)
        tap(bar, "ЛКМ") { CallbackBridge.sendMouseButton(0, 1, 0); CallbackBridge.sendMouseButton(0, 0, 0) }
        tap(bar, "ПКМ") { CallbackBridge.sendMouseButton(1, 1, 0); CallbackBridge.sendMouseButton(1, 0, 0) }
        tap(bar, "Esc") { CallbackBridge.tapKey(256) }
        tap(bar, "E") { CallbackBridge.tapKey(69) }
        tap(bar, "Чат") { CallbackBridge.tapKey(84) }
        val input = EditText(this)
        input.hint = "текст"
        input.setHintTextColor(0x88FFFFFF.toInt())
        input.setTextColor(Color.WHITE)
        input.setBackgroundColor(0x33000000)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setSingleLine(true)
        input.minimumWidth = dp(96)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val now = s?.toString().orEmpty()
                if (now.length > typing.length && now.startsWith(typing)) {
                    now.substring(typing.length).forEach { CallbackBridge.sendChar(it) }
                } else if (now.length < typing.length) {
                    repeat(typing.length - now.length) { CallbackBridge.tapKey(259) }
                }
                typing = now
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            CallbackBridge.tapKey(257)
            true
        }
        bar.addView(input, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        tap(bar, "Клавиатура") {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        tap(bar, "Закрыть") {
            PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        return bar
    }

    private fun hold(bar: LinearLayout, label: String, key: Int) {
        val button = keyButton(label)
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> CallbackBridge.sendKey(key, 0, 1, 0)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> CallbackBridge.sendKey(key, 0, 0, 0)
            }
            true
        }
        bar.addView(button)
    }

    private fun tap(bar: LinearLayout, label: String, action: () -> Unit) {
        val button = keyButton(label)
        button.setOnClickListener { action() }
        bar.addView(button)
    }

    private fun keyButton(label: String): Button {
        val button = Button(this)
        button.text = label
        button.setTextColor(Color.WHITE)
        button.textSize = 11f
        button.setBackgroundColor(0x553A5A8C)
        button.setPadding(dp(6), dp(2), dp(6), dp(2))
        button.minimumWidth = 0
        button.minWidth = 0
        button.minimumHeight = 0
        button.minHeight = 0
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        params.marginEnd = dp(3)
        button.layoutParams = params
        return button
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
