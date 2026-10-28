package dev.fieldnotes.domain

import kotlinx.coroutines.CancellationException

class InvalidNote(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { TITLE_REQUIRED, TITLE_TOO_LONG, BODY_TOO_LONG, PROJECT_REQUIRED }
}

/** The reusable write policy lives outside Android, so UI and import paths agree. */
class SaveNote(
    private val repository: NoteRepository,
    private val scheduler: SyncScheduler,
    private val telemetry: Telemetry,
) {
    suspend operator fun invoke(draft: NoteDraft): String {
        val normalized = validate(draft)
        val id = repository.save(normalized)
        // The durable write is already complete. A scheduling/metrics failure must not
        // tell the editor to retry a new insert; startup and periodic sync recover it.
        bestEffort { scheduler.requestSync() }
        bestEffort { telemetry.record(AnalyticsEvent.NOTE_SAVED) }
        return id
    }

    private inline fun bestEffort(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    companion object {
        fun validate(draft: NoteDraft): NoteDraft {
            val normalized = draft.copy(title = draft.title.trim(), body = draft.body.trim())
            when {
                normalized.projectId.isBlank() -> throw InvalidNote(InvalidNote.Reason.PROJECT_REQUIRED)
                normalized.title.isEmpty() -> throw InvalidNote(InvalidNote.Reason.TITLE_REQUIRED)
                normalized.title.length > 120 -> throw InvalidNote(InvalidNote.Reason.TITLE_TOO_LONG)
                normalized.body.length > 10_000 -> throw InvalidNote(InvalidNote.Reason.BODY_TOO_LONG)
            }
            return normalized
        }
    }
}
