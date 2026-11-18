package dev.fieldnotes.feature

import androidx.lifecycle.SavedStateHandle
import dev.fieldnotes.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeNotes
    private lateinit var settings: FakePreferences
    private var schedules = 0
    private var scheduleFails = false
    private val scheduler = SyncScheduler { schedules++; if (scheduleFails) error("scheduler unavailable") }
    private val telemetry = object : Telemetry { override fun record(event: AnalyticsEvent) = Unit }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeNotes()
        settings = FakePreferences()
        schedules = 0
        scheduleFails = false
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun editor(handle: SavedStateHandle = SavedStateHandle()) =
        EditorViewModel(repository, SaveNote(repository, scheduler, telemetry), handle)

    @Test fun `blank title stays in editor and never reaches storage`() = runTest(dispatcher) {
        val vm = editor()
        runCurrent()
        vm.title("   ")
        vm.body("An unsaved observation")
        vm.save()
        runCurrent()
        assertEquals(InvalidNote.Reason.TITLE_REQUIRED, vm.state.value.validation)
        assertEquals("An unsaved observation", vm.state.value.body)
        assertNull(vm.state.value.savedId)
        assertEquals(0, repository.saves)
        assertEquals(0, schedules)
    }

    @Test fun `valid save normalizes input and schedules durable sync`() = runTest(dispatcher) {
        val vm = editor()
        runCurrent()
        vm.title("  Meadow  ")
        vm.body("  First flowers  ")
        vm.save()
        runCurrent()
        assertEquals("note-1", vm.state.value.savedId)
        assertEquals("Meadow", repository.lastDraft?.title)
        assertEquals("First flowers", repository.lastDraft?.body)
        assertEquals(1, schedules)
    }

    @Test fun `editing an existing note preserves its identity`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("existing-note", "Original", "field"))
        val vm = editor(SavedStateHandle(mapOf("noteId" to "existing-note")))
        runCurrent()
        assertTrue(vm.state.value.editing)
        assertEquals("Original", vm.state.value.title)
        vm.title("Revised observation")
        vm.body("Additional details")
        vm.project("study")
        vm.save()
        runCurrent()
        assertEquals(NoteDraft("existing-note", "study", "Revised observation", "Additional details", expectedRevision = 0L),
            repository.lastDraft)
        assertEquals("existing-note", vm.state.value.savedId)
        assertEquals(1, repository.saves)
        assertEquals(1, schedules)
    }

    @Test fun `draft written by setters survives restoration over older database values`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Original", "field"))
        val handle = SavedStateHandle(mapOf("noteId" to "a"))
        val vm = editor(handle)
        runCurrent()
        vm.title("In progress")
        vm.body("Do not lose this")
        vm.project("study")

        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = editor(restoredHandle)
        runCurrent()
        assertEquals("In progress", restored.state.value.title)
        assertEquals("Do not lose this", restored.state.value.body)
        assertEquals("study", restored.state.value.projectId)
        assertTrue(restored.state.value.editing)
        assertFalse(restored.state.value.loading)
        assertEquals(0, repository.saves)
    }

    @Test fun `save failure keeps fields and allows retry`() = runTest(dispatcher) {
        repository.failSave = true
        val vm = editor()
        runCurrent()
        vm.title("Useful observation")
        vm.save()
        runCurrent()
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.saving)
        assertEquals("Useful observation", vm.state.value.title)
        assertNull(vm.state.value.savedId)
        repository.failSave = false
        vm.save()
        runCurrent()
        assertEquals("note-1", vm.state.value.savedId)
    }

    @Test fun `stale save keeps the original revision and can explicitly save a copy`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Original", "field").copy(revision = 4))
        val vm = editor(SavedStateHandle(mapOf("noteId" to "a")))
        runCurrent()
        vm.title("My unsaved work")
        vm.body("Keep these details")
        repository.notes.value = listOf(note("a", "Changed elsewhere", "field").copy(revision = 5))
        repository.staleSave = true
        vm.save()
        runCurrent()
        assertEquals(4L, repository.lastDraft?.expectedRevision)
        assertTrue(vm.state.value.stale)
        assertEquals("My unsaved work", vm.state.value.title)
        assertEquals("Keep these details", vm.state.value.body)
        assertNull(vm.state.value.savedId)
        assertEquals(0, schedules)
        vm.saveAsNew()
        runCurrent()
        assertNull(repository.lastDraft?.id)
        assertNull(repository.lastDraft?.expectedRevision)
        assertEquals("My unsaved work", repository.lastDraft?.title)
        assertEquals("note-1", vm.state.value.savedId)
        assertEquals(1, schedules)
    }

    @Test fun `restoration retains the original revision and explicit reload replaces the draft`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Original", "field").copy(revision = 4))
        val handle = SavedStateHandle(mapOf("noteId" to "a"))
        val first = editor(handle)
        runCurrent()
        first.title("Unsaved")
        repository.notes.value = listOf(note("a", "Latest", "study").copy(revision = 5))
        val restored = editor(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        runCurrent()
        assertTrue(restored.state.value.stale)
        assertEquals("Unsaved", restored.state.value.title)
        restored.save()
        runCurrent()
        assertEquals(0, repository.saves)
        restored.reloadLatest()
        runCurrent()
        assertFalse(restored.state.value.stale)
        assertEquals("Latest", restored.state.value.title)
        assertEquals("study", restored.state.value.projectId)
        restored.save()
        runCurrent()
        assertEquals(5L, repository.lastDraft?.expectedRevision)
    }

    @Test fun `older restored draft without a revision requires an explicit choice`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Current", "field").copy(revision = 9))
        val vm = editor(SavedStateHandle(mapOf("noteId" to "a", "initialized" to true,
            "title" to "Older draft", "body" to "Keep me", "projectId" to "field")))
        runCurrent()
        assertTrue(vm.state.value.stale)
        assertEquals("Older draft", vm.state.value.title)
        vm.save()
        runCurrent()
        assertEquals(0, repository.saves)
        vm.saveAsNew()
        runCurrent()
        assertNull(repository.lastDraft?.expectedRevision)
        assertNull(repository.lastDraft?.id)
        assertEquals("Older draft", repository.lastDraft?.title)
    }

    @Test fun `restored draft of a deleted note remains available as a copy`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Deleted", "field").copy(revision = 5, deleted = true))
        val vm = editor(SavedStateHandle(mapOf("noteId" to "a", "initialized" to true,
            "expectedRevision" to 4L, "title" to "Unsaved", "body" to "Preserve", "projectId" to "field")))
        runCurrent()
        assertTrue(vm.state.value.stale)
        assertTrue(vm.state.value.latestUnavailable)
        assertFalse(vm.state.value.missing)
        vm.reloadLatest()
        runCurrent()
        assertEquals("Unsaved", vm.state.value.title)
        vm.saveAsNew()
        runCurrent()
        assertEquals("note-1", vm.state.value.savedId)
        assertEquals("Preserve", repository.lastDraft?.body)
    }

    @Test fun `failed initial load keeps draft fields and exposes recovery until retry succeeds`() = runTest(dispatcher) {
        repository.failProjects = true
        val handle = SavedStateHandle()
        val vm = editor(handle)
        runCurrent()
        assertTrue(vm.state.value.loadFailed)
        vm.title("Typed before recovery")
        vm.body("Keep this draft")
        assertTrue(vm.state.value.loadFailed)
        vm.save()
        runCurrent()
        assertEquals(0, repository.saves)
        repository.failProjects = false
        vm.load()
        runCurrent()
        assertFalse(vm.state.value.loadFailed)
        assertEquals("Typed before recovery", vm.state.value.title)
        assertEquals("Keep this draft", vm.state.value.body)
        vm.save()
        runCurrent()
        assertEquals("Typed before recovery", repository.lastDraft?.title)
    }

    @Test fun `deleted record cannot silently be recreated by editor`() = runTest(dispatcher) {
        val vm = editor(SavedStateHandle(mapOf("noteId" to "missing")))
        runCurrent()
        assertTrue(vm.state.value.missing)
        vm.save()
        runCurrent()
        assertEquals(0, repository.saves)
    }

    @Test fun `query and project filters combine while total counts remain global`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Meadow", "field"), note("b", "Meadow study", "study"),
            note("c", "Forest", "field"))
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        vm.search("MEADOW")
        vm.filter("field")
        runCurrent()
        assertEquals(listOf("a"), vm.state.value.notes.map { it.id })
        assertEquals(3, vm.state.value.totalCount)
        assertEquals(3, vm.state.value.pendingCount)
    }

    @Test fun `failed connection schedules retry and releases busy state`() = runTest(dispatcher) {
        repository.syncResult = SyncResult.RetryableFailure
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        vm.sync()
        assertTrue(vm.syncing.value)
        runCurrent()
        assertEquals(Feedback.OFFLINE, vm.feedback.value)
        assertEquals(1, schedules)
        assertFalse(vm.syncing.value)
    }

    @Test fun `deleting a note forwards its identity and schedules sync after success`() = runTest(dispatcher) {
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        vm.delete("note-to-delete")
        runCurrent()
        assertEquals("note-to-delete", repository.lastDeleteId)
        assertEquals(1, schedules)
        assertEquals(Feedback.DELETED, vm.feedback.value)
    }

    @Test fun `failed deletion reports failure without scheduling or announcing deletion`() = runTest(dispatcher) {
        repository.failDelete = true
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        val feedback = mutableListOf<Feedback?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.feedback.toList(feedback) }
        vm.delete("note-to-keep")
        runCurrent()
        assertEquals("note-to-keep", repository.lastDeleteId)
        assertEquals(0, schedules)
        assertEquals(Feedback.FAILED, vm.feedback.value)
        assertFalse(feedback.contains(Feedback.DELETED))
    }

    @Test fun `scheduling failure does not misreport a completed deletion or conflict resolution`() = runTest(dispatcher) {
        scheduleFails = true
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        vm.delete("deleted")
        runCurrent()
        assertEquals("deleted", repository.lastDeleteId)
        assertEquals(Feedback.DELETED, vm.feedback.value)
        vm.resolveConflict("conflicted", keepLocal = false)
        runCurrent()
        assertEquals("conflicted" to false, repository.lastResolution)
        assertEquals(Feedback.RESOLVED, vm.feedback.value)
        assertEquals(2, schedules)
    }

    @Test fun `rejected tombstones stay visible and are distinct from pending uploads`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("paused", "Rejected deletion", "field").copy(deleted = true, syncState = SyncState.REJECTED),
            note("pending", "Pending deletion", "field").copy(deleted = true))
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(listOf("paused"), vm.state.value.notes.map { it.id })
        assertEquals(1, vm.state.value.attentionCount)
        assertEquals(1, vm.state.value.pendingCount)
    }

    @Test fun `retry rejected change delegates its identity and schedules durable work`() = runTest(dispatcher) {
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        vm.retryRejected("paused")
        runCurrent()
        assertEquals("paused", repository.lastRetryId)
        assertEquals(1, schedules)
        assertEquals(Feedback.RETRY_QUEUED, vm.feedback.value)
    }

    @Test fun `failed rejected retry never schedules or reports queued`() = runTest(dispatcher) {
        repository.failRetry = true
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        vm.retryRejected("paused")
        runCurrent()
        assertEquals(0, schedules)
        assertEquals(Feedback.FAILED, vm.feedback.value)
    }

    @Test fun `paused note counts are distinct from an unusable sync response`() = runTest(dispatcher) {
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        repository.syncResult = SyncResult.Success(0, 0, rejected = 1)
        vm.sync()
        runCurrent()
        assertEquals(Feedback.PAUSED, vm.feedback.value)
        repository.syncResult = SyncResult.Rejected
        vm.sync()
        runCurrent()
        assertEquals(Feedback.REJECTED, vm.feedback.value)
    }

    @Test fun `tombstone conflicts remain visible for resolution`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("a", "Deleted", "field").copy(deleted = true),
            note("b", "Conflict", "field").copy(deleted = true, syncState = SyncState.CONFLICT))
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(listOf("b"), vm.state.value.notes.map { it.id })
    }

    @Test fun `pending deletion is hidden but remains in sync count`() = runTest(dispatcher) {
        repository.notes.value = listOf(note("deleted", "Removed observation", "field").copy(deleted = true))
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertTrue(vm.state.value.notes.isEmpty())
        assertEquals(0, vm.state.value.totalCount)
        assertEquals(1, vm.state.value.pendingCount)
    }

    @Test fun `authentication failures do not trigger blind retries`() = runTest(dispatcher) {
        repository.syncResult = SyncResult.AuthenticationRequired
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        vm.sync()
        runCurrent()
        assertEquals(Feedback.SIGN_IN, vm.feedback.value)
        assertEquals(0, schedules)
    }

    @Test fun `read failure is distinct from an empty journal and retry reopens flow`() = runTest(dispatcher) {
        repository.failRead = true
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
        repository.failRead = false
        repository.notes.value = listOf(note("recovered", "Recovered observation", "field"))
        vm.retry()
        runCurrent()
        assertFalse(vm.state.value.loadFailed)
        assertEquals("recovered", vm.state.value.notes.single().id)
    }

    @Test fun `rendered variant and consent come from the same preference snapshot`() = runTest(dispatcher) {
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), telemetry, dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertFalse(vm.state.value.analyticsEnabled)
        val assignment = ExperimentAssignment(ExperimentVariant.COMPACT)
        settings.preferences.value = Preferences(compactCards = true, analyticsEnabled = true,
            compactCardsExperiment = assignment)
        runCurrent()
        assertTrue(vm.state.value.analyticsEnabled)
        assertTrue(vm.state.value.compactCards)
        assertEquals(assignment, vm.state.value.compactCardsExperiment)
        settings.setAnalyticsEnabled(false)
        runCurrent()
        assertFalse(vm.state.value.analyticsEnabled)
    }

    @Test fun `optional telemetry failure does not break the journal`() = runTest(dispatcher) {
        val unavailableTelemetry = object : Telemetry {
            override fun record(event: AnalyticsEvent) = Unit
            override suspend fun recordExposure(assignment: ExperimentAssignment) { error("diagnostic storage unavailable") }
        }
        val vm = JournalViewModel(repository, settings, scheduler, SavedStateHandle(), unavailableTelemetry, dispatcher)
        assertFalse(vm.recordExposure(ExperimentAssignment()))
    }

    private fun note(id: String, title: String, project: String) =
        Note(id, project, title, "", 1000, 0, SyncState.PENDING)
}

