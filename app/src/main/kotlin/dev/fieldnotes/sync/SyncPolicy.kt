package dev.fieldnotes.sync

import dev.fieldnotes.domain.SyncResult

enum class WorkDecision { SUCCESS, RETRY, FAILURE }

/** Retry transient faults, leave auth/conflict recovery to explicit user action. */
fun SyncResult.workDecision(attempt: Int): WorkDecision = when (this) {
    is SyncResult.Success -> WorkDecision.SUCCESS
    SyncResult.RetryableFailure -> if (attempt < 5) WorkDecision.RETRY else WorkDecision.FAILURE
    SyncResult.AuthenticationRequired, SyncResult.PermissionRequired, SyncResult.Rejected -> WorkDecision.FAILURE
}
