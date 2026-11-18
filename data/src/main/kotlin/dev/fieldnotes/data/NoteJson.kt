package dev.fieldnotes.data

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.security.MessageDigest

/** One stateless codec shared by transport and the local simulator. */
internal object NoteJson {
    val gson = Gson()
    private val identifier = Regex("[A-Za-z0-9_-]{1,100}")

    fun validId(value: String?) = value != null && identifier.matches(value)

    @Suppress("SENSELESS_COMPARISON") // Gson bypasses Kotlin field nullability.
    fun valid(note: RemoteNote?) = note != null && validId(note.id) && validId(note.projectId) &&
        !note.title.isNullOrBlank() && note.title.length <= 120 && note.body != null && note.body.length <= 10_000 &&
        note.version > 0 && note.updatedAt >= 0

    fun decodeNote(json: String): RemoteNote? = try {
        gson.fromJson(json, RemoteNote::class.java)?.takeIf(::valid)
    } catch (_: JsonParseException) { null }

    fun requestJson(id: String, mutation: Mutation): String = gson.toJson(mapOf("id" to id, "mutation" to mutation))

    fun matchesHistoricalRequest(stored: String, requestJson: String): Boolean = try {
        JsonParser.parseString(stored) == JsonParser.parseString(requestJson)
    } catch (_: JsonParseException) { false }

    fun fingerprint(id: String, mutation: Mutation): String {
        // Scalar array order is part of the digest version and independent of reflection order.
        val requestJson = gson.toJson(listOf(id, mutation.operationId, mutation.baseVersion,
            mutation.projectId, mutation.title, mutation.body, mutation.updatedAt, mutation.deleted))
        val bytes = MessageDigest.getInstance("SHA-256").digest(requestJson.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(71) {
            append("sha256:")
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
    }
}
