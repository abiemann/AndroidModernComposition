package dev.fieldnotes.data

import androidx.room.withTransaction
import com.google.gson.Gson
import dev.fieldnotes.domain.Clock
import dev.fieldnotes.domain.IdGenerator
import dev.fieldnotes.domain.Note
import dev.fieldnotes.domain.NoteDraft
import dev.fieldnotes.domain.NoteRepository
import dev.fieldnotes.domain.Project
import dev.fieldnotes.domain.SaveNote
import dev.fieldnotes.domain.StaleNoteEdit
import dev.fieldnotes.domain.SyncResult
import dev.fieldnotes.domain.SyncState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OfflineFirstNoteRepository(
    private val database: FieldNotesDatabase,
    private val remote: RemoteNotes,
    private val clock: Clock,
    private val ids: IdGenerator,
) : NoteRepository {
    private val dao = database.notes()
    private val syncMutex = Mutex()
    private val gson = Gson()

    override fun observeNotes() = dao.observeNotes().map { rows -> rows.map(NoteEntity::toDomain) }
        .onStart { initialize() }
    override fun observeNote(id: String) = dao.observeNote(id).map { it?.toDomain() }
        .onStart { initialize() }
    override fun observeProjects() = dao.observeProjects().map { rows -> rows.map { Project(it.id, it.name) } }
        .onStart { initialize() }

    suspend fun initialize() {
        dao.insertProjects(DEFAULT_PROJECTS.map { ProjectEntity(it.id, it.name) })
    }

    override suspend fun save(draft: NoteDraft): String {
        val valid = SaveNote.validate(draft)
        initialize()
        return database.withTransaction {
            val old = valid.id?.let {
                val existing = dao.note(it) ?: throw StaleNoteEdit()
                if (existing.deleted || valid.expectedRevision != existing.localRevision) throw StaleNoteEdit()
                existing
            }
            requireNotNull(dao.project(valid.projectId)) { "Unknown project" }
            val note = NoteEntity(
                id = old?.id ?: ids.next(), projectId = valid.projectId,
                title = valid.title, body = valid.body, updatedAt = clock.now(),
                localRevision = (old?.localRevision ?: 0) + 1,
                serverVersion = old?.serverVersion ?: 0,
                syncState = if (old?.syncState == SyncState.CONFLICT.name) SyncState.CONFLICT.name else SyncState.PENDING.name,
                deleted = false,
            )
            // Only an explicit correction may supersede a definitively rejected head and
            // its never-sent successors. An uncertain PENDING operation keeps its identity.
            if (old?.syncState == SyncState.REJECTED.name) dao.discardPending(note.id)
            dao.putNote(note)
            // Editing a conflict preserves the remote copy until the user makes an explicit choice.
            if (note.syncState != SyncState.CONFLICT.name) enqueue(note)
            note.id
        }
    }

    override suspend fun delete(id: String) {
        database.withTransaction {
            val old = dao.note(id) ?: return@withTransaction
            if (old.deleted) return@withTransaction
            val note = old.copy(deleted = true, updatedAt = clock.now(), localRevision = old.localRevision + 1,
                syncState = if (old.syncState == SyncState.CONFLICT.name) old.syncState else SyncState.PENDING.name)
            if (old.syncState == SyncState.REJECTED.name) dao.discardPending(id)
            dao.putNote(note)
            if (note.syncState != SyncState.CONFLICT.name) enqueue(note)
        }
    }

    override suspend fun retryRejected(id: String) {
        database.withTransaction {
            val current = dao.note(id) ?: return@withTransaction
            if (current.syncState != SyncState.REJECTED.name) return@withTransaction
            val retry = current.copy(localRevision = current.localRevision + 1, syncState = SyncState.PENDING.name)
            dao.discardPending(id)
            dao.putNote(retry)
            enqueue(retry)
        }
    }

    override suspend fun resolveConflict(id: String, keepLocal: Boolean) {
        database.withTransaction {
            val local = dao.note(id) ?: return@withTransaction
            val conflict = dao.conflict(id) ?: return@withTransaction
            val server = gson.fromJson(conflict.remoteJson, RemoteNote::class.java)
            dao.discardPending(id)
            if (keepLocal) {
                val next = local.copy(serverVersion = server.version, localRevision = local.localRevision + 1,
                    syncState = SyncState.PENDING.name, updatedAt = clock.now())
                dao.putNote(next)
                enqueue(next)
            } else {
                dao.putNote(server.toEntity(local.localRevision + 1))
            }
            dao.removeConflict(id)
        }
    }

    override suspend fun sync(): SyncResult = syncMutex.withLock {
        try {
            initialize()
            var uploaded = 0
            while (true) {
                val operation = dao.nextOperation() ?: break
                when (val result = remote.push(operation.noteId, operation.mutation())) {
                    is PushResult.Accepted -> {
                        database.withTransaction {
                            val current = dao.note(operation.noteId)
                            dao.acknowledge(operation.operationId)
                            dao.rebasePending(operation.noteId, result.note.version)
                            if (current != null) {
                                // A concurrent local edit must never be overwritten by an older acknowledgment.
                                if (current.localRevision == operation.localRevision) {
                                    val canonicalContentChanged = current.projectId != result.note.projectId ||
                                        current.title != result.note.title || current.body != result.note.body ||
                                        current.deleted != result.note.deleted
                                    val revision = current.localRevision + if (canonicalContentChanged) 1 else 0
                                    dao.putNote(result.note.toEntity(revision))
                                } else {
                                    dao.putNote(current.copy(serverVersion = result.note.version))
                                }
                            }
                        }
                        uploaded++
                    }
                    is PushResult.Conflict -> {
                        database.withTransaction {
                            dao.note(operation.noteId)?.let { current ->
                                dao.putNote(current.copy(syncState = SyncState.CONFLICT.name))
                                dao.putConflict(ConflictEntity(operation.noteId, gson.toJson(result.current)))
                            }
                            dao.discardPending(operation.noteId)
                        }
                    }
                    PushResult.Rejected -> database.withTransaction {
                        // Retain the complete chain and latest text for explicit user recovery.
                        // nextOperation skips this note so unrelated notes and pulls can progress.
                        dao.note(operation.noteId)?.let { current ->
                            dao.putNote(current.copy(syncState = SyncState.REJECTED.name))
                        }
                    }
                }
            }
            val snapshot = remote.snapshot()
            val unresolved = database.withTransaction {
                dao.insertProjects(snapshot.projects.map { ProjectEntity(it.id, it.name) })
                snapshot.projects.forEach { dao.renameProject(it.id, it.name) }
                snapshot.notes.forEach { server ->
                    val local = dao.note(server.id)
                    if (local == null || (local.syncState == SyncState.SYNCED.name && server.version > local.serverVersion)) {
                        // Pending, conflicted and rejected work always retain their local copy.
                        dao.putNote(server.toEntity((local?.localRevision ?: 0) + 1))
                    }
                }
                dao.conflictCount() to dao.rejectedCount()
            }
            SyncResult.Success(uploaded, unresolved.first, unresolved.second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthenticationFailure) {
            SyncResult.AuthenticationRequired
        } catch (_: NetworkPermissionFailure) {
            SyncResult.PermissionRequired
        } catch (_: RejectedRequest) {
            SyncResult.Rejected
        } catch (_: IOException) {
            SyncResult.RetryableFailure
        }
    }

    private suspend fun enqueue(note: NoteEntity) = dao.enqueue(OutboxEntity(
        operationId = ids.next(), noteId = note.id, localRevision = note.localRevision,
        baseVersion = note.serverVersion, projectId = note.projectId, title = note.title,
        body = note.body, updatedAt = note.updatedAt, deleted = note.deleted,
    ))

    companion object {
        val DEFAULT_PROJECTS = listOf(Project("field", "Field observations"), Project("study", "Android study"))
    }
}

private fun NoteEntity.toDomain() = Note(id, projectId, title, body, updatedAt, localRevision,
    SyncState.valueOf(syncState), deleted)

private fun RemoteNote.toEntity(localRevision: Long) = NoteEntity(id, projectId, title, body, updatedAt,
    localRevision, version, SyncState.SYNCED.name, deleted)
