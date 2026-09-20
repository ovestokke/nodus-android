package org.qosp.notes.data.sync.nodus.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.qosp.notes.data.sync.nodus.NodusJson
import org.qosp.notes.data.sync.nodus.NodusOrigin
import org.qosp.notes.data.sync.nodus.PairingRedeemInput
import java.util.concurrent.TimeUnit

internal interface NodusPairingTransport {
    suspend fun redeem(origin:NodusOrigin,input:PairingRedeemInput):NodusHttpResult
}

/** Isolated unauthenticated client. Pair redemption must never inherit a bearer, cookie, redirect or retry. */
internal class NodusHttpPairingTransport(private val client:OkHttpClient=client()):NodusPairingTransport {
    init {
        require(!client.followRedirects && !client.followSslRedirects && !client.retryOnConnectionFailure)
        require(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
    }
    override suspend fun redeem(origin:NodusOrigin,input:PairingRedeemInput):NodusHttpResult=withContext(Dispatchers.IO) {
        val bytes=NodusJson.encode(PairingRedeemInput.serializer(),input).toByteArray(Charsets.UTF_8)
        val request=Request.Builder()
            .url(origin.url.newBuilder().encodedPath("/auth/pairings/redeem").query(null).build())
            .removeHeader("Authorization").removeHeader("Cookie").removeHeader("Origin").removeHeader("X-CSRF-Token")
            .post(bytes.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            require(response.request.header("Authorization")==null && response.priorResponse==null)
            require(response.headers.values("Cache-Control").size==1 && response.header("Cache-Control")!!.split(',').any { it.trim().equals("no-store",true) })
            val body=response.body?.byteStream()?.use { inputStream ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(true) {
                    val count=inputStream.read(buffer)
                    if(count<0)break
                    require(output.size()+count<=65536) { "Pairing response too large" }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            NodusHttpResult(response.code,body,response.header("Retry-After"))
        }
    }
    private companion object {
        fun client()=OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(30,TimeUnit.SECONDS)
            .readTimeout(30,TimeUnit.SECONDS)
            .writeTimeout(30,TimeUnit.SECONDS)
            .callTimeout(30,TimeUnit.SECONDS)
            .build()
    }
}
