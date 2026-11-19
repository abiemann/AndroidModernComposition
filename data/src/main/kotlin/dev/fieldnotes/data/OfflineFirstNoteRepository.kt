package dev.fieldnotes.data

import androidx.room.withTransaction
import dev.fieldnotes.domain.Clock
import dev.fieldnotes.domain.IdGenerator
import dev.fieldnotes.domain.LocalDiscardResult
import dev.fieldnotes.domain.Note
import dev.fieldnotes.domain.NoteDraft
import dev.fieldnotes.domain.NoteRecovery
import dev.fieldnotes.domain.NoteRepository
import dev.fieldnotes.domain.Project
import dev.fieldnotes.domain.SaveNote
import dev.fieldnotes.domain.StaleNoteEdit
import dev.fieldnotes.domain.SyncResult
import dev.fieldnotes.domain.SyncState
import java.io.IOException
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val FINISH_RESERVE_MILLIS = HTTP_CALL_TIMEOUT_SECONDS * 2 * 1_000 + 5_000
private const val DEFAULT_SYNC_TIMEOUT_MILLIS = 120_000L

/** Bounds each call by operation count and elapsed time, including time waiting for the sync mutex. */
data class SyncLimits(val maxOperations: Int = 128, val timeoutMillis: Long = DEFAULT_SYNC_TIMEOUT_MILLIS) {
    init {
        // Deferred IDs are bound into SQL; keep well below the oldest SQLite host parameter limit.
        require(maxOperations in 1..256)
        // Leave time for the worker to report its result before WorkManager's ten-minute limit.
        require(timeoutMillis in 1..480_000)
    }

    internal val startMoreUploadsUntilMillis: Long
        get() = timeoutMillis - minOf(timeoutMillis * 2 / 3, FINISH_RESERVE_MILLIS)
}

