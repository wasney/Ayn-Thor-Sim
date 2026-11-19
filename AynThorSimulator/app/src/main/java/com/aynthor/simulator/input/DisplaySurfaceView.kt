package com.aynthor.simulator.input

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView

class DisplaySurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    var virtualWidth: Int = 1920
    var virtualHeight: Int = 1080
    var targetDisplayId: Int = -1
    var touchForwardingEnabled: Boolean = true

    var onTouchInject: ((
        displayId: Int,
        action: Int,
        downTime: Long,
        eventTime: Long,
        pointerCount: Int,
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray
    ) -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchForwardingEnabled || targetDisplayId <= 0 || width <= 0 || height <= 0) {
            return super.onTouchEvent(event)
        }

        val count = event.pointerCount
        if (count <= 0) return false

        val pointerIds = IntArray(count)
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val pressures = FloatArray(count)

        val scaleX = virtualWidth.toFloat() / width.toFloat()
        val scaleY = virtualHeight.toFloat() / height.toFloat()

        for (i in 0 until count) {
            pointerIds[i] = event.getPointerId(i)
            xs[i] = (event.getX(i) * scaleX).coerceIn(0f, (virtualWidth - 1).toFloat())
            ys[i] = (event.getY(i) * scaleY).coerceIn(0f, (virtualHeight - 1).toFloat())
            pressures[i] = event.getPressure(i).coerceIn(0.1f, 1.0f)
        }

        onTouchInject?.invoke(
            targetDisplayId,
            event.action,
            event.downTime,
            event.eventTime,
            count,
            pointerIds,
            xs,
            ys,
            pressures
        )
        return true
    }
}
