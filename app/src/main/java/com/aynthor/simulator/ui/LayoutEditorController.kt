package com.aynthor.simulator.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.aynthor.simulator.prefs.SimulatorPrefs
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Interactive full-screen Touch & Toolbar Layout Editor:
 * - Lets the user drag, resize (60%-150%), and set per-item opacity (20%-100%) for all 10 virtual
 *   gamepad controls + both utility toolbars anywhere on screen (including over the virtual displays).
 * - Includes a live draggable horizontal Split Bar between the Top and Bottom virtual screens.
 * - Saves separate normalized layouts for Landscape and Portrait; Reset Item / Reset All restores
 *   items cleanly to their exact original default XML slots.
 * - Zero touch to input/uinput handlers: during gameplay the editor layer is View.GONE.
 */
class LayoutEditorController(
    private val context: Context,
    private val prefs: SimulatorPrefs,
    private val freeControlsLayer: FrameLayout,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onSplitPercentChanged()
        fun onEditModeChanged(editing: Boolean)
        fun onRequestNormalVisibilityRestore()
    }

    enum class ItemId(val key: String, val displayName: String, val isToolbar: Boolean = false) {
        L2("l2", "L2 Trigger"),
        L1("l1", "L1 Bumper"),
        DPAD("dpad", "D-Pad"),
        LSTICK("lstick", "Left Stick"),
        SELECT("select", "Select"),
        R1("r1", "R1 Bumper"),
        R2("r2", "R2 Trigger"),
        ABXY("abxy", "ABXY Buttons"),
        RSTICK("rstick", "Right Stick"),
        START("start", "Start"),
        LEFT_BAR("left_bar", "Left Toolbar", isToolbar = true),
        RIGHT_BAR("right_bar", "Right Toolbar", isToolbar = true)
    }

    private data class DefaultSlot(
        val parent: ViewGroup,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams
    )

    private data class MeasuredBox(
        val centerX: Float,
        val centerY: Float,
        val width: Int,
        val height: Int
    )

    private val items = LinkedHashMap<ItemId, View>()
    private val defaultSlots = HashMap<ItemId, DefaultSlot>()
    private val cachedBaseSizes = HashMap<ItemId, Pair<Int, Int>>()

    private val editorOverlay: FrameLayout
    private val gridView: GridOverlayView
    private val splitBarHandle: FrameLayout
    private val splitBarPill: TextView
    private val proxyLayer: FrameLayout
    private val hudCard: LinearLayout
    private val tvEditingBadge: TextView
    private val swGridSnap: MaterialSwitch
    private val btnFlipHud: TextView
    private val tvSelectedLabel: TextView
    private val sliderSize: Slider
    private val tvSizeValue: TextView
    private val sliderOpacity: Slider
    private val tvOpacityValue: TextView
    private val btnResetItem: TextView

    private val proxyBoxes = HashMap<ItemId, View>()

    private var workingMap = HashMap<String, SimulatorPrefs.CustomItemLayout>()
    private var selectedId: ItemId = ItemId.DPAD
    private var gridSnapEnabled: Boolean = true
    private var hudAtBottom: Boolean = false
    private var suppressSliderCallbacks: Boolean = false
    private var isEditing: Boolean = false

    val isEditModeActive: Boolean
        get() = isEditing

    init {
        editorOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            elevation = dp(20).toFloat()
            isClickable = true
            isFocusable = true
        }

        gridView = GridOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        editorOverlay.addView(gridView)

        // Proxy layer holding draggable hit-boxes over every item during Edit Mode
        proxyLayer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        editorOverlay.addView(proxyLayer)

        // Live draggable horizontal Split Bar between Top and Bottom screens
        splitBarHandle = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            )
        }
        val splitLine = View(context).apply {
            setBackgroundColor(Color.parseColor("#994C9AFF"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2),
                Gravity.CENTER_VERTICAL
            )
        }
        splitBarPill = TextView(context).apply {
            text = "▲ Screen Split: 53% ▼"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            background = roundedRect(
                Color.parseColor("#E61C2436"),
                dp(14),
                strokeColor = Color.parseColor("#FF4C9AFF"),
                strokeWidthPx = dp(1)
            )
            setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(28),
                Gravity.CENTER
            )
        }
        splitBarHandle.addView(splitLine)
        splitBarHandle.addView(splitBarPill)
        attachSplitBarDrag()
        editorOverlay.addView(splitBarHandle)

        // Floating Editor HUD (toggles between top-center and bottom-center via ⇅ button)
        hudCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                Color.parseColor("#F014161D"),
                dp(22),
                strokeColor = Color.parseColor("#38FFFFFF"),
                strokeWidthPx = dp(1)
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
                marginStart = dp(12)
                marginEnd = dp(12)
            }
            isClickable = true
            isFocusable = true
        }

        // HUD Row 1: Editing badge, Grid Snap, Move HUD, Reset All, Save & Done
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvEditingBadge = TextView(context).apply {
            text = "Editing: Landscape"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4C9AFF"))
            background = roundedRect(Color.parseColor("#2A4C9AFF"), dp(12))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        row1.addView(tvEditingBadge)

        val snapLabel = TextView(context).apply {
            text = "Snap"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D1D5DB"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10); marginEnd = dp(4) }
        }
        row1.addView(snapLabel)

        swGridSnap = MaterialSwitch(context).apply {
            isChecked = true
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#0A101D"), Color.parseColor("#9CA3AF"))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#4C9AFF"), Color.parseColor("#363B4D"))
            )
            setOnCheckedChangeListener { _, checked ->
                gridSnapEnabled = checked
                gridView.showGrid = checked
                gridView.invalidate()
            }
        }
        row1.addView(swGridSnap)

        btnFlipHud = createHudPill("⇅", filled = false) {
            hudAtBottom = !hudAtBottom
            val lp = hudCard.layoutParams as FrameLayout.LayoutParams
            lp.gravity = (if (hudAtBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            hudCard.layoutParams = lp
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        row1.addView(btnFlipHud)

        val btnResetAll = createHudPill("Reset All", filled = false) {
            resetAllItems()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        row1.addView(btnResetAll)

        val btnSaveDone = createHudPill("Save & Done", filled = true) {
            exitEditMode(save = true)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        row1.addView(btnSaveDone)
        hudCard.addView(row1)

        // Subtle divider between Row 1 and Inspector Row 2
        hudCard.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#1EFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(6) }
        })

        // HUD Row 2: Selected Item label + Size slider + Opacity slider + Reset Item
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvSelectedLabel = TextView(context).apply {
            text = "D-Pad"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F5F7FA"))
            minWidth = dp(78)
        }
        row2.addView(tvSelectedLabel)

        val sizeCaption = TextView(context).apply {
            text = "Size"
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        row2.addView(sizeCaption)

        sliderSize = createCompactSlider(60f, 150f, 5f, 100f) { v ->
            if (!suppressSliderCallbacks) {
                val pct = v.roundToInt().coerceIn(60, 150)
                tvSizeValue.text = "$pct%"
                updateSelectedScale(pct / 100f)
            }
        }
        row2.addView(sliderSize)

        tvSizeValue = TextView(context).apply {
            text = "100%"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4C9AFF"))
            minWidth = dp(34)
        }
        row2.addView(tvSizeValue)

        val opacityCaption = TextView(context).apply {
            text = "Opacity"
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        row2.addView(opacityCaption)

        sliderOpacity = createCompactSlider(20f, 100f, 5f, 100f) { v ->
            if (!suppressSliderCallbacks) {
                val pct = v.roundToInt().coerceIn(20, 100)
                tvOpacityValue.text = "$pct%"
                updateSelectedOpacity(pct / 100f)
            }
        }
        row2.addView(sliderOpacity)

        tvOpacityValue = TextView(context).apply {
            text = "100%"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4C9AFF"))
            minWidth = dp(34)
        }
        row2.addView(tvOpacityValue)

        btnResetItem = createHudPill("Reset Item", filled = false) {
            resetSelectedItem()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        row2.addView(btnResetItem)
        hudCard.addView(row2)

        editorOverlay.addView(hudCard)
    }

    fun attachTo(rootLayout: ViewGroup) {
        if (editorOverlay.parent === rootLayout) return
        (editorOverlay.parent as? ViewGroup)?.removeView(editorOverlay)
        val lp: ViewGroup.LayoutParams = if (rootLayout is ConstraintLayout) {
            ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(editorOverlay, lp)
    }

    fun registerItems(mapping: Map<ItemId, View>) {
        items.clear()
        defaultSlots.clear()
        for ((id, view) in mapping) {
            items[id] = view
            val parent = view.parent as? ViewGroup
            if (parent != null) {
                val idx = parent.indexOfChild(view)
                defaultSlots[id] = DefaultSlot(parent, idx, copyLayoutParams(view.layoutParams))
            }
        }
    }

    private fun isPortraitNow(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    fun getGamepadViews(): List<View> =
        items.filterKeys { !it.isToolbar }.values.toList()

    fun getToolbarViews(): List<View> =
        items.filterKeys { it.isToolbar }.values.toList()

    fun isItemCustomized(id: ItemId, isPortrait: Boolean = isPortraitNow()): Boolean {
        val map = if (isEditing) workingMap else prefs.getCustomLayout(isPortrait)
        return map.containsKey(id.key)
    }

    /**
     * Applies the saved custom layout (for the current orientation) to all registered items.
     * Items without a custom entry are cleanly restored to their original default XML slots.
     */
    fun applySavedLayoutForCurrentOrientation() {
        val rootW = freeControlsLayer.width
        val rootH = freeControlsLayer.height
        if (rootW <= 0 || rootH <= 0) return

        val savedMap = if (isEditing) workingMap else prefs.getCustomLayout(isPortraitNow())
        val globalAlpha = prefs.getGamepadOpacity()
        val globalScale = prefs.getGamepadScale()

        // First restore any non-customized items back to their default containers in index order
        val toRestore = items.keys.filter { !savedMap.containsKey(it.key) }
        for (id in toRestore) {
            restoreItemToDefaultSlot(id, globalAlpha, globalScale)
        }

        // Next place customized items into freeControlsLayer
        for ((id, view) in items) {
            val custom = savedMap[id.key] ?: continue
            placeItemInFreeLayer(id, view, custom, rootW, rootH, globalAlpha)
        }

        if (isEditing) {
            syncAllProxiesAndSplitBar()
        }
    }

    fun enterEditMode() {
        if (isEditing) return
        val rootW = freeControlsLayer.width
        val rootH = freeControlsLayer.height
        if (rootW <= 0 || rootH <= 0) return

        isEditing = true
        val portrait = isPortraitNow()
        workingMap = HashMap(prefs.getCustomLayout(portrait))
        tvEditingBadge.text = if (portrait) "Editing: Portrait" else "Editing: Landscape"

        // Ensure all 12 items are visible during Edit Mode so user can arrange sticks & buttons
        for ((_, view) in items) {
            view.visibility = View.VISIBLE
        }

        // Capture base sizes of any unmeasured views before moving
        captureCurrentBaseSizes()

        editorOverlay.visibility = View.VISIBLE
        editorOverlay.bringToFront()
        gridView.showGrid = gridSnapEnabled
        gridView.invalidate()

        rebuildProxyBoxes()
        selectItem(selectedId)
        updateSplitBarPosition()
        callbacks.onEditModeChanged(true)

        // Post once so any newly visible sticks (previously GONE) lay out and get accurate proxies
        freeControlsLayer.post {
            if (isEditing) {
                captureCurrentBaseSizes()
                applySavedLayoutForCurrentOrientation()
                syncAllProxiesAndSplitBar()
                syncInspectorSliders()
            }
        }
    }

    fun exitEditMode(save: Boolean) {
        if (!isEditing) return
        isEditing = false
        if (save) {
            prefs.setCustomLayout(isPortraitNow(), workingMap)
        }
        editorOverlay.visibility = View.GONE
        proxyLayer.removeAllViews()
        proxyBoxes.clear()
        applySavedLayoutForCurrentOrientation()
        callbacks.onRequestNormalVisibilityRestore()
        callbacks.onEditModeChanged(false)
    }

    private fun captureCurrentBaseSizes() {
        for ((id, view) in items) {
            if (view.parent === freeControlsLayer) {
                val lp = view.layoutParams
                if (lp.width > 0 && lp.height > 0) {
                    cachedBaseSizes[id] = lp.width to lp.height
                }
            } else if (view.width > 0 && view.height > 0) {
                cachedBaseSizes[id] = view.width to view.height
            } else if (!cachedBaseSizes.containsKey(id)) {
                cachedBaseSizes[id] = fallbackBaseSize(id)
            }
        }
    }

    private fun fallbackBaseSize(id: ItemId): Pair<Int, Int> = when (id) {
        ItemId.L1, ItemId.L2, ItemId.R1, ItemId.R2 -> dp(72) to dp(36)
        ItemId.SELECT, ItemId.START -> dp(104) to dp(38)
        ItemId.DPAD, ItemId.ABXY, ItemId.LSTICK, ItemId.RSTICK -> dp(116) to dp(116)
        ItemId.LEFT_BAR, ItemId.RIGHT_BAR -> dp(180) to dp(34)
    }

    private fun measureItemBoxInRoot(id: ItemId, view: View): MeasuredBox {
        val rootLoc = IntArray(2)
        val viewLoc = IntArray(2)
        freeControlsLayer.getLocationInWindow(rootLoc)
        view.getLocationInWindow(viewLoc)

        val (baseW, baseH) = cachedBaseSizes[id] ?: fallbackBaseSize(id)
        val w = if (view.width > 0) view.width else baseW
        val h = if (view.height > 0) view.height else baseH
        val cx = (viewLoc[0] - rootLoc[0]) + (w * view.scaleX) / 2f
        val cy = (viewLoc[1] - rootLoc[1]) + (h * view.scaleY) / 2f
        return MeasuredBox(cx, cy, w, h)
    }

    private fun ensureItemInWorkingMap(id: ItemId): SimulatorPrefs.CustomItemLayout {
        val existing = workingMap[id.key]
        if (existing != null) return existing

        val view = items[id]!!
        val rootW = freeControlsLayer.width.coerceAtLeast(1)
        val rootH = freeControlsLayer.height.coerceAtLeast(1)
        val box = measureItemBoxInRoot(id, view)
        cachedBaseSizes[id] = box.width to box.height

        val initScale = if (!id.isToolbar &&
            (id == ItemId.DPAD || id == ItemId.LSTICK || id == ItemId.ABXY || id == ItemId.RSTICK)
        ) {
            prefs.getGamepadScale()
        } else {
            1.0f
        }
        val created = SimulatorPrefs.CustomItemLayout(
            xFrac = (box.centerX / rootW).coerceIn(0.05f, 0.95f),
            yFrac = (box.centerY / rootH).coerceIn(0.05f, 0.95f),
            scale = initScale,
            opacity = -1f
        )
        workingMap[id.key] = created
        placeItemInFreeLayer(id, view, created, rootW, rootH, prefs.getGamepadOpacity())
        return created
    }

    private fun placeItemInFreeLayer(
        id: ItemId,
        view: View,
        custom: SimulatorPrefs.CustomItemLayout,
        rootW: Int,
        rootH: Int,
        globalAlpha: Float
    ) {
        val (baseW, baseH) = cachedBaseSizes[id]
            ?: (if (view.width > 0 && view.height > 0) (view.width to view.height) else fallbackBaseSize(id))
        cachedBaseSizes[id] = baseW to baseH

        if (view.parent !== freeControlsLayer) {
            (view.parent as? ViewGroup)?.removeView(view)
            freeControlsLayer.addView(view, FrameLayout.LayoutParams(baseW, baseH))
        } else {
            val lp = view.layoutParams as FrameLayout.LayoutParams
            if (lp.width != baseW || lp.height != baseH) {
                lp.width = baseW
                lp.height = baseH
                view.layoutParams = lp
            }
        }

        val scale = custom.scale.coerceIn(0.60f, 1.50f)
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = when {
            custom.opacity >= 0f -> custom.opacity.coerceIn(0.20f, 1.0f)
            id.isToolbar -> 1.0f
            else -> globalAlpha
        }

        val halfW = (baseW * scale) / 2f
        val halfH = (baseH * scale) / 2f
        val cx = (custom.xFrac * rootW).coerceIn(halfW, (rootW - halfW).coerceAtLeast(halfW))
        val cy = (custom.yFrac * rootH).coerceIn(halfH, (rootH - halfH).coerceAtLeast(halfH))

        view.x = cx - baseW / 2f
        view.y = cy - baseH / 2f
    }

    private fun restoreItemToDefaultSlot(id: ItemId, globalAlpha: Float, globalScale: Float) {
        val view = items[id] ?: return
        val slot = defaultSlots[id] ?: return

        if (view.parent !== slot.parent) {
            (view.parent as? ViewGroup)?.removeView(view)
            val insertIdx = slot.index.coerceIn(0, slot.parent.childCount)
            slot.parent.addView(view, insertIdx, copyLayoutParams(slot.layoutParams))
        }
        view.translationX = 0f
        view.translationY = 0f
        val isScaledMain = (id == ItemId.DPAD || id == ItemId.LSTICK || id == ItemId.ABXY || id == ItemId.RSTICK)
        val sc = if (isScaledMain) globalScale else 1.0f
        view.scaleX = sc
        view.scaleY = sc
        view.alpha = if (id.isToolbar) 1.0f else globalAlpha
    }

    // -------------------------------------------------------------------------
    // Proxy Hitboxes & Drag Handling (Zero Interference With Gameplay Touches)
    // -------------------------------------------------------------------------
    private fun rebuildProxyBoxes() {
        proxyLayer.removeAllViews()
        proxyBoxes.clear()
        for (id in items.keys) {
            val box = View(context).apply {
                isClickable = true
                isFocusable = true
            }
            attachItemDrag(id, box)
            proxyBoxes[id] = box
            proxyLayer.addView(box, FrameLayout.LayoutParams(dp(48), dp(48)))
        }
        syncAllProxiesAndSplitBar()
    }

    private fun syncAllProxiesAndSplitBar() {
        for ((id, view) in items) {
            val proxy = proxyBoxes[id] ?: continue
            val m = measureItemBoxInRoot(id, view)
            val visW = (m.width * view.scaleX).roundToInt().coerceAtLeast(dp(36))
            val visH = (m.height * view.scaleY).roundToInt().coerceAtLeast(dp(32))

            val lp = proxy.layoutParams as FrameLayout.LayoutParams
            if (lp.width != visW || lp.height != visH) {
                lp.width = visW
                lp.height = visH
                proxy.layoutParams = lp
            }
            proxy.x = m.centerX - visW / 2f
            proxy.y = m.centerY - visH / 2f

            val isSel = (id == selectedId)
            proxy.background = if (isSel) {
                roundedRect(
                    Color.parseColor("#2A4C9AFF"),
                    dp(12),
                    strokeColor = Color.parseColor("#FF4C9AFF"),
                    strokeWidthPx = dp(2)
                )
            } else {
                roundedRect(
                    Color.parseColor("#12FFFFFF"),
                    dp(10),
                    strokeColor = Color.parseColor("#55FFFFFF"),
                    strokeWidthPx = dp(1)
                )
            }
        }
        updateSplitBarPosition()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachItemDrag(id: ItemId, proxy: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startCenterX = 0f
        var startCenterY = 0f
        var moved = false

        proxy.setOnTouchListener { _, event ->
            val rootW = freeControlsLayer.width.coerceAtLeast(1)
            val rootH = freeControlsLayer.height.coerceAtLeast(1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectItem(id)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val m = measureItemBoxInRoot(id, items[id]!!)
                    startCenterX = m.centerX
                    startCenterY = m.centerY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (kotlin.math.abs(dx) > dp(3) || kotlin.math.abs(dy) > dp(3))) {
                        moved = true
                    }
                    if (moved) {
                        val cur = ensureItemInWorkingMap(id)
                        var newCx = startCenterX + dx
                        var newCy = startCenterY + dy
                        if (gridSnapEnabled) {
                            val step = dp(16).toFloat()
                            newCx = (newCx / step).roundToInt() * step
                            newCy = (newCy / step).roundToInt() * step
                        }
                        val (baseW, baseH) = cachedBaseSizes[id] ?: fallbackBaseSize(id)
                        val halfW = (baseW * cur.scale) / 2f
                        val halfH = (baseH * cur.scale) / 2f
                        newCx = newCx.coerceIn(halfW, (rootW - halfW).coerceAtLeast(halfW))
                        newCy = newCy.coerceIn(halfH, (rootH - halfH).coerceAtLeast(halfH))

                        val updated = cur.copy(
                            xFrac = (newCx / rootW).coerceIn(0f, 1f),
                            yFrac = (newCy / rootH).coerceIn(0f, 1f)
                        )
                        workingMap[id.key] = updated
                        placeItemInFreeLayer(id, items[id]!!, updated, rootW, rootH, prefs.getGamepadOpacity())
                        syncAllProxiesAndSplitBar()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSplitBarDrag() {
        var downRawY = 0f
        var startPct = 0.53f

        splitBarHandle.setOnTouchListener { _, event ->
            val rootH = freeControlsLayer.height.coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startPct = prefs.getSplitPercent()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    val rawPct = (startPct + dy / rootH.toFloat()).coerceIn(0.40f, 0.66f)
                    val snappedPct = (rawPct * 100f).roundToInt() / 100f
                    if (kotlin.math.abs(snappedPct - prefs.getSplitPercent()) >= 0.005f) {
                        prefs.setSplitPercent(snappedPct)
                        callbacks.onSplitPercentChanged()
                        updateSplitBarPosition()
                        freeControlsLayer.post { if (isEditing) syncAllProxiesAndSplitBar() }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun updateSplitBarPosition() {
        val rootH = freeControlsLayer.height
        if (rootH <= 0) return
        val pct = prefs.getSplitPercent()
        val pctInt = (pct * 100f).roundToInt()
        splitBarPill.text = "▲ Screen Split: $pctInt% ▼"
        splitBarHandle.y = (rootH * pct) - dp(18)
    }

    private fun selectItem(id: ItemId) {
        selectedId = id
        tvSelectedLabel.text = id.displayName
        syncInspectorSliders()
        syncAllProxiesAndSplitBar()
    }

    private fun syncInspectorSliders() {
        val cur = workingMap[selectedId.key]
        val scalePct = ((cur?.scale ?: if (!selectedId.isToolbar &&
                (selectedId == ItemId.DPAD || selectedId == ItemId.LSTICK || selectedId == ItemId.ABXY || selectedId == ItemId.RSTICK)
            ) prefs.getGamepadScale() else 1.0f) * 100f).roundToInt().coerceIn(60, 150)

        val effOpacity = when {
            cur != null && cur.opacity >= 0f -> cur.opacity
            selectedId.isToolbar -> 1.0f
            else -> prefs.getGamepadOpacity()
        }
        val opacityPct = (effOpacity * 100f).roundToInt().coerceIn(20, 100)

        suppressSliderCallbacks = true
        sliderSize.value = (scalePct / 5 * 5).toFloat().coerceIn(60f, 150f)
        tvSizeValue.text = "$scalePct%"
        sliderOpacity.value = (opacityPct / 5 * 5).toFloat().coerceIn(20f, 100f)
        tvOpacityValue.text = "$opacityPct%"
        suppressSliderCallbacks = false
    }

    private fun updateSelectedScale(newScale: Float) {
        val cur = ensureItemInWorkingMap(selectedId)
        val updated = cur.copy(scale = newScale.coerceIn(0.60f, 1.50f))
        workingMap[selectedId.key] = updated
        placeItemInFreeLayer(
            selectedId,
            items[selectedId]!!,
            updated,
            freeControlsLayer.width.coerceAtLeast(1),
            freeControlsLayer.height.coerceAtLeast(1),
            prefs.getGamepadOpacity()
        )
        syncAllProxiesAndSplitBar()
    }

    private fun updateSelectedOpacity(newOpacity: Float) {
        val cur = ensureItemInWorkingMap(selectedId)
        val updated = cur.copy(opacity = newOpacity.coerceIn(0.20f, 1.0f))
        workingMap[selectedId.key] = updated
        placeItemInFreeLayer(
            selectedId,
            items[selectedId]!!,
            updated,
            freeControlsLayer.width.coerceAtLeast(1),
            freeControlsLayer.height.coerceAtLeast(1),
            prefs.getGamepadOpacity()
        )
    }

    private fun resetSelectedItem() {
        workingMap.remove(selectedId.key)
        restoreItemToDefaultSlot(selectedId, prefs.getGamepadOpacity(), prefs.getGamepadScale())
        items[selectedId]?.visibility = View.VISIBLE
        syncInspectorSliders()
        freeControlsLayer.post { if (isEditing) syncAllProxiesAndSplitBar() }
    }

    private fun resetAllItems() {
        workingMap.clear()
        val globalAlpha = prefs.getGamepadOpacity()
        val globalScale = prefs.getGamepadScale()
        for (id in items.keys) {
            restoreItemToDefaultSlot(id, globalAlpha, globalScale)
            items[id]?.visibility = View.VISIBLE
        }
        syncInspectorSliders()
        freeControlsLayer.post { if (isEditing) syncAllProxiesAndSplitBar() }
    }

    // -------------------------------------------------------------------------
    // Helpers & Grid View
    // -------------------------------------------------------------------------
    private class GridOverlayView(context: Context) : View(context) {
        var showGrid: Boolean = true
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#18FFFFFF")
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.parseColor("#5506080E"))
            if (!showGrid) return
            val step = (32f * resources.displayMetrics.density).coerceAtLeast(24f)
            var x = step
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
                x += step
            }
            var y = step
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                y += step
            }
        }
    }

    private fun createCompactSlider(
        from: Float,
        to: Float,
        step: Float,
        initial: Float,
        onChange: (Float) -> Unit
    ): Slider {
        return Slider(context).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            value = initial.coerceIn(from, to)
            labelBehavior = LabelFormatter.LABEL_GONE
            trackHeight = dp(6)
            thumbRadius = dp(7)
            haloRadius = dp(12)
            trackActiveTintList = ColorStateList.valueOf(Color.parseColor("#4C9AFF"))
            trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#363B4D"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#4C9AFF"))
            isTickVisible = false
            layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            addOnChangeListener { _, v, _ -> onChange(v) }
        }
    }

    private fun createHudPill(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = if (filled) {
                roundedRect(Color.parseColor("#4C9AFF"), dp(15))
            } else {
                roundedRect(
                    Color.parseColor("#252834"),
                    dp(15),
                    strokeColor = Color.parseColor("#2AFFFFFF"),
                    strokeWidthPx = dp(1)
                )
            }
            setTextColor(if (filled) Color.parseColor("#0A101D") else Color.parseColor("#F5F7FA"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun copyLayoutParams(src: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        return when (src) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(src.width, src.height, src.weight).apply {
                gravity = src.gravity
                setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin)
            }
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(src.width, src.height, src.gravity).apply {
                setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin)
            }
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(src.width, src.height).apply {
                setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin)
            }
            else -> ViewGroup.LayoutParams(src.width, src.height)
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

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
