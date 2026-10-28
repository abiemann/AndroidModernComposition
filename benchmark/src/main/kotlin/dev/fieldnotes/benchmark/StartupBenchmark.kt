package dev.fieldnotes.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a physical device. Emulators are useful for correctness, not timing claims. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = "dev.fieldnotes.demo", metrics = listOf(StartupTimingMetric()),
        iterations = 10, startupMode = StartupMode.COLD, compilationMode = CompilationMode.None(),
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )
}
