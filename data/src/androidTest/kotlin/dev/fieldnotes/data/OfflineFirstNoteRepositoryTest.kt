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
        assertEquals(SyncResult.Success(2, 0), repository.sync())
        assertEquals("Second", repository.observeNote(id).first()!!.title)
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
        var canonicalTitle: String? = null
        override suspend fun snapshot(): Snapshot {
            failure?.let { throw it }
            return delegate.snapshot()
        }
        override suspend fun push(id: String, mutation: Mutation): PushResult {
            failure?.let { throw it }
            beforePush?.invoke()
            if (id in rejectedIds) return PushResult.Rejected
            val response = delegate.push(id, mutation)
            if (loseNextResponse) { loseNextResponse = false; throw IOException("Response was lost") }
            return if (response is PushResult.Accepted && canonicalTitle != null)
                PushResult.Accepted(response.note.copy(title = canonicalTitle!!)) else response
        }
    }
}
