package com.loosewiredev.applist.data

import com.thelightphone.sdk.apps.LightAppInfo
import com.thelightphone.sdk.apps.LightAppList
import com.thelightphone.sdk.apps.LightSystemSetting

/**
 * Assembles the list the UI renders — every launchable app plus the curated
 * system settings — and performs the actions that need the SDK.
 *
 * The list is cached for the life of the screen: enumeration is a binder call
 * per profile and the device only changes when the user leaves the app.
 */
class AppRepository(private val apps: LightAppList) {

    @Volatile
    private var cached: List<AppEntry>? = null

    /**
     * [force] re-enumerates. Screens call that on resume, so an app installed or
     * removed in another app appears without restarting this one.
     */
    suspend fun entries(force: Boolean = false): List<AppEntry> {
        cached?.let { if (!force) return it }
        return build()
    }

    private suspend fun build(): List<AppEntry> {
        val installed = apps.installed().map { it.toEntry() }
        val curated = LightSystemSetting.entries.map { setting ->
            AppEntry(
                key = AppKeys.system(setting),
                label = setting.label,
                target = AppTarget.Setting(setting),
            )
        }
        return (installed + curated).also { cached = it }
    }

    /**
     * Starts whatever [entry] points at. Returns false when the target no longer
     * resolves, which a caller reports rather than retries.
     */
    suspend fun launch(entry: AppEntry): Boolean = when (val target = entry.target) {
        is AppTarget.Setting -> apps.openSystemSetting(target.setting)
        is AppTarget.Installed -> apps.launch(
            LightAppInfo(
                label = entry.label,
                packageName = target.packageName,
                activityName = target.activityName,
                profileSerial = target.profileSerial,
            ),
        )
    }

    suspend fun openAppInfo(entry: AppEntry): Boolean {
        val target = entry.target as? AppTarget.Installed ?: return false
        return apps.openAppInfo(target.packageName, target.profileSerial)
    }

    suspend fun uninstall(entry: AppEntry): Boolean {
        val target = entry.target as? AppTarget.Installed ?: return false
        return apps.uninstall(target.packageName)
    }

    private fun LightAppInfo.toEntry(): AppEntry = AppEntry(
        key = AppKeys.installed(packageName, profileSerial),
        label = label,
        target = AppTarget.Installed(
            packageName = packageName,
            activityName = activityName,
            profileSerial = profileSerial,
        ),
    )
}
