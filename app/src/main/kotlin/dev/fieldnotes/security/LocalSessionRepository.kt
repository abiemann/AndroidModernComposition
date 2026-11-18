package dev.fieldnotes.security

import dev.fieldnotes.domain.SessionRepository
import dev.fieldnotes.domain.SyncScheduler
import dev.fieldnotes.domain.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalSessionRepository(
    private val tokens: TokenStore,
    private val scheduler: SyncScheduler,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository {
    private val state = MutableStateFlow<Boolean?>(null)
    override val signedIn = state.asStateFlow()
    private val mutex = Mutex()
    init { scope.launch(ioDispatcher) { mutex.withLock { state.value = tokens.read() != null } } }

    override suspend fun signIn(token: String) = withContext(ioDispatcher) {
        val value = token.trim()
        require(value.length in 24..4096 && value.all { it.code in 33..126 }) { "Use a 24–4096 character ASCII server token without spaces." }
        mutex.withLock { tokens.write(value); state.value = true }
        // The credential is already durable. Scheduling is a recoverable follow-up,
        // also attempted by application startup and periodic work.
        try { scheduler.requestSync() } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    override suspend fun signOut() = withContext(ioDispatcher) {
        mutex.withLock { tokens.write(null); state.value = false }
    }
}
