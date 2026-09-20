package org.qosp.notes.data.sync.nodus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.NodusOutbox

internal class NodusHttpResult(val status: Int, val body: ByteArray, val retryAfter: String? = null) {
    override fun toString() = "NodusHttpResult($status, [REDACTED])"
}

/** Fakeable boundary; no ambient credential or provider singleton. */
internal interface NodusEngineTransport {
    suspend fun get(configuration: NodusConfiguration, path: String, query: Map<String, String> = emptyMap()): NodusHttpResult
    suspend fun send(configuration: NodusConfiguration, operation: NodusOutbox): NodusHttpResult
}

internal class NodusHttpEngineTransport : NodusEngineTransport {
    override suspend fun get(configuration: NodusConfiguration, path: String, query: Map<String, String>): NodusHttpResult = execute(configuration, path, query, null)
    override suspend fun send(configuration: NodusConfiguration, operation: NodusOutbox): NodusHttpResult {
        require(operation.contentType == "application/json" && operation.apiVersion == "v2")
        require(operation.credentialEpoch == configuration.credentialEpoch && operation.deviceId == configuration.deviceId)
        return execute(configuration, operation.path, emptyMap(), operation)
    }
    private suspend fun execute(configuration: NodusConfiguration, path: String, query: Map<String, String>, operation: NodusOutbox?): NodusHttpResult = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v2/") && '?' !in path && '#' !in path)
        val url = configuration.origin.url.newBuilder().encodedPath(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).apply {
            if (operation != null) method(operation.method, requireNotNull(operation.body).toRequestBody("application/json".toMediaType()))
        }.build()
        // A credential-bound client per call cannot accidentally reuse a rotated bearer.
        NodusTransport(configuration).client.newCall(request).execute().use { response ->
            val body = response.body
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= MAX_RESPONSE_BYTES) { "Nodus response exceeds bounded page size" }
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
            NodusHttpResult(response.code, bytes, response.header("Retry-After"))
        }
    }
    private companion object { const val MAX_RESPONSE_BYTES = 110L * 1024 * 1024 }
}

internal object NodusConnectionLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    fun get(connectionId: String) = locks.getOrPut(connectionId) { kotlinx.coroutines.sync.Mutex() }
}
