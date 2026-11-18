package com.aynthor.simulator

import android.app.ActivityOptions
import android.content.ComponentName
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

    private val ioExecutor = Executors.newSingleThreadExecutor()

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

    private var leftStickX = 0f
    private var leftStickY = 0f
    private var rightStickX = 0f
    private var rightStickY = 0f
    private val keyDownTimes = mutableMapOf<Int, Long>()

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
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IVirtualDisplayService.Stub.asInterface(binder)
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
            touchForwardingEnabled = prefs.isTopTouchForwardingEnabled()
            onTouchInject = { displayId, action, downTime, eventTime, count, ids, xs, ys, pressures ->
                ioExecutor.execute {
                    userService?.injectTouchEvent(
                        displayId, action, downTime, eventTime, count, ids, xs, ys, pressures
                    )
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
            touchForwardingEnabled = true
            onTouchInject = { displayId, action, downTime, eventTime, count, ids, xs, ys, pressures ->
                ioExecutor.execute {
                    userService?.injectTouchEvent(
                        displayId, action, downTime, eventTime, count, ids, xs, ys, pressures
                    )
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
            showLaunchOnBottomPicker()
        }

        binding.btnToggleTouch.setOnClickListener {
            val currentlyVisible = binding.leftGamepadContainer.visibility == View.VISIBLE
            val next = !currentlyVisible
            manualTouchOverride = next
            prefs.setTouchControlsEnabled(next)
            applyTouchControlsVisibility(next)
        }

        binding.btnToggleLeftStick.setOnClickListener {
            val next = !prefs.isLeftStickMode()
            prefs.setLeftStickMode(next)
            applyStickModes()
        }

        binding.btnToggleRightStick.setOnClickListener {
            val next = !prefs.isRightStickMode()
            prefs.setRightStickMode(next)
            applyStickModes()
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
            sendVirtualJoystick()
        }

        binding.virtualRightStick.onStickMove = { x, y ->
            rightStickX = x
            rightStickY = y
            sendVirtualJoystick()
        }

        applyStickModes()
    }

    private fun applyStickModes() {
        val leftStick = prefs.isLeftStickMode()
        binding.virtualDpad.visibility = if (leftStick) View.GONE else View.VISIBLE
        binding.virtualLeftStick.visibility = if (leftStick) View.VISIBLE else View.GONE

        val rightStick = prefs.isRightStickMode()
        binding.virtualFaceButtons.visibility = if (rightStick) View.GONE else View.VISIBLE
        binding.virtualRightStick.visibility = if (rightStick) View.VISIBLE else View.GONE
    }

    private fun updatePhysicalControllerState() {
        val ids = InputDevice.getDeviceIds()
        var found = false
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.isVirtual) continue
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

        val targetId = activeTopDisplayId
        if (targetId <= 0) return

        ioExecutor.execute {
            userService?.injectKeyEvent(
                targetId,
                action,
                keyCode,
                downTime,
                now,
                0,
                0,
                InputDevice.SOURCE_GAMEPAD
            )
        }
    }

    private fun sendVirtualJoystick() {
        val targetId = activeTopDisplayId
        if (targetId <= 0) return
        val now = SystemClock.uptimeMillis()
        ioExecutor.execute {
            userService?.injectJoystickEvent(
                targetId,
                now,
                now,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
                0f,
                0f,
                0f,
                0f
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepadKey = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (isGamepadKey && binding.launcherOverlay.visibility != View.VISIBLE) {
            val targetId = activeTopDisplayId
            if (targetId > 0) {
                ioExecutor.execute {
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

        if (isJoystick && binding.launcherOverlay.visibility != View.VISIBLE) {
            val targetId = activeTopDisplayId
            if (targetId > 0) {
                val ax = event.getAxisValue(MotionEvent.AXIS_X)
                val ay = event.getAxisValue(MotionEvent.AXIS_Y)
                val az = event.getAxisValue(MotionEvent.AXIS_Z)
                val arz = event.getAxisValue(MotionEvent.AXIS_RZ)
                val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

                ioExecutor.execute {
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
        if (primaryDisplayId > 0 && secondaryDisplayId > 0) return

        val topSurface = binding.topSurfaceView.holder.surface
        val bottomSurface = binding.bottomSurfaceView.holder.surface

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
                    Toast.makeText(this, "Failed to create VirtualDisplays", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun swapScreens() {
        val service = userService ?: return
        if (!topSurfaceReady || !bottomSurfaceReady || primaryDisplayId <= 0 || secondaryDisplayId <= 0) return

        isSwapped = !isSwapped
        val topSurface = binding.topSurfaceView.holder.surface
        val bottomSurface = binding.bottomSurfaceView.holder.surface

        ioExecutor.execute {
            if (isSwapped) {
                service.swapSurfaces(bottomSurface, topSurface)
            } else {
                service.swapSurfaces(topSurface, bottomSurface)
            }
            runOnUiThread {
                syncSurfaceMetadata()
                Toast.makeText(
                    this,
                    if (isSwapped) "Screens swapped" else "Screens restored",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun syncSurfaceMetadata() {
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

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        teardownShizukuDisplays()
        ioExecutor.shutdownNow()
    }
}
