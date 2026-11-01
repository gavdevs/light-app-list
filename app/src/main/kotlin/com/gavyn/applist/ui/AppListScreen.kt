package com.gavyn.applist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.gavyn.applist.AppListServices
import com.gavyn.applist.data.AppEntry
import com.gavyn.applist.data.AppListSorter
import com.gavyn.applist.data.AppListState
import com.gavyn.applist.data.AppListStore
import com.gavyn.applist.data.AppRepository
import com.gavyn.applist.data.AppSearch
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
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
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

/** A rendered row: the entry plus the name the user chose for it. */
data class AppRow(val entry: AppEntry, val name: String)

class AppListViewModel(
    private val repository: AppRepository,
    private val store: AppListStore,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    private var entries: List<AppEntry> = emptyList()
    private var prefs = AppListState()
    private var query = ""

    /**
     * Re-reads preferences and rebuilds the rows. [reenumerate] also re-reads the
     * device, which only differs after the user has been inside another app.
     */
    fun refresh(reenumerate: Boolean = false) {
        viewModelScope.launch {
            prefs = store.current()
            entries = repository.entries(force = reenumerate)
            rebuild()
            _uiState.update { it.copy(loading = false) }
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
            .map { AppRow(it, prefs.displayName(it)) }
            .filter { AppSearch.matches(it.name, query) }
        _uiState.update { it.copy(rows = rows, query = query) }
    }
}

@InitialScreen
class AppListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AppListViewModel>(sealedActivity) {

    private val services by lazy { AppListServices.from(lightContext) }

    override val viewModelClass = AppListViewModel::class.java

    override fun createViewModel() = AppListViewModel(services.repository, services.store)

    override fun willShow() {
        // Returning from the actions or rename screen, or from launching another
        // app, so both preferences and the device list may have moved.
        viewModel.refresh(reenumerate = true)
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

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
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightTextField(
                    label = "Search",
                    value = state.query,
                    placeholder = "All apps",
                    onClick = {
                        navigateTo(
                            screenFactory = { activity ->
                                TextEntryScreen(activity, "Search", state.query)
                            },
                            // Null means the user backed out, which must not be
                            // mistaken for clearing the query.
                            resultCallback = { query -> query?.let(viewModel::search) },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )

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
            }
        }
    }
}

@Composable
private fun AppRows(
    rows: List<AppRow>,
    modifier: Modifier = Modifier,
    hiddenRow: (() -> Unit)? = null,
    onTap: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
) {
    LightScrollView(modifier = modifier.fillMaxSize()) {
        rows.forEach { row ->
            Box(
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
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(
                    text = row.name,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
