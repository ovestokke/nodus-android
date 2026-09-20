package org.qosp.notes.data.sync.nodus

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.qosp.notes.data.sync.nodus.engine.NodusHttpPairingTransport
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

class NodusTransportTest {
    @Test fun httpsRootOriginRejectsUserinfoApiPathsQueriesFragmentsAndNormalizations() {
        for (value in listOf("https://notes.example", "https://notes.example/", "https://notes.example:8443", "https://[::1]:8443/")) {
            assertTrue(NodusOrigin.parse(value).url.isHttps)
        }
        for (value in listOf("http://notes.example", "https://u:p@notes.example", "https://@notes.example", "https://notes.example/api/v2", "https://notes.example//", "https://notes.example/a/..", "https://notes.example/%2e", "https://notes.example?", "https://notes.example#", " https://notes.example", "https://notes.example\n", "https:\\notes.example", "https:///notes.example", "https://notes.example:", "https://notes.example:0", "https://notes.example:65536", "https://notes.example/%2f", "https://notes%2eexample")) {
            val error = assertThrows(Exception::class.java) { NodusOrigin.parse(value) }
            assertFalse(error.message!!.contains(value))
        }
        val root = NodusOrigin.parse("https://notes.example")
        assertEquals(root, NodusOrigin.parse("https://notes.example:443/"))
    }

