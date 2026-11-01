package com.gavyn.applist.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists [AppListState] in the SDK-provided DataStore.
 *
 * Aliases and the hidden set are stored as explicit maps/sets so an uninstalled
 * app's choices stay around if it ever comes back. Pinned order is a single
 * ordered list because order is the value being stored.
 */
class AppListStore(private val dataStore: DataStore<Preferences>) {

    private val aliasesKey = stringPreferencesKey("aliases")
    private val hiddenKey = stringSetPreferencesKey("hidden")
    private val pinnedKey = stringPreferencesKey("pinned")

    val state: Flow<AppListState> = dataStore.data.map { prefs -> prefs.toState() }

    suspend fun current(): AppListState = dataStore.data.first().toState()

    suspend fun update(transform: (AppListState) -> AppListState): AppListState {
        var updated = AppListState()
        dataStore.edit { prefs ->
            val next = transform(prefs.toState())
            updated = next
            prefs[aliasesKey] = encodeAliases(next.aliases)
            prefs[hiddenKey] = next.hidden
            prefs[pinnedKey] = next.pinned.joinToString(SEPARATOR)
        }
        return updated
    }

    private fun Preferences.toState(): AppListState = AppListState(
        aliases = decodeAliases(this[aliasesKey]),
        hidden = this[hiddenKey].orEmpty(),
        pinned = this[pinnedKey].orEmpty().split(SEPARATOR).filter { it.isNotEmpty() },
    )

    private fun encodeAliases(aliases: Map<String, String>): String =
        aliases.entries
            .sortedBy { it.key }
            .joinToString(SEPARATOR) { (key, alias) -> "$key=$alias" }

    /**
     * Keys are package names or `curated:` ids and never contain `=`, so the
     * first separator is unambiguous even when the alias does.
     */
    private fun decodeAliases(raw: String?): Map<String, String> =
        raw.orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
            .toMap()

    private companion object {
        // A character that cannot appear in a package name or an alias typed on
        // the Light Phone keyboard is unnecessary; newline is enough and keeps
        // aliases with punctuation intact.
        const val SEPARATOR = "\n"
    }
}
