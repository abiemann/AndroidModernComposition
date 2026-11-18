package dev.fieldnotes.security

import dev.fieldnotes.domain.SyncScheduler
import dev.fieldnotes.domain.TokenStore
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSessionRepositoryTest {
    @Test fun `credential state stays loading until the dispatched initial read completes`() = runTest {
        val tokens = MemoryTokens(TOKEN)
        val session = LocalSessionRepository(tokens, SyncScheduler {}, backgroundScope, StandardTestDispatcher(testScheduler))
        assertNull(session.signedIn.value)
        assertEquals(0, tokens.reads)
        runCurrent()
        assertEquals(true, session.signedIn.value)
        assertEquals(1, tokens.reads)
    }

    @Test fun `an empty credential store becomes disconnected after loading`() = runTest {
        val session = LocalSessionRepository(MemoryTokens(), SyncScheduler {}, backgroundScope, StandardTestDispatcher(testScheduler))
        assertNull(session.signedIn.value)
        runCurrent()
        assertEquals(false, session.signedIn.value)
    }

    @Test fun `successful sign in persists before scheduling and sign out clears the credential`() = runTest {
        val tokens = MemoryTokens()
        var scheduled = 0
        val scheduler = SyncScheduler { assertEquals(TOKEN, tokens.value); scheduled++ }
        val session = LocalSessionRepository(tokens, scheduler, backgroundScope, StandardTestDispatcher(testScheduler))
        session.signIn(" $TOKEN ")
        assertEquals(true, session.signedIn.value)
        assertEquals(1, scheduled)
        session.signOut()
        assertNull(tokens.value)
        assertEquals(false, session.signedIn.value)
    }

    @Test fun `scheduling failure does not turn a durable sign in into a failed credential write`() = runTest {
        val tokens = MemoryTokens()
        val scheduler = SyncScheduler { throw IOException("test scheduler unavailable") }
        val session = LocalSessionRepository(tokens, scheduler, backgroundScope, StandardTestDispatcher(testScheduler))
        session.signIn(TOKEN)
        assertEquals(TOKEN, tokens.value)
        assertEquals(true, session.signedIn.value)
    }

    @Test fun `failed credential write preserves disconnected state and never schedules`() = runTest {
        val tokens = MemoryTokens().apply { failWrites = true }
        var scheduled = 0
        val session = LocalSessionRepository(tokens, SyncScheduler { scheduled++ }, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        try {
            session.signIn(TOKEN)
            fail("Expected the storage failure")
        } catch (_: IOException) {
            assertEquals(false, session.signedIn.value)
            assertNull(tokens.value)
            assertEquals(0, scheduled)
        }
    }

    private class MemoryTokens(var value: String? = null) : TokenStore {
        var reads = 0
        var failWrites = false
        override fun read(): String? { reads++; return value }
        override fun write(token: String?) {
            if (failWrites) throw IOException("test credential storage unavailable")
            value = token
        }
    }

    private companion object { const val TOKEN = "local-session-test-token-not-a-secret" }
}
