package dev.fieldnotes.domain

import java.security.MessageDigest

enum class ExperimentVariant { CONTROL, COMPACT }

data class ExperimentAssignment(
    val variant: ExperimentVariant = ExperimentVariant.CONTROL,
    val isOverridden: Boolean = false,
) {
    val experimentId: String get() = CompactCardsExperiment.ID
}

/** Stable local allocation. Changing the experiment ID intentionally starts a new allocation. */
object CompactCardsExperiment {
    const val ID = "compact-cards-v1"

    fun assign(installationId: String): ExperimentVariant {
        require(installationId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ID:$installationId".toByteArray(Charsets.UTF_8))
        // The first hash byte divides the installation population into two equal buckets.
        return if ((digest[0].toInt() and 0xff) < 128) ExperimentVariant.CONTROL else ExperimentVariant.COMPACT
    }
}
