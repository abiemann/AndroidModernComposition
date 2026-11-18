package dev.fieldnotes.data

import dev.fieldnotes.domain.TokenStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
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
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
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
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
    }

    @Test fun definitiveRejectionRequiresSemanticStatusAndMatchingOperationId() = runTest {
        listOf(400, 404).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("""{"error":"Invalid mutation","code":"mutation_rejected","operationId":"op1"}"""))
            assertEquals(PushResult.Rejected, remote.push("n1", mutation))
        }
    }

    @Test fun unboundAndFramingFailuresDoNotAuthorizeDiscardingTheOperation() = runTest {
        val unboundResponses = listOf(
            400 to """{"error":"Invalid mutation"}""",
            400 to """{"code":"mutation_rejected"}""",
            400 to """{"code":"mutation_rejected","operationId":"different-operation"}""",
            404 to """{"error":"Unknown endpoint"}""",
            400 to "{",
            411 to """{"code":"mutation_rejected","operationId":"op1"}""",
            413 to """{"code":"mutation_rejected","operationId":"op1"}""",
            415 to """{"code":"mutation_rejected","operationId":"op1"}""",
        )
        unboundResponses.forEach { (status, body) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        }
    }

    @Test fun malformedSuccessAndConflictRemainAmbiguousDespiteARejectionMarker() = runTest {
        listOf(200, 409).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("""{"code":"mutation_rejected","operationId":"op1"}"""))
            assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        }
    }

    @Test fun authenticationAndTransientErrorsCannotQuarantineAMutation() = runTest {
        val body = """{"code":"mutation_rejected","operationId":"op1"}"""
        server.enqueue(MockResponse().setResponseCode(401).setBody(body))
        try { remote.push("n1", mutation); fail("Authentication failure must remain global") } catch (_: AuthenticationFailure) { }
        server.enqueue(MockResponse().setResponseCode(503).setBody(body))
        try { remote.push("n1", mutation); fail("Transient failure must remain retryable") } catch (_: IOException) { }
    }

    @Test fun declaredOversizedBodiesAreRejectedBeforeReadingAndFollowingRequestsStillWork() = runTest {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Length", HttpBodyLimits.SNAPSHOT_BYTES + 1))
        try { remote.snapshot(); fail("Declared snapshot bound must be enforced") } catch (_: RejectedRequest) { }
        server.enqueue(MockResponse().setBody(noteJson).setHeader("Content-Length", HttpBodyLimits.OPERATION_BYTES + 1))
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        server.enqueue(MockResponse().setBody(noteJson))
        assertTrue(remote.push("n1", mutation) is PushResult.Accepted)
    }

    @Test fun chunkedAndCompressedBodiesAreBoundedByDecodedBytes() = runTest {
        val oversized = noteJson + " ".repeat(HttpBodyLimits.OPERATION_BYTES.toInt())
        server.enqueue(MockResponse().setChunkedBody(oversized, 2048))
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(oversized.toByteArray()) }
        }.toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(compressed)).setHeader("Content-Encoding", "gzip"))
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        server.enqueue(MockResponse().setResponseCode(409).setChunkedBody(oversized, 2048))
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        server.enqueue(MockResponse().setResponseCode(401).setBody(oversized))
        try { remote.push("n1", mutation); fail("Body limits must preserve authentication status") }
        catch (_: AuthenticationFailure) { }
    }

    @Test fun snapshotLimitsNoteCountEvenWhenTheBodyFitsInTheByteBudget() = runTest {
        val notes = (1..10_001).map { noteJson.replace("\"n1\"", "\"n$it\"") }
        fun snapshotBody(count: Int) =
            """{"projects":[{"id":"field","name":"Field"}],"notes":[${notes.take(count).joinToString(",")}]}"""
        server.enqueue(MockResponse().setBody(snapshotBody(10_000)))
        assertEquals(10_000, remote.snapshot().notes.size)
        val oversized = snapshotBody(10_001)
        assertTrue(oversized.length.toLong() < HttpBodyLimits.SNAPSHOT_BYTES)
        server.enqueue(MockResponse().setBody(oversized))
        try { remote.snapshot(); fail("Snapshot row count must be enforced") } catch (_: RejectedRequest) { }
    }

    @Test fun malformedBufferedJsonDefersButTruncatedNetworkBodyRemainsRetryable() = runTest {
        server.enqueue(MockResponse().setBody("{\"id\":\"unterminated"))
        assertEquals(PushResult.Deferred, remote.push("n1", mutation))
        server.enqueue(MockResponse().setBody(noteJson).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try { remote.push("n1", mutation); fail("A transport truncation must remain a global retry") }
        catch (_: IOException) { }
    }
}
