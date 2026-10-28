package dev.fieldnotes.domain

import org.junit.Assert.*
import org.junit.Test

class ExperimentTest {
    @Test fun assignmentIsStableAcrossCalls() {
        val first = CompactCardsExperiment.assign("installation-123")
        assertEquals(ExperimentVariant.COMPACT, first)
        assertEquals(ExperimentVariant.CONTROL, CompactCardsExperiment.assign("installation-4"))
        repeat(100) { assertEquals(first, CompactCardsExperiment.assign("installation-123")) }
    }

    @Test fun versionedAlgorithmAllocatesBothArms() {
        val variants = (1..1_000).map { CompactCardsExperiment.assign("installation-$it") }
        val compactCount = variants.count { it == ExperimentVariant.COMPACT }
        assertTrue("Sample allocation should be roughly balanced, got $compactCount", compactCount in 400..600)
        assertEquals("compact-cards-v1", ExperimentAssignment().experimentId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankInstallationIsRejected() { CompactCardsExperiment.assign(" ") }
}
