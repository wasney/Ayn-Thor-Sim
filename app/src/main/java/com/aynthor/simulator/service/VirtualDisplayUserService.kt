package com.aynthor.simulator.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.hardware.input.InputManager
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.aynthor.simulator.IVirtualDisplayService
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

@SuppressLint("WrongConstant", "PrivateApi", "DiscouragedPrivateApi")
class VirtualDisplayUserService(private val baseContext: Context) : IVirtualDisplayService.Stub() {

    companion object {
        private const val TAG = "AynThorUserService"
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10 // 1024
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val MAX_LOG_LINES = 200
    }

    private val logLines = CopyOnWriteArrayList<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(msg: String, tr: Throwable? = null) {
        val ts = timeFormat.format(Date())
        val line = if (tr != null) {
            "$ts | $msg -> ${tr.javaClass.simpleName}: ${tr.message}"
        } else {
            "$ts | $msg"
        }
        Log.i(TAG, line, tr)
        logLines.add(line)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeAt(0)
        }
    }

    /**
     * Wraps Context so DisplayManager/InputManager see "com.android.shell" when running as UID 2000.
     * Prevents SecurityException: packageName must match the owner uid on Android 12-17.
     */
    private class ShellIdentityContext(base: Context) : ContextWrapper(base) {
        private val uid = Process.myUid()
        private val effectivePkg = if (uid == 2000) "com.android.shell" else base.packageName

        override fun getPackageName(): String = effectivePkg

        override fun getOpPackageName(): String = effectivePkg

        override fun getAttributionSource(): AttributionSource {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AttributionSource.Builder(uid)
                    .setPackageName(effectivePkg)
                    .build()
            } else {
                super.getAttributionSource()
            }
        }
    }

    private val shellContext: Context by lazy {
        val ctx = ShellIdentityContext(baseContext)
        log("Initialized ShellIdentityContext: uid=${Process.myUid()}, pkg=${ctx.packageName}, sdk=${Build.VERSION.SDK_INT}")
        ctx
    }

    private var topDisplay: VirtualDisplay? = null
    private var lastStickLogTime = 0L
    private var bottomDisplay: VirtualDisplay? = null

    private val displayManager: DisplayManager by lazy {
        // Prefer creating DisplayManager bound to shellContext via createPackageContext if possible,
        // otherwise use shellContext.getSystemService
        val dmCtx = try {
            if (Process.myUid() == 2000) {
                ShellIdentityContext(
                    baseContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
                )
            } else {
                shellContext
            }
        } catch (e: Throwable) {
            log("createPackageContext(com.android.shell) fallback", e)
            shellContext
        }
        dmCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val inputManager: InputManager by lazy {
        shellContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    private val injectInputEventMethod: Method? by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            log("Failed to reflect InputManager.injectInputEvent", e)
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
        log("createDisplays called: top=${topW}x${topH}, bottom=${bottomW}x${bottomH}")

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
            flagCandidates = listOfNotNull(
                if (trustedFlag != 0) topBaseFlags or trustedFlag else null,
                topBaseFlags,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
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
            flagCandidates = listOfNotNull(
                if (trustedFlag != 0) bottomBaseFlags or trustedFlag else null,
                bottomBaseFlags,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        )

        val topId = topDisplay?.display?.displayId ?: -1
        val bottomId = bottomDisplay?.display?.displayId ?: -1
        log("createDisplays result: topId=$topId, bottomId=$bottomId")
        return intArrayOf(topId, bottomId)
    }

    private fun createVirtualDisplayWithFallback(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flagCandidates: List<Int>
    ): VirtualDisplay? {
        for (flags in flagCandidates) {
            // Try VirtualDisplayConfig (API 33+) first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val config = VirtualDisplayConfig.Builder(name, width, height, dpi)
                        .setFlags(flags)
                        .setSurface(surface)
                        .build()
                    val vd = displayManager.createVirtualDisplay(config)
                    if (vd != null) {
                        log("Created $name via VirtualDisplayConfig flags=0x${Integer.toHexString(flags)} id=${vd.display.displayId}")
                        return vd
                    }
                } catch (e: Throwable) {
                    log("VirtualDisplayConfig failed for $name flags=0x${Integer.toHexString(flags)}", e)
                }
            }

            // Fallback to classic createVirtualDisplay
            try {
                val vd = displayManager.createVirtualDisplay(
                    name,
                    width,
                    height,
                    dpi,
                    surface,
                    flags
                )
                if (vd != null) {
                    log("Created $name via legacy API flags=0x${Integer.toHexString(flags)} id=${vd.display.displayId}")
                    return vd
                }
            } catch (e: Throwable) {
                log("Legacy createVirtualDisplay failed for $name flags=0x${Integer.toHexString(flags)}", e)
            }
        }
        return null
    }

    override fun swapSurfaces(topSurface: Surface, bottomSurface: Surface) {
        try {
            // Detach both first because a Surface can only have one active BufferQueue producer
            topDisplay?.surface = null
            bottomDisplay?.surface = null
            topDisplay?.surface = topSurface
            bottomDisplay?.surface = bottomSurface
            log("Swapped virtual display surfaces cleanly")
        } catch (e: Throwable) {
            log("Failed to swap surfaces", e)
        }
    }

    override fun focusDisplay(displayId: Int) {
        val atm = activityTaskManager ?: return
        try {
            val tasks = getTasksOnDisplay(displayId)
            val topTaskId = tasks.firstOrNull()?.let { extractTaskId(it) } ?: -1
            if (topTaskId > 0) {
                val focusMethod = atm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType)
                focusMethod.invoke(atm, topTaskId)
            }
        } catch (e: Throwable) {
            log("focusDisplay($displayId) failed", e)
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
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            applyDisplayId(event, displayId)
            injectEvent(event, displayId)
        } catch (e: Throwable) {
            log("injectTouchEvent error", e)
        } finally {
            event?.recycle()
        }
    }

    private fun linuxScanCodeForKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 103
        KeyEvent.KEYCODE_DPAD_LEFT -> 105
        KeyEvent.KEYCODE_DPAD_RIGHT -> 106
        KeyEvent.KEYCODE_DPAD_DOWN -> 108
        KeyEvent.KEYCODE_BUTTON_A -> 304
        KeyEvent.KEYCODE_BUTTON_B -> 305
        KeyEvent.KEYCODE_BUTTON_X -> 307
        KeyEvent.KEYCODE_BUTTON_Y -> 308
        KeyEvent.KEYCODE_BUTTON_L1 -> 310
        KeyEvent.KEYCODE_BUTTON_R1 -> 311
        KeyEvent.KEYCODE_BUTTON_L2 -> 312
        KeyEvent.KEYCODE_BUTTON_R2 -> 313
        KeyEvent.KEYCODE_BUTTON_SELECT -> 314
        KeyEvent.KEYCODE_BUTTON_START -> 315
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 317
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 318
        else -> 0
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
            val effectiveSource = if (source != 0) source else (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD)
            val event = KeyEvent(
                downTime,
                eventTime,
                action,
                keyCode,
                repeatCount,
                metaState,
                -1,
                0,
                0,
                effectiveSource
            )
            applyDisplayId(event, displayId)
            injectEvent(event, displayId)
        } catch (e: Throwable) {
            log("injectKeyEvent error", e)
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
                0,
                0,
                InputDevice.SOURCE_JOYSTICK,
                0
            )
            applyDisplayId(event, displayId)
            injectEvent(event, displayId)
        } catch (e: Throwable) {
            log("injectJoystickEvent error", e)
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
            log("Failed to set displayId=$displayId on InputEvent", e)
        }
    }

    @Synchronized
    private fun injectEvent(event: InputEvent, displayId: Int) {
        try {
            val ok = injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean
            if (event is KeyEvent) {
                log("injectKey(${KeyEvent.keyCodeToString(event.keyCode)}, act=${event.action}, disp=$displayId) -> $ok")
            } else if (event is MotionEvent && (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                val now = System.currentTimeMillis()
                val x = event.getAxisValue(MotionEvent.AXIS_X)
                val y = event.getAxisValue(MotionEvent.AXIS_Y)
                if (ok == false || (x == 0f && y == 0f) || now - lastStickLogTime > 250L) {
                    lastStickLogTime = now
                    log("injectJoy(x=${"%.2f".format(x)}, y=${"%.2f".format(y)}, disp=$displayId) -> $ok")
                }
            } else if (ok == false) {
                log("injectMotion(src=0x${Integer.toHexString(event.source)}, disp=$displayId) -> false")
            }
        } catch (e: Throwable) {
            log("injectInputEvent invocation failed", e)
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
            log("am start --display $displayId -n $component -> exitCode=$exitCode")
            exitCode == 0
        } catch (e: Throwable) {
            log("am start --display failed", e)
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


    private val activityTaskManager: Any? by lazy {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            atmClass.getMethod("getService").invoke(null)
        } catch (e: Throwable) {
            log("Failed to get IActivityTaskManager", e)
            null
        }
    }

    private fun getTasksOnDisplay(displayId: Int): List<Any> {
        val atm = activityTaskManager ?: return emptyList()
        return try {
            val method = atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            (method.invoke(atm, displayId) as? List<Any>) ?: emptyList()
        } catch (e: Throwable) {
            log("getAllRootTaskInfosOnDisplay($displayId) failed", e)
            emptyList()
        }
    }

    private fun extractTaskId(taskInfo: Any): Int {
        return try {
            taskInfo.javaClass.getField("taskId").getInt(taskInfo)
        } catch (_: Throwable) {
            -1
        }
    }

    private fun extractPackageName(taskInfo: Any): String? {
        return try {
            val topAct = taskInfo.javaClass.getField("topActivity").get(taskInfo) as? android.content.ComponentName
            val baseAct = taskInfo.javaClass.getField("baseActivity").get(taskInfo) as? android.content.ComponentName
            topAct?.packageName ?: baseAct?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    override fun swapTasksBetweenDisplays(topDisplayId: Int, bottomDisplayId: Int): Boolean {
        val atm = activityTaskManager ?: return false
        return try {
            val moveMethod = atm.javaClass.getMethod(
                "moveRootTaskToDisplay",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val topTasks = getTasksOnDisplay(topDisplayId)
            val bottomTasks = getTasksOnDisplay(bottomDisplayId)

            val topTaskId = topTasks.firstOrNull()?.let { extractTaskId(it) } ?: -1
            val bottomTaskId = bottomTasks.firstOrNull()?.let { extractTaskId(it) } ?: -1
            log("swapTasksBetweenDisplays: topTask=$topTaskId -> $bottomDisplayId, bottomTask=$bottomTaskId -> $topDisplayId")

            if (topTaskId > 0) {
                moveMethod.invoke(atm, topTaskId, bottomDisplayId)
            }
            if (bottomTaskId > 0) {
                moveMethod.invoke(atm, bottomTaskId, topDisplayId)
            }
            topTaskId > 0 || bottomTaskId > 0
        } catch (e: Throwable) {
            log("swapTasksBetweenDisplays failed", e)
            false
        }
    }

    override fun closeTasksOnDisplay(displayId: Int): Boolean {
        val atm = activityTaskManager
        val tasks = getTasksOnDisplay(displayId)
        if (tasks.isEmpty()) {
            log("closeTasksOnDisplay($displayId): no tasks found")
            return false
        }
        var closedAny = false
        val removeMethod = try {
            atm?.javaClass?.getMethod("removeTask", Int::class.javaPrimitiveType)
        } catch (_: Throwable) {
            null
        }

        for (task in tasks) {
            val taskId = extractTaskId(task)
            val pkg = extractPackageName(task)
            var removed = false
            if (atm != null && removeMethod != null && taskId > 0) {
                try {
                    removed = (removeMethod.invoke(atm, taskId) as? Boolean) == true
                    log("removeTask($taskId, pkg=$pkg) -> $removed")
                } catch (e: Throwable) {
                    log("removeTask($taskId) failed", e)
                }
            }
            if (!removed && !pkg.isNullOrBlank()) {
                removed = forceStopPackage(pkg)
            }
            if (removed) closedAny = true
        }
        return closedAny
    }

    override fun forceStopPackage(packageName: String): Boolean {
        return try {
            val process = ProcessBuilder("am", "force-stop", packageName)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            log("am force-stop $packageName -> exitCode=$exitCode")
            exitCode == 0
        } catch (e: Throwable) {
            log("am force-stop $packageName failed", e)
            false
        }
    }

    override fun getDebugLog(): String {
        return logLines.joinToString("\n")
    }

    override fun destroy() {
        releaseDisplays()
        exitProcess(0)
    }
}
