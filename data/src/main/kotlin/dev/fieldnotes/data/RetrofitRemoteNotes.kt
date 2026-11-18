package dev.fieldnotes.data

import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.fieldnotes.domain.Project
import dev.fieldnotes.domain.TokenStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

interface NotesApi {
    @GET("v1/notes") suspend fun snapshot(): Response<ResponseBody>
    @PUT("v1/notes/{id}") suspend fun push(@Path("id") id: String, @Body mutation: Mutation): Response<ResponseBody>
}

internal data class ConflictEnvelope(val current: RemoteNote)
internal data class MutationFailureEnvelope(val code: String?, val operationId: String?)
private class InvalidRemotePayload : Exception()

class RetrofitRemoteNotes(private val api: NotesApi) : RemoteNotes {
    override suspend fun snapshot(): Snapshot = withContext(Dispatchers.Default) {
        val response = try { api.snapshot() } catch (large: ResponseTooLarge) {
            checkGlobalFailure(large.responseCode)
            throw RejectedRequest()
        }
        try {
            checkGlobalFailure(response.code())
            if (!response.isSuccessful) throw RejectedRequest()
            val result = decodeSnapshot(response.body())
            // Gson can populate nullable fields inside objects regardless of Kotlin signatures.
            if (result.projects.any { !NoteJson.validId(it.id) || it.name.isNullOrBlank() || it.name.length > 256 })
                throw RejectedRequest()
            val projects = result.projects.map { it.id }.toSet()
            if (result.notes.any { !NoteJson.valid(it) || it.projectId !in projects }) throw RejectedRequest()
            if (projects.size != result.projects.size || result.notes.map { it.id }.toSet().size != result.notes.size)
                throw RejectedRequest()
            result
        } catch (_: InvalidRemotePayload) {
            throw RejectedRequest()
        } catch (_: ResponseTooLarge) {
            throw RejectedRequest()
        } finally {
            response.body()?.close(); response.errorBody()?.close()
        }
    }

    override suspend fun push(id: String, mutation: Mutation): PushResult {
        val response = try { api.push(id, mutation) } catch (large: ResponseTooLarge) {
            checkGlobalFailure(large.responseCode)
            return PushResult.Deferred
        }
        try {
            checkGlobalFailure(response.code())
            if (response.code() == 409) {
                val current = decode(response.errorBody(), ConflictEnvelope::class.java).current
                return if (NoteJson.valid(current) && current.id == id) PushResult.Conflict(current) else PushResult.Deferred
            }
            if (response.code() == 400 || response.code() == 404) {
                val rejection = decode(response.errorBody(), MutationFailureEnvelope::class.java)
                return if (rejection.code == "mutation_rejected" && rejection.operationId == mutation.operationId)
                    PushResult.Rejected else PushResult.Deferred
            }
            // An unbound response to this operation cannot establish its earlier commit outcome.
            if (response.code() in 400..499) return PushResult.Deferred
            if (!response.isSuccessful) throw RejectedRequest()
            val note = decode(response.body(), RemoteNote::class.java)
            return if (NoteJson.valid(note) && note.id == id && note.version > mutation.baseVersion &&
                note.projectId == mutation.projectId && note.deleted == mutation.deleted) PushResult.Accepted(note)
            else PushResult.Deferred
        } catch (_: InvalidRemotePayload) {
            return PushResult.Deferred
        } catch (_: ResponseTooLarge) {
            return PushResult.Deferred
        } finally {
            response.body()?.close(); response.errorBody()?.close()
        }
    }

    private fun checkGlobalFailure(code: Int) {
        when {
            code == 401 || code == 403 -> throw AuthenticationFailure()
            code == 408 || code == 429 || code in 500..599 -> throw IOException("Transient server response")
        }
    }

    private suspend fun decodeSnapshot(body: ResponseBody?): Snapshot {
        if (body == null) throw InvalidRemotePayload()
        try {
            val reader = NoteJson.gson.newJsonReader(body.charStream())
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw InvalidRemotePayload()
            reader.beginObject()
            var projects: List<Project>? = null
            var notes: List<RemoteNote>? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "projects" -> {
                        if (projects != null) throw InvalidRemotePayload()
                        projects = reader.readArray(Project::class.java, limit = 1_000)
                    }
                    "notes" -> {
                        if (notes != null) throw InvalidRemotePayload()
                        notes = reader.readArray(RemoteNote::class.java, limit = 10_000)
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT || projects == null || notes == null) {
                throw InvalidRemotePayload()
            }
            return Snapshot(projects, notes)
        } catch (_: JsonParseException) {
            throw InvalidRemotePayload()
        } catch (_: IOException) {
            // Retrofit has already buffered the bounded transport body.
            throw InvalidRemotePayload()
        }
    }

    private suspend fun <T : Any> JsonReader.readArray(type: Class<T>, limit: Int): List<T> {
        if (peek() != JsonToken.BEGIN_ARRAY) throw InvalidRemotePayload()
        beginArray()
        val items = ArrayList<T>()
        val adapter = NoteJson.gson.getAdapter(type)
        val context = currentCoroutineContext()
        while (hasNext()) {
            // Cap the number of allocated DTOs as well as the decoded HTTP bytes.
            if (items.size == limit) throw InvalidRemotePayload()
            if (items.size % 128 == 0) context.ensureActive()
            items += adapter.read(this) ?: throw InvalidRemotePayload()
        }
        endArray()
        return items
    }

    private suspend fun <T> decode(body: ResponseBody?, type: Class<T>): T = withContext(Dispatchers.Default) {
        if (body == null) throw InvalidRemotePayload()
        try {
            body.use { NoteJson.gson.fromJson(it.charStream(), type) ?: throw InvalidRemotePayload() }
        } catch (_: JsonParseException) {
            // Retrofit already buffered the bounded body on its call thread. A JSON reader's
            // MalformedJsonException may extend IOException, but is not a socket failure here.
            throw InvalidRemotePayload()
        }
    }

    companion object {
        fun create(baseUrl: String, tokens: TokenStore): RetrofitRemoteNotes {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor(HttpBodyLimit())
                .addInterceptor { chain ->
                    val token = tokens.read()
                    val request = chain.request().newBuilder().apply {
                        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                    }.build()
                    chain.proceed(request)
                }
                .build()
            return RetrofitRemoteNotes(Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(GsonConverterFactory.create(NoteJson.gson)).build().create(NotesApi::class.java))
        }
    }
}
