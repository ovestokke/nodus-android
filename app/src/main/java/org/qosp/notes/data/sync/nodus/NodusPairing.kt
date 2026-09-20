@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import okio.ByteString.Companion.decodeBase64
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private val pairingAlphabet = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{5}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{5}")
private val wireId = Regex("[A-Za-z0-9_-]{1,128}")
private val credentialSecret = Regex("[A-Za-z0-9_-]{43}")

internal fun normalizePairingCode(value: String): String {
    val compact=value.trim().replace(" ","").replace("-","").uppercase(Locale.US)
    require(compact.length==10)
    return "${compact.take(5)}-${compact.drop(5)}".also { require(pairingAlphabet.matches(it)) }
}

internal fun requireCredentialSecret(value: String): String {
    require(credentialSecret.matches(value))
    val decoded=requireNotNull(value.decodeBase64())
    require(decoded.size==32)
    require(decoded.base64Url().trimEnd('=')==value)
    return value
}

@Serializable
internal data class PairingRedeemInput(
    val code:String,
    val deviceId:String,
    val requestId:String,
    val credentialSecret:String
) {
    init {
        require(pairingAlphabet.matches(code))
        require(wireId.matches(deviceId) && wireId.matches(requestId))
        requireCredentialSecret(credentialSecret)
    }
    override fun toString()="PairingRedeemInput([REDACTED])"
}

@Serializable
internal data class PairingRedeem(val credential:String,val tokenId:String) {
    init {
        require(wireId.matches(tokenId))
        require(credential=="$tokenId.${credential.substringAfter('.',"")}")
        requireCredentialSecret(credential.substringAfter('.',""))
    }
    override fun toString()="PairingRedeem([REDACTED])"
}

internal data class NodusPairingLink(val origin:String,val code:String) {
    companion object {
        fun parse(value:String):NodusPairingLink {
            require(value.none { it.isWhitespace() || it=='\\' || it.code<32 || it.code==127 })
            val uri=URI(value)
            require(uri.scheme=="nodus" && uri.host=="pair" && uri.rawUserInfo==null)
            require(uri.rawPath.isNullOrEmpty() && uri.rawFragment==null && '+' !in requireNotNull(uri.rawQuery))
            val pairs=uri.rawQuery.split('&').map { part ->
                val split=part.split('=',limit=2)
                require(split.size==2 && split[0].isNotEmpty() && split[1].isNotEmpty())
                URLDecoder.decode(split[0],StandardCharsets.UTF_8) to URLDecoder.decode(split[1],StandardCharsets.UTF_8)
            }
            require(pairs.size==2 && pairs.map { it.first }.toSet()==setOf("origin","code"))
            val fields=pairs.toMap()
            val origin=requireNotNull(fields["origin"])
            val code=normalizePairingCode(requireNotNull(fields["code"]))
            return NodusPairingLink(NodusOrigin.parse(origin).toString(),code)
        }
    }
}
