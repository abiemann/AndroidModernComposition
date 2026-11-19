package dev.fieldnotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fieldnotes.domain.Clock
import dev.fieldnotes.domain.IdGenerator
import dev.fieldnotes.domain.LocalDiscardResult
import dev.fieldnotes.domain.NoteDraft
import dev.fieldnotes.domain.NoteRecovery
import dev.fieldnotes.domain.StaleNoteEdit
import dev.fieldnotes.domain.SyncResult
import dev.fieldnotes.domain.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DamagedNoteRecoveryTest {
    private lateinit var database: FieldNotesDatabase
    private lateinit var demo: DemoRemoteNotes
    private lateinit var repository: OfflineFirstNoteRepository
    private var identifier = 0

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FieldNotesDatabase::class.java,
        ).build()
        demo = DemoRemoteNotes(database)
        repository = notes(demo)
    }

    @After fun tearDown() { database.close() }

    @Test fun damagedConflictCanDiscardLocalTextAndPullTheServerCopyWithANewerRevision() = runBlocking {
        val dao = database.notes()
        val id = repository.save(NoteDraft(projectId = "field", title = "Local version", body = "Unsent content"))
        val local = requireNotNull(dao.note(id))
        assertTrue(demo.push(id, Mutation("server-edit", 0, "field", "Server version", "Canonical body", 200, false))
            is PushResult.Accepted)
        dao.discardPending(id)
        dao.putNote(local.copy(syncState = SyncState.CONFLICT.name))
        assertEquals(NoteRecovery(local.localRevision, required = true), repository.observeRecovery(id).first())
        dao.putConflict(ConflictEntity(id, "{"))
        assertEquals(NoteRecovery(local.localRevision, required = true), repository.observeRecovery(id).first())

        assertEquals(LocalDiscardResult.Discarded, repository.discardDamagedLocalCopy(id, local.localRevision))
        val hidden = requireNotNull(dao.note(id))
        assertTrue(hidden.deleted)
        assertEquals("", hidden.title)
        assertEquals("", hidden.body)
        assertTrue(hidden.localRevision > local.localRevision)
        assertTrue(repository.observeNotes().first().none { it.id == id })
        assertNull(dao.conflict(id))
        assertNull(dao.nextOperation())

        assertTrue(repository.sync() is SyncResult.Success)
        val restored = requireNotNull(repository.observeNote(id).first())
        assertEquals("Server version", restored.title)
        assertEquals("Canonical body", restored.body)
        assertTrue(restored.revision > hidden.localRevision)
        try {
            repository.save(NoteDraft(id, "field", "Old editor", "", local.localRevision))
            fail("An edit opened before discard must stay stale after the server copy returns")
        } catch (_: StaleNoteEdit) { }
    }

    @Test fun unknownStateDiscardClearsOnlyItsQueueAndAnAlreadyCommittedCopyCanReturn() = runBlocking {
        val dao = database.notes()
        val id = repository.save(NoteDraft(projectId = "field", title = "Previously committed", body = "First version"))
        val discarded = requireNotNull(dao.nextOperation())
        assertTrue(demo.push(id, discarded.mutation()) is PushResult.Accepted)
        val original = requireNotNull(dao.note(id))
        repository.save(NoteDraft(id, "field", "Unsent successor", "", original.localRevision))
        val damaged = requireNotNull(dao.note(id)).copy(syncState = "FUTURE_STATE")
        dao.putNote(damaged)
        val other = repository.save(NoteDraft(projectId = "field", title = "Other pending note", body = "Preserve me"))
        val preserved = requireNotNull(dao.nextOperation())
        assertEquals(other, preserved.noteId)
        assertTrue(demo.push(other, preserved.mutation()) is PushResult.Accepted)
        assertEquals(2, dao.pendingCount(id))

        assertEquals(LocalDiscardResult.Discarded, repository.discardDamagedLocalCopy(id, damaged.localRevision))
        assertEquals(0, dao.pendingCount(id))
        assertNull(dao.demoOperation(discarded.operationId))
        assertEquals(preserved, dao.nextOperation())
        assertNotNull(dao.demoOperation(preserved.operationId))
        assertTrue(repository.sync() is SyncResult.Success)
        assertEquals("Previously committed", repository.observeNote(id).first()?.title)
        assertEquals(1L, demo.snapshot().notes.single { it.id == other }.version)
    }

    @Test fun healthyAndStaleRecoveryRequestsLeaveTheOriginalRowsUntouched() = runBlocking {
        val dao = database.notes()
        val id = repository.save(NoteDraft(projectId = "field", title = "Current", body = "Keep"))
        val healthy = requireNotNull(dao.note(id))
        assertEquals(LocalDiscardResult.Changed, repository.discardDamagedLocalCopy(id, healthy.localRevision))
        assertEquals(healthy, dao.note(id))

        val validConflict = healthy.copy(syncState = SyncState.CONFLICT.name)
        dao.putNote(validConflict)
        dao.putConflict(ConflictEntity(id, NoteJson.gson.toJson(RemoteNote(id, "field", "Server", "", 200, 1))))
        assertEquals(NoteRecovery(validConflict.localRevision, required = false), repository.observeRecovery(id).first())
        assertEquals(LocalDiscardResult.Changed, repository.discardDamagedLocalCopy(id, validConflict.localRevision))
        assertEquals(validConflict, dao.note(id))

        val advanced = healthy.copy(syncState = "FUTURE_STATE", localRevision = healthy.localRevision + 1)
        dao.putNote(advanced)
        assertEquals(LocalDiscardResult.Changed, repository.discardDamagedLocalCopy(id, healthy.localRevision))
        assertEquals(advanced, dao.note(id))
        assertEquals(1, dao.pendingCount(id))
    }

    @Test fun discardDoesNotRaceAnActiveSync() = runBlocking {
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        repository = notes(object : RemoteNotes by demo {
            override suspend fun push(id: String, mutation: Mutation): PushResult {
                pushEntered.complete(Unit)
                releasePush.await()
                return demo.push(id, mutation)
            }
        })
        val dao = database.notes()
        val id = repository.save(NoteDraft(projectId = "field", title = "Keep while syncing", body = ""))
        val damaged = requireNotNull(dao.note(id)).copy(syncState = "FUTURE_STATE")
        dao.putNote(damaged)
        repository.save(NoteDraft(projectId = "field", title = "Uploading", body = ""))
        val active = async { repository.sync() }
        try {
            withTimeout(5_000) { pushEntered.await() }
            assertEquals(LocalDiscardResult.SyncInProgress, repository.discardDamagedLocalCopy(id, damaged.localRevision))
            assertEquals(damaged, dao.note(id))
            assertEquals(1, dao.pendingCount(id))
        } finally {
            releasePush.complete(Unit)
        }
        assertTrue(active.await() is SyncResult.Success)
    }

    private fun notes(remote: RemoteNotes) = OfflineFirstNoteRepository(
        database, remote, Clock { 100 }, IdGenerator { "id-${++identifier}" }, replayReceipts = demo,
    )
}
