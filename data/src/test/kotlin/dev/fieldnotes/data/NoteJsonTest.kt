package dev.fieldnotes.data

import org.junit.Assert.*
import org.junit.Test

class NoteJsonTest {
    private val mutation = Mutation("operation", 2, "field", "Oak", "Leaf", 100, false)

    @Test fun fingerprintCoversEveryRequestFieldAndIsStable() {
        val original = NoteJson.fingerprint("note", mutation)
        assertEquals(original, NoteJson.fingerprint("note", mutation.copy()))
        assertEquals(71, original.length)
        assertNotEquals(original, NoteJson.fingerprint("other-note", mutation))
        listOf(
            mutation.copy(operationId = "other-operation"), mutation.copy(baseVersion = 3),
            mutation.copy(projectId = "study"), mutation.copy(title = "Maple"),
            mutation.copy(body = "Branch"), mutation.copy(updatedAt = 101), mutation.copy(deleted = true),
        ).forEach { assertNotEquals(original, NoteJson.fingerprint("note", it)) }
    }

    @Test fun legacyReceiptMatchingIgnoresObjectFieldOrderButPreservesPayloadIdentity() {
        val reordered = """{"mutation":{"deleted":false,"updatedAt":100,"body":"Leaf","title":"Oak","projectId":"field","baseVersion":2,"operationId":"operation"},"id":"note"}"""
        assertTrue(NoteJson.matchesHistoricalRequest(reordered, NoteJson.requestJson("note", mutation)))
        assertFalse(NoteJson.matchesHistoricalRequest(reordered, NoteJson.requestJson("note", mutation.copy(body = "Changed"))))
        assertFalse(NoteJson.matchesHistoricalRequest("{", NoteJson.requestJson("note", mutation)))
    }

    @Test fun invalidStoredNoteJsonReturnsNoUsableConflictCopy() {
        listOf("null", "{", "{}", "[]", "\"text\"").forEach { assertNull(NoteJson.decodeNote(it)) }
    }
}
