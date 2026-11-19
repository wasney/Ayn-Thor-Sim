package com.aynthor.simulator.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.aynthor.simulator.prefs.SimulatorPrefs
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

/**
 * First-launch interactive 3-step onboarding wizard (Route A) + compact post-reboot
 * Shizuku reconnect gate sharing the same live interactive checklist and 1-tap shortcuts.
 */
class OnboardingOverlayController(
    private val context: Context,
    private val prefs: SimulatorPrefs,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onRequestShizukuPermission()
        fun onRetryShizukuBind()
        fun onVisibilityChanged(visible: Boolean)
    }

    enum class Mode {
        WIZARD,
        SHIZUKU_RECONNECT
    }

    private data class Palette(
        val scrim: Int,
        val surface: Int,
        val surfaceBorder: Int,
        val sidebarBg: Int,
        val divider: Int,
        val cardBg: Int,
        val cardActiveBg: Int,
        val cardBorder: Int,
        val accent: Int,
        val accentSoft: Int,
        val green: Int,
        val greenSoft: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textOnAccent: Int,
        val mutedBg: Int
    )

    private fun darkPalette() = Palette(
        scrim = Color.parseColor("#E606070B"),
        surface = Color.parseColor("#FF14161D"),
        surfaceBorder = Color.parseColor("#24FFFFFF"),
        sidebarBg = Color.parseColor("#FF0E1016"),
        divider = Color.parseColor("#1AFFFFFF"),
        cardBg = Color.parseColor("#FF20232E"),
        cardActiveBg = Color.parseColor("#244C9AFF"),
        cardBorder = Color.parseColor("#18FFFFFF"),
        accent = Color.parseColor("#4C9AFF"),
        accentSoft = Color.parseColor("#2E4C9AFF"),
        green = Color.parseColor("#34D399"),
        greenSoft = Color.parseColor("#2434D399"),
        textPrimary = Color.parseColor("#F5F7FA"),
        textSecondary = Color.parseColor("#9CA3AF"),
        textOnAccent = Color.parseColor("#0A101D"),
        mutedBg = Color.parseColor("#FF282C3A")
    )

    private fun lightPalette() = Palette(
        scrim = Color.parseColor("#E0E5E9F0"),
        surface = Color.parseColor("#FFF8F9FC"),
        surfaceBorder = Color.parseColor("#1E000000"),
        sidebarBg = Color.parseColor("#FFEBF0F7"),
        divider = Color.parseColor("#18000000"),
        cardBg = Color.parseColor("#FFFFFFFF"),
        cardActiveBg = Color.parseColor("#1A2563EB"),
        cardBorder = Color.parseColor("#16000000"),
        accent = Color.parseColor("#2563EB"),
        accentSoft = Color.parseColor("#222563EB"),
        green = Color.parseColor("#059669"),
        greenSoft = Color.parseColor("#1E059669"),
        textPrimary = Color.parseColor("#111827"),
        textSecondary = Color.parseColor("#4B5563"),
        textOnAccent = Color.parseColor("#FFFFFF"),
        mutedBg = Color.parseColor("#FFE5E7EB")
    )

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PLAY_URI = "market://details?id=$SHIZUKU_PACKAGE"
        private const val SHIZUKU_GITHUB_URL = "https://github.com/RikkaApps/Shizuku/releases"
    }

    val overlayRoot: FrameLayout
    private val modalCard: LinearLayout
    private var palette: Palette = darkPalette()
    private var isLight: Boolean = false
    private var mode: Mode = Mode.WIZARD
    private var currentStep: Int = 0 // 0 = Welcome, 1 = Shizuku Setup, 2 = Controls Tour
    private var reconnectStatusNote: String? = null
    private var lastWideLayout: Boolean? = null

    val isVisible: Boolean
        get() = overlayRoot.visibility == View.VISIBLE

    val isInWizardMode: Boolean
        get() = isVisible && mode == Mode.WIZARD

    init {
        overlayRoot = FrameLayout(context).apply {
            visibility = View.GONE
            elevation = dp(28).toFloat()
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        modalCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        overlayRoot.addView(modalCard)
    }

    fun attachTo(parent: ViewGroup) {
        if (overlayRoot.parent === parent) return
        (overlayRoot.parent as? ViewGroup)?.removeView(overlayRoot)
        val lp: ViewGroup.LayoutParams = if (parent is ConstraintLayout) {
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
        parent.addView(overlayRoot, lp)
    }

    fun refreshTheme(light: Boolean) {
        isLight = light
        palette = if (light) lightPalette() else darkPalette()
        if (isVisible) render()
    }

    fun onLayoutSizeChanged() {
        if (!isVisible) return
        val wideNow = isWideScreen()
        if (lastWideLayout != null && lastWideLayout != wideNow) {
            render()
        }
    }

    fun showWizard(startStep: Int = 0) {
        mode = Mode.WIZARD
        currentStep = startStep.coerceIn(0, 2)
        reconnectStatusNote = null
        showInternal()
    }

    fun showShizukuReconnect(statusNote: String? = null) {
        // Don't interrupt the first-launch wizard if it's already open on screen
        if (isVisible && mode == Mode.WIZARD) {
            if (currentStep == 1) render()
            return
        }
        mode = Mode.SHIZUKU_RECONNECT
        reconnectStatusNote = statusNote
        showInternal()
    }

    fun refreshLiveStatus() {
        if (!isVisible) return
        if (mode == Mode.SHIZUKU_RECONNECT) {
            if (isShizukuReady()) {
                dismiss(markOnboardingDone = false)
                return
            }
            render()
        } else if (currentStep == 1) {
            render()
        }
    }

    fun dismiss(markOnboardingDone: Boolean = false) {
        if (markOnboardingDone) {
            prefs.setOnboardingCompleted(true)
        }
        if (overlayRoot.visibility != View.VISIBLE) return
        overlayRoot.visibility = View.GONE
        callbacks.onVisibilityChanged(false)
        callbacks.onRetryShizukuBind()
    }

    fun dismissIfReconnectMode() {
        if (isVisible && mode == Mode.SHIZUKU_RECONNECT) {
            overlayRoot.visibility = View.GONE
            callbacks.onVisibilityChanged(false)
        }
    }

    private fun showInternal() {
        overlayRoot.visibility = View.VISIBLE
        overlayRoot.bringToFront()
        render()
        callbacks.onVisibilityChanged(true)
    }

    private fun isWideScreen(): Boolean {
        val dm = context.resources.displayMetrics
        val w = (overlayRoot.width.takeIf { it > 0 } ?: dm.widthPixels) / dm.density
        return w >= 560f
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuAuthorized(): Boolean {
        return try {
            isShizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuReady(): Boolean = isShizukuRunning() && isShizukuAuthorized()

    private fun render() {
        val p = palette
        val wide = isWideScreen()
        lastWideLayout = wide

        overlayRoot.setBackgroundColor(p.scrim)
        modalCard.removeAllViews()

        val maxW = if (mode == Mode.SHIZUKU_RECONNECT) dp(640) else dp(860)
        val dm = context.resources.displayMetrics
        val availW = (overlayRoot.width.takeIf { it > 0 } ?: dm.widthPixels) - dp(32)
        val availH = (overlayRoot.height.takeIf { it > 0 } ?: dm.heightPixels) - dp(32)
        val targetW = availW.coerceAtMost(maxW).coerceAtLeast(dp(280))
        val targetH = if (mode == Mode.SHIZUKU_RECONNECT) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            availH.coerceAtMost(dp(560)).coerceAtLeast(dp(360))
        }

        modalCard.layoutParams = FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)
        modalCard.background = roundedRect(p.surface, dp(28), strokeColor = p.surfaceBorder, strokeWidthPx = dp(1))

        if (mode == Mode.SHIZUKU_RECONNECT) {
            renderReconnectMode()
        } else {
            renderWizardMode(wide)
        }
    }

    // -------------------------------------------------------------------------
    // Mode 1: Interactive 3-Step Setup Wizard (Route A)
    // -------------------------------------------------------------------------
    private fun renderWizardMode(wide: Boolean) {
        val p = palette

        if (wide) {
            // Two-pane Route A layout matching the unfolded Galaxy Z Fold mockup
            val splitRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            modalCard.addView(splitRow)

            // Left step sidebar (~34% width)
            val leftCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(p.sidebarBg)
                setPadding(dp(20), dp(18), dp(18), dp(18))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.36f)
            }
            splitRow.addView(leftCol)

            // Brand + Skip row
            val brandRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }
            brandRow.addView(TextView(context).apply {
                text = "DroiDS"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            brandRow.addView(createTextAction("Skip") { dismiss(markOnboardingDone = true) })
            leftCol.addView(brandRow)

            // 3 vertical step pills
            val stepTitles = listOf("1. Welcome", "2. Shizuku Setup", "3. Controls Tour")
            for (i in stepTitles.indices) {
                leftCol.addView(createStepPill(stepTitles[i], i, horizontal = false))
            }

            // Step headline & subtitle in left sidebar
            leftCol.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
            val (headline, sub) = stepHeadlineAndSubtitle(currentStep)
            leftCol.addView(TextView(context).apply {
                text = headline
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            })
            leftCol.addView(TextView(context).apply {
                text = sub
                textSize = 12.5f
                setTextColor(p.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
            })

            // Vertical divider
            splitRow.addView(View(context).apply {
                setBackgroundColor(p.divider)
                layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            })

            // Right scrollable step content (~64% width)
            val rightScroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(20), dp(18), dp(20), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.64f)
            }
            val rightContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            populateStepContent(rightContent, currentStep)
            rightScroll.addView(rightContent)
            splitRow.addView(rightScroll)
        } else {
            // Compact portrait/cover layout: Top header + horizontal 3-step pills + scrollable body
            val topBar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(p.sidebarBg)
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val (headline, _) = stepHeadlineAndSubtitle(currentStep)
            headerRow.addView(TextView(context).apply {
                text = headline
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            headerRow.addView(createTextAction("Skip") { dismiss(markOnboardingDone = true) })
            topBar.addView(headerRow)

            val pillRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val shortSteps = listOf("1. Welcome", "2. Shizuku", "3. Tour")
            for (i in shortSteps.indices) {
                pillRow.addView(createStepPill(shortSteps[i], i, horizontal = true))
            }
            topBar.addView(pillRow)
            modalCard.addView(topBar)

            modalCard.addView(View(context).apply {
                setBackgroundColor(p.divider)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })

            val bodyScroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val bodyCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            populateStepContent(bodyCol, currentStep)
            bodyScroll.addView(bodyCol)
            modalCard.addView(bodyScroll)
        }

        // Bottom navigation footer
        modalCard.addView(View(context).apply {
            setBackgroundColor(p.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        if (currentStep > 0) {
            footer.addView(createPillButton("Back", primary = false) {
                currentStep = (currentStep - 1).coerceAtLeast(0)
                render()
            })
        } else {
            footer.addView(createPillButton("Skip Setup", primary = false) {
                dismiss(markOnboardingDone = true)
            })
        }

        footer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })

        val nextLabel = when (currentStep) {
            0 -> "Next: Shizuku Setup"
            1 -> "Next: Controls Tour"
            else -> "Start Using DroiDS"
        }
        footer.addView(createPillButton(nextLabel, primary = true) {
            if (currentStep < 2) {
                currentStep++
                render()
            } else {
                dismiss(markOnboardingDone = true)
            }
        })
        modalCard.addView(footer)
    }

    private fun stepHeadlineAndSubtitle(step: Int): Pair<String, String> = when (step) {
        0 -> "Welcome to DroiDS" to "Dual-screen handheld gaming on your foldable, powered by virtual displays and a built-in kernel gamepad."
        1 -> "Setup Shizuku" to "DroiDS uses Shizuku once per reboot to create trusted dual screens and low-latency controls—no root required."
        else -> "Quick Controls Tour" to "Everything you need to navigate screens, customize layouts, and tweak settings live while playing."
    }

    private fun createStepPill(label: String, stepIndex: Int, horizontal: Boolean): TextView {
        val p = palette
        val active = (stepIndex == currentStep)
        val completed = (stepIndex < currentStep) || (stepIndex == 1 && isShizukuReady())
        val prefix = if (completed && !active) "✓ " else ""

        return TextView(context).apply {
            text = prefix + label
            textSize = if (horizontal) 11.5f else 13f
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            gravity = if (horizontal) Gravity.CENTER else (Gravity.CENTER_VERTICAL or Gravity.START)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = when {
                active -> roundedRect(p.accent, dp(18))
                completed -> roundedRect(p.greenSoft, dp(18), strokeColor = p.green, strokeWidthPx = dp(1))
                else -> roundedRect(p.mutedBg, dp(18))
            }
            setTextColor(
                when {
                    active -> p.textOnAccent
                    completed -> p.green
                    else -> p.textSecondary
                }
            )
            layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (stepIndex < 2) dp(6) else 0
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentStep = stepIndex
                render()
            }
        }
    }

    private fun populateStepContent(container: LinearLayout, step: Int) {
        when (step) {
            0 -> buildWelcomeStep(container)
            1 -> buildShizukuChecklist(container, includeOfflineTip = true)
            else -> buildControlsTourStep(container)
        }
    }

    private fun buildWelcomeStep(container: LinearLayout) {
        container.addView(
            createInfoCard(
                title = "True Dual-Screen Handheld Mode",
                body = "DroiDS creates two independent Android displays on your screen—so DS/3DS emulators like SeedlessDS, DraStic, and Azahar automatically span both screens, or you can run two separate apps at once."
            )
        )
        container.addView(
            createInfoCard(
                title = "Why Shizuku Is Needed (No Root)",
                body = "Stock Android restricts regular apps from creating trusted virtual displays and registering a virtual Xbox 360 gamepad. Shizuku grants DroiDS shell-level access safely via Android's built-in Wireless Debugging."
            )
        )
        container.addView(
            createInfoCard(
                title = "Works Offline After Starting",
                body = "Wi-Fi is only needed once after a phone reboot to start Shizuku. Once running, DroiDS works completely offline—on mobile data, flights, or Airplane Mode—as long as USB Debugging stays turned on."
            )
        )
    }

    private fun buildShizukuChecklist(container: LinearLayout, includeOfflineTip: Boolean) {
        val installed = isShizukuInstalled()
        val running = isShizukuRunning()
        val authorized = isShizukuAuthorized()

        // Step 1: Install Shizuku
        container.addView(
            createChecklistCard(
                stepNumber = 1,
                done = installed,
                active = !installed,
                title = if (installed) "Shizuku Installed" else "Install Shizuku",
                subtitle = if (installed) {
                    "Shizuku is installed on this device."
                } else {
                    "Download the free Shizuku app from Google Play or GitHub."
                },
                primaryButtonLabel = if (installed) "Open Shizuku" else "Download Shizuku",
                primaryIsFilled = !installed,
                secondaryButtonLabel = if (!installed) "GitHub" else null,
                onPrimaryClick = {
                    if (installed) openShizukuApp() else openShizukuDownload(preferPlayStore = true)
                },
                onSecondaryClick = {
                    openShizukuDownload(preferPlayStore = false)
                }
            )
        )

        // Step 2: Start via Wireless Debugging
        container.addView(
            createChecklistCard(
                stepNumber = 2,
                done = running,
                active = installed && !running,
                title = if (running) "Shizuku Service Running" else "Start via Wireless Debugging",
                subtitle = if (running) {
                    "Shizuku is active in the background. Keep USB Debugging ON so it stays alive offline."
                } else {
                    "In Developer Options, enable USB Debugging & Wireless Debugging, then open Shizuku to Pair & Start."
                },
                primaryButtonLabel = if (running) "Battery Info" else "Developer Options",
                primaryIsFilled = installed && !running,
                secondaryButtonLabel = if (!running && installed) "Open Shizuku" else null,
                onPrimaryClick = {
                    if (running) openShizukuAppInfo() else openDeveloperOptions()
                },
                onSecondaryClick = {
                    openShizukuApp()
                }
            )
        )

        // Step 3: Grant DroiDS Permission
        container.addView(
            createChecklistCard(
                stepNumber = 3,
                done = authorized,
                active = running && !authorized,
                title = if (authorized) "DroiDS Authorized — Ready!" else "Grant DroiDS Permission",
                subtitle = if (authorized) {
                    "All set! Dual screens and virtual gamepad are ready to go."
                } else if (running) {
                    "Tap Authorize and select \"Allow all the time\" in the Shizuku prompt."
                } else {
                    "Start Shizuku in Step 2 first, then authorize DroiDS here."
                },
                primaryButtonLabel = if (authorized) "Re-check" else "Authorize",
                primaryIsFilled = running && !authorized,
                secondaryButtonLabel = null,
                onPrimaryClick = {
                    if (running && !authorized) {
                        callbacks.onRequestShizukuPermission()
                    } else {
                        callbacks.onRetryShizukuBind()
                        refreshLiveStatus()
                    }
                },
                onSecondaryClick = null
            )
        )

        if (includeOfflineTip) {
            container.addView(
                createInfoCard(
                    title = "Tip: Keep Shizuku Alive Offline",
                    body = "• Keep USB Debugging ON alongside Wireless Debugging.\n• Enable \"Disable adb authorization timeout\" in Developer Options.\n• Set Shizuku's Battery usage to Unrestricted."
                )
            )
        }
    }

    private fun buildControlsTourStep(container: LinearLayout) {
        container.addView(
            createInfoCard(
                title = "Top & Bottom App Drawers + Screen Swap",
                body = "Use the Top and Bottom launcher buttons to pick apps for each screen. Tap Swap anytime to flip your Top and Bottom displays live—even mid-game."
            )
        )
        container.addView(
            createInfoCard(
                title = "Bottom Home, Performance Gauge & Control Center",
                body = "Tap the Gauge button on the bottom bar to toggle your live FPS/CPU/RAM monitor, Brightness & Volume sliders, Wi-Fi/Bluetooth shortcuts, and AMOLED Bottom Screen Dimmer."
            )
        )
        container.addView(
            createInfoCard(
                title = "Virtual Gamepad & Physical Controllers",
                body = "On-screen controls use a low-latency kernel Xbox 360 gamepad and auto-hide whenever a Bluetooth or USB controller connects. Customize layout (Xbox/Nintendo), L3/R3, scale, and opacity in Settings."
            )
        )
        container.addView(
            createInfoCard(
                title = "Long-Press Any App for Power Tools",
                body = "Long-press an app icon in either drawer to create custom Folders, toggle Dual-Screen mode, hide apps, or save a Per-App Split & Orientation Profile."
            )
        )
        container.addView(
            createInfoCard(
                title = "Live Quick Settings",
                body = "Open Settings anytime from the bottom bar—your top game stays visible while you adjust Screen Split %, Aspect Ratios, or Theme. Tap Done, swipe down, or tap the top screen to close."
            )
        )
    }

    // -------------------------------------------------------------------------
    // Mode 2 (Bonus): Compact Post-Reboot Shizuku Reconnect Gate
    // -------------------------------------------------------------------------
    private fun renderReconnectMode() {
        val p = palette

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.sidebarBg)
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = "Shizuku Setup Required"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textPrimary)
        })
        titleCol.addView(TextView(context).apply {
            text = reconnectStatusNote ?: "Start Shizuku via Wireless Debugging (required once after a phone reboot)."
            textSize = 12.5f
            setTextColor(p.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        header.addView(titleCol)
        header.addView(createTextAction("Full Guide") { showWizard(startStep = 0) })
        modalCard.addView(header)

        modalCard.addView(View(context).apply {
            setBackgroundColor(p.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(18), dp(14), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        buildShizukuChecklist(body, includeOfflineTip = false)
        scroll.addView(body)
        modalCard.addView(scroll)

        modalCard.addView(View(context).apply {
            setBackgroundColor(p.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        footer.addView(TextView(context).apply {
            text = "Works offline once started (keep USB Debugging ON)."
            textSize = 11.5f
            setTextColor(p.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        })
        footer.addView(createPillButton("Retry Connection", primary = true) {
            callbacks.onRetryShizukuBind()
            refreshLiveStatus()
        })
        modalCard.addView(footer)
    }

    // -------------------------------------------------------------------------
    // Reusable Cards & Action Shortcuts
    // -------------------------------------------------------------------------
    private fun createChecklistCard(
        stepNumber: Int,
        done: Boolean,
        active: Boolean,
        title: String,
        subtitle: String,
        primaryButtonLabel: String,
        primaryIsFilled: Boolean,
        secondaryButtonLabel: String?,
        onPrimaryClick: () -> Unit,
        onSecondaryClick: (() -> Unit)?
    ): LinearLayout {
        val p = palette
        val bg = if (active) p.cardActiveBg else p.cardBg
        val border = when {
            active -> p.accent
            done -> p.greenSoft
            else -> p.cardBorder
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(bg, dp(18), strokeColor = border, strokeWidthPx = dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(context).apply {
            text = if (done) "✓" else stepNumber.toString()
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRect(
                if (done) p.green else if (active) p.accent else p.mutedBg,
                dp(14)
            )
            setTextColor(if (done || active) p.textOnAccent else p.textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
        }
        topRow.addView(badge)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        textCol.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (done) p.green else p.textPrimary)
        })
        textCol.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(p.textSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        topRow.addView(textCol)
        card.addView(topRow)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        if (secondaryButtonLabel != null && onSecondaryClick != null) {
            btnRow.addView(createPillButton(secondaryButtonLabel, primary = false, compact = true, onClick = onSecondaryClick).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply { marginEnd = dp(8) }
            })
        }
        btnRow.addView(createPillButton(primaryButtonLabel, primary = primaryIsFilled, compact = true, onClick = onPrimaryClick))
        card.addView(btnRow)

        return card
    }

    private fun createInfoCard(title: String, body: String): LinearLayout {
        val p = palette
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(p.cardBg, dp(18), strokeColor = p.cardBorder, strokeWidthPx = dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            })
            addView(TextView(context).apply {
                text = body
                textSize = 12.5f
                setTextColor(p.textSecondary)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
    }

    private fun createPillButton(
        label: String,
        primary: Boolean,
        compact: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val p = palette
        return TextView(context).apply {
            text = label
            textSize = if (compact) 12f else 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(if (compact) 14 else 18), 0, dp(if (compact) 14 else 18), 0)
            background = if (primary) {
                roundedRect(p.accent, dp(18))
            } else {
                roundedRect(p.mutedBg, dp(18), strokeColor = p.cardBorder, strokeWidthPx = dp(1))
            }
            setTextColor(if (primary) p.textOnAccent else p.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(if (compact) 34 else 38)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createTextAction(label: String, onClick: () -> Unit): TextView {
        val p = palette
        return TextView(context).apply {
            text = label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textSecondary)
            setPadding(dp(10), dp(6), dp(6), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // -------------------------------------------------------------------------
    // System & App Shortcut Intents
    // -------------------------------------------------------------------------
    private fun openShizukuApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                return
            } catch (_: Throwable) {}
        }
        openShizukuDownload(preferPlayStore = true)
    }

    private fun openShizukuDownload(preferPlayStore: Boolean) {
        if (preferPlayStore) {
            try {
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_PLAY_URI)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(playIntent)
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through to GitHub releases
            }
        }
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GITHUB_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        } catch (_: Throwable) {
            Toast.makeText(context, "Could not open browser for Shizuku download", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDeveloperOptions() {
        try {
            val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(devIntent)
        } catch (_: Throwable) {
            try {
                val aboutIntent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(aboutIntent)
                Toast.makeText(
                    context,
                    "Tap 'Build number' 7 times in Software Information to unlock Developer Options",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
                Toast.makeText(context, "Open Settings → Developer Options to enable Wireless Debugging", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openShizukuAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$SHIZUKU_PACKAGE")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Set Battery to 'Unrestricted' so Shizuku stays alive in the background", Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            Toast.makeText(context, "Could not open Shizuku App Info", Toast.LENGTH_SHORT).show()
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
