package dev.fieldnotes.feature

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable

/** Saves text and selection at the saved-state boundary, not on every keystroke. */
@OptIn(SavedStateHandleSaveableApi::class)
internal fun SavedStateHandle.textFieldState(key: String, legacyKey: String): TextFieldState =
    saveable(key = key, saver = TextFieldState.Saver) {
        TextFieldState(initialText = get<String>(legacyKey).orEmpty())
    }
