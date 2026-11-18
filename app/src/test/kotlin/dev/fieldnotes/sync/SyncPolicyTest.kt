package dev.fieldnotes.sync

import dev.fieldnotes.domain.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPolicyTest {
    @Test fun `network errors retry with a bounded budget`() {
        assertEquals(WorkDecision.RETRY, SyncResult.RetryableFailure.workDecision(0))
        assertEquals(WorkDecision.RETRY, SyncResult.RetryableFailure.workDecision(4))
        assertEquals(WorkDecision.RETRY_EXHAUSTED, SyncResult.RetryableFailure.workDecision(5))
    }
    @Test fun `corrective actions complete the current wakeup with distinct outcomes`() {
        assertEquals(WorkDecision.AUTHENTICATION_REQUIRED, SyncResult.AuthenticationRequired.workDecision(0))
        assertEquals(WorkDecision.RESPONSE_REJECTED, SyncResult.Rejected.workDecision(0))
        assertEquals(WorkDecision.PERMISSION_REQUIRED, SyncResult.PermissionRequired.workDecision(0))
    }
    @Test fun `user resolvable conflicts finish background work`() {
        assertEquals(WorkDecision.CONFLICTS, SyncResult.Success(2, 1).workDecision(0))
    }
    @Test fun `a partial sync with a rejected note is distinguishable from a clean sync`() {
        assertEquals(WorkDecision.MUTATIONS_REJECTED, SyncResult.Success(2, 0, rejected = 1).workDecision(0))
        assertEquals(WorkDecision.SYNCED, SyncResult.Success(2, 0).workDecision(0))
    }
    @Test fun `eligible backlog continues before ambiguity is retried with backoff`() {
        val partial = SyncResult.Success(8, 0, morePending = true, deferred = 1)
        assertEquals(WorkDecision.MORE_PENDING, partial.workDecision(0))
        val onlyDeferred = partial.copy(morePending = false)
        assertEquals(WorkDecision.RETRY, onlyDeferred.workDecision(4))
        assertEquals(WorkDecision.RETRY_EXHAUSTED, onlyDeferred.workDecision(5))
    }
}
