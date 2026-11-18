package com.aynthor.simulator.prefs

import android.content.Context
import android.content.SharedPreferences

class SimulatorPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ayn_thor_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        private const val KEY_SHOW_ALL_APPS = "show_all_apps"
        private const val KEY_TOUCH_CONTROLS = "touch_controls_enabled"
        private const val KEY_LEFT_STICK_MODE = "left_stick_mode"
        private const val KEY_RIGHT_STICK_MODE = "right_stick_mode"
        private const val KEY_TOP_TOUCH_FORWARDING = "top_touch_forwarding"
    }

    fun getHiddenPackages(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setPackageHidden(packageName: String, hidden: Boolean) {
        val updated = getHiddenPackages().toMutableSet()
        if (hidden) {
            updated.add(packageName)
        } else {
            updated.remove(packageName)
        }
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

    fun isLeftStickMode(): Boolean = prefs.getBoolean(KEY_LEFT_STICK_MODE, false)

    fun setLeftStickMode(stickMode: Boolean) {
        prefs.edit().putBoolean(KEY_LEFT_STICK_MODE, stickMode).apply()
    }

    fun isRightStickMode(): Boolean = prefs.getBoolean(KEY_RIGHT_STICK_MODE, false)

    fun setRightStickMode(stickMode: Boolean) {
        prefs.edit().putBoolean(KEY_RIGHT_STICK_MODE, stickMode).apply()
    }

    fun isTopTouchForwardingEnabled(): Boolean = prefs.getBoolean(KEY_TOP_TOUCH_FORWARDING, true)

    fun setTopTouchForwardingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOP_TOUCH_FORWARDING, enabled).apply()
    }
}
