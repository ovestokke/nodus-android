package org.qosp.notes.data.sync.nodus

import android.util.AtomicFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NodusConfigurationStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store(file: File, key: SecretKey) = NodusConfigurationStore(AtomicFile(file)) { key }

    @Test fun encryptedRecordSurvivesReopenAndCredentialChangesRetainDeviceButRotateEpoch() {
        val file = File(directory.root, "config")
        val key = key()
        val first = store(file, key)
        assertNull(first.configuration())
        val id = first.deviceId()
        val configured = first.configure(NodusOrigin.parse("https://notes.example"), "synthetic-token")
        val bytes = file.readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("synthetic-token"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("notes.example"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(id))
        val reopened = store(file, key)
        assertEquals(id, reopened.deviceId())
        assertEquals(configured.credentialEpoch, reopened.configuration()!!.credentialEpoch)
        assertEquals(configured.bearerToken, reopened.configuration()!!.bearerToken)
        val rotated = reopened.configure(configured.origin, configured.bearerToken)
        assertNotEquals(configured.credentialEpoch, rotated.credentialEpoch)
        assertEquals(id, rotated.deviceId)
        reopened.clearCredential()
        assertNull(store(file, key).configuration())
        assertEquals(id, store(file, key).deviceId())
        val enrolled = reopened.configure(NodusOrigin.parse("https://new.example"), "other-token")
        assertEquals(id, enrolled.deviceId)
        assertNotEquals(rotated.credentialEpoch, enrolled.credentialEpoch)
    }

    @Test fun pairingEvidenceIsEncryptedDurableAndExactAcrossUnknownOutcomeRetry() {
        val file=File(directory.root,"pairing")
        val key=key()
        val first=store(file,key)
        val pending=first.beginPairing(NodusOrigin.parse("https://notes.example"),"23456-789ab",null)
        assertEquals("23456-789AB",pending.code)
        assertEquals(43,pending.credentialSecret.length)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(pending.code))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(pending.credentialSecret))
        val reopened=store(file,key)
        assertEquals(pending,reopened.pendingPairing())
        assertEquals(pending,reopened.beginPairing(NodusOrigin.parse("https://notes.example"),"",null))
        val changedOrigin=assertThrows(IllegalArgumentException::class.java) {
            reopened.beginPairing(NodusOrigin.parse("https://other.example"),"",null)
        }
        assertEquals("pairing_origin_changed",changedOrigin.message)
        assertEquals(pending,reopened.pendingPairing())
        assertThrows(Exception::class.java) { reopened.beginPairing(NodusOrigin.parse("https://notes.example"),"CDEFG-HJKLM",null) }
        val response=PairingRedeem("paired.${pending.credentialSecret}","paired")
        val accepted=reopened.acceptPairing(pending,response)
        val acceptedPending=requireNotNull(reopened.pendingPairing())
        assertEquals("paired",acceptedPending.acceptedTokenId)
        assertEquals(accepted.credentialEpoch,reopened.acceptPairing(acceptedPending,response).credentialEpoch)
        reopened.completePairing(pending.requestId)
        assertNull(store(file,key).pendingPairing())
        assertEquals(response.credential,store(file,key).configuration()!!.bearerToken)
    }

    @Test fun corruptionWrongKeyAndUnknownVersionFailClosedWithoutOverwritingIdentity() {
        val file = File(directory.root, "config")
        val key = key()
        val store = store(file, key)
        store.configure(NodusOrigin.parse("https://notes.example"), "synthetic-token")
        val original = file.readBytes()
        assertThrows(IOException::class.java) { store(file, key()).configuration() }
        assertArrayEquals(original, file.readBytes())
        val corrupt = original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupt)
        assertThrows(IOException::class.java) { store.configuration() }
        assertThrows(IOException::class.java) { store.clearCredential() }
        assertThrows(IOException::class.java) { store.configure(NodusOrigin.parse("https://notes.example"), "new-token") }
        assertArrayEquals(corrupt, file.readBytes())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("nodus-configuration-v1".toByteArray())
        val unknownRecord = """{"version":2,"deviceId":"d","credentialEpoch":"epoch","origin":null,"bearerToken":null}"""
        val unknown = byteArrayOf(1) + cipher.iv + cipher.doFinal(unknownRecord.toByteArray())
        file.writeBytes(unknown)
        assertThrows(IOException::class.java) { store.configuration() }
        assertArrayEquals(unknown, file.readBytes())
    }

    @Test fun failedAtomicWriteKeepsPreviousCredentialRecord() {
        val file = File(directory.root, "config")
        val key = key()
        val store = store(file, key)
        val configuration = store.configure(NodusOrigin.parse("https://notes.example"), "synthetic-token")
        val atomic = AtomicFile(file)
        val interrupted = atomic.startWrite()
        interrupted.write(byteArrayOf(1,2,3))
        atomic.failWrite(interrupted)
        assertEquals(configuration.credentialEpoch, store.configuration()!!.credentialEpoch)
        assertEquals(configuration.deviceId, store.deviceId())
    }
}
