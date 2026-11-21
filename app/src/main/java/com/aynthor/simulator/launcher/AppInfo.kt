package com.aynthor.simulator.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED
)

object AppRepository {
    fun loadLaunchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val selfPkg = context.packageName
        return resolveInfos
            .asSequence()
            .filter { it.activityInfo.packageName != selfPkg }
            .map { info ->
                AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    icon = info.loadIcon(pm),
                    category = info.activityInfo.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
                )
            }
            .distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
