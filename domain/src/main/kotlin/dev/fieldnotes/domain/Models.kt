package dev.fieldnotes.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class Project(val id: String, val name: String)

enum class SyncState { PENDING, SYNCED, CONFLICT, REJECTED, UNRECOGNIZED }

data class Note(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val revision: Long,
    val syncState: SyncState,
    val deleted: Boolean = false,
)

data class NoteDraft(
    val id: String? = null,
    val projectId: String,
    val title: String,
    val body: String,
    val expectedRevision: Long? = null,
)

/** The saved note changed or disappeared after the editor read it; keep the user's draft. */
class StaleNoteEdit : Exception()

data class NoteRecovery(val revision: Long, val required: Boolean)

enum class LocalDiscardResult { Discarded, Changed, SyncInProgress }

sealed interface SyncResult {
    /** [morePending] requests another bounded pass. [deferred] counts notes with an uncertain acknowledgement. */
    data class Success(
        val uploaded: Int,
        val conflicts: Int,
        val rejected: Int = 0,
        val morePending: Boolean = false,
        val deferred: Int = 0,
    ) : SyncResult
    data object AuthenticationRequired : SyncResult
    data object PermissionRequired : SyncResult
    data object RetryableFailure : SyncResult
    data object Rejected : SyncResult
}

interface NoteRepository {
    fun observeNotes(): Flow<List<Note>>
    fun observeNote(id: String): Flow<Note?>
    fun observeRecovery(id: String): Flow<NoteRecovery?>
    fun observeProjects(): Flow<List<Project>>
    suspend fun save(draft: NoteDraft): String
    suspend fun delete(id: String)
    suspend fun resolveConflict(id: String, keepLocal: Boolean)
    /** Discard a damaged local copy after confirmation. No deletion is sent to the server. */
    suspend fun discardDamagedLocalCopy(id: String, expectedRevision: Long): LocalDiscardResult
    /** Retry the retained latest snapshot after a definitive rejection, including deletion tombstones. */
    suspend fun retryRejected(id: String)
    suspend fun sync(): SyncResult
}

fun interface SyncScheduler {
    suspend fun requestSync()
    val backgroundSyncFailed: Flow<Boolean> get() = flowOf(false)
}
fun interface Clock { fun now(): Long }
fun interface IdGenerator { fun next(): String }

enum class AnalyticsEvent { NOTE_SAVED, SYNC_SUCCEEDED, SYNC_FAILED, JOURNAL_EXPOSED }
interface Telemetry {
    suspend fun record(event: AnalyticsEvent)
    suspend fun recordExposure(assignment: ExperimentAssignment) { record(AnalyticsEvent.JOURNAL_EXPOSED) }
}

data class Preferences(
    val compactCards: Boolean = false,
    val analyticsEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val compactCardsExperiment: ExperimentAssignment = ExperimentAssignment(),
)

interface PreferencesRepository {
    val preferences: Flow<Preferences>
    suspend fun setCompactCards(enabled: Boolean)
    suspend fun resetCompactCardsOverride()
    suspend fun setAnalyticsEnabled(enabled: Boolean)
    suspend fun setNotificationsEnabled(enabled: Boolean)
}

interface SessionRepository {
    val signedIn: Flow<Boolean?>
    suspend fun signIn(token: String)
    suspend fun signOut()
}

/** Only the network boundary sees the token; never expose it in UI state or logs. */
interface TokenStore {
    fun read(): String?
    fun write(token: String?)
}
