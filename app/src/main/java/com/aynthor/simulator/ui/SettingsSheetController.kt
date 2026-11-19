package com.aynthor.simulator.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.aynthor.simulator.R
import com.aynthor.simulator.prefs.SimulatorPrefs
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bottom-anchored, two-pane DroiDS Settings sheet (Variant 3B):
 * - Keeps the top game display unobstructed for live preview while tweaking settings.
 * - Compact Material 3 icon-over-label left navigation rail right under the left thumb,
 *   with a Done pill button anchored at the bottom-left.
 * - One UI Focus Block cards + expressive M3 sliders, segmented pills, and switches on the right.
 * - Supports swipe-down on the drag handle/header and tap-on-top-screen to dismiss.
 */
class SettingsSheetController(
    private val context: Context,
    private val prefs: SimulatorPrefs,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onDisplaySettingsChanged(orientationChanged: Boolean)
        fun onBottomHomeSettingsChanged()
        fun onGamepadSettingsChanged(layoutChanged: Boolean)
        fun onThemeChanged()
        fun onDrawerSettingsChanged()
        fun onExportBackupRequested()
        fun onImportBackupRequested()
        fun onVisibilityChanged(visible: Boolean)
    }

    enum class Tab(val shortLabel: String, val fullTitle: String, val iconRes: Int) {
        DISPLAY("Display", "Display & Orientation", R.drawable.ic_settings_display),
        HOME("Home", "Home & Control Center", R.drawable.ic_settings_home),
        GAMEPAD("Gamepad", "Virtual Gamepad", R.drawable.ic_settings_gamepad),
        THEME("Theme", "App Drawers & Theme", R.drawable.ic_settings_theme),
        BACKUP("Backup", "Backup & Restore", R.drawable.ic_settings_backup)
    }

    private data class Palette(
        val backdrop: Int,
        val sheetBg: Int,
        val sheetBorder: Int,
        val railBg: Int,
        val railDivider: Int,
        val handleColor: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val accent: Int,
        val accentSoftBg: Int,
        val pillTrackBg: Int,
        val pillInactiveBorder: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textOnAccent: Int,
        val sliderInactive: Int,
        val badgeBg: Int
    )

    private fun darkPalette() = Palette(
        backdrop = Color.parseColor("#18000000"),
        sheetBg = Color.parseColor("#F214161D"),
        sheetBorder = Color.parseColor("#2AFFFFFF"),
        railBg = Color.parseColor("#EE0E1016"),
        railDivider = Color.parseColor("#1CFFFFFF"),
        handleColor = Color.parseColor("#55FFFFFF"),
        cardBg = Color.parseColor("#FF20232E"),
        cardBorder = Color.parseColor("#14FFFFFF"),
        accent = Color.parseColor("#4C9AFF"),
        accentSoftBg = Color.parseColor("#334C9AFF"),
        pillTrackBg = Color.parseColor("#FF171A23"),
        pillInactiveBorder = Color.parseColor("#28FFFFFF"),
        textPrimary = Color.parseColor("#F5F7FA"),
        textSecondary = Color.parseColor("#9CA3AF"),
        textOnAccent = Color.parseColor("#0A101D"),
        sliderInactive = Color.parseColor("#FF363B4D"),
        badgeBg = Color.parseColor("#FF2B3040")
    )

    private fun lightPalette() = Palette(
        backdrop = Color.parseColor("#1F000000"),
        sheetBg = Color.parseColor("#F5F2F4F8"),
        sheetBorder = Color.parseColor("#1F000000"),
        railBg = Color.parseColor("#F0E6E9F2"),
        railDivider = Color.parseColor("#1A000000"),
        handleColor = Color.parseColor("#44000000"),
        cardBg = Color.parseColor("#FFFFFFFF"),
        cardBorder = Color.parseColor("#14000000"),
        accent = Color.parseColor("#2563EB"),
        accentSoftBg = Color.parseColor("#222563EB"),
        pillTrackBg = Color.parseColor("#FFE8ECF4"),
        pillInactiveBorder = Color.parseColor("#22000000"),
        textPrimary = Color.parseColor("#111827"),
        textSecondary = Color.parseColor("#4B5563"),
        textOnAccent = Color.parseColor("#FFFFFF"),
        sliderInactive = Color.parseColor("#FFD1D5DB"),
        badgeBg = Color.parseColor("#FFE5E7EB")
    )

    private var isLight: Boolean = false
    private var palette: Palette = darkPalette()
    private var activeTab: Tab = Tab.DISPLAY
    private var isAnimatingOut: Boolean = false

    val overlayRoot: FrameLayout
    private val backdropView: View
    private val sheetContainer: LinearLayout
    private val dragHandleBar: FrameLayout
    private val dragHandlePill: View
    private val railContainer: LinearLayout
    private val railDividerView: View
    private val railTabsContainer: LinearLayout
    private val btnDone: TextView
    private val headerContainer: LinearLayout
    private val tvCategoryTitle: TextView
    private val contentScroll: ScrollView
    private val contentContainer: LinearLayout

    private val tabPillViews = mutableMapOf<Tab, FrameLayout>()
    private val tabIconViews = mutableMapOf<Tab, ImageView>()
    private val tabLabelViews = mutableMapOf<Tab, TextView>()

    val isVisible: Boolean
        get() = overlayRoot.visibility == View.VISIBLE && !isAnimatingOut

    init {
        overlayRoot = FrameLayout(context).apply {
            visibility = View.GONE
            elevation = dp(24).toFloat()
            isClickable = false
        }

        // Top backdrop: transparent/ultra-light scrim so the live game stays visible; tap dismisses.
        backdropView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dismiss() }
        }
        overlayRoot.addView(backdropView)

        // Bottom-anchored translucent sheet
        sheetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(380),
                Gravity.BOTTOM
            )
            isClickable = true
            isFocusable = true
            clipToOutline = true
        }
        overlayRoot.addView(sheetContainer)

        // Drag handle area along top edge of sheet (supports swipe-down to dismiss)
        dragHandleBar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(22)
            )
        }
        dragHandlePill = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(4), Gravity.CENTER)
        }
        dragHandleBar.addView(dragHandlePill)
        sheetContainer.addView(dragHandleBar)

        // Two-pane body: Left compact M3 Icon Rail + Right Scrollable Focus Blocks
        val bodyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        sheetContainer.addView(bodyRow)

        // Left navigation rail (~80dp compact M3 icon-over-label rail + Done anchored at bottom)
        railContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dp(82),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(6), dp(2), dp(6), dp(10))
        }
        bodyRow.addView(railContainer)

        val railScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        railTabsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        railScroll.addView(railTabsContainer)
        railContainer.addView(railScroll)

        // Build the 5 compact icon-over-label rail tabs
        for (tab in Tab.values()) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(4)
                }
                setPadding(dp(2), dp(4), dp(2), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(tab) }
            }

            val pill = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(28))
            }
            val icon = ImageView(context).apply {
                setImageResource(tab.iconRes)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            }
            pill.addView(icon)

            val label = TextView(context).apply {
                text = tab.shortLabel
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                }
            }

            item.addView(pill)
            item.addView(label)
            railTabsContainer.addView(item)

            tabPillViews[tab] = pill
            tabIconViews[tab] = icon
            tabLabelViews[tab] = label
        }

        // Done button anchored cleanly to bottom-left of the rail (right under the left thumb)
        btnDone = TextView(context).apply {
            text = "Done"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply {
                topMargin = dp(6)
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }
        railContainer.addView(btnDone)

        // Subtle vertical divider between rail and content pane
        railDividerView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        bodyRow.addView(railDividerView)

        // Right pane: Header + Scrollable Focus Block cards
        val rightPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        }
        bodyRow.addView(rightPane)

        headerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        tvCategoryTitle = TextView(context).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerContainer.addView(tvCategoryTitle)
        rightPane.addView(headerContainer)

        contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), dp(2), dp(14), dp(16))
        }
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentScroll.addView(contentContainer)
        rightPane.addView(contentScroll)

        attachSwipeDownToDismiss(dragHandleBar)
        attachSwipeDownToDismiss(headerContainer)

        applyPaletteToChrome()
    }

    fun attachTo(parent: ViewGroup) {
        if (overlayRoot.parent === parent) return
        (overlayRoot.parent as? ViewGroup)?.removeView(overlayRoot)
        val lp: ViewGroup.LayoutParams = if (parent is androidx.constraintlayout.widget.ConstraintLayout) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        parent.addView(overlayRoot, lp)
    }

    fun refreshTheme(light: Boolean) {
        isLight = light
        palette = if (light) lightPalette() else darkPalette()
        applyPaletteToChrome()
        if (overlayRoot.visibility == View.VISIBLE) {
            renderActiveTab()
        }
    }

    fun updateSheetHeight(rootHeightPx: Int) {
        if (rootHeightPx <= 0) return
        val desired = (rootHeightPx * 0.56f).roundToInt().coerceIn(dp(320), dp(560))
        val lp = sheetContainer.layoutParams as FrameLayout.LayoutParams
        if (lp.height != desired) {
            lp.height = desired
            sheetContainer.layoutParams = lp
        }
    }

    fun show(initialTab: Tab? = null) {
        if (initialTab != null) activeTab = initialTab
        isAnimatingOut = false

        val parentHeight = (overlayRoot.parent as? View)?.height ?: 0
        if (parentHeight > 0) updateSheetHeight(parentHeight)

        applyPaletteToChrome()
        selectTab(activeTab, animateScrollTop = true)

        overlayRoot.visibility = View.VISIBLE
        overlayRoot.bringToFront()
        callbacks.onVisibilityChanged(true)

        val startOffset = (sheetContainer.layoutParams.height.takeIf { it > 0 } ?: dp(380)).toFloat()
        sheetContainer.animate().cancel()
        backdropView.animate().cancel()
        sheetContainer.translationY = startOffset
        backdropView.alpha = 0f

        sheetContainer.animate()
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .setListener(null)
            .start()
        backdropView.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    fun dismiss() {
        if (overlayRoot.visibility != View.VISIBLE || isAnimatingOut) return
        isAnimatingOut = true

        val targetY = (sheetContainer.height.takeIf { it > 0 } ?: dp(380)).toFloat()
        sheetContainer.animate().cancel()
        backdropView.animate().cancel()

        backdropView.animate()
            .alpha(0f)
            .setDuration(160L)
            .start()
        sheetContainer.animate()
            .translationY(targetY)
            .setDuration(180L)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlayRoot.visibility = View.GONE
                    sheetContainer.translationY = 0f
                    isAnimatingOut = false
                    callbacks.onVisibilityChanged(false)
                }
            })
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeDownToDismiss(target: View) {
        var downRawY = 0f
        var startTransY = 0f
        var dragging = false
        var velocityTracker: VelocityTracker? = null

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startTransY = sheetContainer.translationY
                    dragging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dy = event.rawY - downRawY
                    if (!dragging && dy > dp(6)) {
                        dragging = true
                    }
                    if (dragging) {
                        sheetContainer.translationY = (startTransY + dy).coerceAtLeast(0f)
                        val maxH = (sheetContainer.height.takeIf { it > 0 } ?: dp(380)).toFloat()
                        backdropView.alpha = (1f - (sheetContainer.translationY / maxH)).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val yVelocity = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val draggedDist = sheetContainer.translationY
                    if (dragging && (draggedDist > dp(72) || yVelocity > 900f)) {
                        dismiss()
                    } else if (draggedDist > 0f) {
                        sheetContainer.animate()
                            .translationY(0f)
                            .setDuration(160L)
                            .setInterpolator(DecelerateInterpolator())
                            .setListener(null)
                            .start()
                        backdropView.animate().alpha(1f).setDuration(160L).start()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun applyPaletteToChrome() {
        val p = palette
        backdropView.setBackgroundColor(p.backdrop)

        val topRadius = dp(28).toFloat()
        sheetContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(p.sheetBg)
            cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
            setStroke(dp(1), p.sheetBorder)
        }

        dragHandlePill.background = roundedRect(p.handleColor, dp(2))
        railContainer.setBackgroundColor(p.railBg)
        railDividerView.setBackgroundColor(p.railDivider)
        tvCategoryTitle.setTextColor(p.textPrimary)

        btnDone.background = roundedRect(p.accentSoftBg, dp(18), strokeColor = p.accent, strokeWidthPx = dp(1))
        btnDone.setTextColor(p.accent)

        updateRailSelection()
    }

    private fun selectTab(tab: Tab, animateScrollTop: Boolean = true) {
        activeTab = tab
        tvCategoryTitle.text = tab.fullTitle
        updateRailSelection()
        renderActiveTab()
        if (animateScrollTop) {
            contentScroll.scrollTo(0, 0)
        }
    }

    private fun updateRailSelection() {
        val p = palette
        for (tab in Tab.values()) {
            val selected = (tab == activeTab)
            tabPillViews[tab]?.background = if (selected) {
                roundedRect(p.accentSoftBg, dp(14))
            } else {
                null
            }
            tabIconViews[tab]?.let { icon ->
                ImageViewCompat.setImageTintList(
                    icon,
                    ColorStateList.valueOf(if (selected) p.accent else p.textSecondary)
                )
            }
            tabLabelViews[tab]?.apply {
                setTextColor(if (selected) p.accent else p.textSecondary)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    private fun isWidePane(): Boolean {
        val dm = context.resources.displayMetrics
        val widthDp = (overlayRoot.width.takeIf { it > 0 } ?: dm.widthPixels) / dm.density
        return widthDp >= 520f
    }

    private fun renderActiveTab() {
        contentContainer.removeAllViews()
        when (activeTab) {
            Tab.DISPLAY -> buildDisplayTab()
            Tab.HOME -> buildHomeTab()
            Tab.GAMEPAD -> buildGamepadTab()
            Tab.THEME -> buildThemeTab()
            Tab.BACKUP -> buildBackupTab()
        }
    }

    // -------------------------------------------------------------------------
    // Tab 1: Display & Orientation
    // -------------------------------------------------------------------------
    private fun buildDisplayTab() {
        // Card 1: Orientation segmented pill selector
        val orientCard = createFocusCard("Orientation")
        val orientOptions = listOf(
            "Landscape" to SimulatorPrefs.ORIENTATION_LANDSCAPE,
            "Portrait" to SimulatorPrefs.ORIENTATION_PORTRAIT,
            "Auto" to SimulatorPrefs.ORIENTATION_AUTO,
            "System" to SimulatorPrefs.ORIENTATION_SYSTEM
        )
        orientCard.addView(
            createSegmentedPillRow(
                options = orientOptions,
                currentValue = prefs.getOrientationMode()
            ) { chosen ->
                prefs.setOrientationMode(chosen)
                callbacks.onDisplaySettingsChanged(orientationChanged = true)
            }
        )
        contentContainer.addView(orientCard)

        // Card 2: Screen Split slider + quick preset chips (live preview on top game!)
        val splitCard = createFocusCard("Screen Split")
        val curSplitPct = (prefs.getSplitPercent() * 100f).roundToInt().coerceIn(45, 62)

        val splitHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val tvSplitDesc = TextView(context).apply {
            text = "Top $curSplitPct%  •  Bottom ${100 - curSplitPct}%"
            textSize = 12.5f
            setTextColor(palette.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSplitBadge = createValueBadge("$curSplitPct%")
        splitHeaderRow.addView(tvSplitDesc)
        splitHeaderRow.addView(tvSplitBadge)
        splitCard.addView(splitHeaderRow)

        val presets = listOf(45, 50, 53, 58, 62)
        val presetViews = mutableMapOf<Int, TextView>()

        fun updatePresetHighlight(pct: Int) {
            val p = palette
            for ((value, chip) in presetViews) {
                val active = (value == pct)
                chip.background = if (active) {
                    roundedRect(p.accentSoftBg, dp(14), strokeColor = p.accent, strokeWidthPx = dp(1))
                } else {
                    roundedRect(p.pillTrackBg, dp(14), strokeColor = p.pillInactiveBorder, strokeWidthPx = dp(1))
                }
                chip.setTextColor(if (active) p.accent else p.textSecondary)
                chip.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        val splitSlider = createExpressiveSlider(
            valueFrom = 45f,
            valueTo = 62f,
            stepSize = 1f,
            initialValue = curSplitPct.toFloat()
        ) { newVal ->
            val pct = newVal.roundToInt().coerceIn(45, 62)
            tvSplitDesc.text = "Top $pct%  •  Bottom ${100 - pct}%"
            tvSplitBadge.text = "$pct%"
            updatePresetHighlight(pct)
            prefs.setSplitPercent(pct / 100f)
            callbacks.onDisplaySettingsChanged(orientationChanged = false)
        }
        splitCard.addView(splitSlider)

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        for (pct in presets) {
            val chip = TextView(context).apply {
                text = "$pct%"
                textSize = 11.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    marginEnd = if (pct != presets.last()) dp(6) else 0
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    splitSlider.value = pct.toFloat()
                }
            }
            presetViews[pct] = chip
            chipRow.addView(chip)
        }
        updatePresetHighlight(curSplitPct)
        splitCard.addView(chipRow)
        contentContainer.addView(splitCard)

        // Card 3: Aspect Ratios (Top & Bottom side-by-side on wide screens)
        val topCard = createFocusCard("Top Aspect Ratio")
        topCard.addView(
            createSegmentedPillRow(
                options = listOf("16:9" to "16:9", "4:3" to "4:3", "3:2" to "3:2", "Fill" to "Fill"),
                currentValue = prefs.getTopAspectRatio()
            ) { chosen ->
                prefs.setTopAspectRatio(chosen)
                callbacks.onDisplaySettingsChanged(orientationChanged = false)
            }
        )

        val bottomCard = createFocusCard("Bottom Aspect Ratio")
        bottomCard.addView(
            createSegmentedPillRow(
                options = listOf("Thor" to "1240:1080", "4:3" to "4:3", "1:1" to "1:1", "16:9" to "16:9"),
                currentValue = prefs.getBottomAspectRatio()
            ) { chosen ->
                prefs.setBottomAspectRatio(chosen)
                callbacks.onDisplaySettingsChanged(orientationChanged = false)
            }
        )

        addResponsiveCardPair(topCard, bottomCard)
    }

    // -------------------------------------------------------------------------
    // Tab 2: Bottom Home & Control Center
    // -------------------------------------------------------------------------
    private fun buildHomeTab() {
        val widgetsCard = createFocusCard("Dashboard Widgets")
        widgetsCard.addView(
            createSwitchRow(
                title = "Clock & Date",
                subtitle = "Header clock on Bottom Home",
                checked = prefs.isShowHomeClock()
            ) {
                prefs.setShowHomeClock(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        widgetsCard.addView(createHairlineDivider())
        widgetsCard.addView(
            createSwitchRow(
                title = "Performance Monitor",
                subtitle = "Live FPS, CPU, GPU, RAM & Battery",
                checked = prefs.isShowHomePerf()
            ) {
                prefs.setShowHomePerf(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        widgetsCard.addView(createHairlineDivider())
        widgetsCard.addView(
            createSwitchRow(
                title = "Quick Shortcuts",
                subtitle = "Pinned bottom launch shortcuts",
                checked = prefs.isShowHomeShortcuts()
            ) {
                prefs.setShowHomeShortcuts(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        widgetsCard.addView(createHairlineDivider())
        widgetsCard.addView(
            createSwitchRow(
                title = "Quick Control Center",
                subtitle = "Sliders and system toggles card",
                checked = prefs.isShowControlCenter()
            ) {
                prefs.setShowControlCenter(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        contentContainer.addView(widgetsCard)

        val ccCard = createFocusCard("Control Center Items")
        ccCard.addView(
            createSwitchRow(
                title = "Brightness Slider",
                subtitle = null,
                checked = prefs.isShowCcBrightness()
            ) {
                prefs.setShowCcBrightness(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        ccCard.addView(createHairlineDivider())
        ccCard.addView(
            createSwitchRow(
                title = "Media Volume Slider",
                subtitle = null,
                checked = prefs.isShowCcVolume()
            ) {
                prefs.setShowCcVolume(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        ccCard.addView(createHairlineDivider())
        ccCard.addView(
            createSwitchRow(
                title = "Wi-Fi Quick Toggle",
                subtitle = null,
                checked = prefs.isShowCcWifi()
            ) {
                prefs.setShowCcWifi(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        ccCard.addView(createHairlineDivider())
        ccCard.addView(
            createSwitchRow(
                title = "Bluetooth Quick Toggle",
                subtitle = null,
                checked = prefs.isShowCcBluetooth()
            ) {
                prefs.setShowCcBluetooth(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        ccCard.addView(createHairlineDivider())
        ccCard.addView(
            createSwitchRow(
                title = "Dim / Sleep Bottom Screen",
                subtitle = "AMOLED blackout overlay (tap to wake)",
                checked = prefs.isShowCcSleepBottom()
            ) {
                prefs.setShowCcSleepBottom(it)
                callbacks.onBottomHomeSettingsChanged()
            }
        )
        contentContainer.addView(ccCard)
    }

    // -------------------------------------------------------------------------
    // Tab 3: Virtual Gamepad (Matches Variant 3B side-by-side cards!)
    // -------------------------------------------------------------------------
    private fun buildGamepadTab() {
        val layoutCard = createFocusCard("Button Layout")
        val tvLayoutHint = TextView(context).apply {
            text = if (prefs.getFaceButtonLayout() == SimulatorPrefs.FACE_LAYOUT_XBOX) {
                "A bottom • B right • X left • Y top"
            } else {
                "B bottom • A right • Y left • X top"
            }
            textSize = 11.5f
            setTextColor(palette.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        layoutCard.addView(
            createSegmentedPillRow(
                options = listOf(
                    "Xbox" to SimulatorPrefs.FACE_LAYOUT_XBOX,
                    "Nintendo" to SimulatorPrefs.FACE_LAYOUT_NINTENDO
                ),
                currentValue = prefs.getFaceButtonLayout()
            ) { chosen ->
                prefs.setFaceButtonLayout(chosen)
                tvLayoutHint.text = if (chosen == SimulatorPrefs.FACE_LAYOUT_XBOX) {
                    "A bottom • B right • X left • Y top"
                } else {
                    "B bottom • A right • Y left • X top"
                }
                callbacks.onGamepadSettingsChanged(layoutChanged = true)
            }
        )
        layoutCard.addView(tvLayoutHint)

        val visualsCard = createFocusCard("Visuals")
        val curOpacity = (prefs.getGamepadOpacity() * 100f).roundToInt().coerceIn(25, 100)
        val opacityHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val tvOpacityLabel = TextView(context).apply {
            text = "Opacity"
            textSize = 12.5f
            setTextColor(palette.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvOpacityBadge = createValueBadge("$curOpacity%")
        opacityHeader.addView(tvOpacityLabel)
        opacityHeader.addView(tvOpacityBadge)
        visualsCard.addView(opacityHeader)
        visualsCard.addView(
            createExpressiveSlider(25f, 100f, 5f, curOpacity.toFloat()) { v ->
                val pct = v.roundToInt().coerceIn(25, 100)
                tvOpacityBadge.text = "$pct%"
                prefs.setGamepadOpacity(pct / 100f)
                callbacks.onGamepadSettingsChanged(layoutChanged = false)
            }
        )

        val curScale = (prefs.getGamepadScale() * 100f).roundToInt().coerceIn(75, 125)
        val scaleHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val tvScaleLabel = TextView(context).apply {
            text = "Scale"
            textSize = 12.5f
            setTextColor(palette.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvScaleBadge = createValueBadge("$curScale%")
        scaleHeader.addView(tvScaleLabel)
        scaleHeader.addView(tvScaleBadge)
        visualsCard.addView(scaleHeader)
        visualsCard.addView(
            createExpressiveSlider(75f, 125f, 5f, curScale.toFloat()) { v ->
                val pct = v.roundToInt().coerceIn(75, 125)
                tvScaleBadge.text = "$pct%"
                prefs.setGamepadScale(pct / 100f)
                callbacks.onGamepadSettingsChanged(layoutChanged = false)
            }
        )

        addResponsiveCardPair(layoutCard, visualsCard, leftWeight = 0.9f, rightWeight = 1.15f)

        val controlsCard = createFocusCard("Controls & Input")
        controlsCard.addView(
            createSwitchRow(
                title = "L3 / R3 Buttons",
                subtitle = "Shown when virtual joysticks are visible",
                checked = prefs.isShowL3R3()
            ) {
                prefs.setShowL3R3(it)
                callbacks.onGamepadSettingsChanged(layoutChanged = false)
            }
        )
        controlsCard.addView(createHairlineDivider())
        controlsCard.addView(
            createSwitchRow(
                title = "Haptic Feedback",
                subtitle = "Subtle vibration on button presses",
                checked = prefs.isHapticsEnabled()
            ) {
                prefs.setHapticsEnabled(it)
                callbacks.onGamepadSettingsChanged(layoutChanged = false)
            }
        )
        controlsCard.addView(createHairlineDivider())
        controlsCard.addView(
            createSwitchRow(
                title = "Fast Kernel Input (uinput)",
                subtitle = "Avoids 5s Android input injection stalls",
                checked = prefs.isUseKernelGamepad()
            ) {
                prefs.setUseKernelGamepad(it)
                callbacks.onGamepadSettingsChanged(layoutChanged = false)
            }
        )
        controlsCard.addView(createHairlineDivider())
        controlsCard.addView(
            createSwitchRow(
                title = "4-Way D-Pad Only",
                subtitle = "Prevents accidental diagonals in menus",
                checked = prefs.isDpadFourWayOnly()
            ) {
                prefs.setDpadFourWayOnly(it)
                callbacks.onGamepadSettingsChanged(layoutChanged = false)
            }
        )
        contentContainer.addView(controlsCard)
    }

    // -------------------------------------------------------------------------
    // Tab 4: App Drawers & Theme
    // -------------------------------------------------------------------------
    private fun buildThemeTab() {
        val themeCard = createFocusCard("App Theme")
        themeCard.addView(
            createSegmentedPillRow(
                options = listOf(
                    "System" to SimulatorPrefs.THEME_SYSTEM,
                    "Dark (AMOLED)" to SimulatorPrefs.THEME_DARK,
                    "Light" to SimulatorPrefs.THEME_LIGHT
                ),
                currentValue = prefs.getThemeMode()
            ) { chosen ->
                prefs.setThemeMode(chosen)
                callbacks.onThemeChanged()
            }
        )
        contentContainer.addView(themeCard)

        val drawersCard = createFocusCard("App Drawers")
        drawersCard.addView(
            createSwitchRow(
                title = "Recently Launched Row",
                subtitle = "Show recent apps strip in both drawers",
                checked = prefs.isShowRecentRow()
            ) {
                prefs.setShowRecentRow(it)
                callbacks.onDrawerSettingsChanged()
            }
        )
        contentContainer.addView(drawersCard)

        val tipCard = createFocusCard("Per-App Profiles & Folders")
        tipCard.addView(
            TextView(context).apply {
                text = "Long-press any app in either drawer to hide it, assign folders, toggle Dual-Screen mode, or set a custom Per-App Layout Profile."
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
        )
        contentContainer.addView(tipCard)
    }

    // -------------------------------------------------------------------------
    // Tab 5: Backup & Restore
    // -------------------------------------------------------------------------
    private fun buildBackupTab() {
        val backupCard = createFocusCard("JSON Configuration Backup")
        backupCard.addView(
            TextView(context).apply {
                text = "Save or restore your display splits, gamepad preferences, folders, dual-screen overrides, and per-app layout profiles."
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
        )

        val exportBtn = createActionRowButton(
            title = "Export Settings to JSON",
            subtitle = "Save DroiDS-backup.json to device storage",
            iconRes = R.drawable.ic_settings_export,
            primary = true
        ) {
            callbacks.onExportBackupRequested()
        }
        val importBtn = createActionRowButton(
            title = "Import Settings from JSON",
            subtitle = "Restore a previously saved backup file",
            iconRes = R.drawable.ic_settings_import,
            primary = false
        ) {
            callbacks.onImportBackupRequested()
        }

        backupCard.addView(exportBtn)
        backupCard.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        })
        backupCard.addView(importBtn)
        contentContainer.addView(backupCard)
    }

    // -------------------------------------------------------------------------
    // Reusable One UI Focus Block & Material 3 Expressive Building Blocks
    // -------------------------------------------------------------------------
    private fun createFocusCard(title: String): LinearLayout {
        val p = palette
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(p.cardBg, dp(20), strokeColor = p.cardBorder, strokeWidthPx = dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            addView(TextView(context).apply {
                text = title
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            })
        }
    }

    private fun addResponsiveCardPair(
        leftCard: LinearLayout,
        rightCard: LinearLayout,
        leftWeight: Float = 1f,
        rightWeight: Float = 1f
    ) {
        if (isWidePane()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            leftCard.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                leftWeight
            ).apply { marginEnd = dp(10) }
            rightCard.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                rightWeight
            )
            row.addView(leftCard)
            row.addView(rightCard)
            contentContainer.addView(row)
        } else {
            contentContainer.addView(leftCard)
            contentContainer.addView(rightCard)
        }
    }

    private fun <T> createSegmentedPillRow(
        options: List<Pair<String, T>>,
        currentValue: T,
        onSelect: (T) -> Unit
    ): LinearLayout {
        val p = palette
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedRect(p.pillTrackBg, dp(18), strokeColor = p.pillInactiveBorder, strokeWidthPx = dp(1))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            )
        }

        val itemViews = mutableListOf<Pair<TextView, T>>()
        var selectedVal = currentValue

        fun refreshVisuals() {
            for ((tv, v) in itemViews) {
                val active = (v == selectedVal)
                tv.background = if (active) roundedRect(p.accent, dp(15)) else null
                tv.setTextColor(if (active) p.textOnAccent else p.textSecondary)
                tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        for ((label, value) in options) {
            val tv = TextView(context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedVal != value) {
                        selectedVal = value
                        refreshVisuals()
                        onSelect(value)
                    }
                }
            }
            itemViews.add(tv to value)
            container.addView(tv)
        }
        refreshVisuals()
        return container
    }

    private fun createExpressiveSlider(
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        initialValue: Float,
        onChange: (Float) -> Unit
    ): Slider {
        val p = palette
        val clamped = initialValue.coerceIn(valueFrom, valueTo)
        val snapped = if (stepSize > 0f) {
            val steps = ((clamped - valueFrom) / stepSize).roundToInt()
            (valueFrom + steps * stepSize).coerceIn(valueFrom, valueTo)
        } else clamped

        return Slider(context).apply {
            this.valueFrom = valueFrom
            this.valueTo = valueTo
            this.stepSize = stepSize
            this.value = snapped
            labelBehavior = LabelFormatter.LABEL_GONE
            trackHeight = dp(8)
            thumbRadius = dp(9)
            haloRadius = dp(15)
            trackActiveTintList = ColorStateList.valueOf(p.accent)
            trackInactiveTintList = ColorStateList.valueOf(p.sliderInactive)
            thumbTintList = ColorStateList.valueOf(p.accent)
            haloTintList = ColorStateList.valueOf(p.accentSoftBg)
            isTickVisible = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addOnChangeListener { _, value, fromUser ->
                if (fromUser || abs(value - snapped) > 0.001f) {
                    onChange(value)
                }
            }
        }
    }

    private fun createValueBadge(initial: String): TextView {
        val p = palette
        return TextView(context).apply {
            text = initial
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.accent)
            background = roundedRect(p.badgeBg, dp(10))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
    }

    private fun createSwitchRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): LinearLayout {
        val p = palette
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(6), 0, dp(6))
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            setTextColor(p.textPrimary)
        })
        if (!subtitle.isNullOrBlank()) {
            textCol.addView(TextView(context).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(p.textSecondary)
            })
        }

        val sw = MaterialSwitch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(p.textOnAccent, p.textSecondary)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(p.accent, p.sliderInactive)
            )
            setOnCheckedChangeListener { _, isNowChecked ->
                onCheckedChange(isNowChecked)
            }
        }

        row.isClickable = true
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun createActionRowButton(
        title: String,
        subtitle: String,
        iconRes: Int,
        primary: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val p = palette
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (primary) {
                roundedRect(p.accentSoftBg, dp(16), strokeColor = p.accent, strokeWidthPx = dp(1))
            } else {
                roundedRect(p.pillTrackBg, dp(16), strokeColor = p.pillInactiveBorder, strokeWidthPx = dp(1))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(if (primary) p.accent else p.textPrimary))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) p.accent else p.textPrimary)
        })
        textCol.addView(TextView(context).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(p.textSecondary)
        })

        row.addView(icon)
        row.addView(textCol)
        return row
    }

    private fun createHairlineDivider(): View {
        return View(context).apply {
            setBackgroundColor(palette.railDivider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
    }

    private fun roundedRect(
        fillColor: Int,
        radiusPx: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radiusPx.toFloat()
            if (strokeColor != null && strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
