package com.aynthor.simulator.service

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.aynthor.simulator.IVirtualDisplayService
import java.lang.reflect.Method
import kotlin.system.exitProcess

@SuppressLint("WrongConstant", "PrivateApi", "DiscouragedPrivateApi")
class VirtualDisplayUserService(private val context: Context) : IVirtualDisplayService.Stub() {

    companion object {
        private const val TAG = "AynThorUserService"
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10 // 1024
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }

    private var topDisplay: VirtualDisplay? = null
    private var bottomDisplay: VirtualDisplay? = null

    private val displayManager: DisplayManager by lazy {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val inputManager: InputManager by lazy {
        context.getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    private val injectInputEventMethod: Method? by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to reflect InputManager.injectInputEvent", e)
            null
        }
    }

    private val setDisplayIdEventMethod: Method? by lazy {
        try {
            InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private val setDisplayIdManagerMethod: Method? by lazy {
        try {
            InputManager::class.java.getMethod(
                "setDisplayId",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveTrustedFlag(): Int {
        return try {
            DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null)
        } catch (_: Throwable) {
            VIRTUAL_DISPLAY_FLAG_TRUSTED
        }
    }

    override fun createDisplays(
        topSurface: Surface,
        topW: Int,
        topH: Int,
        topDpi: Int,
        bottomSurface: Surface,
        bottomW: Int,
        bottomH: Int,
        bottomDpi: Int
    ): IntArray {
        releaseDisplays()

        val trustedFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveTrustedFlag()
        } else {
            0
        }

        val topBaseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        topDisplay = createVirtualDisplayWithFallback(
            name = "AYN-Thor-Top",
            width = topW,
            height = topH,
            dpi = topDpi,
            surface = topSurface,
            baseFlags = topBaseFlags,
            trustedFlag = trustedFlag
        )

        val bottomBaseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        bottomDisplay = createVirtualDisplayWithFallback(
            name = "AYN-Thor-Bottom",
            width = bottomW,
            height = bottomH,
            dpi = bottomDpi,
            surface = bottomSurface,
            baseFlags = bottomBaseFlags,
            trustedFlag = trustedFlag
        )

        val topId = topDisplay?.display?.displayId ?: -1
        val bottomId = bottomDisplay?.display?.displayId ?: -1
        Log.i(TAG, "Created VirtualDisplays: topId=$topId, bottomId=$bottomId")
        return intArrayOf(topId, bottomId)
    }

    private fun createVirtualDisplayWithFallback(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        baseFlags: Int,
        trustedFlag: Int
    ): VirtualDisplay? {
        if (trustedFlag != 0) {
            try {
                return displayManager.createVirtualDisplay(
                    name,
                    width,
                    height,
                    dpi,
                    surface,
                    baseFlags or trustedFlag
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "VIRTUAL_DISPLAY_FLAG_TRUSTED rejected for $name, falling back", e)
            } catch (e: Throwable) {
                Log.w(TAG, "createVirtualDisplay with trusted flag failed for $name", e)
            }
        }
        return try {
            displayManager.createVirtualDisplay(
                name,
                width,
                height,
                dpi,
                surface,
                baseFlags
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create VirtualDisplay $name", e)
            null
        }
    }

    override fun swapSurfaces(topSurface: Surface, bottomSurface: Surface) {
        try {
            topDisplay?.surface = topSurface
            bottomDisplay?.surface = bottomSurface
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to swap surfaces", e)
        }
    }

    override fun injectTouchEvent(
        displayId: Int,
        action: Int,
        downTime: Long,
        eventTime: Long,
        pointerCount: Int,
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray
    ) {
        if (displayId <= 0 || pointerCount <= 0) return
        var event: MotionEvent? = null
        try {
            val properties = Array(pointerCount) { i ->
                MotionEvent.PointerProperties().apply {
                    id = pointerIds[i]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(pointerCount) { i ->
                MotionEvent.PointerCoords().apply {
                    x = xs[i]
                    y = ys[i]
                    pressure = pressures[i]
                    size = 1.0f
                }
            }

            event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pointerCount,
                properties,
                coords,
                0, // metaState
                0, // buttonState
                1.0f, // xPrecision
                1.0f, // yPrecision
                0, // deviceId
                0, // edgeFlags
                InputDevice.SOURCE_TOUCHSCREEN,
                0 // flags
            )
            applyDisplayId(event, displayId)
            injectEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "injectTouchEvent error", e)
        } finally {
            event?.recycle()
        }
    }

    override fun injectKeyEvent(
        displayId: Int,
        action: Int,
        keyCode: Int,
        downTime: Long,
        eventTime: Long,
        repeatCount: Int,
        metaState: Int,
        source: Int
    ) {
        if (displayId <= 0) return
        try {
            val effectiveSource = if (source != 0) source else InputDevice.SOURCE_GAMEPAD
            val event = KeyEvent(
                downTime,
                eventTime,
                action,
                keyCode,
                repeatCount,
                metaState,
                -1, // deviceId (virtual)
                0,  // scanCode
                KeyEvent.FLAG_FROM_SYSTEM,
                effectiveSource
            )
            applyDisplayId(event, displayId)
            injectEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "injectKeyEvent error", e)
        }
    }

    override fun injectJoystickEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        axisX: Float,
        axisY: Float,
        axisZ: Float,
        axisRz: Float,
        axisLTrigger: Float,
        axisRTrigger: Float,
        axisHatX: Float,
        axisHatY: Float
    ) {
        if (displayId <= 0) return
        var event: MotionEvent? = null
        try {
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_UNKNOWN
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    setAxisValue(MotionEvent.AXIS_X, axisX)
                    setAxisValue(MotionEvent.AXIS_Y, axisY)
                    setAxisValue(MotionEvent.AXIS_Z, axisZ)
                    setAxisValue(MotionEvent.AXIS_RZ, axisRz)
                    setAxisValue(MotionEvent.AXIS_LTRIGGER, axisLTrigger)
                    setAxisValue(MotionEvent.AXIS_RTRIGGER, axisRTrigger)
                    setAxisValue(MotionEvent.AXIS_HAT_X, axisHatX)
                    setAxisValue(MotionEvent.AXIS_HAT_Y, axisHatY)
                }
            )
            event = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                1,
                properties,
                coords,
                0,
                0,
                1.0f,
                1.0f,
                -1,
                0,
                InputDevice.SOURCE_JOYSTICK,
                0
            )
            applyDisplayId(event, displayId)
            injectEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "injectJoystickEvent error", e)
        } finally {
            event?.recycle()
        }
    }

    private fun applyDisplayId(event: InputEvent, displayId: Int) {
        try {
            if (setDisplayIdEventMethod != null) {
                setDisplayIdEventMethod?.invoke(event, displayId)
                return
            }
            setDisplayIdManagerMethod?.invoke(null, event, displayId)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set displayId=$displayId on InputEvent", e)
        }
    }

    private fun injectEvent(event: InputEvent) {
        try {
            injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (e: Throwable) {
            Log.e(TAG, "injectInputEvent invocation failed", e)
        }
    }

    override fun launchAppOnDisplay(
        packageName: String,
        activityName: String,
        displayId: Int
    ): Boolean {
        return try {
            val component = "$packageName/$activityName"
            val process = ProcessBuilder(
                "am", "start",
                "--display", displayId.toString(),
                "-n", component,
                "--activity-single-top"
            ).redirectErrorStream(true).start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Throwable) {
            Log.e(TAG, "am start --display failed", e)
            false
        }
    }

    override fun releaseDisplays() {
        try {
            topDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            topDisplay = null
        }

        try {
            bottomDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            bottomDisplay = null
        }
    }

    override fun destroy() {
        releaseDisplays()
        exitProcess(0)
    }
}
