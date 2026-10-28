package dev.fieldnotes

import android.app.Activity
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
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

        // Semantic actions can run behind a lock screen; require a usable window first.
        awaitFocusedDecor(MainActivity::class.java)
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
        var lastSnapshot = "No main-thread state was captured."
        try {
            compose.waitUntil(10_000) {
                // Read lifecycle, View, and system state together on the main thread.
                instrumentation.runOnMainSync {
                    val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                    val activity = resumed.firstOrNull { activityClass.isInstance(it) }
                    val decor = activity?.window?.decorView
                    val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
                    val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
                    lastSnapshot = "expected=${activityClass.name}; " +
                        "resumed=${resumed.map { it.javaClass.name }}; " +
                        "matchingActivity=$activity; decor=$decor; " +
                        "attached=${decor?.isAttachedToWindow}; shown=${decor?.isShown}; " +
                        "laidOut=${decor?.isLaidOut}; windowFocus=${decor?.hasWindowFocus()}; " +
                        "layoutRequested=${decor?.isLayoutRequested}; " +
                        "keyguardLocked=${keyguard?.isKeyguardLocked}; deviceLocked=${keyguard?.isDeviceLocked}; " +
                        "interactive=${power?.isInteractive}"
                    focusedDecor = decor?.takeIf { it.isAttachedToWindow && it.isShown && it.isLaidOut &&
                        it.hasWindowFocus() && !it.isLayoutRequested }
                }
                focusedDecor != null
            }
        } catch (timeout: ComposeTimeoutException) {
            captureWindowDiagnostics()
            throw AssertionError("Activity window did not become ready. Last state: $lastSnapshot", timeout)
        }
        return checkNotNull(focusedDecor)
    }

    private fun captureWindowDiagnostics() {
        val tag = "FieldNotesWindow"
        listOf(
            "mkdir -p /sdcard/Download",
            "screencap -p /sdcard/Download/fieldnotes-ci-window.png",
            "dumpsys window displays",
            "dumpsys input",
            "dumpsys activity activities",
        ).forEach { command ->
            runCatching {
                Log.e(tag, "Window timeout diagnostic: $command")
                val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                // Drain to EOF before closing, including commands that only write a file.
                ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        line.chunked(1_000).forEach { chunk -> Log.e(tag, chunk) }
                    }
                }
            }.onFailure { failure ->
                // Diagnostic failures must not hide the original window timeout.
                Log.w(tag, "Could not capture diagnostic: $command", failure)
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }
}
