package com.aynthor.simulator.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class VirtualDpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onKeyEvent: ((keyCode: Int, isDown: Boolean) -> Unit)? = null

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var upPressed = false
    private var downPressed = false
    private var leftPressed = false
    private var rightPressed = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#242429")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#484852")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C9AFF")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D0D0D8")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h) * 0.9f
        val cx = w / 2f
        val cy = h / 2f
        val arm = size / 3f
        val halfArm = arm / 2f
        val r = 14f

        // Up
        val upRect = RectF(cx - halfArm, cy - size / 2f, cx + halfArm, cy - halfArm)
        canvas.drawRoundRect(upRect, r, r, if (upPressed) activePaint else bgPaint)
        canvas.drawRoundRect(upRect, r, r, borderPaint)
        canvas.drawText("▲", cx, upRect.centerY() + 9f, labelPaint)

        // Down
        val downRect = RectF(cx - halfArm, cy + halfArm, cx + halfArm, cy + size / 2f)
        canvas.drawRoundRect(downRect, r, r, if (downPressed) activePaint else bgPaint)
        canvas.drawRoundRect(downRect, r, r, borderPaint)
        canvas.drawText("▼", cx, downRect.centerY() + 9f, labelPaint)

        // Left
        val leftRect = RectF(cx - size / 2f, cy - halfArm, cx - halfArm, cy + halfArm)
        canvas.drawRoundRect(leftRect, r, r, if (leftPressed) activePaint else bgPaint)
        canvas.drawRoundRect(leftRect, r, r, borderPaint)
        canvas.drawText("◀", leftRect.centerX(), cy + 9f, labelPaint)

        // Right
        val rightRect = RectF(cx + halfArm, cy - halfArm, cx + size / 2f, cy + halfArm)
        canvas.drawRoundRect(rightRect, r, r, if (rightPressed) activePaint else bgPaint)
        canvas.drawRoundRect(rightRect, r, r, borderPaint)
        canvas.drawText("▶", rightRect.centerX(), cy + 9f, labelPaint)

        // Center
        val centerRect = RectF(cx - halfArm, cy - halfArm, cx + halfArm, cy + halfArm)
        canvas.drawRect(centerRect, bgPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val idx = event.actionIndex
                    activePointerId = event.getPointerId(idx)
                    updateDirection(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    updateDirection(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    setDirections(false, false, false, false)
                }
            }
        }
        return true
    }

    private fun updateDirection(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val deadzone = minOf(width, height) * 0.06f
        if (hypot(dx, dy) < deadzone) {
            setDirections(false, false, false, false)
            return
        }
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        // 8-way directional sectors
        val r = angle > -67.5 && angle < 67.5
        val d = angle > 22.5 && angle < 157.5
        val l = angle > 112.5 || angle < -112.5
        val u = angle < -22.5 && angle > -157.5
        setDirections(u, d, l, r)
    }

    private fun setDirections(u: Boolean, d: Boolean, l: Boolean, r: Boolean) {
        // Always send releases (ACTION_UP) before new presses (ACTION_DOWN)
        if (!u && upPressed) {
            upPressed = false
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_UP, false)
        }
        if (!d && downPressed) {
            downPressed = false
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_DOWN, false)
        }
        if (!l && leftPressed) {
            leftPressed = false
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_LEFT, false)
        }
        if (!r && rightPressed) {
            rightPressed = false
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_RIGHT, false)
        }

        if (u && !upPressed) {
            upPressed = true
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_UP, true)
        }
        if (d && !downPressed) {
            downPressed = true
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_DOWN, true)
        }
        if (l && !leftPressed) {
            leftPressed = true
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_LEFT, true)
        }
        if (r && !rightPressed) {
            rightPressed = true
            onKeyEvent?.invoke(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        }
        invalidate()
    }
}

class VirtualThumbstickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onStickMove: ((axisX: Float, axisY: Float) -> Unit)? = null

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var anchorX = 0f
    private var anchorY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var lastSentX = 0f
    private var lastSentY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1D22")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#484852")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#52525E")
        style = Paint.Style.FILL
    }
    private val knobActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C9AFF")
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        anchorX = w / 2f
        anchorY = h / 2f
        knobX = anchorX
        knobY = anchorY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val outerR = maxOf(minOf(width, height) * 0.40f, 52f)
        val knobR = outerR * 0.42f

        canvas.drawCircle(anchorX, anchorY, outerR, basePaint)
        canvas.drawCircle(anchorX, anchorY, outerR, ringPaint)
        canvas.drawCircle(
            knobX,
            knobY,
            knobR,
            if (activePointerId != MotionEvent.INVALID_POINTER_ID) knobActivePaint else knobPaint
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val maxDist = maxOf(minOf(width, height) * 0.38f, 64f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val idx = event.actionIndex
                    activePointerId = event.getPointerId(idx)
                    // Floating origin: wherever the user taps becomes the center (0, 0)
                    anchorX = event.getX(idx)
                    anchorY = event.getY(idx)
                    knobX = anchorX
                    knobY = anchorY
                    emitIfChanged(0f, 0f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    updateStick(event.getX(idx), event.getY(idx), maxDist)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    anchorX = width / 2f
                    anchorY = height / 2f
                    knobX = anchorX
                    knobY = anchorY
                    emitIfChanged(0f, 0f)
                    invalidate()
                }
            }
        }
        return true
    }

    private fun updateStick(x: Float, y: Float, maxDist: Float) {
        if (maxDist <= 0f) return
        val dx = x - anchorX
        val dy = y - anchorY
        val dist = hypot(dx, dy)
        val deadzone = maxDist * 0.06f

        val nx: Float
        val ny: Float
        if (dist <= deadzone) {
            knobX = x
            knobY = y
            nx = 0f
            ny = 0f
        } else if (dist > maxDist) {
            val angle = atan2(dy, dx)
            knobX = anchorX + cos(angle) * maxDist
            knobY = anchorY + sin(angle) * maxDist
            nx = cos(angle)
            ny = sin(angle)
        } else {
            knobX = x
            knobY = y
            nx = (dx / maxDist).coerceIn(-1f, 1f)
            ny = (dy / maxDist).coerceIn(-1f, 1f)
        }
        emitIfChanged(nx, ny)
        invalidate()
    }

    private fun emitIfChanged(nx: Float, ny: Float) {
        if (nx != lastSentX || ny != lastSentY) {
            lastSentX = nx
            lastSentY = ny
            onStickMove?.invoke(nx, ny)
        }
    }
}

class VirtualFaceButtonsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onKeyEvent: ((keyCode: Int, isDown: Boolean) -> Unit)? = null

    private data class FaceBtn(
        val label: String,
        val keyCode: Int,
        val color: Int,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var radius: Float = 0f
    )

    // Nintendo/AYN Thor style diamond: Top=X, Right=A, Bottom=B, Left=Y
    private val buttons = listOf(
        FaceBtn("X", KeyEvent.KEYCODE_BUTTON_X, Color.parseColor("#3B82F6")),
        FaceBtn("A", KeyEvent.KEYCODE_BUTTON_A, Color.parseColor("#EF4444")),
        FaceBtn("B", KeyEvent.KEYCODE_BUTTON_B, Color.parseColor("#EAB308")),
        FaceBtn("Y", KeyEvent.KEYCODE_BUTTON_Y, Color.parseColor("#22C55E"))
    )

    private val pointerToKey = mutableMapOf<Int, Int>()
    private val activeKeys = mutableSetOf<Int>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#242429")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#484852")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val spread = minOf(w, h) * 0.29f
        val r = minOf(w, h) * 0.16f

        buttons[0].apply { this.cx = cx; this.cy = cy - spread; radius = r } // X (Top)
        buttons[1].apply { this.cx = cx + spread; this.cy = cy; radius = r } // A (Right)
        buttons[2].apply { this.cx = cx; this.cy = cy + spread; radius = r } // B (Bottom)
        buttons[3].apply { this.cx = cx - spread; this.cy = cy; radius = r } // Y (Left)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (btn in buttons) {
            val isPressed = activeKeys.contains(btn.keyCode)
            if (isPressed) {
                activePaint.color = btn.color
                canvas.drawCircle(btn.cx, btn.cy, btn.radius, activePaint)
            } else {
                canvas.drawCircle(btn.cx, btn.cy, btn.radius, bgPaint)
            }
            canvas.drawCircle(btn.cx, btn.cy, btn.radius, borderPaint)
            canvas.drawText(btn.label, btn.cx, btn.cy + 10f, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val hit = findButtonAt(event.getX(idx), event.getY(idx), 1.85f)
                if (hit != null) {
                    pointerToKey[pid] = hit.keyCode
                    recomputeActiveKeys()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val currentKey = pointerToKey[pid]
                    if (currentKey != null) {
                        val currentBtn = buttons.firstOrNull { it.keyCode == currentKey }
                        if (currentBtn != null && hypot(event.getX(i) - currentBtn.cx, event.getY(i) - currentBtn.cy) <= currentBtn.radius * 2.4f) {
                            continue
                        }
                    }
                    val hit = findButtonAt(event.getX(i), event.getY(i), 1.85f)
                    if (hit != null) {
                        pointerToKey[pid] = hit.keyCode
                    } else {
                        pointerToKey.remove(pid)
                    }
                }
                recomputeActiveKeys()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                pointerToKey.remove(pid)
                recomputeActiveKeys()
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerToKey.clear()
                recomputeActiveKeys()
            }
        }
        return true
    }

    private fun findButtonAt(x: Float, y: Float, multiplier: Float = 1.85f): FaceBtn? {
        return buttons
            .filter { hypot(x - it.cx, y - it.cy) <= it.radius * multiplier }
            .minByOrNull { hypot(x - it.cx, y - it.cy) }
    }

    private fun recomputeActiveKeys() {
        val newKeys = pointerToKey.values.toSet()
        for (k in activeKeys - newKeys) {
            onKeyEvent?.invoke(k, false)
        }
        for (k in newKeys - activeKeys) {
            onKeyEvent?.invoke(k, true)
        }
        activeKeys.clear()
        activeKeys.addAll(newKeys)
        invalidate()
    }
}

class VirtualButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var keyCode: Int = KeyEvent.KEYCODE_UNKNOWN
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }
    var onKeyEvent: ((keyCode: Int, isDown: Boolean) -> Unit)? = null

    private var isPressedState = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#242429")
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C9AFF")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#484852")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8F0")
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(4f, 4f, width - 4f, height - 4f)
        canvas.drawRoundRect(rect, 16f, 16f, if (isPressedState) activePaint else bgPaint)
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
        canvas.drawText(label, width / 2f, height / 2f + 8f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    activePointerId = event.getPointerId(event.actionIndex)
                    setPressedState(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pid = event.getPointerId(event.actionIndex)
                if (pid == activePointerId || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    setPressedState(false)
                }
            }
        }
        return true
    }

    private fun setPressedState(down: Boolean) {
        if (isPressedState != down && keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            isPressedState = down
            onKeyEvent?.invoke(keyCode, down)
            invalidate()
        }
    }
}