class OfflineFirstNoteRepository(
    private val database: FieldNotesDatabase,
    private val remote: RemoteNotes,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val limits: SyncLimits = SyncLimits(),
    private val replayReceipts: ReplayReceiptCleanup? = null,
    private val syncTimeSource: TimeSource = TimeSource.Monotonic,
) : NoteRepository {
    private val dao = database.notes()
    private val syncMutex = Mutex()
    private var batchesWithoutPull = MAX_BATCHES_WITHOUT_PULL

    override fun observeNotes() = dao.observeNotes().map { rows -> rows.map(NoteEntity::toDomain) }
        .onStart { initialize() }
    override fun observeNote(id: String) = dao.observeNote(id).map { it?.toDomain() }
        .onStart { initialize() }
    override fun observeRecovery(id: String) =
        combine(dao.observeRecovery(id), dao.observeProjects()) { record, projects ->
            record?.let {
                val required = when (it.syncState.knownState()) {
                    null -> true
                    SyncState.CONFLICT -> {
                        val server = conflictVersion(id, it.remoteJson)
                        server == null || projects.none { project -> project.id == server.projectId }
                    }
                    else -> false
                }
                NoteRecovery(it.revision, required)
            }
        }.distinctUntilChanged()
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
                existing.requireKnownState()
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
            old.requireKnownState()
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
            current.requireKnownState()
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
            local.requireKnownState()
            if (local.syncState != SyncState.CONFLICT.name) return@withTransaction
            val server = checkNotNull(usableConflictVersion(id)) {
                "Stored conflict requires recovery"
            }
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

    override suspend fun discardDamagedLocalCopy(id: String, expectedRevision: Long): LocalDiscardResult {
        // A sync may be holding a mutation outside Room while it waits for its acknowledgement.
        if (!syncMutex.tryLock()) return LocalDiscardResult.SyncInProgress
        try {
            return database.withTransaction {
                val local = dao.note(id) ?: return@withTransaction LocalDiscardResult.Changed
                if (local.localRevision != expectedRevision) return@withTransaction LocalDiscardResult.Changed
                val needsRecovery = when (local.knownState()) {
                    null -> true
                    SyncState.CONFLICT -> usableConflictVersion(id) == null
                    else -> false
                }
                if (!needsRecovery) return@withTransaction LocalDiscardResult.Changed
                dao.discardPending(id)
                dao.removeConflict(id)
                replayReceipts?.pruneOrphans()
                // Keep the revision high-water mark so an older draft cannot attach to a
                // server copy that reappears with the same ID after the next pull.
                dao.putNote(local.copy(title = "", body = "", deleted = true,
                    localRevision = local.localRevision + 1, serverVersion = 0, syncState = SyncState.SYNCED.name))
                LocalDiscardResult.Discarded
            }
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun usableConflictVersion(id: String): RemoteNote? {
        val server = conflictVersion(id, dao.conflict(id)?.remoteJson) ?: return null
        return server.takeIf { dao.project(it.projectId) != null }
    }

    override suspend fun sync(): SyncResult = try {
        val started = syncTimeSource.markNow()
        withTimeoutOrNull(limits.timeoutMillis) {
            syncMutex.withLock { syncPass(started) }
        } ?: SyncResult.RetryableFailure
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

    private suspend fun syncPass(started: TimeMark): SyncResult.Success {
        initialize()
        val (watermark, startCursor) = database.withTransaction {
            replayReceipts?.pruneOrphans()
            dao.lastSequence() to (dao.syncCursor() ?: 0)
        }
        var uploaded = 0
        var durableProgress = 0
        var attempts = 0
        var afterSequence = startCursor
        var throughSequence = watermark
        var wrapped = false
        val deferred = mutableSetOf<String>()
        while (attempts < limits.maxOperations) {
            // Reserve time for the last request, a pull and local commits. A short custom
            // budget still permits a first attempt. The hard deadline applies to it as well.
            val shouldReserveTime = attempts > 0 || limits.timeoutMillis >= DEFAULT_SYNC_TIMEOUT_MILLIS
            if (shouldReserveTime && started.elapsedNow().inWholeMilliseconds >= limits.startMoreUploadsUntilMillis) break
            val operation = dao.nextOperation(afterSequence, throughSequence, deferred.toList())
            if (operation == null) {
                if (!wrapped && startCursor > 0) {
                    wrapped = true
                    afterSequence = 0
                    throughSequence = minOf(startCursor, watermark)
                    continue
                }
                break
            }
            attempts++
            afterSequence = operation.sequence
            when (val result = remote.push(operation.noteId, operation.mutation())) {
                is PushResult.Accepted -> {
                    database.withTransaction {
                        val current = dao.note(operation.noteId)
                        dao.acknowledge(operation.operationId)
                        replayReceipts?.removeAcknowledged(operation.operationId)
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
                        dao.putSyncCursor(SyncCursorEntity(afterSequence = operation.sequence))
                    }
                    uploaded++
                    durableProgress++
                }
                is PushResult.Conflict -> {
                    database.withTransaction {
                        dao.note(operation.noteId)?.let { current ->
                            dao.putNote(current.copy(syncState = SyncState.CONFLICT.name))
                            dao.putConflict(ConflictEntity(operation.noteId, NoteJson.gson.toJson(result.current)))
                        }
                        dao.discardPending(operation.noteId)
                        replayReceipts?.pruneOrphans()
                        dao.putSyncCursor(SyncCursorEntity(afterSequence = operation.sequence))
                    }
                    durableProgress++
                }
                PushResult.Rejected -> {
                    database.withTransaction {
                        // Keep the queued chain for explicit user recovery.
                        dao.note(operation.noteId)?.let { current ->
                            dao.putNote(current.copy(syncState = SyncState.REJECTED.name))
                        }
                        dao.putSyncCursor(SyncCursorEntity(afterSequence = operation.sequence))
                    }
                    durableProgress++
                }
                PushResult.Deferred -> {
                    // Keep this exact operation and all causal successors for a later pass.
                    deferred += operation.noteId
                    dao.putSyncCursor(SyncCursorEntity(afterSequence = operation.sequence))
                }
            }
        }
        // Pull at the beginning, after ambiguous results, periodically during a healthy backlog,
        // and at the end. An intermediate batch needs a continuation even if its remaining writes
        // disappear before result reporting, because the snapshot was skipped.
        val skipPull = deferred.isEmpty() && batchesWithoutPull < MAX_BATCHES_WITHOUT_PULL &&
            dao.hasEligiblePending(emptyList())
        val snapshot = if (skipPull) null else remote.snapshot()
        return database.withTransaction {
            snapshot?.let { mergeSnapshot(it) }
            // All-ambiguous batches back off. The cursor gives the next batch a chance
            // while the head-only query preserves mutation order for each note.
            val morePending = (durableProgress > 0 || deferred.isEmpty()) &&
                dao.hasEligiblePending(deferred.toList())
            SyncResult.Success(
                uploaded = uploaded,
                conflicts = dao.conflictCount(),
                rejected = dao.rejectedCount(),
                morePending = skipPull || morePending,
                deferred = deferred.size,
            )
        }.also { result ->
            batchesWithoutPull = when {
                !result.morePending -> MAX_BATCHES_WITHOUT_PULL
                skipPull -> batchesWithoutPull + 1
                else -> 0
            }
        }
    }

    private suspend fun mergeSnapshot(snapshot: Snapshot) {
        dao.insertProjects(snapshot.projects.map { ProjectEntity(it.id, it.name) })
        snapshot.projects.forEach { dao.renameProject(it.id, it.name) }
        // Read only the local version metadata. Chunks keep SQLite's bind count bounded.
        for (batch in snapshot.notes.chunked(SNAPSHOT_MERGE_BATCH_SIZE)) {
            val localVersions = dao.noteVersions(batch.map(RemoteNote::id)).associateBy(NoteVersion::id)
            val changed = batch.mapNotNull { server ->
                val local = localVersions[server.id]
                if (local == null || (local.syncState == SyncState.SYNCED.name && server.version > local.serverVersion)) {
                    server.toEntity((local?.localRevision ?: 0) + 1)
                } else {
                    null
                }
            }
            if (changed.isNotEmpty()) dao.putNotes(changed)
        }
    }

    private suspend fun enqueue(note: NoteEntity) = dao.enqueue(OutboxEntity(
        operationId = ids.next(), noteId = note.id, localRevision = note.localRevision,
        baseVersion = note.serverVersion, projectId = note.projectId, title = note.title,
        body = note.body, updatedAt = note.updatedAt, deleted = note.deleted,
    ))

    companion object {
        val DEFAULT_PROJECTS = listOf(Project("field", "Field observations"), Project("study", "Android study"))
        private const val MAX_BATCHES_WITHOUT_PULL = 2
        private const val SNAPSHOT_MERGE_BATCH_SIZE = 500
    }
}

private fun String.knownState(): SyncState? = SyncState.entries.firstOrNull { it.name == this }
    ?.takeUnless { it == SyncState.UNRECOGNIZED }

private fun NoteEntity.knownState() = syncState.knownState()

private fun conflictVersion(id: String, json: String?): RemoteNote? =
    json?.let(NoteJson::decodeNote)?.takeIf { it.id == id }

private fun NoteEntity.requireKnownState() {
    check(knownState() != null) { "Stored sync state requires recovery" }
}

private fun NoteEntity.toDomain() = Note(id, projectId, title, body, updatedAt, localRevision,
    knownState() ?: SyncState.UNRECOGNIZED, deleted)

private fun RemoteNote.toEntity(localRevision: Long) = NoteEntity(id, projectId, title, body, updatedAt,
    localRevision, version, SyncState.SYNCED.name, deleted)