    private fun withTls(block: (MockWebServer, NodusTransport, OkHttpClient, NodusApi) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            server.start()
            val configuration = NodusConfiguration(NodusOrigin.parse(server.url("/").toString()), "secret-test-token", "epoch", "device")
            val transport = NodusTransport(configuration)
            val client = transport.client.newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val api = Retrofit.Builder().baseUrl(configuration.origin.url).client(client)
                .addConverterFactory(NodusConverterFactory()).validateEagerly(true).build().create(NodusApi::class.java)
            assertFalse(configuration.toString().contains("secret-test-token"))
            block(server, transport, client, api)
        }
    }

    @Test fun redirectsNeverFollowAndBearerNeverCrossesOrigin() = withTls { server, transport, client, _ ->
        assertFalse(transport.client.followRedirects)
        assertFalse(transport.client.followSslRedirects)
        assertFalse(transport.client.retryOnConnectionFailure)
        assertEquals(0, transport.client.networkInterceptors.size)
        assertEquals(1, transport.client.interceptors.size)
        for (code in listOf(301,302,303,307,308)) {
            server.enqueue(MockResponse.Builder().code(code).addHeader("Location", "https://other.example/api/v2/capabilities").build())
            client.newCall(Request.Builder().url(server.url("/api/v2/capabilities"))
                .header("Cookie", "session=secret").header("Authorization", "Bearer wrong")
                .header("X-CSRF-Token", "csrf-secret").build()).execute().use { assertEquals(code, it.code) }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer secret-test-token", request.headers["Authorization"])
            assertEquals(1, request.headers.values("Authorization").size)
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["X-CSRF-Token"])
        }
        for (url in listOf("https://other.example/api/v2/capabilities", server.url("/").newBuilder().port(server.port + 1).build().toString() + "api/v2/capabilities", server.url("/auth/login").toString(), server.url("/api/v2/capabilities").toString().replace("https:", "http:"))) {
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(url).build()).execute() }
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun exactRetrofitJsonBinaryHeadersAndNoAutomaticWriteReplay() = withTls { server, _, client, api ->
        runTest {
            val receipt = """{"resourceType":"note","resourceId":"n","revision":"2"}"""
            server.enqueue(MockResponse.Builder().body(receipt).build())
            assertEquals("2", api.editNote("n", V2EditNote("d", "r", "1", notebookId = WireField.Present(null))).body()!!.revision)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PATCH", request.method)
            assertEquals("/api/v2/notes/n", request.url.encodedPath)
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals("""{"deviceId":"d","requestId":"r","expectedRevision":"1","notebookId":null}""", request.body!!.utf8())
            server.enqueue(MockResponse.Builder().body(receipt).build())
            api.deleteItem("n", "i", V2ChildDelete("d", "delete", "1"))
            assertEquals("DELETE", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            server.enqueue(MockResponse.Builder().body("""{"resourceType":"blob","resourceId":"b","revision":"3","state":"ready"}""").build())
            api.uploadBlob("b", "d", "upload", byteArrayOf(0, 1, -1).toRequestBody("application/octet-stream".toMediaType()))
            val upload = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("application/octet-stream", upload.headers["Content-Type"])
            assertEquals("d", upload.headers["X-Nodus-Device-Id"])
            assertEquals("upload", upload.headers["X-Nodus-Request-Id"])
            assertArrayEquals(byteArrayOf(0, 1, -1), upload.body!!.toByteArray())
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("""{"error":"unavailable"}""").build())
            val result = api.trash("n", V2Trash("d", "uncertain", "2"))
            assertEquals(503, result.code())
            assertEquals(4, server.requestCount)
            result.errorBody()!!.close()
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(MockResponse.Builder().code(408).body("timeout").build())
            val timedOut = api.restore("n", V2Lifecycle("d", "timed-out", "2"))
            assertEquals(408, timedOut.code())
            timedOut.errorBody()!!.close()
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(MockResponse.Builder().code(206).body("bytes").addHeader("Content-Range", "bytes 1-5/10").build())
            val download = api.downloadBlob("b", "bytes=1-5")
            assertEquals(206, download.code())
            assertEquals("bytes", download.body()!!.use { it.string() })
            assertEquals("bytes=1-5", server.takeRequest(2, TimeUnit.SECONDS)!!.headers["Range"])
            server.enqueue(MockResponse.Builder().addHeader("Content-Length", "10").build())
            val head = api.headBlob("b")
            assertEquals(200, head.code())
            assertEquals("10", head.headers()["Content-Length"])
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            server.enqueue(MockResponse.Builder().body(NodusFixtures.capabilities).build())
            assertEquals("2.0", api.getCapabilities().body()!!.contractVersion)
            server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals(8, server.requestCount)
            assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { api.getChanges(after = "01") } }
            assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { api.getNote("n/other") } }
            assertEquals(8, server.requestCount)
            assertFalse(client.interceptors.any { it.javaClass.name.contains("logging", ignoreCase = true) })
        }
    }

    @Test fun pairingRedemptionIsUnauthenticatedExactNoStoreAndNeverFollowsRedirects() {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory());server.start()
            val client=OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(),clientCertificates.trustManager)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
            val transport=NodusHttpPairingTransport(client)
            val secret="A".repeat(43)
            val input=PairingRedeemInput("23456-789AB","device","request",secret)
            server.enqueue(MockResponse.Builder().addHeader("Cache-Control","no-store").body("""{"credential":"token.$secret","tokenId":"token"}""").build())
            val result=kotlinx.coroutines.runBlocking { transport.redeem(NodusOrigin.parse(server.url("/").toString()),input) }
            assertEquals(200,result.status)
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("POST",request.method);assertEquals("/auth/pairings/redeem",request.url.encodedPath)
            assertNull(request.headers["Authorization"]);assertNull(request.headers["Cookie"]);assertNull(request.headers["Origin"]);assertNull(request.headers["X-CSRF-Token"])
            assertEquals(NodusJson.encode(PairingRedeemInput.serializer(),input),request.body!!.utf8())
            server.enqueue(MockResponse.Builder().code(307).addHeader("Cache-Control","no-store").addHeader("Location","https://other.example/auth/pairings/redeem").build())
            assertEquals(307,kotlinx.coroutines.runBlocking { transport.redeem(NodusOrigin.parse(server.url("/").toString()),input) }.status)
            server.takeRequest(2,TimeUnit.SECONDS)
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun blobHeadersStayStringsAndEnforceSafeRepresentation() {
        val headers = Headers.Builder()
            .add("Content-Length", "536870912")
            .add("ETag", "\"sha256-${"a".repeat(64)}\"")
            .add("Content-Type", "application/octet-stream")
            .add("Content-Disposition", "attachment")
            .add("X-Content-Type-Options", "nosniff")
            .add("Accept-Ranges", "bytes").build()
        val meta = BlobDownloadMetadata.from(headers)
        assertEquals("536870912", meta.size)
        assertEquals("a".repeat(64), meta.sha256)
        assertThrows(Exception::class.java) { BlobDownloadMetadata.from(headers.newBuilder().set("Content-Type", "text/html").build()) }
        assertThrows(Exception::class.java) { BlobDownloadMetadata.from(headers.newBuilder().add("Content-Length", "0").build()) }
    }
}
