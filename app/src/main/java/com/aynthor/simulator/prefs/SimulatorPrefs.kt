package com.aynthor.simulator.prefs

import android.content.Context
import android.content.SharedPreferences

class SimulatorPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ayn_thor_prefs", Context.MODE_PRIVATE)

    companion object {
        const val MODE_PRIMARY = 0 // D-Pad or ABXY
        const val MODE_STICK = 1   // Left Stick or Right Stick
        const val MODE_BOTH = 2    // Both shown simultaneously

        const val FACE_LAYOUT_XBOX = 0     // Bottom=A, Right=B, Left=X, Top=Y
        const val FACE_LAYOUT_NINTENDO = 1 // Bottom=B, Right=A, Left=Y, Top=X

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        const val SORT_AZ = 0
        const val SORT_ZA = 1
        const val SORT_RECENT = 2

        const val ORIENTATION_LANDSCAPE = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_AUTO = 2
        const val ORIENTATION_SYSTEM = 3

        private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        private const val KEY_SPLIT_PERCENT = "split_percent"
        private const val KEY_TOP_ASPECT_RATIO = "top_aspect_ratio"
        private const val KEY_BOTTOM_ASPECT_RATIO = "bottom_aspect_ratio"
        private const val KEY_SHOW_RECENT_ROW = "show_recent_row"
        private const val KEY_ORIENTATION_MODE = "orientation_mode"
        private const val KEY_SHOW_ALL_APPS = "show_all_apps"
        private const val KEY_TOUCH_CONTROLS = "touch_controls_enabled"
        private const val KEY_LEFT_CONTROL_MODE = "left_control_mode"
        private const val KEY_RIGHT_CONTROL_MODE = "right_control_mode"
        private const val KEY_TOP_TOUCH_FORWARDING = "top_touch_forwarding"
        private const val KEY_FACE_LAYOUT = "face_button_layout"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TOP_SORT_MODE = "top_sort_mode"
        private const val KEY_BOTTOM_SORT_MODE = "bottom_sort_mode"
        private const val KEY_SHOW_HOME_CLOCK = "show_home_clock"
        private const val KEY_SHOW_HOME_PERF = "show_home_perf"
        private const val KEY_SHOW_HOME_SHORTCUTS = "show_home_shortcuts"
        private const val KEY_FOLDERS = "custom_folders"
        private const val PREFIX_FOLDER_APPS = "folder_apps_"
        private const val PREFIX_LAST_LAUNCH = "last_launch_"
        private const val KEY_DUAL_SCREEN_ADDED = "dual_screen_added"
        private const val KEY_DUAL_SCREEN_REMOVED = "dual_screen_removed"

        val DEFAULT_DUAL_SCREEN_PACKAGES = setOf(
            "com.seedlessds.app",
            "com.dsemu.drastic",
            "io.github.azahar_emu.azahar",
            "org.citra.citra_emu",
            "org.lime3ds.android",
            "me.magnum.melonds",
            "info.cemu.cemu"
        )
    }

    fun getHiddenPackages(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setPackageHidden(packageName: String, hidden: Boolean) {
        val updated = getHiddenPackages().toMutableSet()
        if (hidden) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, updated).apply()
    }

    fun isShowAllApps(): Boolean = prefs.getBoolean(KEY_SHOW_ALL_APPS, false)

    fun setShowAllApps(showAll: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ALL_APPS, showAll).apply()
    }

    fun isTouchControlsEnabled(): Boolean = prefs.getBoolean(KEY_TOUCH_CONTROLS, true)

    fun setTouchControlsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOUCH_CONTROLS, enabled).apply()
    }

    fun getLeftControlMode(): Int = prefs.getInt(KEY_LEFT_CONTROL_MODE, MODE_PRIMARY)

    fun setLeftControlMode(mode: Int) {
        prefs.edit().putInt(KEY_LEFT_CONTROL_MODE, mode).apply()
    }

    fun getRightControlMode(): Int = prefs.getInt(KEY_RIGHT_CONTROL_MODE, MODE_PRIMARY)

    fun setRightControlMode(mode: Int) {
        prefs.edit().putInt(KEY_RIGHT_CONTROL_MODE, mode).apply()
    }

    fun isTopTouchForwardingEnabled(): Boolean = prefs.getBoolean(KEY_TOP_TOUCH_FORWARDING, true)

    fun setTopTouchForwardingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOP_TOUCH_FORWARDING, enabled).apply()
    }

    fun getFaceButtonLayout(): Int = prefs.getInt(KEY_FACE_LAYOUT, FACE_LAYOUT_XBOX)

    fun setFaceButtonLayout(layout: Int) {
        prefs.edit().putInt(KEY_FACE_LAYOUT, layout).apply()
    }

    fun getThemeMode(): Int = prefs.getInt(KEY_THEME_MODE, THEME_DARK)

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    fun getTopSortMode(): Int = prefs.getInt(KEY_TOP_SORT_MODE, SORT_AZ)

    fun setTopSortMode(mode: Int) {
        prefs.edit().putInt(KEY_TOP_SORT_MODE, mode).apply()
    }

    fun getBottomSortMode(): Int = prefs.getInt(KEY_BOTTOM_SORT_MODE, SORT_AZ)

    fun setBottomSortMode(mode: Int) {
        prefs.edit().putInt(KEY_BOTTOM_SORT_MODE, mode).apply()
    }

    fun isShowHomeClock(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_CLOCK, true)

    fun setShowHomeClock(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_CLOCK, show).apply()
    }

    fun isShowHomePerf(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_PERF, true)

    fun setShowHomePerf(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_PERF, show).apply()
    }

    fun isShowHomeShortcuts(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_SHORTCUTS, true)

    fun setShowHomeShortcuts(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_SHORTCUTS, show).apply()
    }

    fun getFolders(): List<String> {
        val set = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        return set.sortedBy { it.lowercase() }
    }

    fun addFolder(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val updated = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        updated.add(clean)
        prefs.edit().putStringSet(KEY_FOLDERS, updated).apply()
    }

    fun removeFolder(name: String) {
        val updated = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        updated.remove(name)
        prefs.edit()
            .putStringSet(KEY_FOLDERS, updated)
            .remove(PREFIX_FOLDER_APPS + name)
            .apply()
    }

    fun getAppsInFolder(folder: String): Set<String> {
        return prefs.getStringSet(PREFIX_FOLDER_APPS + folder, emptySet())?.toSet() ?: emptySet()
    }

    fun setAppInFolder(folder: String, packageName: String, inFolder: Boolean) {
        val updated = getAppsInFolder(folder).toMutableSet()
        if (inFolder) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(PREFIX_FOLDER_APPS + folder, updated).apply()
    }

    fun recordAppLaunch(packageName: String) {
        prefs.edit().putLong(PREFIX_LAST_LAUNCH + packageName, System.currentTimeMillis()).apply()
    }

    fun getLastLaunchTime(packageName: String): Long {
        return prefs.getLong(PREFIX_LAST_LAUNCH + packageName, 0L)
    }

    fun isDualScreenApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val added = prefs.getStringSet(KEY_DUAL_SCREEN_ADDED, emptySet()) ?: emptySet()
        if (added.contains(packageName)) return true
        val removed = prefs.getStringSet(KEY_DUAL_SCREEN_REMOVED, emptySet()) ?: emptySet()
        if (removed.contains(packageName)) return false
        return DEFAULT_DUAL_SCREEN_PACKAGES.contains(packageName)
    }

    fun setDualScreenApp(packageName: String, enabled: Boolean) {
        val added = (prefs.getStringSet(KEY_DUAL_SCREEN_ADDED, emptySet()) ?: emptySet()).toMutableSet()
        val removed = (prefs.getStringSet(KEY_DUAL_SCREEN_REMOVED, emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) {
            added.add(packageName)
            removed.remove(packageName)
        } else {
            added.remove(packageName)
            removed.add(packageName)
        }
        prefs.edit()
            .putStringSet(KEY_DUAL_SCREEN_ADDED, added)
            .putStringSet(KEY_DUAL_SCREEN_REMOVED, removed)
            .apply()
    }

    fun getSplitPercent(): Float = prefs.getFloat(KEY_SPLIT_PERCENT, 0.53f).coerceIn(0.40f, 0.68f)

    fun setSplitPercent(pct: Float) {
        prefs.edit().putFloat(KEY_SPLIT_PERCENT, pct.coerceIn(0.40f, 0.68f)).apply()
    }

    fun getTopAspectRatio(): String = prefs.getString(KEY_TOP_ASPECT_RATIO, "16:9") ?: "16:9"

    fun setTopAspectRatio(ratio: String) {
        prefs.edit().putString(KEY_TOP_ASPECT_RATIO, ratio).apply()
    }

    fun getBottomAspectRatio(): String = prefs.getString(KEY_BOTTOM_ASPECT_RATIO, "1240:1080") ?: "1240:1080"

    fun setBottomAspectRatio(ratio: String) {
        prefs.edit().putString(KEY_BOTTOM_ASPECT_RATIO, ratio).apply()
    }

    fun isShowRecentRow(): Boolean = prefs.getBoolean(KEY_SHOW_RECENT_ROW, true)

    fun setShowRecentRow(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RECENT_ROW, show).apply()
    }

    fun getOrientationMode(): Int = prefs.getInt(KEY_ORIENTATION_MODE, ORIENTATION_LANDSCAPE)

    fun setOrientationMode(mode: Int) {
        prefs.edit().putInt(KEY_ORIENTATION_MODE, mode).apply()
    }
}
