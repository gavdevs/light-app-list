package com.loosewiredev.applist.data

import com.thelightphone.sdk.apps.LightSystemSetting
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppEntryTest {

    private fun installed(name: String, pkg: String = name.lowercase(), serial: Long = 0L) =
        AppEntry(
            key = AppKeys.installed(pkg, serial),
            label = name,
            target = AppTarget.Installed(pkg, "$pkg.Main", serial),
        )

    private fun setting(value: LightSystemSetting) = AppEntry(
        key = AppKeys.system(value),
        label = value.label,
        target = AppTarget.Setting(value),
    )

    private val alphabetical: Collator = Collator.getInstance(Locale.US)

    @Test
    fun `display name prefers an alias and falls back to the label`() {
        val entry = installed("Camera")

        assertEquals("Camera", AppListState().displayName(entry))
        assertEquals("Snaps", AppListState().withAlias(entry.key, "Snaps").displayName(entry))
    }

    @Test
    fun `a blank alias clears the previous one`() {
        val entry = installed("Camera")
        val renamed = AppListState().withAlias(entry.key, "Snaps")

        val cleared = renamed.withAlias(entry.key, "   ")

        assertEquals(emptyMap(), cleared.aliases)
        assertEquals("Camera", cleared.displayName(entry))
    }

    @Test
    fun `aliases sort under the alias`() {
        val camera = installed("Camera")
        val zed = installed("Zed")
        val state = AppListState().withAlias(camera.key, "Aaa")

        val ordered = AppListSorter.ordered(listOf(zed, camera), state, alphabetical)

        assertEquals(listOf(camera.key, zed.key), ordered.map { it.key })
    }

    @Test
    fun `pinned rows come first in the user's order`() {
        val a = installed("Aaa")
        val b = installed("Bbb")
        val c = installed("Ccc")
        val state = AppListState()
            .withPinned(b.key, true)
            .withPinned(c.key, true)

        val ordered = AppListSorter.ordered(listOf(a, b, c), state, alphabetical)

        assertEquals(listOf(c.key, b.key, a.key), ordered.map { it.key })
    }

    @Test
    fun `a pinned row that no longer exists is skipped rather than crashing`() {
        val a = installed("Aaa")
        val state = AppListState(pinned = listOf("uninstalled|0", a.key))

        assertEquals(listOf(a.key), AppListSorter.ordered(listOf(a), state, alphabetical).map { it.key })
    }

    @Test
    fun `hiding unpins so the row cannot reappear at the top`() {
        val a = installed("Aaa")
        val state = AppListState().withPinned(a.key, true).withHidden(a.key, true)

        assertTrue(state.isHidden(a))
        assertFalse(state.isPinned(a))
        assertEquals(emptyList(), state.pinned)
    }

    @Test
    fun `showing a hidden row leaves it unpinned`() {
        val a = installed("Aaa")
        val state = AppListState().withHidden(a.key, true).withHidden(a.key, false)

        assertFalse(state.isHidden(a))
        assertFalse(state.isPinned(a))
    }

    @Test
    fun `pinning an already pinned row keeps its position`() {
        val a = installed("Aaa")
        val b = installed("Bbb")
        val state = AppListState().withPinned(a.key, true).withPinned(b.key, true)

        assertEquals(listOf(b.key, a.key), state.pinned)
        assertEquals(state, state.withPinned(a.key, true))
    }

    @Test
    fun `moving a pinned row stops at both ends`() {
        val a = installed("Aaa")
        val b = installed("Bbb")
        val c = installed("Ccc")
        val state = AppListState(pinned = listOf(a.key, b.key, c.key))

        assertEquals(listOf(b.key, a.key, c.key), state.movedUp(b.key).pinned)
        assertEquals(listOf(a.key, b.key, c.key), state.movedUp(a.key).pinned)
        assertEquals(listOf(a.key, c.key, b.key), state.movedDown(b.key).pinned)
        assertEquals(listOf(a.key, b.key, c.key), state.movedDown(c.key).pinned)
    }

    @Test
    fun `repeated moves walk a row through the whole pinned block`() {
        val a = installed("Aaa")
        val b = installed("Bbb")
        val c = installed("Ccc")
        val d = installed("Ddd")
        var state = AppListState(pinned = listOf(a.key, b.key, c.key, d.key))

        // The reorder screen applies one step per tap, so a sequence of taps has
        // to land where a user watching the rows expects.
        state = state.movedDown(a.key).movedDown(a.key).movedDown(a.key)
        assertEquals(listOf(b.key, c.key, d.key, a.key), state.pinned)

        state = state.movedUp(a.key).movedUp(a.key)
        assertEquals(listOf(b.key, a.key, c.key, d.key), state.pinned)
    }

    @Test
    fun `the reorder screen shows the pinned order not the alphabetical one`() {
        val a = installed("Aaa")
        val b = installed("Bbb")
        val c = installed("Ccc")
        val state = AppListState(pinned = listOf(c.key, a.key, b.key))

        val ordered = AppListSorter.ordered(listOf(a, b, c), state, alphabetical)

        assertEquals(listOf(c.key, a.key, b.key), ordered.map { it.key })
    }

    @Test
    fun `moving a row that is not pinned changes nothing`() {
        val a = installed("Aaa")
        val state = AppListState(pinned = listOf(installed("Other").key))

        assertEquals(state, state.movedUp(a.key))
        assertEquals(state, state.movedDown(a.key))
    }

    @Test
    fun `search ignores case and diacritics`() {
        assertTrue(AppSearch.matches("Claudé", "claude"))
        assertTrue(AppSearch.matches("Claude", "CLAUDÉ"))
        assertTrue(AppSearch.matches("Café Nero", "cafe"))
    }

    @Test
    fun `search ignores separators`() {
        assertTrue(AppSearch.matches("App List", "applist"))
        assertTrue(AppSearch.matches("app-list", "app list"))
        assertTrue(AppSearch.matches("Tab_Co", "tabco"))
    }

    @Test
    fun `an empty query matches everything and a miss does not`() {
        assertTrue(AppSearch.matches("Camera", ""))
        assertTrue(AppSearch.matches("Camera", "   "))
        assertFalse(AppSearch.matches("Camera", "phone"))
    }

    @Test
    fun `curated settings have stable keys distinct from packages`() {
        val settings = setting(LightSystemSetting.Settings)

        assertEquals("curated:settings", settings.key)
        assertTrue(settings.target is AppTarget.Setting)
        assertFalse(settings.key.startsWith("com."))
    }

    @Test
    fun `curated settings sort alphabetically among installed apps`() {
        val camera = installed("Camera")
        val wifi = setting(LightSystemSetting.Wifi)

        val ordered = AppListSorter.ordered(listOf(wifi, camera), AppListState(), alphabetical)

        assertEquals(listOf(camera.key, wifi.key), ordered.map { it.key })
    }
}
