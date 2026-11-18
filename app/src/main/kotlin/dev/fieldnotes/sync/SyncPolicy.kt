package dev.fieldnotes.sync

import dev.fieldnotes.domain.SyncResult

internal enum class WorkDecision {
    RETRY,
    SYNCED,
    CONFLICTS,
    MUTATIONS_REJECTED,
    AUTHENTICATION_REQUIRED,
    PERMISSION_REQUIRED,
    RESPONSE_REJECTED,
    RETRY_EXHAUSTED,
    UNEXPECTED_FAILURE,
}

/** Only transient sync outcomes request a WorkManager retry. Other outcomes finish this wakeup. */
internal fun SyncResult.workDecision(attempt: Int): WorkDecision = when (this) {
    is SyncResult.Success -> when {
        rejected > 0 -> WorkDecision.MUTATIONS_REJECTED
        conflicts > 0 -> WorkDecision.CONFLICTS
        else -> WorkDecision.SYNCED
    }
    SyncResult.RetryableFailure -> if (attempt < 5) WorkDecision.RETRY else WorkDecision.RETRY_EXHAUSTED
    SyncResult.AuthenticationRequired -> WorkDecision.AUTHENTICATION_REQUIRED
    SyncResult.PermissionRequired -> WorkDecision.PERMISSION_REQUIRED
    SyncResult.Rejected -> WorkDecision.RESPONSE_REJECTED
}
