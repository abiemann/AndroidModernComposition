package dev.fieldnotes.data

import dev.fieldnotes.domain.Project

/** The wire model deliberately excludes local revisions, queue state and credentials. */
data class RemoteNote(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val version: Long,
    val deleted: Boolean = false,
)

data class Snapshot(val projects: List<Project>, val notes: List<RemoteNote>)

data class Mutation(
    val operationId: String,
    val baseVersion: Long,
    val projectId: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val deleted: Boolean,
)

sealed interface PushResult {
    data class Accepted(val note: RemoteNote) : PushResult
    data class Conflict(val current: RemoteNote) : PushResult
    /** The server explicitly guarantees this mutation was not committed. */
    data object Rejected : PushResult
    /** Outcome is unknown. Retain this exact operation and defer its note for the current pass. */
    data object Deferred : PushResult
}

class AuthenticationFailure : Exception()
class NetworkPermissionFailure : Exception()
class RejectedRequest : Exception()

interface RemoteNotes {
    suspend fun snapshot(): Snapshot
    suspend fun push(id: String, mutation: Mutation): PushResult
}
