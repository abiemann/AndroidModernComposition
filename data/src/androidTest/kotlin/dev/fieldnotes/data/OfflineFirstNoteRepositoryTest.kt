package dev.fieldnotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fieldnotes.domain.Clock
import dev.fieldnotes.domain.IdGenerator
import dev.fieldnotes.domain.NoteDraft
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
            repository.save(NoteDraft(id, "field", "Second", "New observation"))
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
        repository.save(NoteDraft(id, "field", "Second", ""))
        assertEquals(SyncResult.Success(2, 0), repository.sync())
        assertEquals(2L, remote.delegate.snapshot().notes.single().version)
        assertEquals("Second", repository.observeNote(id).first()!!.title)
    }

    @Test fun conflictKeepsLocalCopyUntilUserAcceptsServer() = runBlocking {
        val id = createSyncedNote()
        remote.delegate.push(id, Mutation("other-device", 1, "field", "Remote", "Remote body", 200, false))
        repository.save(NoteDraft(id, "field", "Local", "Local body"))
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
        repository.save(NoteDraft(id, "field", "Local", ""))
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

    private suspend fun createSyncedNote(): String {
        val id = repository.save(NoteDraft(projectId = "field", title = "Original", body = ""))
        repository.sync()
        return id
    }

    private class TestRemote(val delegate: RemoteNotes) : RemoteNotes {
        var failure: Exception? = null
        var beforePush: (suspend () -> Unit)? = null
        var loseNextResponse = false
        override suspend fun snapshot(): Snapshot {
            failure?.let { throw it }
            return delegate.snapshot()
        }
        override suspend fun push(id: String, mutation: Mutation): PushResult {
            failure?.let { throw it }
            beforePush?.invoke()
            val response = delegate.push(id, mutation)
            if (loseNextResponse) { loseNextResponse = false; throw IOException("Response was lost") }
            return response
        }
    }
}
