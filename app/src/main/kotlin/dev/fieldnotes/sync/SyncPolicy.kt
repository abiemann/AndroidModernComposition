package dev.fieldnotes.sync

import dev.fieldnotes.domain.SyncResult

internal enum class WorkDecision {
    RETRY,
    SYNCED,
    CONFLICTS,
    MUTATIONS_REJECTED,
    MORE_PENDING,
    AUTHENTICATION_REQUIRED,
    PERMISSION_REQUIRED,
    RESPONSE_REJECTED,
    RETRY_EXHAUSTED,
    UNEXPECTED_FAILURE,
}

private const val MAX_RETRIES = 5

internal fun unexpectedFailureDecision(attempt: Int): WorkDecision =
    retryOr(attempt, WorkDecision.UNEXPECTED_FAILURE)

private fun retryOr(attempt: Int, exhausted: WorkDecision): WorkDecision =
    if (attempt < MAX_RETRIES) WorkDecision.RETRY else exhausted

internal fun SyncResult.workDecision(attempt: Int): WorkDecision = when (this) {
    is SyncResult.Success -> when {
        morePending -> WorkDecision.MORE_PENDING
        deferred > 0 -> retryOr(attempt, WorkDecision.RETRY_EXHAUSTED)
        rejected > 0 -> WorkDecision.MUTATIONS_REJECTED
        conflicts > 0 -> WorkDecision.CONFLICTS
        else -> WorkDecision.SYNCED
    }
    SyncResult.RetryableFailure -> retryOr(attempt, WorkDecision.RETRY_EXHAUSTED)
    SyncResult.AuthenticationRequired -> WorkDecision.AUTHENTICATION_REQUIRED
    SyncResult.PermissionRequired -> WorkDecision.PERMISSION_REQUIRED
    SyncResult.Rejected -> WorkDecision.RESPONSE_REJECTED
}
