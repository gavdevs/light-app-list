package com.loosewire.applist.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.thelightphone.sdk.apps.LightSystemSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In-memory [DataStore] so the store's encoding can be exercised without an
 * Android context or a real file.
 */
private class FakePreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

class AppListStoreTest {

    private fun store() = AppListStore(FakePreferencesStore())

    @Test
    fun `an untouched store reads as empty`() = runTest {
        assertEquals(AppListState(), store().current())
    }

    @Test
    fun `aliases, hidden rows, and pin order survive a write`() = runTest {
        val store = store()
        store.update {
            it.withAlias("com.example.camera|0", "Snaps")
                .withAlias("curated:wifi", "Network")
                .withHidden("com.example.notes|0", true)
                .withPinned("com.example.camera|0", true)
        }

        val reloaded = store.current()

        assertEquals(
            mapOf("com.example.camera|0" to "Snaps", "curated:wifi" to "Network"),
            reloaded.aliases,
        )
        assertEquals(setOf("com.example.notes|0"), reloaded.hidden)
        assertEquals(listOf("com.example.camera|0"), reloaded.pinned)
    }

    @Test
    fun `pin order is preserved rather than sorted`() = runTest {
        val store = store()
        store.update {
            it.withPinned("zebra|0", true).withPinned("alpha|0", true).withPinned("mike|0", true)
        }

        assertEquals(listOf("mike|0", "alpha|0", "zebra|0"), store.current().pinned)
    }

    @Test
    fun `an alias containing separators round-trips intact`() = runTest {
        val store = store()
        val alias = "Cam =era, best-of"
        store.update { it.withAlias("com.example.camera|0", alias) }

        assertEquals(alias, store.current().aliases["com.example.camera|0"])
    }

    @Test
    fun `clearing an alias removes the entry instead of storing an empty string`() = runTest {
        val store = store()
        store.update { it.withAlias("com.example.camera|0", "Snaps") }
        store.update { it.withAlias("com.example.camera|0", "") }

        assertTrue(store.current().aliases.isEmpty())
    }

    @Test
    fun `update returns the state it persisted`() = runTest {
        val store = store()

        val returned = store.update { it.withHidden("curated:settings", true) }

        assertEquals(store.current(), returned)
        assertEquals(setOf("curated:settings"), returned.hidden)
    }

    @Test
    fun `hiding through the store also unpins`() = runTest {
        val store = store()
        store.update { it.withPinned("com.example.camera|0", true) }

        store.update { it.withHidden("com.example.camera|0", true) }

        val reloaded = store.current()
        assertTrue(reloaded.pinned.isEmpty())
        assertTrue(reloaded.isHidden(AppEntry("com.example.camera|0", "Camera", AppTarget.Setting(LightSystemSetting.Settings))))
    }
}
