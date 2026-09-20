package org.qosp.notes.data.sync.nodus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.NodusOutbox
import java.io.Closeable
import java.io.File
import java.io.InputStream

internal class NodusDownload(val status: Int, val length: String?, val mediaType: String?, val disposition: String?,
    val encoding: String?, val stream: InputStream, private val closeResponse: () -> Unit = {}
) : Closeable {
    override fun close() { try { stream.close() } finally { closeResponse() } }
}

internal interface NodusBinaryTransport {
    suspend fun upload(configuration: NodusConfiguration, operation: NodusOutbox, source: File): NodusHttpResult
    suspend fun download(configuration: NodusConfiguration, blobId: String): NodusDownload
}

/** No Range requests, redirects, ambient cookies or automatic write replay. */
internal class NodusHttpBinaryTransport(private val client: (NodusConfiguration) -> okhttp3.OkHttpClient = { NodusTransport(it).binaryClient }) : NodusBinaryTransport {
    override suspend fun upload(configuration: NodusConfiguration, operation: NodusOutbox, source: File): NodusHttpResult = withContext(Dispatchers.IO) {
        require(operation.credentialEpoch == configuration.credentialEpoch && operation.deviceId == configuration.deviceId)
        require(operation.method == "PUT" && operation.contentType == "application/octet-stream")
        require(Regex("/api/v2/blobs/[A-Za-z0-9_-]{1,128}/content").matches(operation.path))
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = requireNotNull(operation.sourceSize).toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var total = 0L
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count; require(total <= contentLength())
                    hash.update(buffer, 0, count)
                    sink.write(buffer, 0, count)
                }
                require(total == contentLength())
                require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == operation.sourceSha256) { "source_hash_mismatch" }
            } }
        }
        val request = Request.Builder().url(configuration.origin.url.newBuilder().encodedPath(operation.path).build())
            .header("X-Nodus-Device-Id", operation.deviceId).header("X-Nodus-Request-Id", operation.requestId).put(body).build()
        client(configuration).newCall(request).execute().use { response ->
            val bytes = response.body.byteStream().use { it.readBytesBounded(1048576) }
            NodusHttpResult(response.code, bytes, response.header("Retry-After"))
        }
    }
    override suspend fun download(configuration: NodusConfiguration, blobId: String): NodusDownload = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(blobId))
        val request = Request.Builder().url(configuration.origin.url.newBuilder().encodedPath("/api/v2/blobs/$blobId/content").build())
            .header("Accept-Encoding", "identity").get().build()
        val response = client(configuration).newCall(request).execute()
        NodusDownload(response.code, response.header("Content-Length"), response.header("Content-Type"),
            response.header("Content-Disposition"), response.header("Content-Encoding"), response.body.byteStream()) { response.close() }
    }
}

private fun InputStream.readBytesBounded(limit: Int): ByteArray = java.io.ByteArrayOutputStream().use { output ->
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    output.toByteArray()
}
