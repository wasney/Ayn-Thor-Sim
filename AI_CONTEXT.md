# DroiDS — AI & Developer Context Guide

This document exists so any future AI session (Gemini, Claude, AI Studio, etc.) or human contributor has the full architectural context and **critical "do not break" decisions** before making changes.

---

## 1. Project Overview & Identity
- **App Name:** DroiDS (formerly `AynThorSimulator`)
- **Repository:** `https://github.com/wasney/DroidDS`
- **Application ID (Package ID):** `com.droids.app`
- **Code Namespace:** `com.aynthor.simulator` *(intentionally kept separate from `applicationId` so Kotlin packages, AIDL, `R`, and ViewBinding never break during package ID changes; manifest providers and `AppUpdater` resolve `${applicationId}` / `packageName` dynamically).*
- **Target Devices Tested:** Samsung Galaxy Z Fold 7 (`SM-F966U1`, `8.0"` `2184×1968`, Android 16/SDK 37) and Samsung Galaxy Z Fold 8 (`7.6"` `2448×1848` `4:3`), plus GameSir USB-C telescoping controllers.
- **Core Purpose:** No-root, Shizuku-powered dual-screen handheld environment for foldable and large-screen Android devices. Creates two independent hardware-accelerated `VirtualDisplay`s—a 16:9 top screen (`1920×1080`) and a bottom-center `PRESENTATION` display (`1240×1080` default, dynamically resizable)—so dual-screen emulators, recompilations, and mods work natively on a single display.

---

## 2. Critical "Do Not Break" Architectural Decisions

Before refactoring or "optimizing" any of the following, understand **why** they are built this way:

1. **Shell `am start --display` in `launchAppOnDisplay()` (Do NOT replace with `ActivityOptions`):**
   - Android 16+ Background Activity Launch (BAL) restrictions silently drop `ActivityOptions.setLaunchDisplayId()` when switching apps from an overlay or launcher.
   - `MainActivity` intentionally calls the Shizuku shell service's `launchAppOnDisplay()` (`am start --display <id> -n <component> --activity-single-top --activity-reorder-to-front`) first, with `ActivityOptions` only as a fallback.
2. **Kernel `/dev/uinput` Virtual Gamepad (`UinputGamepad.kt`) & Window Focusability (`FLAG_NOT_FOCUSABLE`):**
   - Android's `InputManager.injectInputEvent()` suffers from 5-second stalls on Samsung Android 16/17 (SDK 37).
   - Virtual touch buttons/sticks default to writing directly to a kernel Xbox 360 `/dev/uinput` device (~0.02ms latency), while `MainActivity` toggles `FLAG_NOT_FOCUSABLE` during gameplay so the game on the virtual display keeps permanent input focus.
   - **Never modify `uinput` or focus handling** unless explicitly fixing a verified input bug.
3. **`pokeDisplayFocus()` in `VirtualDisplayUserService.kt`:**
   - Android has no public/shell API to shift input focus to a `VirtualDisplay` without injecting a touch event. Do not remove `pokeDisplayFocus()` without verifying physical controller routing on-device.
4. **Keep `isMinifyEnabled = false` in `app/build.gradle.kts`:**
   - `VirtualDisplayUserService.kt` uses reflection into hidden Android APIs (`DisplayManager`, `InputManager.injectInputEvent`, `InputEvent.setDisplayId`, `ActivityTaskManager`). Enabling R8/ProGuard without exhaustive tested keep rules will break Shizuku virtual display creation and input injection at runtime.
5. **Always Bump Both `versionCode` and `UserServiceArgs.version(...)` Together:**
   - In `app/build.gradle.kts` (`versionCode = N`) and `MainActivity.kt` (`Shizuku.UserServiceArgs(...).version(N)`), both numbers must match and increment on every release so Shizuku cleanly restarts the shell `UserService` after an in-place APK update.
6. **Invisible `Space` Placeholders in `LayoutEditorController.kt`:**
   - When the user drags a control out of its default `LinearLayout` into `freeControlsLayer`, an invisible `android.widget.Space` placeholder is inserted at the original index so `layout_weight="1"` siblings (e.g., D-Pad + Left Stick in `MODE_BOTH`) do not double in size.
   - `applyShoulderButtonLayout()` in `MainActivity.kt` must always check parent containers before reparenting shoulder buttons to avoid `IllegalStateException`.
