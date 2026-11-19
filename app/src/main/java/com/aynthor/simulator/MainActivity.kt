package com.aynthor.simulator

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.aynthor.simulator.databinding.ActivityMainBinding
import com.aynthor.simulator.launcher.AppInfo
import com.aynthor.simulator.launcher.AppLauncherAdapter
import com.aynthor.simulator.launcher.AppRepository
import com.aynthor.simulator.prefs.SimulatorPrefs
import com.aynthor.simulator.service.VirtualDisplayUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 1001
        private const val TOP_WIDTH = 1920
        private const val TOP_HEIGHT = 1080
        private const val BOTTOM_WIDTH = 1240
        private const val BOTTOM_HEIGHT = 1080
        private const val VIRTUAL_DPI = 320
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SimulatorPrefs
    private lateinit var inputManager: InputManager
    private lateinit var launcherAdapter: AppLauncherAdapter
    private lateinit var bottomLauncherAdapter: AppLauncherAdapter
    private var lastBottomPackage: String? = null

    private val clientDeathToken = android.os.Binder()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private val joystickRunning = AtomicBoolean(false)
    private val joystickDirty = AtomicBoolean(false)
    private var lastBottomTouchTime = 0L

    private var userService: IVirtualDisplayService? = null
    private var isServiceBound = false
    private var topSurfaceReady = false
    private var bottomSurfaceReady = false

    private var primaryDisplayId = -1    // 1920x1080
    private var secondaryDisplayId = -1  // 1240x1080
    private var isSwapped = false

    private var allApps: List<AppInfo> = emptyList()
    private var physicalControllerConnected = false
    private var manualTouchOverride: Boolean? = null

    @Volatile private var leftStickX = 0f
    @Volatile private var leftStickY = 0f
    @Volatile private var rightStickX = 0f
    @Volatile private var rightStickY = 0f
    @Volatile private var hatX = 0f
    @Volatile private var hatY = 0f
    @Volatile private var lTrigger = 0f
    @Volatile private var rTrigger = 0f
    @Volatile private var stickDownTime = 0L
    private val keyDownTimes = mutableMapOf<Int, Long>()
    private val localLogs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun addLocalLog(msg: String) {
        localLogs.add(msg)
        while (localLogs.size > 40) {
            localLogs.removeAt(0)
        }
    }

    private val activeTopDisplayId: Int
        get() = if (isSwapped) secondaryDisplayId else primaryDisplayId

    private val activeBottomDisplayId: Int
        get() = if (isSwapped) primaryDisplayId else secondaryDisplayId

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, VirtualDisplayUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("virtual_display_service")
            .debuggable(false)
            .version(26)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IVirtualDisplayService.Stub.asInterface(binder)
            try { userService?.registerClientDeathToken(clientDeathToken) } catch (_: Throwable) {}
            isServiceBound = true
            tryCreateVirtualDisplays()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            isServiceBound = false
            primaryDisplayId = -1
            secondaryDisplayId = -1
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { checkShizukuAndBind() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            userService = null
            isServiceBound = false
            showShizukuOverlay(getString(R.string.shizuku_not_running), showAction = true, isPermission = false)
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                runOnUiThread {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        checkShizukuAndBind()
                    } else {
                        showShizukuOverlay(
                            getString(R.string.shizuku_permission_needed),
                            showAction = true,
                            isPermission = true
                        )
                    }
                }
            }
        }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updatePhysicalControllerState()
        override fun onInputDeviceRemoved(deviceId: Int) = updatePhysicalControllerState()
        override fun onInputDeviceChanged(deviceId: Int) = updatePhysicalControllerState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SimulatorPrefs(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        setupFullscreen()
        binding.root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
            }
        }
        setupSurfaces()
        setupLauncher()
        setupUtilityBar()
        setupVirtualGamepad()
        updatePhysicalControllerState()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        inputManager.registerInputDeviceListener(inputDeviceListener, null)

        checkShizukuAndBind()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupFullscreen()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupSurfaces() {
        binding.topSurfaceView.apply {
            virtualWidth = TOP_WIDTH
            virtualHeight = TOP_HEIGHT
            holder.setFixedSize(TOP_WIDTH, TOP_HEIGHT)
            touchForwardingEnabled = prefs.isTopTouchForwardingEnabled()
            onTouchInject = { displayId, action, downTime, eventTime, count, ids, xs, ys, pressures ->
                inputExecutor.execute {
                    userService?.injectTouchEvent(
                        displayId, action, downTime, eventTime, count, ids, xs, ys, pressures
                    )
                    if (displayId == secondaryDisplayId &&
                        (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP
                    ) {
                        lastBottomTouchTime = SystemClock.uptimeMillis()
                        userService?.focusDisplay(primaryDisplayId)
                    }
                }
            }
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    topSurfaceReady = true
                    tryCreateVirtualDisplays()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    topSurfaceReady = false
                }
            })
        }

        binding.bottomSurfaceView.apply {
            virtualWidth = BOTTOM_WIDTH
            virtualHeight = BOTTOM_HEIGHT
            holder.setFixedSize(BOTTOM_WIDTH, BOTTOM_HEIGHT)
            touchForwardingEnabled = true
            onTouchInject = { displayId, action, downTime, eventTime, count, ids, xs, ys, pressures ->
                inputExecutor.execute {
                    userService?.injectTouchEvent(
                        displayId, action, downTime, eventTime, count, ids, xs, ys, pressures
                    )
                    if (displayId == secondaryDisplayId &&
                        (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP
                    ) {
                        lastBottomTouchTime = SystemClock.uptimeMillis()
                        userService?.focusDisplay(primaryDisplayId)
                    }
                }
            }
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    bottomSurfaceReady = true
                    tryCreateVirtualDisplays()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    bottomSurfaceReady = false
                }
            })
        }
    }

    private fun setupLauncher() {
        launcherAdapter = AppLauncherAdapter { app ->
            launchAppOnDisplay(app, activeTopDisplayId, hideLauncherAfter = true)
        }
        binding.appRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = launcherAdapter
        }

        bottomLauncherAdapter = AppLauncherAdapter { app ->
            lastBottomPackage = app.packageName
            binding.bottomLauncherOverlay.visibility = View.GONE
            launchAppOnDisplay(app, activeBottomDisplayId, hideLauncherAfter = false)
        }
        binding.bottomAppRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = bottomLauncherAdapter
        }
        binding.btnCancelBottomLauncher.setOnClickListener {
            binding.bottomLauncherOverlay.visibility = View.GONE
        }
        binding.btnCloseBottomApp.setOnClickListener {
            closeBottomApp()
            binding.bottomLauncherOverlay.visibility = View.GONE
        }

        updateShowAllButtonText()
        binding.btnToggleShowAll.setOnClickListener {
            prefs.setShowAllApps(!prefs.isShowAllApps())
            updateShowAllButtonText()
            refreshLauncherList()
        }

        binding.btnFilterApps.setOnClickListener {
            showFilterAppsDialog()
        }

        ioExecutor.execute {
            val loaded = AppRepository.loadLaunchableApps(this)
            runOnUiThread {
                allApps = loaded
                refreshLauncherList()
            }
        }
    }

    private fun updateShowAllButtonText() {
        binding.btnToggleShowAll.text = if (prefs.isShowAllApps()) {
            getString(R.string.show_all_apps)
        } else {
            getString(R.string.show_filtered_apps)
        }
    }

    private fun refreshLauncherList() {
        val showAll = prefs.isShowAllApps()
        val hidden = prefs.getHiddenPackages()
        val filtered = if (showAll) {
            allApps
        } else {
            allApps.filterNot { hidden.contains(it.packageName) }
        }
        launcherAdapter.submitList(filtered)
        bottomLauncherAdapter.submitList(filtered)
    }

    private fun showFilterAppsDialog() {
        if (allApps.isEmpty()) return
        val hidden = prefs.getHiddenPackages()
        val labels = allApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(allApps.size) { i -> hidden.contains(allApps[i].packageName) }

        AlertDialog.Builder(this)
            .setTitle("Hide Apps from Launcher")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                prefs.setPackageHidden(allApps[which].packageName, isChecked)
            }
            .setPositiveButton("Done") { _, _ ->
                refreshLauncherList()
            }
            .show()
    }

    private fun setupUtilityBar() {
        binding.btnHome.setOnClickListener {
            val showing = binding.launcherOverlay.visibility == View.VISIBLE
            binding.launcherOverlay.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) {
                binding.appRecyclerView.requestFocus()
            }
        }

        binding.btnSwap.setOnClickListener {
            swapScreens()
        }

        binding.btnLaunchBottom.setOnClickListener {
            val showing = binding.bottomLauncherOverlay.visibility == View.VISIBLE
            binding.bottomLauncherOverlay.visibility = if (showing) View.GONE else View.VISIBLE
        }

        binding.btnCloseBottom.setOnClickListener {
            closeBottomApp()
        }

        binding.btnToggleTouch.setOnClickListener {
            val currentlyVisible = binding.leftGamepadContainer.visibility == View.VISIBLE
            val next = !currentlyVisible
            manualTouchOverride = next
            prefs.setTouchControlsEnabled(next)
            applyTouchControlsVisibility(next)
        }

        binding.btnToggleLeftStick.setOnClickListener {
            val next = (prefs.getLeftControlMode() + 1) % 3
            prefs.setLeftControlMode(next)
            applyStickModes()
        }

        binding.btnToggleRightStick.setOnClickListener {
            val next = (prefs.getRightControlMode() + 1) % 3
            prefs.setRightControlMode(next)
            applyStickModes()
        }

        binding.btnDebugLog.setOnClickListener {
            showDebugLogDialog()
        }

        binding.btnExit.setOnClickListener {
            shutdownAndExit()
        }
    }

    private fun setupVirtualGamepad() {
        binding.btnL1.apply {
            label = "L1"
            keyCode = KeyEvent.KEYCODE_BUTTON_L1
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnL2.apply {
            label = "L2"
            keyCode = KeyEvent.KEYCODE_BUTTON_L2
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnR1.apply {
            label = "R1"
            keyCode = KeyEvent.KEYCODE_BUTTON_R1
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnR2.apply {
            label = "R2"
            keyCode = KeyEvent.KEYCODE_BUTTON_R2
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnSelect.apply {
            label = "SELECT"
            keyCode = KeyEvent.KEYCODE_BUTTON_SELECT
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnStart.apply {
            label = "START"
            keyCode = KeyEvent.KEYCODE_BUTTON_START
            onKeyEvent = ::handleVirtualKey
        }

        binding.virtualDpad.onKeyEvent = ::handleVirtualKey
        binding.virtualFaceButtons.onKeyEvent = ::handleVirtualKey

        binding.virtualLeftStick.onStickMove = { x, y ->
            leftStickX = x
            leftStickY = y
            sendVirtualJoystick(force = (x == 0f && y == 0f))
        }

        binding.virtualRightStick.onStickMove = { x, y ->
            rightStickX = x
            rightStickY = y
            sendVirtualJoystick(force = (x == 0f && y == 0f))
        }

        applyStickModes()
    }

    private fun applyStickModes() {
        val leftMode = prefs.getLeftControlMode()
        binding.virtualDpad.visibility =
            if (leftMode == SimulatorPrefs.MODE_PRIMARY || leftMode == SimulatorPrefs.MODE_BOTH) View.VISIBLE else View.GONE
        binding.virtualLeftStick.visibility =
            if (leftMode == SimulatorPrefs.MODE_STICK || leftMode == SimulatorPrefs.MODE_BOTH) View.VISIBLE else View.GONE
        binding.btnToggleLeftStick.text = when (leftMode) {
            SimulatorPrefs.MODE_STICK -> "L-STK"
            SimulatorPrefs.MODE_BOTH -> "D+LS"
            else -> "D-PAD"
        }

        val rightMode = prefs.getRightControlMode()
        binding.virtualFaceButtons.visibility =
            if (rightMode == SimulatorPrefs.MODE_PRIMARY || rightMode == SimulatorPrefs.MODE_BOTH) View.VISIBLE else View.GONE
        binding.virtualRightStick.visibility =
            if (rightMode == SimulatorPrefs.MODE_STICK || rightMode == SimulatorPrefs.MODE_BOTH) View.VISIBLE else View.GONE
        binding.btnToggleRightStick.text = when (rightMode) {
            SimulatorPrefs.MODE_STICK -> "R-STK"
            SimulatorPrefs.MODE_BOTH -> "ABXY+RS"
            else -> "ABXY"
        }
    }

    private fun updatePhysicalControllerState() {
        val ids = InputDevice.getDeviceIds()
        var found = false
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.isVirtual || dev.name == "AYN Thor X360 Pad") continue
            val sources = dev.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (isGamepad) {
                found = true
                break
            }
        }
        physicalControllerConnected = found
        val showTouch = manualTouchOverride ?: (!found && prefs.isTouchControlsEnabled())
        applyTouchControlsVisibility(showTouch)
    }

    private fun applyTouchControlsVisibility(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        binding.leftGamepadContainer.visibility = vis
        binding.rightGamepadContainer.visibility = vis
    }

    private fun handleVirtualKey(keyCode: Int, isDown: Boolean) {
        val now = SystemClock.uptimeMillis()
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val downTime = if (isDown) {
            keyDownTimes[keyCode] = now
            now
        } else {
            keyDownTimes.remove(keyCode) ?: now
        }

        // If launcher overlay is visible, route D-pad & A button locally to navigate the launcher
        if (binding.launcherOverlay.visibility == View.VISIBLE) {
            val localEvent = KeyEvent(downTime, now, action, keyCode, 0)
            super.dispatchKeyEvent(localEvent)
            return
        }

        // Always target the top virtual display (primaryDisplayId) for gamepad controls
        val targetId = primaryDisplayId
        if (targetId <= 0) {
            addLocalLog("vKey $keyCode dropped: primaryDisplayId=$targetId")
            return
        }

        // Also update HAT axes when D-pad is pressed so games reading either KeyEvent or HAT work
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> hatX = if (isDown) -1f else if (hatX < 0f) 0f else hatX
            KeyEvent.KEYCODE_DPAD_RIGHT -> hatX = if (isDown) 1f else if (hatX > 0f) 0f else hatX
            KeyEvent.KEYCODE_DPAD_UP -> hatY = if (isDown) -1f else if (hatY < 0f) 0f else hatY
            KeyEvent.KEYCODE_DPAD_DOWN -> hatY = if (isDown) 1f else if (hatY > 0f) 0f else hatY
            KeyEvent.KEYCODE_BUTTON_L2 -> lTrigger = if (isDown) 1f else 0f
            KeyEvent.KEYCODE_BUTTON_R2 -> rTrigger = if (isDown) 1f else 0f
        }

        val source = if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD
        } else {
            InputDevice.SOURCE_GAMEPAD
        }

        if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_R2
        ) {
            sendVirtualJoystick()
        }

        inputExecutor.execute {
            if (lastBottomTouchTime > 0L) {
                lastBottomTouchTime = 0L
                userService?.focusDisplay(targetId)
            }
            userService?.injectKeyEvent(
                targetId,
                action,
                keyCode,
                downTime,
                now,
                0,
                0,
                source
            )

        }
    }

    private fun sendVirtualJoystick(force: Boolean = false) {
        val targetId = primaryDisplayId
        if (targetId <= 0) {
            addLocalLog("vStick dropped: primaryDisplayId=$targetId")
            return
        }
        val isCentered = leftStickX == 0f && leftStickY == 0f && rightStickX == 0f && rightStickY == 0f && lTrigger == 0f && rTrigger == 0f
        if (isCentered) {
            stickDownTime = 0L
        } else if (stickDownTime == 0L) {
            stickDownTime = SystemClock.uptimeMillis()
        }

        joystickDirty.set(true)
        if (!force && !joystickRunning.compareAndSet(false, true)) return

        inputExecutor.execute {
            try {
                while (joystickDirty.getAndSet(false)) {
                    val now = SystemClock.uptimeMillis()
                    val dTime = if (stickDownTime != 0L) stickDownTime else now
                    if (lastBottomTouchTime > 0L) {
                        lastBottomTouchTime = 0L
                        userService?.focusDisplay(targetId)
                    }
                    userService?.injectJoystickEvent(
                        targetId,
                        dTime,
                        now,
                        leftStickX,
                        leftStickY,
                        rightStickX,
                        rightStickY,
                        lTrigger,
                        rTrigger,
                        hatX,
                        hatY
                    )
                }
            } finally {
                joystickRunning.set(false)
                if (joystickDirty.get()) {
                    sendVirtualJoystick(force = false)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepadKey = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (event.device?.name == "AYN Thor X360 Pad") return true
        if (isGamepadKey && binding.launcherOverlay.visibility != View.VISIBLE) {
            val targetId = primaryDisplayId
            if (targetId > 0) {
                inputExecutor.execute {
                    userService?.injectKeyEvent(
                        targetId,
                        event.action,
                        event.keyCode,
                        event.downTime,
                        event.eventTime,
                        event.repeatCount,
                        event.metaState,
                        event.source
                    )
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

        if (event.device?.name == "AYN Thor X360 Pad") return true
        if (isJoystick && binding.launcherOverlay.visibility != View.VISIBLE) {
            val targetId = primaryDisplayId
            if (targetId > 0) {
                val ax = event.getAxisValue(MotionEvent.AXIS_X)
                val ay = event.getAxisValue(MotionEvent.AXIS_Y)
                val az = event.getAxisValue(MotionEvent.AXIS_Z)
                val arz = event.getAxisValue(MotionEvent.AXIS_RZ)
                val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

                inputExecutor.execute {
                    userService?.injectJoystickEvent(
                        targetId,
                        event.downTime,
                        event.eventTime,
                        ax, ay, az, arz, lt, rt, hx, hy
                    )
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun checkShizukuAndBind() {
        if (!Shizuku.pingBinder()) {
            showShizukuOverlay(
                getString(R.string.shizuku_not_running),
                showAction = true,
                isPermission = false
            )
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            showShizukuOverlay(
                getString(R.string.shizuku_permission_needed),
                showAction = true,
                isPermission = true
            )
            return
        }

        binding.shizukuOverlay.visibility = View.GONE
        if (!isServiceBound) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (e: Throwable) {
                showShizukuOverlay("Shizuku bind failed: ${e.message}", showAction = true, isPermission = false)
            }
        } else {
            tryCreateVirtualDisplays()
        }
    }

    private fun showShizukuOverlay(message: String, showAction: Boolean, isPermission: Boolean) {
        binding.shizukuOverlay.visibility = View.VISIBLE
        binding.shizukuStatusText.text = message
        binding.btnShizukuAction.visibility = if (showAction) View.VISIBLE else View.GONE
        binding.btnShizukuAction.text = if (isPermission) {
            getString(R.string.grant_shizuku)
        } else {
            getString(R.string.retry)
        }
        binding.btnShizukuAction.setOnClickListener {
            if (isPermission && Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            } else {
                checkShizukuAndBind()
            }
        }
    }

    private fun tryCreateVirtualDisplays() {
        val service = userService ?: return
        if (!topSurfaceReady || !bottomSurfaceReady) return

        val topSurface = binding.topSurfaceView.holder.surface
        val bottomSurface = binding.bottomSurfaceView.holder.surface

        // If displays already exist (e.g. returning after minimizing), re-attach the newly created Surfaces
        if (primaryDisplayId > 0 && secondaryDisplayId > 0) {
            ioExecutor.execute {
                service.swapSurfaces(topSurface, bottomSurface)
            }
            return
        }

        ioExecutor.execute {
            val ids = service.createDisplays(
                topSurface, TOP_WIDTH, TOP_HEIGHT, VIRTUAL_DPI,
                bottomSurface, BOTTOM_WIDTH, BOTTOM_HEIGHT, VIRTUAL_DPI
            )
            runOnUiThread {
                if (ids != null && ids.size >= 2 && ids[0] > 0 && ids[1] > 0) {
                    primaryDisplayId = ids[0]
                    secondaryDisplayId = ids[1]
                    syncSurfaceMetadata()
                } else {
                    localLogs.add("createDisplays returned invalid IDs: ${ids?.contentToString()}")
                    showDebugLogDialog()
                }
            }
        }
    }

    private fun swapScreens() {
        val service = userService ?: return
        if (primaryDisplayId <= 0 || secondaryDisplayId <= 0) return

        ioExecutor.execute {
            val ok = service.swapTasksBetweenDisplays(primaryDisplayId, secondaryDisplayId)
            runOnUiThread {
                if (ok) {
                    binding.launcherOverlay.visibility = View.GONE
                    binding.bottomLauncherOverlay.visibility = View.GONE
                    Toast.makeText(this, "Swapped top & bottom apps", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No active apps to swap", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncSurfaceMetadata() {
        val topParams = binding.topScreenContainer.layoutParams as ConstraintLayout.LayoutParams
        val bottomParams = binding.bottomScreenContainer.layoutParams as ConstraintLayout.LayoutParams

        if (isSwapped) {
            binding.topSurfaceView.apply {
                targetDisplayId = secondaryDisplayId
                virtualWidth = BOTTOM_WIDTH
                virtualHeight = BOTTOM_HEIGHT
            }
            binding.bottomSurfaceView.apply {
                targetDisplayId = primaryDisplayId
                virtualWidth = TOP_WIDTH
                virtualHeight = TOP_HEIGHT
            }
            topParams.dimensionRatio = "1240:1080"
            bottomParams.dimensionRatio = "16:9"
        } else {
            binding.topSurfaceView.apply {
                targetDisplayId = primaryDisplayId
                virtualWidth = TOP_WIDTH
                virtualHeight = TOP_HEIGHT
            }
            binding.bottomSurfaceView.apply {
                targetDisplayId = secondaryDisplayId
                virtualWidth = BOTTOM_WIDTH
                virtualHeight = BOTTOM_HEIGHT
            }
            topParams.dimensionRatio = "16:9"
            bottomParams.dimensionRatio = "1240:1080"
        }
        binding.topScreenContainer.layoutParams = topParams
        binding.bottomScreenContainer.layoutParams = bottomParams
    }

    private fun closeBottomApp() {
        val service = userService ?: return
        if (secondaryDisplayId <= 0) return
        ioExecutor.execute {
            var ok = service.closeTasksOnDisplay(secondaryDisplayId)
            if (!ok && !lastBottomPackage.isNullOrBlank()) {
                ok = service.forceStopPackage(lastBottomPackage!!)
            }
            runOnUiThread {
                if (ok) {
                    lastBottomPackage = null
                    Toast.makeText(this, "Closed bottom app", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No active app on bottom screen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLaunchOnBottomPicker() {
        val targetDisplayId = activeBottomDisplayId
        if (targetDisplayId <= 0) {
            Toast.makeText(this, "Bottom virtual display not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val showAll = prefs.isShowAllApps()
        val hidden = prefs.getHiddenPackages()
        val candidates = if (showAll) allApps else allApps.filterNot { hidden.contains(it.packageName) }
        if (candidates.isEmpty()) return

        val names = candidates.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Launch App on Bottom Screen")
            .setItems(names) { _, which ->
                launchAppOnDisplay(candidates[which], targetDisplayId, hideLauncherAfter = false)
            }
            .show()
    }

    private fun launchAppOnDisplay(app: AppInfo, displayId: Int, hideLauncherAfter: Boolean) {
        if (displayId <= 0) {
            Toast.makeText(this, "Virtual display is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        var launched = false
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            startActivity(intent, options.toBundle())
            launched = true
        } catch (_: Throwable) {
            launched = false
        }

        if (!launched) {
            ioExecutor.execute {
                val ok = userService?.launchAppOnDisplay(app.packageName, app.activityName, displayId) == true
                runOnUiThread {
                    if (ok && hideLauncherAfter) {
                        binding.launcherOverlay.visibility = View.GONE
                    } else if (!ok) {
                        Toast.makeText(this, "Failed to launch ${app.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (hideLauncherAfter) {
            addLocalLog("Launched ${app.packageName} on display $displayId")
            binding.root.postDelayed({ ioExecutor.execute { userService?.focusDisplay(displayId) } }, 450L)
            binding.launcherOverlay.visibility = View.GONE
        }
    }

    private fun shutdownAndExit() {
        teardownShizukuDisplays()
        finishAndRemoveTask()
    }

    private fun teardownShizukuDisplays() {
        try {
            userService?.releaseDisplays()
            userService?.destroy()
        } catch (_: Throwable) {
        }
        try {
            if (isServiceBound) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        } catch (_: Throwable) {
        }
        userService = null
        isServiceBound = false
        primaryDisplayId = -1
        secondaryDisplayId = -1
    }


    private fun showDebugLogDialog() {
        ioExecutor.execute {
            val serviceLog = try {
                userService?.debugLog ?: "(UserService not bound)"
            } catch (e: Throwable) {
                "(Failed to read UserService log: ${e.message})"
            }
            val fullLog = buildString {
                appendLine("=== Device & Shizuku ===")
                appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Shizuku alive: ${Shizuku.pingBinder()}, Bound: $isServiceBound")
                appendLine("Primary(1920x1080) ID: $primaryDisplayId, Secondary(1240x1080) ID: $secondaryDisplayId, Swapped: $isSwapped")
                if (localLogs.isNotEmpty()) {
                    appendLine("\n=== App Log ===")
                    localLogs.forEach { appendLine(it) }
                }
                appendLine("\n=== UserService Log ===")
                appendLine(serviceLog.ifBlank { "(empty)" })
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Debug Log")
                    .setMessage(fullLog)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AynThorDebugLog", fullLog))
                        Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }


    override fun onPause() {
        super.onPause()
        // Release any held virtual keys so nothing stays stuck when switching apps
        val heldKeys = keyDownTimes.keys.toList()
        for (kc in heldKeys) {
            handleVirtualKey(kc, false)
        }
        if (leftStickX != 0f || leftStickY != 0f || rightStickX != 0f || rightStickY != 0f || lTrigger != 0f || rTrigger != 0f) {
            leftStickX = 0f
            leftStickY = 0f
            rightStickX = 0f
            rightStickY = 0f
            lTrigger = 0f
            rTrigger = 0f
            hatX = 0f
            hatY = 0f
            sendVirtualJoystick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        teardownShizukuDisplays()
        ioExecutor.shutdownNow()
        inputExecutor.shutdownNow()
    }
}
