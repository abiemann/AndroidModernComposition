package dev.fieldnotes.data

import androidx.room.withTransaction
import com.google.gson.Gson

/** A persistent, local server simulator. It never claims that data reached an external server. */
class DemoRemoteNotes(private val database: FieldNotesDatabase) : RemoteNotes {
    private val dao = database.notes()
    private val gson = Gson()

    override suspend fun snapshot() = database.withTransaction {
        Snapshot(OfflineFirstNoteRepository.DEFAULT_PROJECTS,
            dao.demoNotes().map { gson.fromJson(it.remoteJson, RemoteNote::class.java) })
    }

    override suspend fun push(id: String, mutation: Mutation): PushResult = database.withTransaction {
        val requestJson = gson.toJson(mapOf("id" to id, "mutation" to mutation))
        dao.demoOperation(mutation.operationId)?.let {
            if (it.requestJson != requestJson) throw RejectedRequest()
            return@withTransaction PushResult.Accepted(gson.fromJson(it.responseJson, RemoteNote::class.java))
        }
        val current = dao.demoNote(id)?.let { gson.fromJson(it.remoteJson, RemoteNote::class.java) }
        if (current != null && current.version != mutation.baseVersion) return@withTransaction PushResult.Conflict(current)
        if (current == null && mutation.baseVersion != 0L) throw RejectedRequest()
        val note = RemoteNote(id, mutation.projectId, mutation.title, mutation.body, mutation.updatedAt,
            (current?.version ?: 0) + 1, mutation.deleted)
        val responseJson = gson.toJson(note)
        dao.putDemoNote(DemoNoteEntity(id, responseJson))
        dao.putDemoOperation(DemoOperationEntity(mutation.operationId, requestJson, responseJson))
        PushResult.Accepted(note)
    }
}
