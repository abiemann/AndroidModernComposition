package dev.fieldnotes

import android.app.Activity
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.fieldnotes.legacy.LegacyActivity
import org.hamcrest.CoreMatchers.sameInstance
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
        // Compose idleness does not guarantee that a different Activity owns the focused window.
        val legacyRoot = withDecorView(sameInstance(awaitFocusedDecor(LegacyActivity::class.java)))
        onView(withText("The migration workshop")).inRoot(legacyRoot).check(matches(isDisplayed()))
        onView(isRoot()).inRoot(legacyRoot).perform(pressBack())
        // Back can finish asynchronously; wait for the journal's actual window before querying Compose.
        awaitFocusedDecor(MainActivity::class.java)
        waitForText("Journal")

        compose.onNodeWithText("Journal").performClick()
        compose.onNode(hasText(revised) and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Delete note").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(revised) and !hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitFocusedDecor(activityClass: Class<out Activity>): View {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var focusedDecor: View? = null
        compose.waitUntil(10_000) {
            // Both the lifecycle monitor and View window state must be read on the main thread.
            instrumentation.runOnMainSync {
                focusedDecor = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { activityClass.isInstance(it) }
                    ?.window?.decorView
                    ?.takeIf { it.isAttachedToWindow && it.isShown && it.isLaidOut &&
                        it.hasWindowFocus() && !it.isLayoutRequested }
            }
            focusedDecor != null
        }
        return checkNotNull(focusedDecor)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
