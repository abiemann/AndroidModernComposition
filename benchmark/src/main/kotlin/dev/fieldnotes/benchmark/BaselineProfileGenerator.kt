package dev.fieldnotes.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun journalJourney() = profile.collect(packageName = "dev.fieldnotes.demo") {
        pressHome(); startActivityAndWait()
        device.wait(Until.hasObject(By.text("Field Notes")), 5_000)
    }
}
