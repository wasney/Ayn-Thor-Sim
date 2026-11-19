package com.aynthor.simulator

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
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
    private val perfExecutor = Executors.newSingleThreadExecutor()
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
    private var topSearchQuery = ""
    private var bottomSearchQuery = ""
    private var selectedTopFolder: String? = null
    private var selectedBottomFolder: String? = null
    private var lastLauncherStickNavMs = 0L
    private var isLightThemeActive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val homeRefreshRunnable = object : Runnable {
        override fun run() {
            updateBottomHomeDashboard()
            uiHandler.postDelayed(this, 3000L)
        }
    }

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
    private val keyRepeatRunnables = mutableMapOf<Int, Runnable>()
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
            .version(37)
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
        setupBottomHomeScreen()
        setupVirtualGamepad()
        applyAppTheme()
        updatePhysicalControllerState()
        uiHandler.post(homeRefreshRunnable)

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
        launcherAdapter = AppLauncherAdapter(
            onAppClick = { app ->
                prefs.recordAppLaunch(app.packageName)
                launchAppOnDisplay(app, activeTopDisplayId, hideLauncherAfter = true)
            },
            onAppLongClick = { app -> showAppContextMenu(app) }
        )
        binding.appRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = launcherAdapter
        }

        bottomLauncherAdapter = AppLauncherAdapter(
            onAppClick = { app ->
                prefs.recordAppLaunch(app.packageName)
                lastBottomPackage = app.packageName
                binding.bottomLauncherOverlay.visibility = View.GONE
                binding.bottomHomeOverlay.visibility = View.GONE
                launchAppOnDisplay(app, activeBottomDisplayId, hideLauncherAfter = false)
            },
            onAppLongClick = { app -> showAppContextMenu(app) }
        )
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

        binding.etTopSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                topSearchQuery = s?.toString()?.trim().orEmpty()
                refreshLauncherList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etBottomSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                bottomSearchQuery = s?.toString()?.trim().orEmpty()
                refreshLauncherList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnTopSort.setOnClickListener {
            val next = (prefs.getTopSortMode() + 1) % 3
            prefs.setTopSortMode(next)
            updateDrawerFilterButtons()
            refreshLauncherList()
        }

        binding.btnBottomSort.setOnClickListener {
            val next = (prefs.getBottomSortMode() + 1) % 3
            prefs.setBottomSortMode(next)
            updateDrawerFilterButtons()
            refreshLauncherList()
        }

        binding.btnTopFolder.setOnClickListener { showFolderPickerDialog(isTop = true) }
        binding.btnBottomFolder.setOnClickListener { showFolderPickerDialog(isTop = false) }

        updateShowAllButtonText()
        updateDrawerFilterButtons()
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
                if (launcherAdapter.itemCount > 0) {
                    launcherAdapter.setSelectedPosition(0)
                }
            }
        }
    }

    private fun sortLabel(mode: Int): String = when (mode) {
        SimulatorPrefs.SORT_ZA -> "Z-A"
        SimulatorPrefs.SORT_RECENT -> "Recent"
        else -> "A-Z"
    }

    private fun updateDrawerFilterButtons() {
        binding.btnTopSort.text = sortLabel(prefs.getTopSortMode())
        binding.btnBottomSort.text = sortLabel(prefs.getBottomSortMode())
        binding.btnTopFolder.text = selectedTopFolder ?: "All"
        binding.btnBottomFolder.text = selectedBottomFolder ?: "All"
    }

    private fun updateShowAllButtonText() {
        binding.btnToggleShowAll.text = if (prefs.isShowAllApps()) {
            getString(R.string.show_all_apps)
        } else {
            getString(R.string.show_filtered_apps)
        }
    }

    private fun filterAndSortApps(query: String, folder: String?, sortMode: Int): List<AppInfo> {
        val showAll = prefs.isShowAllApps()
        val hidden = prefs.getHiddenPackages()
        val folderPkgs = folder?.let { prefs.getAppsInFolder(it) }

        val filtered = allApps.filter { app ->
            val passHidden = showAll || !hidden.contains(app.packageName)
            val passFolder = folderPkgs == null || folderPkgs.contains(app.packageName)
            val passQuery = query.isEmpty() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            passHidden && passFolder && passQuery
        }

        return when (sortMode) {
            SimulatorPrefs.SORT_ZA -> filtered.sortedByDescending { it.label.lowercase(Locale.getDefault()) }
            SimulatorPrefs.SORT_RECENT -> filtered.sortedWith(
                compareByDescending<AppInfo> { prefs.getLastLaunchTime(it.packageName) }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
            else -> filtered.sortedBy { it.label.lowercase(Locale.getDefault()) }
        }
    }

    private fun refreshLauncherList() {
        val topList = filterAndSortApps(topSearchQuery, selectedTopFolder, prefs.getTopSortMode())
        val bottomList = filterAndSortApps(bottomSearchQuery, selectedBottomFolder, prefs.getBottomSortMode())
        launcherAdapter.submitList(topList)
        bottomLauncherAdapter.submitList(bottomList)
        if (topList.isNotEmpty() && launcherAdapter.selectedPosition < 0) {
            launcherAdapter.setSelectedPosition(0)
        }
    }

    private fun showFolderPickerDialog(isTop: Boolean) {
        val folders = prefs.getFolders()
        val options = mutableListOf("All Apps")
        options.addAll(folders)
        options.add("+ Create New Folder")

        AlertDialog.Builder(this)
            .setTitle(if (isTop) "Top Drawer Folder" else "Bottom Drawer Folder")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        if (isTop) selectedTopFolder = null else selectedBottomFolder = null
                        updateDrawerFilterButtons()
                        refreshLauncherList()
                    }
                    which == options.lastIndex -> showCreateFolderDialog()
                    else -> {
                        val chosen = folders[which - 1]
                        if (isTop) selectedTopFolder = chosen else selectedBottomFolder = chosen
                        updateDrawerFilterButtons()
                        refreshLauncherList()
                    }
                }
            }
            .show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "Folder name (e.g. Emulators, Switch)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Create Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.addFolder(name)
                    Toast.makeText(this, "Folder '$name' created. Long-press any app to add it.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppContextMenu(app: AppInfo) {
        val folders = prefs.getFolders()
        val isHidden = prefs.getHiddenPackages().contains(app.packageName)
        val actions = mutableListOf(
            if (isHidden) "Unhide App" else "Hide App",
            "Assign to Folders…"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        prefs.setPackageHidden(app.packageName, !isHidden)
                        refreshLauncherList()
                    }
                    1 -> {
                        if (folders.isEmpty()) {
                            showCreateFolderDialog()
                        } else {
                            val checked = BooleanArray(folders.size) { i ->
                                prefs.getAppsInFolder(folders[i]).contains(app.packageName)
                            }
                            AlertDialog.Builder(this)
                                .setTitle("Folders for ${app.label}")
                                .setMultiChoiceItems(folders.toTypedArray(), checked) { _, idx, isChecked ->
                                    prefs.setAppInFolder(folders[idx], app.packageName, isChecked)
                                }
                                .setPositiveButton("Done") { _, _ -> refreshLauncherList() }
                                .show()
                        }
                    }
                }
            }
            .show()
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
                if (launcherAdapter.itemCount > 0 && launcherAdapter.selectedPosition < 0) {
                    launcherAdapter.setSelectedPosition(0)
                }
                binding.appRecyclerView.requestFocus()
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
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

        binding.virtualFaceButtons.isXboxLayout =
            (prefs.getFaceButtonLayout() == SimulatorPrefs.FACE_LAYOUT_XBOX)

        binding.virtualLeftStick.onStickMove = { x, y ->
            if (binding.launcherOverlay.visibility == View.VISIBLE) {
                handleTopLauncherStick(x, y)
            } else {
                leftStickX = x
                leftStickY = y
                sendVirtualJoystick(force = (x == 0f && y == 0f))
            }
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

        // If launcher overlay is visible, route virtual gamepad to navigate the top launcher
        if (binding.launcherOverlay.visibility == View.VISIBLE) {
            handleTopLauncherKey(keyCode, isDown)
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

        val isFaceBtn = keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_BUTTON_X ||
            keyCode == KeyEvent.KEYCODE_BUTTON_Y
        if (isFaceBtn) {
            keyRepeatRunnables.remove(keyCode)?.let { uiHandler.removeCallbacks(it) }
            if (isDown) {
                var repCount = 0
                val repeatTask = object : Runnable {
                    override fun run() {
                        if (!keyDownTimes.containsKey(keyCode)) return
                        repCount++
                        val tNow = SystemClock.uptimeMillis()
                        val dTime = keyDownTimes[keyCode] ?: tNow
                        inputExecutor.execute {
                            userService?.injectKeyEvent(
                                targetId,
                                KeyEvent.ACTION_DOWN,
                                keyCode,
                                dTime,
                                tNow,
                                repCount,
                                0,
                                source
                            )
                        }
                        uiHandler.postDelayed(this, 100L)
                    }
                }
                keyRepeatRunnables[keyCode] = repeatTask
                uiHandler.postDelayed(repeatTask, 250L)
            }
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
        if (isGamepadKey && binding.launcherOverlay.visibility == View.VISIBLE && !binding.etTopSearch.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                if (handleTopLauncherKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)) {
                    return true
                }
            }
        }
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
        if (isJoystick && binding.launcherOverlay.visibility == View.VISIBLE && !binding.etTopSearch.hasFocus()) {
            val ax = event.getAxisValue(MotionEvent.AXIS_X)
            val ay = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val effX = if (kotlin.math.abs(hx) > 0.5f) hx else ax
            val effY = if (kotlin.math.abs(hy) > 0.5f) hy else ay
            handleTopLauncherStick(effX, effY)
            return true
        }
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

        // If displays already exist (e.g. returning after minimizing), re-attach each display to its permanent SurfaceView
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

        // If currently visually swapped (dual-screen Presentation mode), restore layout constraints instantly
        if (isSwapped) {
            isSwapped = false
            syncSurfaceMetadata()
            addLocalLog("Visual swap restored (isSwapped=false)")
            Toast.makeText(this, "Restored top & bottom screens", Toast.LENGTH_SHORT).show()
            return
        }

        ioExecutor.execute {
            // Two independent apps running: swap tasks between displays so each resizes natively
            val swappedTasks = service.swapTasksBetweenDisplays(primaryDisplayId, secondaryDisplayId)
            runOnUiThread {
                if (swappedTasks) {
                    binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                    binding.bottomLauncherOverlay.visibility = View.GONE
                    binding.bottomHomeOverlay.visibility = View.GONE
                    Toast.makeText(this, "Swapped top & bottom apps", Toast.LENGTH_SHORT).show()
                } else {
                    // Single-task or dual-screen Presentation app (e.g. SeedlessDS, Azahar, DraStic):
                    // reposition the two SurfaceViews via ConstraintLayout without touching VirtualDisplay surfaces!
                    isSwapped = true
                    syncSurfaceMetadata()
                    binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                    binding.bottomLauncherOverlay.visibility = View.GONE
                    binding.bottomHomeOverlay.visibility = View.GONE
                    addLocalLog("Visual swap enabled (isSwapped=true)")
                    Toast.makeText(this, "Swapped top & bottom screens", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncSurfaceMetadata() {
        // Each SurfaceView stays permanently bound to its own VirtualDisplay & native resolution:
        // topSurfaceView -> primaryDisplayId (1920x1080), bottomSurfaceView -> secondaryDisplayId (1240x1080).
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

        val topLp = binding.topSurfaceView.layoutParams as ConstraintLayout.LayoutParams
        val botLp = binding.bottomSurfaceView.layoutParams as ConstraintLayout.LayoutParams

        if (isSwapped) {
            // topSurfaceView (1920x1080) -> letterboxed 16:9 centered inside bottomScreenContainer
            topLp.topToTop = R.id.bottomScreenContainer
            topLp.bottomToBottom = R.id.bottomScreenContainer
            topLp.startToStart = R.id.bottomScreenContainer
            topLp.endToEnd = R.id.bottomScreenContainer
            topLp.dimensionRatio = "H,16:9"

            // bottomSurfaceView (1240x1080) -> pillarboxed 1240:1080 centered inside topScreenContainer
            botLp.topToTop = R.id.topScreenContainer
            botLp.bottomToBottom = R.id.topScreenContainer
            botLp.startToStart = R.id.topScreenContainer
            botLp.endToEnd = R.id.topScreenContainer
            botLp.dimensionRatio = "W,1240:1080"
        } else {
            // Normal: topSurfaceView -> topScreenContainer (16:9), bottomSurfaceView -> bottomScreenContainer (1240:1080)
            topLp.topToTop = R.id.topScreenContainer
            topLp.bottomToBottom = R.id.topScreenContainer
            topLp.startToStart = R.id.topScreenContainer
            topLp.endToEnd = R.id.topScreenContainer
            topLp.dimensionRatio = "16:9"

            botLp.topToTop = R.id.bottomScreenContainer
            botLp.bottomToBottom = R.id.bottomScreenContainer
            botLp.startToStart = R.id.bottomScreenContainer
            botLp.endToEnd = R.id.bottomScreenContainer
            botLp.dimensionRatio = "1240:1080"
        }

        binding.topSurfaceView.layoutParams = topLp
        binding.bottomSurfaceView.layoutParams = botLp
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
                binding.bottomHomeOverlay.visibility = View.VISIBLE
                if (ok) {
                    lastBottomPackage = null
                    Toast.makeText(this, "Closed bottom app", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Returned to Bottom Home", Toast.LENGTH_SHORT).show()
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
                        binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                    } else if (!ok) {
                        Toast.makeText(this, "Failed to launch ${app.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (hideLauncherAfter) {
            addLocalLog("Launched ${app.packageName} on display $displayId")
            binding.root.postDelayed({ ioExecutor.execute { userService?.focusDisplay(displayId) } }, 450L)
            binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
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



    private fun setupBottomHomeScreen() {
        binding.btnHomeTopApps.setOnClickListener {
            binding.launcherOverlay.visibility = View.VISIBLE
            if (launcherAdapter.itemCount > 0 && launcherAdapter.selectedPosition < 0) {
                launcherAdapter.setSelectedPosition(0)
            }
        }
        binding.btnHomeBottomApps.setOnClickListener {
            binding.bottomLauncherOverlay.visibility = View.VISIBLE
        }
        binding.btnHomeSettings.setOnClickListener {
            showSettingsDialog()
        }
        binding.btnHomeSwap.setOnClickListener {
            swapScreens()
        }
        binding.btnHomeHideDashboard.setOnClickListener {
            binding.bottomHomeOverlay.visibility = View.GONE
        }
        applyBottomHomeWidgetVisibility()
    }

    private fun applyBottomHomeWidgetVisibility() {
        binding.homeClockCard.visibility = if (prefs.isShowHomeClock()) View.VISIBLE else View.GONE
        binding.homePerfCard.visibility = if (prefs.isShowHomePerf()) View.VISIBLE else View.GONE
        binding.homeShortcutsCard.visibility = if (prefs.isShowHomeShortcuts()) View.VISIBLE else View.GONE
    }

    private fun updateBottomHomeDashboard() {
        if (binding.bottomHomeOverlay.visibility != View.VISIBLE) return
        val now = Date()
        if (prefs.isShowHomeClock()) {
            binding.tvHomeClock.text = clockFormat.format(now)
            binding.tvHomeDate.text = dateFormat.format(now)
        }
        if (prefs.isShowHomePerf()) {
            val battIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val tempTenths = battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val battTempC = tempTenths / 10.0
            val bolt = if (charging) " ⚡" else ""
            binding.tvPerfBattery.text = if (pct >= 0) {
                String.format(Locale.US, "BAT: %d%%%s (%.0f°C)", pct, bolt, battTempC)
            } else {
                "BAT: --%"
            }

            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            val usedGb = (mi.totalMem - mi.availMem) / (1024.0 * 1024.0 * 1024.0)
            binding.tvPerfFps.text = String.format(Locale.US, "RAM: %.1f / %.0f GB", usedGb, totalGb)

            val svc = userService
            val topDisp = primaryDisplayId
            if (svc != null) {
                perfExecutor.execute {
                    val raw = try {
                        svc.getPerformanceStats(topDisp)
                    } catch (_: Throwable) {
                        null
                    }
                    if (!raw.isNullOrBlank()) {
                        val parts = raw.split("|")
                        if (parts.size >= 4) {
                            runOnUiThread {
                                binding.tvPerfCpu.text = "CPU: ${parts[0]} (${parts[1]})"
                                binding.tvPerfGpu.text = "GPU: ${parts[2]} (${parts[3]})"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyAppTheme() {
        val mode = prefs.getThemeMode()
        val nightMode = when (mode) {
            SimulatorPrefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SimulatorPrefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        val isLight = when (mode) {
            SimulatorPrefs.THEME_LIGHT -> true
            SimulatorPrefs.THEME_DARK -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        }
        isLightThemeActive = isLight

        val rootBg = Color.parseColor(if (isLight) "#F4F4F8" else "#000000")
        val overlayBg = Color.parseColor(if (isLight) "#F2F4F4F8" else "#EE0B0B0F")
        val cardBg = Color.parseColor(if (isLight) "#E4E4EC" else "#181820")
        val inputBg = Color.parseColor(if (isLight) "#FFFFFF" else "#1E1E28")
        val primaryText = Color.parseColor(if (isLight) "#18181B" else "#FFFFFF")
        val secondaryText = Color.parseColor(if (isLight) "#52525B" else "#9CA3AF")
        val pillBg = Color.parseColor(if (isLight) "#E4E4EC" else "#1A1A20")

        binding.rootLayout.setBackgroundColor(rootBg)
        binding.launcherOverlay.setBackgroundColor(overlayBg)
        binding.bottomLauncherOverlay.setBackgroundColor(overlayBg)
        binding.bottomHomeOverlay.setBackgroundColor(overlayBg)

        binding.homeClockCard.setBackgroundColor(cardBg)
        binding.homePerfCard.setBackgroundColor(cardBg)
        binding.homeShortcutsCard.setBackgroundColor(cardBg)

        binding.launcherTitle.setTextColor(primaryText)
        binding.tvBottomLauncherTitle.setTextColor(primaryText)
        binding.tvHomeClock.setTextColor(primaryText)
        binding.tvHomeDate.setTextColor(secondaryText)
        binding.tvPerfFps.setTextColor(primaryText)
        binding.tvPerfCpu.setTextColor(secondaryText)
        binding.tvPerfGpu.setTextColor(secondaryText)

        binding.etTopSearch.setBackgroundColor(inputBg)
        binding.etTopSearch.setTextColor(primaryText)
        binding.etBottomSearch.setBackgroundColor(inputBg)
        binding.etBottomSearch.setTextColor(primaryText)

        binding.btnToggleLeftStick.setBackgroundColor(pillBg)
        binding.btnToggleLeftStick.setTextColor(secondaryText)
        binding.btnToggleRightStick.setBackgroundColor(pillBg)
        binding.btnToggleRightStick.setTextColor(secondaryText)

        launcherAdapter.isLightTheme = isLight
        bottomLauncherAdapter.isLightTheme = isLight

        binding.virtualDpad.applyTheme(isLight)
        binding.virtualLeftStick.applyTheme(isLight)
        binding.virtualFaceButtons.applyTheme(isLight)
        binding.virtualRightStick.applyTheme(isLight)
        binding.btnL1.applyTheme(isLight)
        binding.btnL2.applyTheme(isLight)
        binding.btnR1.applyTheme(isLight)
        binding.btnR2.applyTheme(isLight)
        binding.btnSelect.applyTheme(isLight)
        binding.btnStart.applyTheme(isLight)
    }

    private fun showSettingsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 16)
        }
        scroll.addView(container)

        fun addSectionHeader(title: String) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 20, 0, 8)
            })
        }

        // 1. Face Button Layout
        addSectionHeader("Face Button Layout (A/B/X/Y)")
        val layoutGroup = RadioGroup(this)
        val rbXbox = RadioButton(this).apply { text = "Xbox / GameSir (A bottom, B right, X left, Y top)" }
        val rbNintendo = RadioButton(this).apply { text = "Nintendo (B bottom, A right, Y left, X top)" }
        layoutGroup.addView(rbXbox)
        layoutGroup.addView(rbNintendo)
        if (prefs.getFaceButtonLayout() == SimulatorPrefs.FACE_LAYOUT_XBOX) {
            rbXbox.isChecked = true
        } else {
            rbNintendo.isChecked = true
        }
        container.addView(layoutGroup)

        // 2. Theme
        addSectionHeader("Theme")
        val themeGroup = RadioGroup(this)
        val rbSystem = RadioButton(this).apply { text = "Follow System" }
        val rbDark = RadioButton(this).apply { text = "Dark (AMOLED)" }
        val rbLight = RadioButton(this).apply { text = "Light" }
        themeGroup.addView(rbSystem)
        themeGroup.addView(rbDark)
        themeGroup.addView(rbLight)
        when (prefs.getThemeMode()) {
            SimulatorPrefs.THEME_LIGHT -> rbLight.isChecked = true
            SimulatorPrefs.THEME_DARK -> rbDark.isChecked = true
            else -> rbSystem.isChecked = true
        }
        container.addView(themeGroup)

        // 3. Bottom Home Screen Widgets
        addSectionHeader("Bottom Home Screen Widgets")
        val cbClock = CheckBox(this).apply {
            text = "Show Clock & Date"
            isChecked = prefs.isShowHomeClock()
        }
        val cbPerf = CheckBox(this).apply {
            text = "Show Performance Monitor (FPS, CPU, GPU, Battery)"
            isChecked = prefs.isShowHomePerf()
        }
        val cbShortcuts = CheckBox(this).apply {
            text = "Show Quick Shortcuts"
            isChecked = prefs.isShowHomeShortcuts()
        }
        container.addView(cbClock)
        container.addView(cbPerf)
        container.addView(cbShortcuts)

        // 4. Folders & Filters helper note
        addSectionHeader("App Folders & Filters")
        container.addView(TextView(this).apply {
            text = "Tip: Long-press any app in the Top or Bottom drawer to assign it to a folder or hide it."
            textSize = 12f
        })

        AlertDialog.Builder(this)
            .setTitle("DroiDS Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val chosenLayout = if (rbXbox.isChecked) {
                    SimulatorPrefs.FACE_LAYOUT_XBOX
                } else {
                    SimulatorPrefs.FACE_LAYOUT_NINTENDO
                }
                prefs.setFaceButtonLayout(chosenLayout)
                binding.virtualFaceButtons.isXboxLayout = (chosenLayout == SimulatorPrefs.FACE_LAYOUT_XBOX)

                val chosenTheme = when {
                    rbLight.isChecked -> SimulatorPrefs.THEME_LIGHT
                    rbDark.isChecked -> SimulatorPrefs.THEME_DARK
                    else -> SimulatorPrefs.THEME_SYSTEM
                }
                prefs.setThemeMode(chosenTheme)

                prefs.setShowHomeClock(cbClock.isChecked)
                prefs.setShowHomePerf(cbPerf.isChecked)
                prefs.setShowHomeShortcuts(cbShortcuts.isChecked)

                applyBottomHomeWidgetVisibility()
                applyAppTheme()
                updateBottomHomeDashboard()
            }
            .setNeutralButton("Manage Folders") { _, _ ->
                showManageFoldersDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageFoldersDialog() {
        val folders = prefs.getFolders()
        val items = mutableListOf("+ Create New Folder")
        items.addAll(folders.map { "Delete '$it'" })
        AlertDialog.Builder(this)
            .setTitle("Manage Folders")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreateFolderDialog()
                } else {
                    val toDelete = folders[which - 1]
                    prefs.removeFolder(toDelete)
                    if (selectedTopFolder == toDelete) selectedTopFolder = null
                    if (selectedBottomFolder == toDelete) selectedBottomFolder = null
                    updateDrawerFilterButtons()
                    refreshLauncherList()
                    Toast.makeText(this, "Deleted folder '$toDelete'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun handleTopLauncherKey(keyCode: Int, isDown: Boolean): Boolean {
        if (!isDown) {
            return keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1
            )
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveTopLauncherSelection(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveTopLauncherSelection(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveTopLauncherSelection(-5); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveTopLauncherSelection(5); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                launcherAdapter.getSelectedItem()?.let { app ->
                    prefs.recordAppLaunch(app.packageName)
                    launchAppOnDisplay(app, activeTopDisplayId, hideLauncherAfter = true)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> { cycleTopFolder(-1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { cycleTopFolder(1); true }
            else -> false
        }
    }

    private fun handleTopLauncherStick(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLauncherStickNavMs < 180L) return
        val delta = when {
            x < -0.55f -> -1
            x > 0.55f -> 1
            y < -0.55f -> -5
            y > 0.55f -> 5
            else -> 0
        }
        if (delta != 0) {
            lastLauncherStickNavMs = now
            moveTopLauncherSelection(delta)
        }
    }

    private fun moveTopLauncherSelection(delta: Int) {
        val count = launcherAdapter.itemCount
        if (count <= 0) return
        val current = if (launcherAdapter.selectedPosition < 0) 0 else launcherAdapter.selectedPosition
        val next = (current + delta).coerceIn(0, count - 1)
        launcherAdapter.setSelectedPosition(next)
        binding.appRecyclerView.smoothScrollToPosition(next)
    }

    private fun cycleTopFolder(step: Int) {
        val allFolders = listOf<String?>(null) + prefs.getFolders()
        if (allFolders.size <= 1) return
        val idx = allFolders.indexOf(selectedTopFolder).coerceAtLeast(0)
        val nextIdx = (idx + step + allFolders.size) % allFolders.size
        selectedTopFolder = allFolders[nextIdx]
        updateDrawerFilterButtons()
        refreshLauncherList()
        if (launcherAdapter.itemCount > 0) launcherAdapter.setSelectedPosition(0)
    }

    override fun onPause() {
        super.onPause()
        // Release any held virtual keys so nothing stays stuck when switching apps
        keyRepeatRunnables.values.forEach { uiHandler.removeCallbacks(it) }
        keyRepeatRunnables.clear()
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
        uiHandler.removeCallbacks(homeRefreshRunnable)
        perfExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        inputExecutor.shutdownNow()
    }
}
