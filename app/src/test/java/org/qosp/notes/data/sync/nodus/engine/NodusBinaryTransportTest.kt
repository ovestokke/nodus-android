package org.qosp.notes.data.sync.nodus.engine

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NodusBinaryTransportTest {
    private fun tls(block:(MockWebServer,NodusConfiguration,NodusHttpBinaryTransport)->Unit) {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory()); server.start()
            val config=NodusConfiguration(NodusOrigin.parse(server.url("/").toString()),"synthetic-secret","epoch","device")
            val transport=NodusHttpBinaryTransport { current -> NodusTransport(current).binaryClient.newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager).build() }
            block(server,config,transport)
        }
    }
    private fun operation(payload:ByteArray)=NodusOutbox("c","upload","intent","blob",1,"v2","PUT","/api/v2/blobs/b/content","application/octet-stream",null,
        "00000000-0000-0000-0000-000000000000",MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it.toInt() and 255)},payload.size.toString(),"device","request","epoch",NodusOutboxState.SENT)

    @Test fun wholeByteUploadHasBoundCredentialsHeadersLengthAndNoAutomaticReplay()=tls { server,config,transport -> runBlocking {
        val payload=byteArrayOf(0,1,-1,60,62)
        val file=Files.createTempFile("nodus-upload",".bin").toFile()
        try {
            file.writeBytes(payload)
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After","0").body("""{"error":"unavailable"}""").build())
            assertEquals(503,transport.upload(config,operation(payload),file).status)
            val sent=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertArrayEquals(payload,sent.body!!.toByteArray())
            assertEquals("Bearer synthetic-secret",sent.headers["Authorization"])
            assertEquals("device",sent.headers["X-Nodus-Device-Id"])
            assertEquals("request",sent.headers["X-Nodus-Request-Id"])
            assertEquals("application/octet-stream",sent.headers["Content-Type"])
            assertEquals(payload.size.toString(),sent.headers["Content-Length"])
            assertNull(sent.headers["Cookie"]); assertNull(sent.headers["Range"])
            assertEquals(1,server.requestCount)
            assertThrows(Exception::class.java){runBlocking {transport.upload(NodusConfiguration(config.origin,"new-token","new-epoch","device"),operation(payload),file)}}
            assertEquals(1,server.requestCount)
        } finally {file.delete()}
    } }

    @Test fun authenticatedDownloadNeverFollowsRedirectOrUsesRemoteFilenameAsAPath()=tls { server,config,transport -> runBlocking {
        for(code in listOf(301,302,303,307,308)) {
            server.enqueue(MockResponse.Builder().code(code).addHeader("Location","https://other.example/private").addHeader("Content-Disposition","attachment; filename=../../active.html").build())
            transport.download(config,"b").use { assertEquals(code,it.status) }
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("Bearer synthetic-secret",request.headers["Authorization"])
            assertEquals("identity",request.headers["Accept-Encoding"])
            assertNull(request.headers["Range"])
            assertEquals("/api/v2/blobs/b/content",request.url.encodedPath)
        }
        assertEquals(5,server.requestCount)
        assertThrows(Exception::class.java){runBlocking {transport.download(config,"../escape")}}
        assertEquals(5,server.requestCount)
    } }
}
