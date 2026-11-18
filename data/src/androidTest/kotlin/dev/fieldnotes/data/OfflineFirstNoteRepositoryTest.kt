package dev.fieldnotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fieldnotes.domain.Clock
import dev.fieldnotes.domain.IdGenerator
import dev.fieldnotes.domain.NoteDraft
import dev.fieldnotes.domain.StaleNoteEdit
import dev.fieldnotes.domain.SyncResult
import dev.fieldnotes.domain.SyncState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineFirstNoteRepositoryTest {
    private lateinit var db: FieldNotesDatabase
    private lateinit var repository: OfflineFirstNoteRepository
    private lateinit var remote: TestRemote
    private var identifier = 0
    private var time = 100L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FieldNotesDatabase::class.java).build()
        remote = TestRemote(DemoRemoteNotes(db))
        repository = OfflineFirstNoteRepository(db, remote, Clock { time++ }, IdGenerator { "id-${++identifier}" })
    }

    @After fun tearDown() { db.close() }

    @Test fun offlineWriteIsReadableAndRetryDoesNotLoseIt() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "  Oak  ", body = "Leaf"))
        remote.failure = IOException()
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        assertEquals("Oak", repository.observeNote(id).first()!!.title)
        assertEquals(SyncState.PENDING, repository.observeNote(id).first()!!.syncState)
        remote.failure = null
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertEquals(SyncState.SYNCED, repository.observeNote(id).first()!!.syncState)
        assertEquals(0, db.notes().pendingCount(id))
    }

    @Test fun editDuringUploadSurvivesAcknowledgment() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "First", body = ""))
        remote.beforePush = {
            remote.beforePush = null
            edit(id, "Second", "New observation")
        }
        assertEquals(SyncResult.Success(1, 0, morePending = true), repository.sync())
        assertEquals("Second", repository.observeNote(id).first()!!.title)
        assertEquals("First", remote.delegate.snapshot().notes.single().title)
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertEquals("Second", remote.delegate.snapshot().notes.single().title)
        assertEquals(2L, remote.delegate.snapshot().notes.single().version)
    }

    @Test fun lostResponseReplaysSameOperationAfterNewEdit() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "First", body = ""))
        remote.loseNextResponse = true
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        edit(id, "Second")
        assertEquals(SyncResult.Success(2, 0), repository.sync())
        assertEquals(2L, remote.delegate.snapshot().notes.single().version)
        assertEquals("Second", repository.observeNote(id).first()!!.title)
    }

    @Test fun conflictKeepsLocalCopyUntilUserAcceptsServer() = runBlocking {
        val id = createSyncedNote()
        remote.delegate.push(id, Mutation("other-device", 1, "field", "Remote", "Remote body", 200, false))
        edit(id, "Local", "Local body")
        assertEquals(SyncResult.Success(0, 1), repository.sync())
        assertEquals("Local", repository.observeNote(id).first()!!.title)
        assertEquals(SyncState.CONFLICT, repository.observeNote(id).first()!!.syncState)
        repository.resolveConflict(id, keepLocal = false)
        assertEquals("Remote", repository.observeNote(id).first()!!.title)
        assertEquals(SyncState.SYNCED, repository.observeNote(id).first()!!.syncState)
    }

    @Test fun keepLocalRebasesAndDeletionConflictRemainsVisible() = runBlocking {
        val id = createSyncedNote()
        remote.delegate.push(id, Mutation("other-device", 1, "field", "Remote", "", 200, false))
        repository.delete(id)
        repository.sync()
        assertTrue(repository.observeNotes().first().single().deleted)
        assertEquals(SyncState.CONFLICT, repository.observeNotes().first().single().syncState)
        repository.resolveConflict(id, keepLocal = true)
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertTrue(remote.delegate.snapshot().notes.single().deleted)
        assertTrue(repository.observeNotes().first().isEmpty())
    }

    @Test fun cancellationPropagatesAndRetainsOutbox() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "Oak", body = ""))
        remote.failure = CancellationException("Worker stopped")
        try { repository.sync(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, db.notes().pendingCount(id))
    }

    @Test fun pendingDeletionRemainsObservableUntilAcknowledged() = runBlocking {
        val id = createSyncedNote()
        repository.delete(id)
        remote.failure = IOException()
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        val tombstone = repository.observeNotes().first().single()
        assertTrue(tombstone.deleted)
        assertEquals(SyncState.PENDING, tombstone.syncState)
        assertEquals(1, db.notes().pendingCount(id))
        remote.failure = null
        repository.sync()
        assertTrue(repository.observeNotes().first().isEmpty())
    }

    @Test fun repeatedSyncReportsPreviouslyUnresolvedConflicts() = runBlocking {
        val id = createSyncedNote()
        remote.delegate.push(id, Mutation("remote-edit", 1, "field", "Remote", "", 200, false))
        edit(id, "Local")
        assertEquals(SyncResult.Success(0, 1), repository.sync())
        assertEquals(SyncResult.Success(0, 1), repository.sync())
        repository.resolveConflict(id, keepLocal = false)
        assertEquals(SyncResult.Success(0, 0), repository.sync())
    }

    @Test fun missingLocalNetworkPermissionRetainsOutboxForUserRecovery() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "Oak", body = ""))
        remote.failure = NetworkPermissionFailure()
        assertEquals(SyncResult.PermissionRequired, repository.sync())
        assertEquals(1, db.notes().pendingCount(id))
        assertEquals(SyncState.PENDING, repository.observeNote(id).first()!!.syncState)
    }

    @Test fun unknownProjectIsRejectedAtomically() = runBlocking {
        try {
            repository.save(NoteDraft(projectId = "missing", title = "Oak", body = ""))
            fail("Unknown project must fail")
        } catch (_: IllegalArgumentException) { }
        assertTrue(repository.observeNotes().first().isEmpty())
        assertNull(db.notes().nextOperation())
    }

    @Test fun staleDraftCannotOverwriteAPulledRemoteEdit() = runBlocking {
        val id = createSyncedNote()
        val draftRevision = repository.observeNote(id).first()!!.revision
        remote.delegate.push(id, Mutation("remote-edit", 1, "field", "Remote revision", "Changed elsewhere", 200, false))
        repository.sync()
        try {
            repository.save(NoteDraft(id, "field", "Old editor", "Old draft", draftRevision))
            fail("An editor must not silently adopt a version it never read")
        } catch (_: StaleNoteEdit) { }
        assertEquals("Remote revision", repository.observeNote(id).first()!!.title)
        assertEquals(0, db.notes().pendingCount(id))
    }

    @Test fun staleDraftCannotResurrectAPulledDeletion() = runBlocking {
        val id = createSyncedNote()
        val draftRevision = repository.observeNote(id).first()!!.revision
        remote.delegate.push(id, Mutation("remote-delete", 1, "field", "Original", "", 200, true))
        repository.sync()
        try {
            repository.save(NoteDraft(id, "field", "Old editor", "", draftRevision))
            fail("Deleted notes require an explicit save-as-new choice")
        } catch (_: StaleNoteEdit) { }
        assertTrue(repository.observeNote(id).first()!!.deleted)
        assertEquals(0, db.notes().pendingCount(id))
    }

    @Test fun missingExpectedRevisionAndMissingNoteRejectWithoutWriting() = runBlocking {
        val id = createSyncedNote()
        listOf(NoteDraft(id, "field", "Unversioned", ""), NoteDraft("missing", "field", "Missing", "", 1))
            .forEach { draft ->
                try { repository.save(draft); fail("An existing edit needs the revision it read") } catch (_: StaleNoteEdit) { }
            }
        assertEquals("Original", repository.observeNote(id).first()!!.title)
        assertNull(db.notes().nextOperation())
    }

    @Test fun twoDraftsFromTheSameRevisionCannotOverwriteEachOther() = runBlocking {
        val id = createSyncedNote()
        val revision = repository.observeNote(id).first()!!.revision
        repository.save(NoteDraft(id, "field", "First editor", "", revision))
        try {
            repository.save(NoteDraft(id, "field", "Second editor", "", revision))
            fail("The second draft is stale")
        } catch (_: StaleNoteEdit) { }
        assertEquals("First editor", repository.observeNote(id).first()!!.title)
        assertEquals(1, db.notes().pendingCount(id))
    }

    @Test fun canonicalAcknowledgmentInvalidatesAnOlderDraftButOrdinaryAckDoesNot() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "Original", body = ""))
        val before = repository.observeNote(id).first()!!.revision
        repository.sync()
        assertEquals(before, repository.observeNote(id).first()!!.revision)
        edit(id, "Canonicalize me")
        val editorRevision = repository.observeNote(id).first()!!.revision
        remote.canonicalTitle = "Server canonical title"
        repository.sync()
        assertTrue(repository.observeNote(id).first()!!.revision > editorRevision)
        try {
            repository.save(NoteDraft(id, "field", "Old editor", "", editorRevision))
            fail("Canonical content changed after this draft was loaded")
        } catch (_: StaleNoteEdit) { }
    }

    @Test fun rejectedHeadRetainsItsChainWhileUnrelatedUploadsAndPullsContinue() = runBlocking {
        val rejectedId = repository.save(NoteDraft(projectId = "field", title = "Rejected first", body = ""))
        edit(rejectedId, "Latest local correction")
        val otherId = repository.save(NoteDraft(projectId = "study", title = "Unrelated", body = ""))
        remote.delegate.push("remote-only", Mutation("remote-create", 0, "field", "Pulled note", "", 200, false))
        remote.rejectedIds += rejectedId

        assertEquals(SyncResult.Success(1, 0, 1), repository.sync())
        assertEquals(SyncState.REJECTED, repository.observeNote(rejectedId).first()!!.syncState)
        assertEquals("Latest local correction", repository.observeNote(rejectedId).first()!!.title)
        assertEquals(2, db.notes().pendingCount(rejectedId))
        assertEquals(SyncState.SYNCED, repository.observeNote(otherId).first()!!.syncState)
        assertEquals("Pulled note", repository.observeNote("remote-only").first()!!.title)
        assertNull(db.notes().nextOperation())
        assertEquals(SyncResult.Success(0, 0, 1), repository.sync())
    }

    @Test fun correctingRejectedNoteReplacesOnlyItsDefinitivelyRejectedChain() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "First", body = ""))
        val firstOperation = db.notes().nextOperation()!!
        edit(id, "Second")
        remote.rejectedIds += id
        repository.sync()
        edit(id, "Corrected explicitly", "Retained latest body")
        val replacement = db.notes().nextOperation()!!
        assertNotEquals(firstOperation.operationId, replacement.operationId)
        assertEquals(1, db.notes().pendingCount(id))
        assertEquals("Corrected explicitly", replacement.title)
        remote.rejectedIds.clear()
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertEquals("Retained latest body", remote.delegate.snapshot().notes.single().body)
    }

    @Test fun explicitRetryPreservesRejectedDeletionTombstone() = runBlocking {
        val id = createSyncedNote()
        repository.delete(id)
        val firstOperation = db.notes().nextOperation()!!
        remote.rejectedIds += id
        repository.sync()
        val rejected = repository.observeNotes().first().single()
        assertTrue(rejected.deleted)
        assertEquals(SyncState.REJECTED, rejected.syncState)
        repository.retryRejected(id)
        val replacement = db.notes().nextOperation()!!
        assertNotEquals(firstOperation.operationId, replacement.operationId)
        assertTrue(replacement.deleted)
        assertEquals(firstOperation.baseVersion, replacement.baseVersion)
        assertEquals(1, db.notes().pendingCount(id))
        remote.rejectedIds.clear()
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertTrue(remote.delegate.snapshot().notes.single().deleted)
        assertTrue(repository.observeNotes().first().isEmpty())
    }

    @Test fun editDuringDefinitiveRejectionIsRetainedForExplicitRetry() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "First", body = ""))
        remote.beforePush = {
            remote.beforePush = null
            edit(id, "Edited during request", "Latest draft")
        }
        remote.rejectedIds += id
        repository.sync()
        assertEquals("Edited during request", repository.observeNote(id).first()!!.title)
        assertEquals(2, db.notes().pendingCount(id))
        repository.retryRejected(id)
        assertEquals("Latest draft", db.notes().nextOperation()!!.body)
        assertEquals(1, db.notes().pendingCount(id))
    }

    @Test fun ambiguousFailuresNeverQuarantineOrRewritePendingOperation() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "Original", body = ""))
        val original = db.notes().nextOperation()!!
        remote.failure = RejectedRequest()
        assertEquals(SyncResult.Rejected, repository.sync())
        repository.retryRejected(id)
        assertEquals(original, db.notes().nextOperation())
        assertEquals(SyncState.PENDING, repository.observeNote(id).first()!!.syncState)
        remote.failure = IOException()
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        assertEquals(original, db.notes().nextOperation())
    }

    @Test fun ambiguousHeadFreezesItsSuccessorsWhileOtherNotesAndPullsProgress() = runBlocking {
        val deferredId = repository.save(NoteDraft(projectId = "field", title = "Uncertain first", body = ""))
        val original = db.notes().nextOperation()!!
        edit(deferredId, "Latest retained text")
        val healthyId = repository.save(NoteDraft(projectId = "study", title = "Healthy", body = ""))
        remote.delegate.push("pulled", Mutation("pull-create", 0, "field", "Pulled", "", 200, false))
        remote.deferredIds += deferredId

        assertEquals(SyncResult.Success(1, 0, deferred = 1), repository.sync())
        assertEquals(original, db.notes().nextOperation())
        assertEquals(2, db.notes().pendingCount(deferredId))
        assertEquals(SyncState.PENDING, repository.observeNote(deferredId).first()!!.syncState)
        assertEquals("Latest retained text", repository.observeNote(deferredId).first()!!.title)
        assertEquals(SyncState.SYNCED, repository.observeNote(healthyId).first()!!.syncState)
        assertEquals("Pulled", repository.observeNote("pulled").first()!!.title)
        assertEquals(listOf(original.operationId), remote.attempted.filter { it.first == deferredId }.map { it.second.operationId })
        remote.deferredIds.clear()
        assertEquals(SyncResult.Success(2, 0), repository.sync())
        assertEquals("Latest retained text", remote.delegate.snapshot().notes.first { it.id == deferredId }.title)
    }

    @Test fun boundedPassReportsRemainingWorkAndResumesFromDurableCursor() = runBlocking {
        repository = limitedRepository(maxOperations = 2)
        repeat(3) { repository.save(NoteDraft(projectId = "field", title = "Note $it", body = "")) }
        assertEquals(SyncResult.Success(2, 0, morePending = true), repository.sync())
        repository = limitedRepository(maxOperations = 2)
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertEquals(3, remote.delegate.snapshot().notes.size)
        assertNull(db.notes().nextOperation())
    }

    @Test fun allDeferredBatchBacksOffAndCursorReachesLaterHealthyNotesWithoutOvertakingHeads() = runBlocking {
        val deferredIds = (1..8).map { repository.save(NoteDraft(projectId = "field", title = "Deferred $it", body = "")) }
        val firstHead = db.notes().nextOperation()!!
        val healthyId = repository.save(NoteDraft(projectId = "field", title = "Ninth healthy note", body = ""))
        edit(deferredIds.first(), "A causal successor must wait")
        remote.deferredIds += deferredIds

        assertEquals(SyncResult.Success(0, 0, deferred = 8), repository.sync())
        assertEquals(8, remote.attempted.size)
        // A new repository simulates restart: fairness cannot depend on an in-memory cursor.
        repository = limitedRepository(maxOperations = 8)
        assertEquals(SyncResult.Success(1, 0, morePending = true, deferred = 7), repository.sync())
        assertEquals(SyncState.SYNCED, repository.observeNote(healthyId).first()!!.syncState)
        assertEquals(firstHead, db.notes().nextOperation())
        assertTrue(remote.attempted.filter { it.first == deferredIds.first() }.all { it.second.operationId == firstHead.operationId })
        assertEquals(2, db.notes().pendingCount(deferredIds.first()))
    }

    @Test fun passDeadlineRetainsExactOperationAndDoesNotEscapeAsCallerCancellation() = runBlocking {
        repository = limitedRepository(timeoutMillis = 1_000)
        val id = repository.save(NoteDraft(projectId = "field", title = "Slow request", body = ""))
        val original = db.notes().nextOperation()!!
        remote.beforePush = { delay(60_000) }
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        assertEquals(original, db.notes().nextOperation())
        assertEquals(SyncState.PENDING, repository.observeNote(id).first()!!.syncState)
        remote.beforePush = null
        assertEquals(SyncResult.Success(1, 0), repository.sync())
    }

    @Test fun unknownStateIsVisibleAndProtectedWithoutBlockingHealthyRows() = runBlocking {
        val unknownId = repository.save(NoteDraft(projectId = "field", title = "Retained unknown", body = ""))
        val healthyId = repository.save(NoteDraft(projectId = "field", title = "Healthy", body = ""))
        val original = db.notes().note(unknownId)!!
        for (state in listOf("FUTURE_STATE", "UNRECOGNIZED")) {
            db.notes().putNote(original.copy(syncState = state))
            assertEquals(SyncState.UNRECOGNIZED, repository.observeNotes().first().first { it.id == unknownId }.syncState)
            val writes: List<suspend () -> Unit> = listOf(
                { repository.save(NoteDraft(unknownId, "field", "Overwrite", "", original.localRevision)); Unit },
                { repository.delete(unknownId) },
                { repository.retryRejected(unknownId) },
                { repository.resolveConflict(unknownId, true) },
            )
            writes.forEach { write ->
                try { write(); fail("An unrecognized state must be preserved for recovery") } catch (_: IllegalStateException) { }
            }
            assertEquals(original.copy(syncState = state), db.notes().note(unknownId))
        }
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertEquals(SyncState.SYNCED, repository.observeNote(healthyId).first()!!.syncState)
        assertEquals(SyncState.UNRECOGNIZED, repository.observeNote(unknownId).first()!!.syncState)
        assertEquals(1, db.notes().pendingCount(unknownId))
    }

    @Test fun malformedPersistedConflictCannotDiscardLocalWork() = runBlocking {
        val id = createSyncedNote()
        remote.delegate.push(id, Mutation("remote-conflict", 1, "field", "Remote", "", 200, false))
        edit(id, "Local conflict copy")
        repository.sync()
        val local = db.notes().note(id)!!
        val valid = db.notes().conflict(id)!!.remoteJson
        val invalid = listOf("null", "{", "{}", valid.replace("\"id\":\"$id\"", "\"id\":\"other\""),
            valid.replace("\"projectId\":\"field\"", "\"projectId\":\"missing\""))
        invalid.forEach { json ->
            db.notes().putConflict(ConflictEntity(id, json))
            for (keepLocal in listOf(true, false)) {
                try { repository.resolveConflict(id, keepLocal); fail("Corrupt conflict must preserve recovery data") }
                catch (_: IllegalStateException) { }
                assertEquals(local, db.notes().note(id))
                assertEquals(json, db.notes().conflict(id)!!.remoteJson)
            }
        }
    }

    @Test fun simulatorReceiptsSurviveLostReplyAndAreRemovedOnlyAfterDurableAck() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "Replay me", body = ""))
        val operation = db.notes().nextOperation()!!
        remote.loseNextResponse = true
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        val receipt = db.notes().demoOperation(operation.operationId)!!
        assertTrue(receipt.requestJson.startsWith("sha256:"))
        assertEquals(71, receipt.requestJson.length)
        repository = limitedRepository()
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertNull(db.notes().demoOperation(operation.operationId))
        assertEquals(1L, remote.delegate.snapshot().notes.single { it.id == id }.version)
    }

    @Test fun historicalReceiptReplaysAndOrphanReceiptsArePrunedWithoutTouchingPendingOnes() = runBlocking {
        val id = repository.save(NoteDraft(projectId = "field", title = "Legacy replay", body = ""))
        val operation = db.notes().nextOperation()!!
        assertTrue(remote.delegate.push(id, operation.mutation()) is PushResult.Accepted)
        db.notes().compactDemoOperation(operation.operationId, NoteJson.requestJson(id, operation.mutation()))
        remote.delegate.push("orphan", Mutation("orphan-operation", 0, "field", "External simulator edit", "", 200, false))
        remote.failure = IOException()
        assertEquals(SyncResult.RetryableFailure, repository.sync())
        assertNull(db.notes().demoOperation("orphan-operation"))
        assertNotNull(db.notes().demoOperation(operation.operationId))
        remote.failure = null
        assertEquals(SyncResult.Success(1, 0), repository.sync())
        assertEquals(1L, remote.delegate.snapshot().notes.first { it.id == id }.version)
        assertNull(db.notes().demoOperation(operation.operationId))
    }

    @Test fun largeSnapshotMergesBeyondBatchBoundaryAndPreservesPendingContent() = runBlocking {
        val pendingId = repository.save(NoteDraft(projectId = "field", title = "Protected local", body = ""))
        remote.deferredIds += pendingId
        val firstSnapshot = (0..500).map { RemoteNote("remote-$it", "field", "Remote $it", "", 200, 1, false) }
        val pendingRemote = RemoteNote(pendingId, "field", "Must not overwrite", "", 200, 5, false)
        remote.snapshotOverride = Snapshot(OfflineFirstNoteRepository.DEFAULT_PROJECTS, firstSnapshot + pendingRemote)
        assertEquals(SyncResult.Success(0, 0, deferred = 1), repository.sync())
        remote.snapshotOverride = Snapshot(OfflineFirstNoteRepository.DEFAULT_PROJECTS,
            firstSnapshot.map { if (it.id == "remote-500") it.copy(title = "Updated beyond batch", version = 2) else it } + pendingRemote)
        assertEquals(SyncResult.Success(0, 0, deferred = 1), repository.sync())
        assertEquals(502, repository.observeNotes().first().size)
        assertEquals("Updated beyond batch", repository.observeNote("remote-500").first()!!.title)
        assertEquals("Protected local", repository.observeNote(pendingId).first()!!.title)
        assertEquals(1, db.notes().pendingCount(pendingId))
    }

    private fun limitedRepository(maxOperations: Int = 8, timeoutMillis: Long = 120_000) =
        OfflineFirstNoteRepository(db, remote, Clock { time++ }, IdGenerator { "id-${++identifier}" },
            SyncLimits(maxOperations, timeoutMillis))

    private suspend fun edit(id: String, title: String, body: String = "") = repository.save(
        NoteDraft(id, "field", title, body, repository.observeNote(id).first()!!.revision),
    )

    private suspend fun createSyncedNote(): String {
        val id = repository.save(NoteDraft(projectId = "field", title = "Original", body = ""))
        repository.sync()
        return id
    }

    private class TestRemote(val delegate: RemoteNotes) : RemoteNotes {
        var failure: Exception? = null
        var beforePush: (suspend () -> Unit)? = null
        var loseNextResponse = false
        val rejectedIds = mutableSetOf<String>()
        val deferredIds = mutableSetOf<String>()
        val attempted = mutableListOf<Pair<String, Mutation>>()
        var snapshotOverride: Snapshot? = null
        var canonicalTitle: String? = null
        override suspend fun snapshot(): Snapshot {
            failure?.let { throw it }
            return snapshotOverride ?: delegate.snapshot()
        }
        override suspend fun push(id: String, mutation: Mutation): PushResult {
            failure?.let { throw it }
            beforePush?.invoke()
            attempted += id to mutation
            if (id in rejectedIds) return PushResult.Rejected
            if (id in deferredIds) return PushResult.Deferred
            val response = delegate.push(id, mutation)
            if (loseNextResponse) { loseNextResponse = false; throw IOException("Response was lost") }
            return if (response is PushResult.Accepted && canonicalTitle != null)
                PushResult.Accepted(response.note.copy(title = canonicalTitle!!)) else response
        }
    }
}
