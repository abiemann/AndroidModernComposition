package dev.fieldnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real Activity, DI graph, Room, navigation, Java screen, and a durable demo server. */
class JournalEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createRestoreEditFindAndDeleteObservation() {
        assumeTrue("This deterministic journey runs in the demo flavor", BuildConfig.DEMO)
        val title = "E2E shoreline ${System.nanoTime()}"
        val revised = "$title revised"
        val body = "A field observation that survives Activity recreation."

        compose.onAllNodesWithText("New note").onFirst().performClick()
        waitForText("Title")
        compose.onNodeWithText("Title").performTextInput(title)
        compose.onNodeWithText("Your observations").performTextInput(body)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(body).assertExists()
        compose.onNodeWithText("Save note").performScrollTo().performClick()

        waitForText("Search notes")
        waitForText(title)
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.onNodeWithText(body).assertExists()
        compose.onNodeWithText("Edit note").performScrollTo().performClick()
        waitForText("Title")
        compose.onNodeWithText("Title").performTextReplacement(revised)
        compose.onNodeWithText("Save note").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Your observations").fetchSemanticsNodes().isEmpty() }
        waitForText(revised)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search notes").performTextInput(revised)
        compose.onNode(hasText(revised) and !hasSetTextAction()).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Compact note cards").assertExists()
        compose.onNodeWithText("Study guide").performClick()
        compose.onNodeWithText("Open legacy example").performScrollTo().performClick()
        onView(withText("The migration workshop")).check(matches(isDisplayed()))
        pressBack()
        // Predictive back can finish asynchronously after Espresso releases the View activity.
        waitForText("Journal")

        compose.onNodeWithText("Journal").performClick()
        compose.onNode(hasText(revised) and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Delete note").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(revised) and !hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
