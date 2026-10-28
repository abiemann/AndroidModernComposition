package dev.fieldnotes.domain

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SaveNoteTest {
    private val repository = RecordingRepository()
    private var scheduled = 0
    private val events = mutableListOf<AnalyticsEvent>()
    private val save = SaveNote(repository, SyncScheduler { scheduled++ }, object : Telemetry {
        override fun record(event: AnalyticsEvent) { events += event }
    })

    @Test fun `whitespace only title never writes or schedules`() = runTest {
        val error = runCatching { save(NoteDraft(projectId = "field", title = " \n ", body = "")) }.exceptionOrNull()
        assertEquals(InvalidNote.Reason.TITLE_REQUIRED, (error as InvalidNote).reason)
        assertNull(repository.saved); assertEquals(0, scheduled); assertTrue(events.isEmpty())
    }

    @Test fun `valid write trims inputs and schedules after persistence`() = runTest {
        val id = save(NoteDraft(projectId = "field", title = " Heron ", body = " At the lake \n"))
        assertEquals("new-note", id)
        assertEquals("Heron", repository.saved?.title)
        assertEquals("At the lake", repository.saved?.body)
        assertEquals(1, scheduled); assertEquals(listOf(AnalyticsEvent.NOTE_SAVED), events)
    }

    @Test fun `storage failure never signals success`() = runTest {
        repository.fail = true
        assertTrue(runCatching { save(NoteDraft(projectId = "field", title = "Heron", body = "")) }.exceptionOrNull() is IOException)
        assertEquals(0, scheduled); assertTrue(events.isEmpty())
    }

    @Test fun `failure of optional effects does not turn a saved insert into a retry`() = runTest {
        val effectsFail = SaveNote(repository, SyncScheduler { throw IllegalStateException("Unavailable") },
            object : Telemetry { override fun record(event: AnalyticsEvent) { throw IllegalStateException() } })
        assertEquals("new-note", effectsFail(NoteDraft(projectId = "field", title = "Heron", body = "")))
        assertNotNull(repository.saved)
    }

    @Test fun `accepts exact text limits`() {
        SaveNote.validate(NoteDraft(projectId = "field", title = "x".repeat(120), body = "x".repeat(10_000)))
    }

    @Test fun `rejects oversized title`() {
        assertEquals(InvalidNote.Reason.TITLE_TOO_LONG, rejection("x".repeat(121), ""))
    }

    @Test fun `rejects oversized body`() {
        assertEquals(InvalidNote.Reason.BODY_TOO_LONG, rejection("Title", "x".repeat(10_001)))
    }

    @Test fun `requires project`() {
        val error = runCatching { SaveNote.validate(NoteDraft(projectId = " ", title = "Title", body = "")) }.exceptionOrNull()
        assertEquals(InvalidNote.Reason.PROJECT_REQUIRED, (error as InvalidNote).reason)
    }

    private fun rejection(title: String, body: String) = (runCatching {
        SaveNote.validate(NoteDraft(projectId = "field", title = title, body = body))
    }.exceptionOrNull() as InvalidNote).reason

    private class RecordingRepository : NoteRepository {
        var saved: NoteDraft? = null
        var fail = false
        override suspend fun save(draft: NoteDraft): String { if (fail) throw IOException(); saved = draft; return "new-note" }
        override fun observeNotes() = flowOf(emptyList<Note>())
        override fun observeNote(id: String): Flow<Note?> = flowOf(null)
        override fun observeProjects() = flowOf(emptyList<Project>())
        override suspend fun delete(id: String) = Unit
        override suspend fun resolveConflict(id: String, keepLocal: Boolean) = Unit
        override suspend fun sync() = SyncResult.Success(0, 0)
    }
}
