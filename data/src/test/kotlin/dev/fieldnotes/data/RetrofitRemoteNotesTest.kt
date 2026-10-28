package dev.fieldnotes.data

import dev.fieldnotes.domain.TokenStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RetrofitRemoteNotesTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RemoteNotes
    private val noteJson = """{"id":"n1","projectId":"field","title":"Oak","body":"Leaf","updatedAt":100,"version":1,"deleted":false}"""
    private val mutation = Mutation("op1", 0, "field", "Oak", "Leaf", 100, false)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        remote = RetrofitRemoteNotes.create(server.url("/").toString(), object : TokenStore {
            override fun read() = "test-token"
            override fun write(token: String?) = Unit
        })
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun snapshotUsesBearerAndParsesRelationships() = runTest {
        server.enqueue(MockResponse().setBody("""{"projects":[{"id":"field","name":"Field observations"}],"notes":[$noteJson]}"""))
        val result = remote.snapshot()
        assertEquals("Oak", result.notes.single().title)
        val request = server.takeRequest()
        assertEquals("/v1/notes", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test fun putCarriesStableOperationAndExpectedVersion() = runTest {
        server.enqueue(MockResponse().setBody(noteJson))
        assertEquals(1L, (remote.push("n1", mutation) as PushResult.Accepted).note.version)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/notes/n1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"operationId\":\"op1\""))
        assertTrue(body.contains("\"baseVersion\":0"))
    }

    @Test fun conflictReturnsCanonicalServerCopy() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"current":$noteJson}"""))
        val result = remote.push("n1", mutation) as PushResult.Conflict
        assertEquals("Leaf", result.current.body)
    }

    @Test fun authenticationIsDistinctFromTransientFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try { remote.snapshot(); fail("Expected authentication failure") } catch (_: AuthenticationFailure) { }
        server.enqueue(MockResponse().setResponseCode(503))
        try { remote.snapshot(); fail("Expected retryable failure") } catch (_: IOException) { }
    }

    @Test fun malformedAndUnrelatedResponsesAreRejected() = runTest {
        server.enqueue(MockResponse().setBody("""{"projects":[],"notes":[$noteJson]}"""))
        try { remote.snapshot(); fail("Unknown project must fail") } catch (_: RejectedRequest) { }
        server.enqueue(MockResponse().setBody(noteJson.replace("n1", "other")))
        try { remote.push("n1", mutation); fail("Wrong id must fail") } catch (_: RejectedRequest) { }
        server.enqueue(MockResponse().setBody("""{"projects":null,"notes":null}"""))
        try { remote.snapshot(); fail("Null collections must fail") } catch (_: RejectedRequest) { }
    }

    @Test fun redirectsDoNotForwardCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        try { remote.snapshot(); fail("Redirect must be rejected") } catch (_: RejectedRequest) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun nullCollectionElementsAndConflictBodiesAreRejected() = runTest {
        val invalidSnapshots = listOf(
            """{"projects":[null],"notes":[]}""",
            """{"projects":[{"id":"field","name":"Field"}],"notes":[null]}""",
            """{"projects":[{}],"notes":[]}""",
            """{"projects":[{"id":"field","name":"Field"}],"notes":[{}]}""",
        )
        invalidSnapshots.forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            try { remote.snapshot(); fail("Invalid snapshot must be rejected: $body") } catch (_: RejectedRequest) { }
        }
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"current":null}"""))
        try { remote.push("n1", mutation); fail("Null conflict must be rejected") } catch (_: RejectedRequest) { }
    }
}
