package com.gavyn.applist

import com.gavyn.applist.data.AppListStore
import com.gavyn.applist.data.AppRepository
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.apps.LightAppList

/**
 * Process-wide dependencies.
 *
 * The store is created once so every screen shares one DataStore instance;
 * creating a second one for the same file throws.
 */
class AppListServices private constructor(
    val repository: AppRepository,
    val store: AppListStore,
) {
    companion object {
        @Volatile
        private var instance: AppListServices? = null

        fun from(context: SealedLightContext): AppListServices =
            instance ?: synchronized(this) {
                instance ?: AppListServices(
                    repository = AppRepository(context.appAccess.apps),
                    store = AppListStore(context.dataStore),
                ).also { instance = it }
            }
    }
}
