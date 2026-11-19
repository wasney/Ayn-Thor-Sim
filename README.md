# DroiDS

**DroiDS** is a no-root, Shizuku-powered dual-screen handheld gaming environment for foldable and large-screen Android devices (tested on Samsung Galaxy Z Fold 7 / Android SDK 37). It creates two independent hardware-accelerated virtual displays—a **16:9 top screen (`1920×1080`)** and a **bottom-center secondary screen (`1240×1080`, `PRESENTATION`)**—flanked by customizable multi-touch controls, full physical gamepad passthrough, and a configurable bottom home dashboard.

---

## Features (v2.0.0)

* **True Dual Virtual Displays (No Root):** Uses [Shizuku](https://github.com/RikkaApps/Shizuku-API) (`UID 2000` shell service) to create two independent virtual screens so you can run two apps side-by-side or run dual-screen emulators (Nintendo DS, 3DS, Wii U) natively across both displays.
* **Hybrid Hardware + Touch Controls:**
  * Full virtual gamepad (`D-Pad`, `L/R` analog thumbsticks with locked center, `A/B/X/Y` with hardware press-and-hold via Android `uinput` Xbox 360 registration, `L1/L2/R1/R2`, `SELECT/START`, plus 3-mode toggles `D-PAD` / `L-STK` / `D+LS` and `ABXY` / `R-STK` / `ABXY+RS`).
  * Automatic physical controller detection (tested with GameSir X2S) that hides on-screen controls when plugged in while allowing simultaneous touch + physical input if toggled back on.
  * **Xbox vs. Nintendo Face Button Layout** switch in Settings.
* **Controller-Navigable Top Launcher:** Navigate the top app drawer using your physical controller or virtual D-pad/left stick (`D-Pad`/`Stick` to move, `A` to launch, `B` to close, `L1`/`R1` to cycle folders) without affecting in-game input.
* **Configurable Bottom Home Screen:** Handheld-style dashboard on the secondary display with toggleable **Clock**, **Live Performance Monitor** (FPS, CPU speed & temp, GPU speed & load, Battery % & temp), and **Quick Shortcuts**.
* **Upgraded Top & Bottom App Drawers:** Live search bar, custom folders (long-press any app to organize), sorting (`A–Z`, `Z–A`, `Recent`), and hide/show filters.
* **Light, Dark & System Themes:** Switch between System default, AMOLED Dark, and Light mode from Settings.
* **Live Screen Swap & Bottom App Controls:** Swap running apps between the top and bottom screens on the fly or close bottom-screen apps cleanly.

---

## Requirements & Setup

1. Install and start **[Shizuku](https://shizuku.rikka.app/)** via Wireless Debugging (no root required).
2. Download and install the latest **`DroiDS-vX.Y.Z-debug.apk`** from **GitHub Releases**.
3. Open **DroiDS** and grant Shizuku permission when prompted.
4. Launch any game or emulator on the top screen, or tap **Bottom Apps** / **`+`** to launch a companion app on the bottom screen.

### Testing Native Dual-Screen Emulators
* **Azahar (Nintendo 3DS):** `Settings → Layout → Landscape Screen Layout → Single Screen` and `Secondary Display Screen Layout → Bottom Screen`.
* **melonDS / DraStic (Nintendo DS):** `Settings → Video → External Display Screen → Bottom Screen`.

---

## Changelog Highlights

* **v2.0.0 (Major Release — Rebranded to DroiDS):**
  * Rebranded app to **DroiDS** (preserving `applicationId` and signing key for seamless in-place updates).
  * Added **Settings** menu with **Xbox / Nintendo** face button layout toggle and **System / Dark / Light** theme switcher.
  * Added **Configurable Bottom Home Screen** with Clock, live Performance Monitor (FPS, CPU, GPU, Battery), and Quick Shortcuts.
  * Added **Search**, **Sorting (`A–Z`, `Z–A`, `Recent`)**, and **Custom Folders** (long-press any app) to both Top and Bottom app drawers.
  * Added **Physical & Virtual Controller Navigation** for the Top App Launcher.
  * Fixed first-press `InputManager` lazy-init hitch and added focus-loss visual highlight resets on virtual gamepad controls.
* **v1.0.25 – v1.0.27:** Full analog thumbstick support and hardware press-and-hold `A/B/X/Y` via `uinput` Xbox 360 registration + cached `focusDisplay()` IPC optimization.
* **v1.0.1 – v1.0.24:** Core Shizuku dual `VirtualDisplay` engine, live task swapping, bottom app closing, 3-mode virtual controls, and physical gamepad passthrough.

---

## Building from Source

Builds automatically via GitHub Actions (`.github/workflows/build-apk.yml`) using **JDK 17**, **Android SDK 35**, and **Gradle 8.9**, publishing signed versioned APKs to GitHub Releases with a consistent debug keystore for in-place upgrades.
