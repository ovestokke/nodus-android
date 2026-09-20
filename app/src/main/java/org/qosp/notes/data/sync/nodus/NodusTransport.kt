package org.qosp.notes.data.sync.nodus

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Converter
import retrofit2.Retrofit
import okio.BufferedSink
import java.io.IOException
import java.lang.reflect.Type
import java.net.URI
import java.util.concurrent.TimeUnit

/** HTTPS authority only. Never accepts an API path, userinfo, query or fragment. */
internal class NodusOrigin private constructor(val url: HttpUrl) {
    fun contains(url: HttpUrl): Boolean = this.url.scheme == url.scheme &&
        this.url.host == url.host && this.url.port == url.port

    override fun equals(other: Any?) = other is NodusOrigin && url == other.url
    override fun hashCode() = url.hashCode()
    override fun toString() = url.toString()

    companion object {
        fun parse(value: String): NodusOrigin {
            try {
                require(value.none { it.isWhitespace() || it == '\\' || it.code < 32 || it.code == 127 })
                val uri = URI(value)
                require(uri.scheme == "https" && uri.rawAuthority != null)
                require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
                require(!uri.rawAuthority.contains('@') && !uri.rawAuthority.contains('%') && !uri.rawAuthority.endsWith(':'))
                val url = value.toHttpUrl()
                require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
                return NodusOrigin(url)
            } catch (_: Exception) {
                throw IllegalArgumentException("Expected an HTTPS root origin")
            }
        }
    }
}

internal class NodusConfiguration(
    val origin: NodusOrigin,
    internal val bearerToken: String,
    val credentialEpoch: String,
    val deviceId: String
) {
    init {
        require(bearerToken.isNotEmpty() && bearerToken.all { it.code in 33..126 }) { "Invalid bearer credential" }
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(deviceId)) { "Invalid device identity" }
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(credentialEpoch)) { "Invalid credential epoch" }
    }
    override fun toString() = "NodusConfiguration([REDACTED])"
}

/** No shared provider client, permissive TLS, cookie jar, logging, redirects or automatic retries. */
internal class NodusTransport(configuration: NodusConfiguration) {
    internal val client: OkHttpClient = client(configuration, false)
    val api: NodusApi = retrofit(configuration.origin, client)
    internal val binaryClient: OkHttpClient = client(configuration, true)
    val binaryApi: NodusApi = retrofit(configuration.origin, binaryClient)

    companion object {
        private fun client(configuration: NodusConfiguration, binary: Boolean) = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(if (binary) 600 else 30, TimeUnit.SECONDS)
            .writeTimeout(if (binary) 600 else 30, TimeUnit.SECONDS)
            .callTimeout(if (binary) 600 else 30, TimeUnit.SECONDS)
            .addInterceptor(OriginBearerInterceptor(configuration))
            .build()

        private fun retrofit(origin: NodusOrigin, client: OkHttpClient): NodusApi = Retrofit.Builder()
            .baseUrl(origin.url)
            .client(client)
            .addConverterFactory(NodusConverterFactory())
            .validateEagerly(true)
            .build()
            .create(NodusApi::class.java)
    }
}

internal class OriginBearerInterceptor(private val configuration: NodusConfiguration) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!configuration.origin.contains(request.url) || !request.url.encodedPath.startsWith("/api/v2/")) {
            throw IOException("Nodus request origin or API path rejected")
        }
        try {
            val segments = request.url.encodedPath.removePrefix("/api/v2/").split('/')
            require(segments.all { Regex("[A-Za-z0-9_-]{1,128}").matches(it) })
            for (name in request.url.queryParameterNames) {
                require(request.url.queryParameterValues(name).size == 1)
                val value = requireNotNull(request.url.queryParameter(name))
                when (name) {
                    "after", "until" -> requireDecimal(value)
                    "limit" -> require(value.toInt() in 1..100)
                    else -> throw IllegalArgumentException()
                }
            }
            for (name in listOf("X-Nodus-Device-Id", "X-Nodus-Request-Id")) {
                if (request.header(name) != null) {
                    require(request.headers.values(name).size == 1)
                    require(Regex("[A-Za-z0-9_-]{1,128}").matches(request.header(name)!!))
                }
            }
        } catch (_: Exception) {
            throw IOException("Invalid Nodus request parameters")
        }
        // Replace rather than append; cookie/session credentials never participate.
        val body = request.body?.let { source ->
            // OkHttp can follow 408/503 responses even with connection retry disabled.
            // One-shot bodies prevent every automatic write replay, including binary streams.
            object : RequestBody() {
                override fun contentType() = source.contentType()
                override fun contentLength() = source.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = source.writeTo(sink)
            }
        }
        return chain.proceed(request.newBuilder()
            .method(request.method, body)
            .removeHeader("Cookie")
            .removeHeader("Origin")
            .removeHeader("X-CSRF-Token")
            .header("Authorization", "Bearer ${configuration.bearerToken}")
            .build())
    }
    override fun toString() = "OriginBearerInterceptor([REDACTED])"
}

internal class NodusConverterFactory : Converter.Factory() {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: Type): KSerializer<Any> = serializer(type) as KSerializer<Any>

    override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit): Converter<ResponseBody, *> {
        val serializer = serializerFor(type)
        return Converter<ResponseBody, Any> { body ->
            body.use {
                try { NodusJson.decode(serializer, it.string()) }
                catch (_: Exception) { throw IOException("Invalid Nodus response") }
            }
        }
    }
    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<out Annotation>, methodAnnotations: Array<out Annotation>, retrofit: Retrofit): Converter<*, RequestBody> {
        val serializer = serializerFor(type)
        return Converter<Any, RequestBody> { value ->
            val bytes = NodusJson.encode(serializer, value).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 1_048_576) { "Nodus mutation exceeds wire limit" }
            bytes.toRequestBody("application/json".toMediaType())
        }
    }
}

/** Header strings stay decimal strings; callers own streaming bytes and digest verification. */
internal data class BlobDownloadMetadata(
    val size: String,
    val sha256: String,
    val contentRange: String?
) {
    companion object {
        fun from(headers: Headers): BlobDownloadMetadata {
            fun single(name: String): String {
                require(headers.values(name).size == 1) { "Missing or duplicate blob header" }
                return headers[name]!!
            }
            val size = single("Content-Length").also { requireDecimal(it) }
            val etag = single("ETag")
            require(Regex("\"sha256-[0-9a-f]{64}\"").matches(etag))
            require(single("Content-Type") == "application/octet-stream")
            require(single("Content-Disposition") == "attachment")
            require(single("X-Content-Type-Options") == "nosniff")
            require(single("Accept-Ranges") == "bytes")
            require(headers.values("Content-Range").size <= 1)
            val range = headers["Content-Range"]
            if (range != null) require(Regex("bytes [0-9]+-[0-9]+/[0-9]+").matches(range))
            return BlobDownloadMetadata(size, etag.substring(8, etag.length - 1), range)
        }
    }
}