private class FakeNotes : NoteRepository {
    val notes = MutableStateFlow<List<Note>>(emptyList())
    var saves = 0
    var failSave = false
    var failRead = false
    var failDelete = false
    var failProjects = false
    var staleSave = false
    var failRetry = false
    var lastDraft: NoteDraft? = null
    var lastDeleteId: String? = null
    var lastRetryId: String? = null
    var lastResolution: Pair<String, Boolean>? = null
    var syncResult: SyncResult = SyncResult.Success(0, 0)
    override fun observeNotes(): Flow<List<Note>> = flow {
        if (failRead) error("read unavailable")
        emitAll(notes)
    }
    override fun observeNote(id: String): Flow<Note?> = notes.map { list -> list.find { it.id == id } }
    override fun observeProjects(): Flow<List<Project>> = flow {
        if (failProjects) error("projects unavailable")
        emit(listOf(Project("field", "Fieldwork"), Project("study", "Study")))
    }
    override suspend fun save(draft: NoteDraft): String {
        if (failSave) error("storage unavailable")
        lastDraft = draft
        if (staleSave && draft.id != null) throw StaleNoteEdit()
        saves++
        return draft.id ?: "note-1"
    }
    override suspend fun delete(id: String) {
        lastDeleteId = id
        if (failDelete) error("storage unavailable")
    }
    override suspend fun resolveConflict(id: String, keepLocal: Boolean) { lastResolution = id to keepLocal }
    override suspend fun retryRejected(id: String) {
        lastRetryId = id
        if (failRetry) error("retry unavailable")
    }
    override suspend fun sync(): SyncResult = syncResult
}

private class FakePreferences : PreferencesRepository {
    override val preferences = MutableStateFlow(Preferences())
    override suspend fun setCompactCards(enabled: Boolean) { preferences.update { it.copy(compactCards = enabled) } }
    override suspend fun resetCompactCardsOverride() { preferences.update { it.copy(compactCards = false) } }
    override suspend fun setAnalyticsEnabled(enabled: Boolean) { preferences.update { it.copy(analyticsEnabled = enabled) } }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { preferences.update { it.copy(notificationsEnabled = enabled) } }
}
