@file:UseSerializers(StrictStringSerializer::class, StrictBooleanSerializer::class, StrictIntSerializer::class)

package org.qosp.notes.data.sync.nodus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class NodusPendingPairing(
    val origin:String,
    val code:String,
    val deviceId:String,
    val requestId:String,
    val credentialSecret:String,
    val targetConnectionId:String?,
    val acceptedTokenId:String?=null
) {
    init {
        NodusOrigin.parse(origin)
        require(normalizePairingCode(code)==code)
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(deviceId) && Regex("[A-Za-z0-9_-]{1,128}").matches(requestId))
        requireCredentialSecret(credentialSecret)
        require(targetConnectionId==null || Regex("[A-Za-z0-9_-]{1,128}").matches(targetConnectionId))
        require(acceptedTokenId==null || Regex("[A-Za-z0-9_-]{1,128}").matches(acceptedTokenId))
    }
    fun input()=PairingRedeemInput(code,deviceId,requestId,credentialSecret)
    override fun toString()="NodusPendingPairing([REDACTED])"
}

/** One authenticated encrypted, atomic, versioned record in Android's no-backup directory.
 * Corruption/key loss fails closed and never regenerates an existing device identity.
 */
internal class NodusConfigurationStore internal constructor(
    private val file: AtomicFile,
    private val keyProvider: (create: Boolean) -> SecretKey
) {
    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, "nodus-configuration")),
        ::androidKey
    )

    fun configuration(): NodusConfiguration? = synchronized(lock) { loadOrCreate().configuration() }
    fun deviceId(): String = synchronized(lock) { loadOrCreate().deviceId }
    fun pendingPairing():NodusPendingPairing?=synchronized(lock) { loadOrCreate().pendingPairing }

    /** Persist every redemption identity field before the first unauthenticated request. */
    fun beginPairing(origin:NodusOrigin,code:String,targetConnectionId:String?):NodusPendingPairing=synchronized(lock) {
        val previous=loadOrCreate()
        val normalized=if(code.isBlank()) null else normalizePairingCode(code)
        previous.pendingPairing?.let { pending ->
            require(normalized==null || normalized==pending.code) { "pairing_retry_required" }
            require(targetConnectionId==pending.targetConnectionId) { "pairing_target_changed" }
            return@synchronized pending
        }
        val canonical=requireNotNull(normalized) { "pairing_code_required" }
        val secret=ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded=secret.toByteString().base64Url().trimEnd('=')
        val pending=NodusPendingPairing(origin.toString(),canonical,previous.deviceId,randomId(),encoded,targetConnectionId)
        save(previous.copy(pendingPairing=pending))
        pending
    }

    /** Save the reconstructed credential while retaining replay evidence until DB enrollment commits. */
    fun acceptPairing(pending:NodusPendingPairing,response:PairingRedeem):NodusConfiguration=synchronized(lock) {
        val previous=loadOrCreate()
        val current=requireNotNull(previous.pendingPairing)
        require(current.requestId==pending.requestId && current==pending)
        require(response.credential=="${response.tokenId}.${pending.credentialSecret}")
        if(current.acceptedTokenId!=null) {
            require(current.acceptedTokenId==response.tokenId)
            return@synchronized requireNotNull(previous.configuration()).also {
                require(it.origin.toString()==pending.origin && it.bearerToken==response.credential)
            }
        }
        val configuration=NodusConfiguration(NodusOrigin.parse(pending.origin),response.credential,randomId(),previous.deviceId)
        save(previous.copy(credentialEpoch=configuration.credentialEpoch,origin=pending.origin,bearerToken=response.credential,
            pendingPairing=current.copy(acceptedTokenId=response.tokenId)))
        configuration
    }

    fun completePairing(requestId:String)=synchronized(lock) {
        val previous=loadOrCreate()
        val pending=requireNotNull(previous.pendingPairing)
        require(pending.requestId==requestId && pending.acceptedTokenId!=null)
        save(previous.copy(pendingPairing=null))
    }

    /** Only a known terminal redemption failure may erase unaccepted retry evidence automatically. */
    fun clearPendingPairing(requestId:String)=synchronized(lock) {
        val previous=loadOrCreate()
        val pending=requireNotNull(previous.pendingPairing)
        require(pending.requestId==requestId && pending.acceptedTokenId==null)
        save(previous.copy(pendingPairing=null))
    }

    /** Explicit cancellation can abandon an accepted-but-unfinished credential after the warning UI. */
    fun cancelPendingPairing()=synchronized(lock) {
        val previous=loadOrCreate()
        val pending=previous.pendingPairing ?: return@synchronized
        if(pending.acceptedTokenId==null) save(previous.copy(pendingPairing=null))
        else save(previous.copy(credentialEpoch=randomId(),origin=null,bearerToken=null,pendingPairing=null))
    }

    /** Every explicit credential assignment gets a fresh epoch, even for the same token.
     * Unknown-outcome writes from an older epoch must remain manual-review work.
     */
    fun configure(origin: NodusOrigin, bearerToken: String): NodusConfiguration = synchronized(lock) {
        val previous = loadOrCreate()
        val configuration = NodusConfiguration(origin, bearerToken, randomId(), previous.deviceId)
        save(Record(1, previous.deviceId, configuration.credentialEpoch, origin.url.toString(), bearerToken))
        configuration
    }

    /** Disconnect retains the installation identity and rotates the credential epoch. */
    fun clearCredential() = synchronized(lock) {
        val previous = loadOrCreate()
        save(Record(1, previous.deviceId, randomId(), null, null))
    }

    private fun loadOrCreate(): Record {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return Record(1, randomId(), randomId(), null, null).also { save(it) }
        }
        try {
            val bytes = file.openRead().use { input ->
                val bytes = input.readBytes()
                require(bytes.size in 30..65536)
                bytes
            }
            require(bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
            return NodusJson.decode(Record.serializer(), plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw IOException("Nodus configuration unavailable; explicit recovery required")
        }
    }

    private fun save(record: Record) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
            cipher.updateAAD(aad)
            val plaintext = NodusJson.encode(Record.serializer(), record).toByteArray(Charsets.UTF_8)
            require(plaintext.size <= 65507) { "Configuration too large" }
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        } catch (_: Exception) {
            throw IOException("Nodus configuration could not be saved")
        }
    }

    @Serializable
    private data class Record(
        val version: Int,
        val deviceId: String,
        val credentialEpoch: String,
        val origin: String?,
        val bearerToken: String?,
        val pendingPairing:NodusPendingPairing?=null
    ) {
        init {
            require(version == 1)
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(deviceId))
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(credentialEpoch))
            require((origin == null) == (bearerToken == null))
            require(pendingPairing==null || pendingPairing.deviceId==deviceId)
            configuration()
        }
        fun configuration(): NodusConfiguration? = origin?.let {
            NodusConfiguration(NodusOrigin.parse(it), requireNotNull(bearerToken), credentialEpoch, deviceId)
        }
        override fun toString() = "NodusConfigurationRecord([REDACTED])"
    }

    private companion object {
        val lock = Any()
        const val alias = "nodus-configuration-v1"
        val aad = "nodus-configuration-v1".toByteArray(Charsets.UTF_8)
        fun randomId() = UUID.randomUUID().toString()

        fun androidKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null)
            if (existing != null) return existing as SecretKey
            check(create) { "Missing configuration key" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        }
    }
}