7. **Physical Controller Auto-Switching (`effectiveBottomAspectRatio()` & `resizeBottomDisplay()`):**
   - When a physical controller is connected and touch controls are hidden in 2-Layer mode, sidebar margins drop to `0dp`.
   - `effectiveBottomAspectRatio()` automatically locks the bottom display to `"1240:1080"` (Thor ratio) for dual-screen emulators (`isDualScreenApp && !hasBottomAppRunning`) to prevent stretching, and switches to `"Fill"` for normal bottom apps (browsers, YouTube, Bottom Home), calling `resizeBottomDisplay(w, h, VIRTUAL_DPI)` so the virtual display renders 1:1.
8. **Hitbox Normalization in `VirtualGamepadViews.kt` & `LayoutEditorController.kt`:**
   - `DPAD`, `LSTICK`, `ABXY`, and `RSTICK` normalize to exact square bounds (`side = minOf(w, h)`) and pass through `ACTION_DOWN` outside their circular/button hit zones so adjacent controls (`L1`/`L2`/`R1`/`R2`) never get blocked.

---

## 3. Key Files Map

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/aynthor/simulator/MainActivity.kt` | Main container activity, virtual display lifecycle, layout engine, physical controller auto-switching, app drawers, bottom home dashboard, crash logger (`last_crash.txt`). |
| `app/src/main/java/com/aynthor/simulator/service/VirtualDisplayUserService.kt` | Shizuku `UID 2000` shell service (`IVirtualDisplayService.aidl`): creates/resizes/releases virtual displays, `uinput` & `InputManager` input routing, `am start`/`force-stop`, task swapping, sysfs perf telemetry. |
| `app/src/main/java/com/aynthor/simulator/service/UinputGamepad.kt` | Kernel `/dev/uinput` Xbox 360 virtual controller driver. |
| `app/src/main/java/com/aynthor/simulator/ui/SettingsSheetController.kt` | Bottom-anchored 2-pane translucent Settings sheet (Display, Home, Gamepad, Theme, Backup, About & Updates). |
| `app/src/main/java/com/aynthor/simulator/ui/OnboardingOverlayController.kt` | First-launch 3-step Welcome/Shizuku/Controls wizard + compact post-reboot Shizuku reconnect gate. |
| `app/src/main/java/com/aynthor/simulator/ui/LayoutEditorController.kt` | Interactive drag/resize/opacity/grid-snap touch & toolbar layout editor + draggable screen split bar. |
| `app/src/main/java/com/aynthor/simulator/update/AppUpdater.kt` | GitHub Releases updater (`wasney/DroidDS`), semver comparator (ignoring `-b<run_number>`), APK downloader + `FileProvider` installer. |
| `app/src/main/java/com/aynthor/simulator/prefs/SimulatorPrefs.kt` | All `SharedPreferences` keys, per-app profiles, folders, dual-screen overrides, custom layouts, and JSON Backup/Restore. |
| `app/src/main/java/com/aynthor/simulator/input/VirtualGamepadViews.kt` | Custom touch gamepad views (`VirtualDpadView`, `VirtualFaceButtonsView`, `VirtualThumbstickView`, `VirtualButtonView`). |

---

## 4. CI/CD, Signing & Release Workflow

- **Workflow file:** `.github/workflows/build-apk.yml`
- **Build Trigger:** Triggers **only** when a zip matching `DroiDS-v*.zip` is pushed to `main`/`master` (or manually via `workflow_dispatch`). Editing `README.md`, `AI_CONTEXT.md`, or other files will **not** trigger a build or publish a release.
- **Zip Format:** Root-level project files (no wrapper folder), with `gradlew` set to `0755`. Note that `unzip -o "$ZIP_FILE" -x ".github/*"` intentionally skips `.github/*`, so workflow edits must be committed directly on GitHub.
- **Release Signing (GitHub Secrets — NEVER commit keystores):**
  - `DROIDS_KEYSTORE_BASE64` — Base64-encoded `.jks` release keystore
  - `DROIDS_KEYSTORE_PASSWORD` — Keystore password
  - `DROIDS_KEY_ALIAS` — Key alias
  - `DROIDS_KEY_PASSWORD` — Key password
- **In-App Updater Compatibility:** `AppUpdater` queries `https://api.github.com/repos/wasney/DroidDS/releases?per_page=10` (requires the repo to be public), ignores `-b<run_number>` suffixes, and installs the first `.apk` asset.
