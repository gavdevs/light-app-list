package com.loosewiredev.applist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Edits one line of text and returns it.
 *
 * Returns the submitted text, or `null` when the user backed out. An empty
 * submission is meaningful — it clears a search or an alias — so callers must
 * tell it apart from abandoning the edit.
 */
class TextEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String = "",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()
        val textState = rememberTextFieldState(initialValue)

        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = title,
                state = textState,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { value -> goBack(value.toString().trim()) },
                onBack = { goBack(null) },
                submitLabel = "SAVE",
                singleLine = true,
                initialCaps = initialValue.isBlank(),
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            )
        }
    }
}
