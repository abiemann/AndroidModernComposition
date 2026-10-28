package dev.fieldnotes.sync

import dev.fieldnotes.domain.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPolicyTest {
    @Test fun `network errors retry with a bounded budget`() {
        assertEquals(WorkDecision.RETRY, SyncResult.RetryableFailure.workDecision(0))
        assertEquals(WorkDecision.RETRY, SyncResult.RetryableFailure.workDecision(4))
        assertEquals(WorkDecision.FAILURE, SyncResult.RetryableFailure.workDecision(5))
    }
    @Test fun `invalid credentials do not create retry storms`() {
        assertEquals(WorkDecision.FAILURE, SyncResult.AuthenticationRequired.workDecision(0))
        assertEquals(WorkDecision.FAILURE, SyncResult.Rejected.workDecision(0))
        assertEquals(WorkDecision.FAILURE, SyncResult.PermissionRequired.workDecision(0))
    }
    @Test fun `user resolvable conflicts finish background work`() {
        assertEquals(WorkDecision.SUCCESS, SyncResult.Success(2, 1).workDecision(0))
    }
}
