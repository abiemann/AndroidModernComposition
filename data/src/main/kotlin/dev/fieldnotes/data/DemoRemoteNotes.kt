package dev.fieldnotes.data

import androidx.room.withTransaction

/** A persistent, local server simulator. It never claims that data reached an external server. */
class DemoRemoteNotes(private val database: FieldNotesDatabase) : RemoteNotes, ReplayReceiptCleanup {
    private val dao = database.notes()

    override suspend fun removeAcknowledged(operationId: String) = dao.removeDemoOperation(operationId)

    override suspend fun pruneOrphans() = dao.pruneAcknowledgedDemoOperations()

    override suspend fun snapshot() = database.withTransaction {
        Snapshot(OfflineFirstNoteRepository.DEFAULT_PROJECTS,
            dao.demoNotes().map { NoteJson.decodeNote(it.remoteJson) ?: throw RejectedRequest() })
    }

    override suspend fun push(id: String, mutation: Mutation): PushResult = database.withTransaction {
        val requestJson = NoteJson.requestJson(id, mutation)
        val fingerprint = NoteJson.fingerprint(id, mutation)
        dao.demoOperation(mutation.operationId)?.let {
            // Historical pending receipts used full JSON. Replay them without changing identity,
            // then compact their request representation; never evict them by age or count.
            if (it.requestJson != fingerprint && !NoteJson.matchesHistoricalRequest(it.requestJson, requestJson))
                return@withTransaction PushResult.Rejected
            val accepted = NoteJson.decodeNote(it.responseJson)?.takeIf { note -> note.id == id }
                ?: throw RejectedRequest()
            dao.compactDemoOperation(mutation.operationId, fingerprint)
            return@withTransaction PushResult.Accepted(accepted)
        }
        val current = dao.demoNote(id)?.let { NoteJson.decodeNote(it.remoteJson) ?: throw RejectedRequest() }
        if (current != null && current.version != mutation.baseVersion) return@withTransaction PushResult.Conflict(current)
        if (current == null && mutation.baseVersion != 0L) return@withTransaction PushResult.Rejected
        val note = RemoteNote(id, mutation.projectId, mutation.title, mutation.body, mutation.updatedAt,
            (current?.version ?: 0) + 1, mutation.deleted)
        val responseJson = NoteJson.gson.toJson(note)
        dao.putDemoNote(DemoNoteEntity(id, responseJson))
        dao.putDemoOperation(DemoOperationEntity(mutation.operationId, fingerprint, responseJson))
        PushResult.Accepted(note)
    }
}
