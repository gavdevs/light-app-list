package com.gavyn.applist.data

import com.thelightphone.sdk.apps.LightSystemSetting
import java.text.Collator
import java.text.Normalizer

/**
 * What tapping a row does.
 *
 * System settings screens are exposed by intent rather than as launchable
 * activities, so they cannot be represented by package and activity name the
 * way an installed app is.
 */
sealed interface AppTarget {
    data class Installed(
        val packageName: String,
        val activityName: String,
        val profileSerial: Long,
    ) : AppTarget

    data class Setting(val setting: LightSystemSetting) : AppTarget
}

/**
 * A row in the list.
 *
 * [key] is the stable identity the user's hide/pin/alias choices are stored
 * against. It is derived from the package rather than from the enumerated
 * activity, because the activity a package reports can change between releases
 * while the user's choice should not.
 */
data class AppEntry(
    val key: String,
    val label: String,
    val target: AppTarget,
)

object AppKeys {
    private const val SYSTEM_PREFIX = "curated:"

    fun installed(packageName: String, profileSerial: Long): String =
        "$packageName|$profileSerial"

    fun system(setting: LightSystemSetting): String = "$SYSTEM_PREFIX${setting.id}"
}

/**
 * The user's list preferences, in the shape [AppListStore] persists.
 *
 * Kept as plain data so ordering, filtering, and the effects of each action can
 * be tested without Android or a device.
 */
data class AppListState(
    val aliases: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val pinned: List<String> = emptyList(),
) {
    /** What the list, search, and action sheet all show. */
    fun displayName(entry: AppEntry): String =
        aliases[entry.key]?.takeIf { it.isNotBlank() } ?: entry.label

    fun isHidden(entry: AppEntry): Boolean = entry.key in hidden

    fun isPinned(entry: AppEntry): Boolean = entry.key in pinned

    fun withAlias(key: String, alias: String): AppListState =
        copy(aliases = if (alias.isBlank()) aliases - key else aliases + (key to alias.trim()))

    /**
     * Hiding also unpins, so a hidden app cannot reappear via the pinned block
     * the main list renders first.
     */
    fun withHidden(key: String, hidden: Boolean): AppListState =
        if (hidden) {
            copy(hidden = this.hidden + key, pinned = pinned - key)
        } else {
            copy(hidden = this.hidden - key)
        }

    fun withPinned(key: String, pinned: Boolean): AppListState =
        when {
            pinned && key in this.pinned -> this
            pinned -> copy(pinned = listOf(key) + this.pinned)
            else -> copy(pinned = this.pinned - key)
        }

    /** Moves a pinned entry one slot towards the start; a no-op at the top. */
    fun movedUp(key: String): AppListState {
        val index = pinned.indexOf(key)
        if (index <= 0) return this
        return copy(pinned = pinned.toMutableList().apply { add(index - 1, removeAt(index)) })
    }

    /** Moves a pinned entry one slot towards the end; a no-op at the bottom. */
    fun movedDown(key: String): AppListState {
        val index = pinned.indexOf(key)
        if (index < 0 || index >= pinned.lastIndex) return this
        return copy(pinned = pinned.toMutableList().apply { add(index + 1, removeAt(index)) })
    }
}

object AppListSorter {
    /**
     * Pinned entries first, in the user's order, then everything else by
     * display name. Aliases sort under the alias, matching what is on screen.
     */
    fun ordered(
        entries: List<AppEntry>,
        state: AppListState,
        collator: Collator = Collator.getInstance(),
    ): List<AppEntry> {
        val byKey = entries.associateBy { it.key }
        val pinned = state.pinned.mapNotNull { byKey[it] }
        val pinnedKeys = pinned.mapTo(mutableSetOf()) { it.key }
        val rest = entries
            .filterNot { it.key in pinnedKeys }
            .sortedWith { left, right ->
                collator.compare(state.displayName(left), state.displayName(right))
            }
        return pinned + rest
    }
}

object AppSearch {
    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val SEPARATORS = Regex("[-_+,. ]")

    /**
     * Case- and diacritic-insensitive substring match over the display name.
     * `"claude"` matches `"Claudé"`, and separators are ignored so `"applist"`
     * matches `"App List"`.
     */
    fun matches(displayName: String, query: String): Boolean {
        if (query.isBlank()) return true
        if (displayName.contains(query, ignoreCase = true)) return true
        return normalize(displayName).contains(normalize(query), ignoreCase = true)
    }

    private fun normalize(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(SEPARATORS, "")
}
