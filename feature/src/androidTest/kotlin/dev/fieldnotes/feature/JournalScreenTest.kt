package dev.fieldnotes.feature

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.fieldnotes.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class JournalScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noteCardOpensTheCorrectRecord() {
        var opened = ""
        val note = Note("meadow-1", "field", "Meadow survey", "Three new flowers", 1000, 0, SyncState.PENDING)
        compose.setContent {
            FieldNotesTheme {
                JournalScreen(JournalUiState(notes = listOf(note), projects = listOf(Project("field", "Fieldwork")),
                    loading = false, totalCount = 1, pendingCount = 1), {}, {}, { opened = it }, {})
            }
        }
        compose.onNodeWithText("Meadow survey").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("meadow-1", opened) }
    }

    @Test fun editorForwardsTypedFieldsAndSaveAction() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            var state by remember { mutableStateOf(EditorUiState(projectId = "field",
                projects = listOf(Project("field", "Fieldwork")), loading = false)) }
            FieldNotesTheme {
                EditorScreen(state, { state = state.copy(title = it) }, { state = state.copy(body = it) }, {},
                    { saved = state.title to state.body }, {})
            }
        }
        compose.onNodeWithText("Title").performTextInput("Shoreline")
        compose.onNodeWithText("Your observations").performTextInput("Water level changed")
        compose.onNodeWithText("Save note").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Shoreline" to "Water level changed", saved) }
    }

    @Test fun emptyStateOffersCreationAndExplainsOfflineBehavior() {
        var created = false
        compose.setContent {
            FieldNotesTheme {
                JournalScreen(JournalUiState(loading = false), {}, {}, {}, { created = true })
            }
        }
        compose.onNodeWithText("A place for your next discovery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New note").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, created) }
    }

    @Test fun localNetworkPermissionRequiresAnExplicitActionAndExplainsDenial() {
        var requests = 0
        var openedSettings = false
        compose.setContent {
            FieldNotesTheme {
                Column {
                    LocalNetworkPermissionCard(granted = false, denied = true,
                        onRequest = { requests++ }, onOpenSettings = { openedSettings = true })
                }
            }
        }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("Local network access was not granted. You can keep writing offline, try again, or allow access in Android app settings.")
            .assertIsDisplayed()
        compose.onNodeWithText("Allow local network access").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Open Android app settings").performClick()
        compose.runOnIdle { assertEquals(true, openedSettings) }
    }

    @Test fun grantedLocalNetworkAccessDoesNotOfferAnotherPermissionPrompt() {
        compose.setContent {
            FieldNotesTheme {
                Column { LocalNetworkPermissionCard(granted = true, denied = false, onRequest = {}, onOpenSettings = {}) }
            }
        }
        compose.onNodeWithText("Local network access is allowed. Your local server must also be running to synchronize.")
            .assertIsDisplayed()
        compose.onNodeWithText("Allow local network access").assertDoesNotExist()
    }
}
