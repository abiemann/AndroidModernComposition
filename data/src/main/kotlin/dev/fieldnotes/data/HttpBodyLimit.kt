package dev.fieldnotes.data

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal object HttpBodyLimits {
    const val SNAPSHOT_BYTES = 8L * 1024 * 1024
    const val OPERATION_BYTES = 64L * 1024
}

internal class ResponseTooLarge(val responseCode: Int) : IOException("HTTP response exceeded its byte budget")

/** An application interceptor sees decompressed bytes before Retrofit buffers or Gson parses them. */
internal class HttpBodyLimit : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val limit = if (chain.request().method == "GET" && response.isSuccessful)
            HttpBodyLimits.SNAPSHOT_BYTES else HttpBodyLimits.OPERATION_BYTES
        if (body.contentLength() > limit) {
            response.close()
            throw ResponseTooLarge(response.code)
        }
        val source = object : ForwardingSource(body.source()) {
            private var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, minOf(byteCount, limit - total + 1))
                if (count > 0) {
                    total += count
                    if (total > limit) {
                        super.close()
                        throw ResponseTooLarge(response.code)
                    }
                }
                return count
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    }
}
