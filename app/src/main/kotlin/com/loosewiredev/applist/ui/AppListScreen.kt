package com.loosewiredev.applist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.loosewiredev.applist.AppListServices
import com.loosewiredev.applist.data.AppEntry
import com.loosewiredev.applist.data.AppListSorter
import com.loosewiredev.applist.data.AppListState
import com.loosewiredev.applist.data.AppListStore
import com.loosewiredev.applist.data.AppRepository
import com.loosewiredev.applist.data.AppSearch
import com.loosewiredev.applist.data.AppTarget
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.apps.LightNotifications
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.lightTapAndLongPress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which slice of the list the screen shows. Hidden apps are never a filter. */
enum class AppListScope {
    Visible,
    Hidden,
}

data class AppListUiState(
    val loading: Boolean = true,
    val rows: List<AppRow> = emptyList(),
    val scope: AppListScope = AppListScope.Visible,
    val query: String = "",
    val message: String? = null,
    val notificationsGranted: Boolean = false,
    val pinnedCount: Int = 0,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

/** A rendered row: the entry plus what the list shows for it. */
data class AppRow(
    val entry: AppEntry,
    val name: String,
    val hasNotifications: Boolean = false,
)

class AppListViewModel(
    private val repository: AppRepository,
    private val store: AppListStore,
    private val notifications: LightNotifications,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private var entries: List<AppEntry> = emptyList()
    private var prefs = AppListState()
    private var query = ""
    private var notifyingPackages: Set<String> = emptySet()

    /**
     * Re-reads preferences and rebuilds the rows. [reenumerate] also re-reads the
     * device, which only differs after the user has been inside another app.
     */
    fun refresh(reenumerate: Boolean = false) {
        viewModelScope.launch {
            prefs = store.current()
            entries = repository.entries(force = reenumerate)
            val granted = notifications.isAccessGranted()
            notifyingPackages = if (granted) readNotifyingPackages() else emptySet()
            rebuild()
            _uiState.update {
                it.copy(
                    loading = false,
                    notificationsGranted = granted,
                    pinnedCount = prefs.pinned.size,
                )
            }
        }
    }

    /**
     * Packages with a notification currently posted. Empty when the user has not
     * granted notification access, which shows no indicators rather than an
     * error — the list is still useful without them.
     */
    private suspend fun readNotifyingPackages(): Set<String> =
        notifications.active().mapTo(mutableSetOf()) { it.packageName }

    fun openNotificationAccessSettings() {
        viewModelScope.launch {
            if (!notifications.openAccessSettings()) {
                _uiState.update { it.copy(message = "Could not open notification settings") }
            }
        }
    }

    fun search(text: String) {
        query = text
        rebuild()
    }

    fun showScope(scope: AppListScope) {
        _uiState.update { it.copy(scope = scope) }
        rebuild()
    }

    fun launch(entry: AppEntry) {
        viewModelScope.launch {
            if (!repository.launch(entry)) {
                _uiState.update { it.copy(message = "Could not open ${prefs.displayName(entry)}") }
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun rebuild() {
        val scope = _uiState.value.scope
        val rows = AppListSorter
            .ordered(entries, prefs)
            .filter { (it.key in prefs.hidden) == (scope == AppListScope.Hidden) }
            .map { entry ->
                AppRow(
                    entry = entry,
                    name = prefs.displayName(entry),
                    hasNotifications = when (val target = entry.target) {
                        is AppTarget.Installed -> target.packageName in notifyingPackages
                        is AppTarget.Setting -> false
                    },
                )
            }
            .filter { AppSearch.matches(it.name, query) }
        _uiState.update { it.copy(rows = rows, query = query) }
    }
}

@InitialScreen
class AppListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AppListViewModel>(sealedActivity) {

    private val services by lazy { AppListServices.from(lightContext) }

    override val viewModelClass = AppListViewModel::class.java

    override fun createViewModel() = AppListViewModel(
        services.repository,
        services.store,
        services.notifications,
    )

    override fun willShow() {
        // Returning from the actions or rename screen, or from launching another
        // app, so both preferences and the device list may have moved.
        viewModel.refresh(reenumerate = true)
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        fun openSearch(current: String) {
            navigateTo(
                screenFactory = { activity -> TextEntryScreen(activity, "Search", current) },
                // Null means the user backed out, which must not be mistaken for
                // clearing the query.
                resultCallback = { query -> query?.let(viewModel::search) },
            )
        }

        LightTheme(colors = colors) {
            val palette = LightThemeTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.background),
            ) {
                LightTopBar(
                    leftButton = if (state.scope == AppListScope.Hidden) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { viewModel.showScope(AppListScope.Visible) },
                        )
                    } else {
                        null
                    },
                    center = LightTopBarCenter.Text(
                        if (state.scope == AppListScope.Hidden) "Hidden" else "Apps"
                    ),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = { openSearch(state.query) },
                        contentDescription = "Search",
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                // The query is shown only once it is set: a permanent labelled
                // field costs several rows of list space for a control that is
                // usually untouched.
                if (state.query.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { openSearch(state.query) }
                            .padding(
                                horizontal = 1f.gridUnitsAsDp(),
                                vertical = 0.5f.gridUnitsAsDp(),
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightText(
                            text = state.query,
                            variant = LightTextVariant.Copy,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(
                            text = "✕",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .lightClickable { viewModel.search("") }
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }
                }

                state.message?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.dismissMessage() }
                            .padding(
                                horizontal = 1f.gridUnitsAsDp(),
                                vertical = 0.5f.gridUnitsAsDp(),
                            ),
                    )
                }

                when {
                    state.loading -> Centered("Loading…")
                    state.rows.isEmpty() && state.isSearching -> Centered("No apps found")
                    state.rows.isEmpty() && state.scope == AppListScope.Hidden ->
                        Centered("No hidden apps")
                    state.rows.isEmpty() -> Centered("No apps installed")
                    else -> AppRows(
                        rows = state.rows,
                        modifier = Modifier.weight(1f),
                        // Spec 1.3: the Hidden Apps screen is reached from a row
                        // at the end of the list, so it scrolls with the list
                        // rather than sitting in a fixed bar.
                        hiddenRow = if (state.scope == AppListScope.Visible) {
                            {
                                viewModel.showScope(AppListScope.Hidden)
                            }
                        } else {
                            null
                        },
                        // Ordering only matters once two rows can swap places.
                        reorderRow = if (state.scope == AppListScope.Visible &&
                            state.pinnedCount > 1 &&
                            !state.isSearching
                        ) {
                            {
                                navigateTo(
                                    screenFactory = { activity ->
                                        ReorderPinnedScreen(
                                            activity,
                                            services.repository,
                                            services.store,
                                        )
                                    },
                                    resultCallback = { viewModel.refresh() },
                                )
                            }
                        } else {
                            null
                        },
                        onTap = viewModel::launch,
                        onLongPress = { entry ->
                            navigateTo(
                                screenFactory = { activity ->
                                    AppActionsScreen(
                                        activity,
                                        entry,
                                        services.repository,
                                        services.store,
                                    )
                                },
                                resultCallback = { viewModel.refresh() },
                            )
                        },
                    )
                }

                // Indicators are the only thing notification access is used
                // for, so the prompt appears once and only on the main list.
                if (!state.loading &&
                    state.scope == AppListScope.Visible &&
                    !state.notificationsGranted
                ) {
                    LightText(
                        text = "Turn on notification access to mark apps with *",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.openNotificationAccessSettings() }
                            .padding(
                                horizontal = 1f.gridUnitsAsDp(),
                                vertical = 0.5f.gridUnitsAsDp(),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRows(
    rows: List<AppRow>,
    modifier: Modifier = Modifier,
    hiddenRow: (() -> Unit)? = null,
    reorderRow: (() -> Unit)? = null,
    onTap: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
) {
    LightScrollView(modifier = modifier.fillMaxSize()) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT_UNITS.gridUnitsAsDp())
                    .lightTapAndLongPress(
                        onClickLabel = "Open",
                        onLongClickLabel = "App actions",
                        onTap = { onTap(row.entry) },
                        onLongPress = { onLongPress(row.entry) },
                    )
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = row.name,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.hasNotifications) {
                    LightText(
                        // Same mark LightOS uses for an unread item.
                        text = "*",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
        hiddenRow?.let { openHidden ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT_UNITS.gridUnitsAsDp())
                    .lightClickable(onClick = openHidden)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(text = "Hidden apps", variant = LightTextVariant.Copy, lighten = true)
            }
        }
        reorderRow?.let { openReorder ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT_UNITS.gridUnitsAsDp())
                    .lightClickable(onClick = openReorder)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(text = "Order pinned", variant = LightTextVariant.Copy, lighten = true)
            }
        }
    }
}

@Composable
internal fun Centered(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            lighten = true,
            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
        )
    }
}

private const val ROW_HEIGHT_UNITS = 4f
