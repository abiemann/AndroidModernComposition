package dev.fieldnotes.data

import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.fieldnotes.domain.TokenStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

interface NotesApi {
    @GET("v1/notes") suspend fun snapshot(): Response<Snapshot>
    @PUT("v1/notes/{id}") suspend fun push(@Path("id") id: String, @Body mutation: Mutation): Response<RemoteNote>
}

internal data class ConflictEnvelope(val current: RemoteNote)
internal data class MutationFailureEnvelope(val code: String?, val operationId: String?)

class RetrofitRemoteNotes(private val api: NotesApi) : RemoteNotes {
    private val gson = Gson()

    override suspend fun snapshot(): Snapshot {
        val response = parseResponse { api.snapshot() }
        checkStatus(response.code())
        val result = response.body() ?: throw RejectedRequest()
        // Gson does not enforce Kotlin nullability, so validate the trust boundary explicitly.
        if (result.projects == null || result.notes == null || result.projects.size > 1_000 || result.notes.size > 10_000) {
            throw RejectedRequest()
        }
        if (result.projects.any { it == null || it.id.isNullOrBlank() || it.name.isNullOrBlank() }) throw RejectedRequest()
        val projects = result.projects.map { it.id }.toSet()
        if (result.notes.any { !valid(it) || it.projectId !in projects }) throw RejectedRequest()
        if (projects.size != result.projects.size || result.notes.map { it.id }.toSet().size != result.notes.size) {
            throw RejectedRequest()
        }
        return result
    }

    override suspend fun push(id: String, mutation: Mutation): PushResult {
        val response = parseResponse { api.push(id, mutation) }
        if (response.code() == 409) {
            val current = try {
                response.errorBody()?.use { gson.fromJson(it.charStream(), ConflictEnvelope::class.java)?.current }
            } catch (_: JsonParseException) { null }
            if (current == null || !valid(current) || current.id != id) throw RejectedRequest()
            return PushResult.Conflict(current)
        }
        if (response.code() == 400 || response.code() == 404) {
            val rejection = try {
                response.errorBody()?.use { gson.fromJson(it.charStream(), MutationFailureEnvelope::class.java) }
            } catch (_: JsonParseException) { null }
            // Status alone is not proof: parsing/framing failures may follow a previously
            // committed attempt. Only the server's operation-bound semantic rejection is safe.
            if (rejection?.code == "mutation_rejected" && rejection.operationId == mutation.operationId) {
                return PushResult.Rejected
            }
        }
        checkStatus(response.code())
        val note = response.body() ?: throw RejectedRequest()
        if (!valid(note) || note.id != id || note.version <= mutation.baseVersion ||
            note.projectId != mutation.projectId || note.deleted != mutation.deleted) throw RejectedRequest()
        return PushResult.Accepted(note)
    }

    private fun checkStatus(code: Int) {
        when {
            code == 401 || code == 403 -> throw AuthenticationFailure()
            code == 408 || code == 429 || code in 500..599 -> throw IOException("Transient server response")
            code !in 200..299 -> throw RejectedRequest()
        }
    }

    private suspend fun <T> parseResponse(block: suspend () -> T): T = try {
        block()
    } catch (_: JsonParseException) {
        throw RejectedRequest()
    }

    private fun valid(note: RemoteNote?) = note != null && !note.id.isNullOrBlank() && !note.projectId.isNullOrBlank() &&
        !note.title.isNullOrBlank() && note.title.length <= 120 && note.body != null && note.body.length <= 10_000 &&
        note.version > 0 && note.updatedAt >= 0

    companion object {
        fun create(baseUrl: String, tokens: TokenStore): RetrofitRemoteNotes {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                // Explicit retry belongs to the durable queue; HTTP redirects must not leak credentials.
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val token = tokens.read()
                    val request = chain.request().newBuilder().apply {
                        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                    }.build()
                    chain.proceed(request)
                }
                .build()
            return RetrofitRemoteNotes(Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(GsonConverterFactory.create()).build().create(NotesApi::class.java))
        }
    }
}
