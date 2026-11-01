package com.gavyn.applist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.gavyn.applist.data.AppEntry
import com.gavyn.applist.data.AppListState
import com.gavyn.applist.data.AppListStore
import com.gavyn.applist.data.AppRepository
import com.gavyn.applist.data.AppTarget
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row of the actions menu.
 *
 * Curated settings rows expose only the subset that makes sense for a
 * destination with no package to rename, inspect, or uninstall.
 */
enum class AppAction {
    OpenSystemSetting,
    Rename,
    Pin,
    Unpin,
    MoveUp,
    MoveDown,
    Hide,
    Show,
    AppInfo,
    Uninstall;

    val label: String
        get() = when (this) {
            OpenSystemSetting -> "Open system setting"
            Rename -> "Rename"
            Pin -> "Pin"
            Unpin -> "Unpin"
            MoveUp -> "Move up"
            MoveDown -> "Move down"
            Hide -> "Hide"
            Show -> "Show"
            AppInfo -> "App info"
            Uninstall -> "Uninstall"
        }
}

data class AppActionsUiState(
    val displayName: String = "",
    val actions: List<AppAction> = emptyList(),
    val confirmUninstall: Boolean = false,
    val message: String? = null,
    val close: Boolean = false,
)

class AppActionsViewModel(
    private val entry: AppEntry,
    private val repository: AppRepository,
    private val store: AppListStore,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(AppActionsUiState())
    val uiState: StateFlow<AppActionsUiState> = _uiState.asStateFlow()

    private var prefs = AppListState()

    fun load() {
        viewModelScope.launch {
            prefs = store.current()
            render()
        }
    }

    /**
     * Which actions apply depends on three things: whether the row is a curated
     * setting (no package to rename, inspect, or uninstall), whether the row is
     * hidden, and where it sits in the pinned order.
     *
     * A hidden row offers only Show, per spec 1.6 — the rest belong to a row the
     * user can see in the main list. Curated rows additionally keep their
     * settings action, which is their only way to open.
     */
    private fun render(confirmUninstall: Boolean = false, message: String? = null) {
        val installed = entry.target is AppTarget.Installed
        val hidden = entry.key in prefs.hidden
        val pinnedIndex = prefs.pinned.indexOf(entry.key)
        val canMoveUp = pinnedIndex > 0
        val canMoveDown = pinnedIndex >= 0 && pinnedIndex < prefs.pinned.lastIndex

        val actions = if (hidden) {
            if (installed) listOf(AppAction.Show) else listOf(AppAction.OpenSystemSetting, AppAction.Show)
        } else {
            buildList {
                if (!installed) add(AppAction.OpenSystemSetting)
                if (installed) add(AppAction.Rename)
                add(if (pinnedIndex >= 0) AppAction.Unpin else AppAction.Pin)
                if (canMoveUp) add(AppAction.MoveUp)
                if (canMoveDown) add(AppAction.MoveDown)
                add(AppAction.Hide)
                if (installed) {
                    add(AppAction.AppInfo)
                    add(AppAction.Uninstall)
                }
            }
        }
        _uiState.update {
            it.copy(
                displayName = prefs.displayName(entry),
                actions = actions,
                confirmUninstall = confirmUninstall,
                message = message,
            )
        }
    }

    fun perform(action: AppAction) {
        when (action) {
            AppAction.OpenSystemSetting -> open()
            AppAction.Rename -> Unit // The screen navigates instead.
            AppAction.Pin -> update { it.withPinned(entry.key, true) }
            AppAction.Unpin -> update { it.withPinned(entry.key, false) }
            AppAction.MoveUp -> update { it.movedUp(entry.key) }
            AppAction.MoveDown -> update { it.movedDown(entry.key) }
            AppAction.Hide -> update { it.withHidden(entry.key, true) }
            AppAction.Show -> update { it.withHidden(entry.key, false) }
            AppAction.AppInfo -> viewModelScope.launch {
                if (!repository.openAppInfo(entry)) {
                    render(message = "App info is unavailable for ${prefs.displayName(entry)}")
                }
            }
            AppAction.Uninstall -> render(confirmUninstall = true)
        }
    }

    /**
     * Pre-filled with the current display name, per spec 1.5: the user edits
     * what they see rather than starting from a blank field.
     */
    fun renameSeed(): String = prefs.displayName(entry)
    fun cancelUninstall() = render()

    fun confirmUninstall() {
        viewModelScope.launch {
            if (repository.uninstall(entry)) {
                render(confirmUninstall = false)
            } else {
                render(message = "Could not start uninstall for ${prefs.displayName(entry)}")
            }
        }
    }

    /** Persists [alias] and shows the result under the new name. */
    fun rename(alias: String) {
        update { it.withAlias(entry.key, alias) }
    }

    private fun open() {
        viewModelScope.launch {
            if (!repository.launch(entry)) {
                render(message = "Could not open ${prefs.displayName(entry)}")
            } else {
                // The launched app covers this screen; closing keeps Back from
                // returning to a menu for a row the user has moved on from.
                _uiState.update { it.copy(close = true) }
            }
        }
    }

    private fun update(transform: (AppListState) -> AppListState) {
        viewModelScope.launch {
            prefs = store.update(transform)
            render()
        }
    }
}

class AppActionsScreen(
    sealedActivity: SealedLightActivity,
    private val entry: AppEntry,
    private val repository: AppRepository,
    private val store: AppListStore,
) : LightScreen<Unit, AppActionsViewModel>(sealedActivity) {

    override val viewModelClass = AppActionsViewModel::class.java

    override fun createViewModel() = AppActionsViewModel(entry, repository, store)

    override fun willShow() {
        viewModel.load()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(state.close) {
            if (state.close) goBack()
        }

        LightTheme(colors = colors) {
            val palette = LightThemeTokens.colors
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(state.displayName),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                state.message?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }

                if (state.confirmUninstall) {
                    ConfirmUninstall(
                        displayName = state.displayName,
                        onConfirm = viewModel::confirmUninstall,
                        onCancel = viewModel::cancelUninstall,
                    )
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        state.actions.forEach { action ->
                            ActionRow(
                                label = action.label,
                                onClick = {
                                    if (action == AppAction.Rename) {
                                        navigateTo(
                                            screenFactory = { activity ->
                                                TextEntryScreen(
                                                    activity,
                                                    "Rename",
                                                    viewModel.renameSeed(),
                                                )
                                            },
                                            resultCallback = { alias ->
                                                // Null means the user backed out;
                                                // an empty string clears the alias.
                                                alias?.let(viewModel::rename)
                                            },
                                        )
                                    } else {
                                        viewModel.perform(action)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ConfirmUninstall(
    displayName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "Uninstall $displayName?",
            variant = LightTextVariant.Copy,
        )
    }
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "CANCEL", onClick = onCancel),
            LightBarButton.Text(text = "UNINSTALL", onClick = onConfirm),
        ),
    )
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.CenterStart,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val ACTION_ROW_UNITS = 4f
