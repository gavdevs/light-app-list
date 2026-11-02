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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.loosewiredev.applist.data.AppEntry
import com.loosewiredev.applist.data.AppListStore
import com.loosewiredev.applist.data.AppRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One pinned row in the reorder list. */
data class PinnedRow(val entry: AppEntry, val name: String)

data class ReorderUiState(
    val rows: List<PinnedRow> = emptyList(),
    val loading: Boolean = true,
)

class ReorderViewModel(
    private val repository: AppRepository,
    private val store: AppListStore,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(ReorderUiState())
    val uiState: StateFlow<ReorderUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val prefs = store.current()
            val byKey = repository.entries().associateBy { it.key }
            // Pinned order is the list's order, so render it rather than the
            // entries' natural sort.
            val rows = prefs.pinned.mapNotNull { byKey[it] }
                .map { PinnedRow(it, prefs.displayName(it)) }
            _uiState.update { it.copy(rows = rows, loading = false) }
        }
    }

    fun move(row: PinnedRow, up: Boolean) {
        viewModelScope.launch {
            store.update { if (up) it.movedUp(row.entry.key) else it.movedDown(row.entry.key) }
            load()
        }
    }
}

/**
 * Reorders the pinned block.
 *
 * The actions menu can already nudge a row one place, but ordering a whole block
 * that way means reopening the menu per step. This screen shows the pinned rows
 * together so the order can be set in one pass, with up/down on each row because
 * the Light Phone has no drag gesture on text lists.
 */
class ReorderPinnedScreen(
    sealedActivity: SealedLightActivity,
    private val repository: AppRepository,
    private val store: AppListStore,
) : LightScreen<Unit, ReorderViewModel>(sealedActivity) {

    override val viewModelClass = ReorderViewModel::class.java

    override fun createViewModel() = ReorderViewModel(repository, store)

    override fun willShow() {
        viewModel.load()
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
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Order"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when {
                    state.loading -> Centered("Loading…")
                    state.rows.isEmpty() -> Centered("Nothing is pinned")
                    else -> LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        state.rows.forEachIndexed { index, row ->
                            PinnedRowView(
                                row = row,
                                canMoveUp = index > 0,
                                canMoveDown = index < state.rows.lastIndex,
                                onMoveUp = { viewModel.move(row, up = true) },
                                onMoveDown = { viewModel.move(row, up = false) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedRowView(
    row: PinnedRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_UNITS.gridUnitsAsDp())
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
        Box(
            modifier = Modifier
                .padding(start = 0.5f.gridUnitsAsDp())
                .lightClickable(enabled = canMoveUp, onClick = onMoveUp)
                .padding(horizontal = 0.5f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "↑",
                variant = LightTextVariant.Copy,
                lighten = !canMoveUp,
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 0.25f.gridUnitsAsDp())
                .lightClickable(enabled = canMoveDown, onClick = onMoveDown)
                .padding(horizontal = 0.5f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "↓",
                variant = LightTextVariant.Copy,
                lighten = !canMoveDown,
            )
        }
    }
}

private const val ROW_HEIGHT_UNITS = 4f
