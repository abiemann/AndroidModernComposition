package dev.fieldnotes

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fieldnotes.data.FieldNotesDatabase
import dev.fieldnotes.data.OfflineFirstNoteRepository
import dev.fieldnotes.data.RetrofitRemoteNotes
import dev.fieldnotes.data.RemoteNotes
import dev.fieldnotes.data.Mutation
import dev.fieldnotes.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executed by verify-local-server: real Android HTTP stack -> Python -> SQLite. */
@RunWith(AndroidJUnit4::class)
class LocalServerIntegrationTest {
    @Test fun offlineCreateEditDeleteReachesTheRealServer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val token = requireNotNull(arguments.getString("serverToken")) { "Run scripts/verify-local-server with its isolated test server." }
        val baseUrl = arguments.getString("baseUrl") ?: "http://10.0.2.2:8080/"
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }
        val name = "http-integration-${UUID.randomUUID()}.db"
        val database = FieldNotesDatabase.create(context, name)
        val tokens = object : TokenStore {
            override fun read() = token
            override fun write(token: String?) = Unit
        }
        val remote = RetrofitRemoteNotes.create(baseUrl, tokens)
        val diagnosticRemote = object : RemoteNotes by remote {
            override suspend fun push(id: String, mutation: Mutation) = try {
                remote.push(id, mutation)
            } catch (failure: java.io.IOException) {
                throw AssertionError("HTTP mutation transport failed", failure)
            }
        }
        val repository = OfflineFirstNoteRepository(database, diagnosticRemote, Clock(System::currentTimeMillis), IdGenerator { UUID.randomUUID().toString() })
        try {
            assertEquals(setOf("field", "study"), remote.snapshot().projects.map { it.id }.toSet())
            val title = "Heron ${UUID.randomUUID()}"
            val id = repository.save(NoteDraft(projectId = "field", title = title, body = "Observed offline 🪶"))
            assertEquals(SyncState.PENDING, repository.observeNote(id).first()?.syncState)
            val created = repository.sync()
            assertTrue("Initial sync outcome: $created", created is SyncResult.Success)
            assertEquals(title, remote.snapshot().notes.single { it.id == id }.title)
            assertEquals(SyncState.SYNCED, repository.observeNote(id).first()?.syncState)

            repository.save(NoteDraft(id, "study", title, "A revised observation"))
            assertTrue(repository.sync() is SyncResult.Success)
            val edited = remote.snapshot().notes.single { it.id == id }
            assertEquals("study", edited.projectId)
            assertEquals("A revised observation", edited.body)
            assertEquals(2L, edited.version)

            repository.delete(id)
            assertTrue(repository.sync() is SyncResult.Success)
            val deleted = remote.snapshot().notes.single { it.id == id }
            assertTrue(deleted.deleted)
            assertEquals(3L, deleted.version)
            assertFalse(repository.observeNotes().first().any { it.id == id })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
