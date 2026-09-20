package org.qosp.notes.data.sync.nodus.engine

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal data class NodusStoredBytes(val id: String, val size: String, val sha256: String)

/** Only opaque generated names in private storage. Remote names/media types never choose paths. */
internal data class NodusVerifiedFile(val id: String, val fingerprint: String)

internal class NodusByteStore(directory: File, private val maxTotalBytes: Long = MAX_TOTAL_BYTES, private val publish: (File, File) -> Unit = { from, to -> Os.link(from.path, to.path) }, private val fingerprintFile: (File) -> String = { file ->
    val stat = Os.stat(file.path)
    "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}:${file.lastModified()}"
}, private val syncDirectory: (File) -> Unit = { dir ->
    val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}) {
    constructor(context: android.content.Context) : this(File(context.filesDir, "nodus-bytes"))
    private val directory = directory.canonicalFile
    init {
        if (!this.directory.isDirectory) {
            require(this.directory.mkdirs())
            syncDirectory(requireNotNull(this.directory.parentFile))
        }
    }
    /** Probe only: never publishes a domain byte object or touches retained content. */
    fun checkAvailable() = synchronized(admissionLocks.getOrPut(directory.path) { Any() }) {
        val source=File.createTempFile("probe-", ".part", directory)
        val linked=File(directory,"${newNodusId()}.part")
        try {
            FileOutputStream(source).use { it.fd.sync() }
            publish(source,linked)
            require(linked.isFile && linked.length()==0L)
            syncDirectory(directory)
        } finally { linked.delete();source.delete();syncDirectory(directory) }
    }

    fun file(id: String): File {
        require(Regex("[0-9a-f-]{36}").matches(id))
        return File(directory, "$id.bin").also { require(it.canonicalFile == it.absoluteFile) }
    }
    fun idFromUri(uri: String): String {
        val file = File(java.net.URI(uri))
        require(file.parentFile == directory && file.name.endsWith(".bin"))
        return file.name.removeSuffix(".bin").also { require(this.file(it) == file) }
    }
    fun verify(id: String, size: String, hash: String): File {
        val source = file(id)
        require(source.isFile && source.length().toString() == size) { "source_length_mismatch" }
        FileInputStream(source).use { require(digest(it, null).second == hash) { "source_hash_mismatch" } }
        return source
    }
    fun verified(id: String, size: String, hash: String): NodusVerifiedFile {
        val before = fingerprintFile(file(id))
        verify(id, size, hash)
        require(before == fingerprintFile(file(id))) { "source_replaced_during_verification" }
        return NodusVerifiedFile(id, before)
    }
    fun isCurrent(value: NodusVerifiedFile): Boolean = runCatching {
        file(value.id).isFile && fingerprintFile(file(value.id)) == value.fingerprint
    }.getOrDefault(false)

    fun inspect(id: String): NodusStoredBytes = file(id).inputStream().use {
        val measured = digest(it, null)
        NodusStoredBytes(id, measured.first.toString(), measured.second)
    }
    fun install(open: () -> InputStream, expectedSize: String? = null, expectedHash: String? = null, id: String = newNodusId()): NodusStoredBytes = synchronized(admissionLocks.getOrPut(directory.path) { Any() }) {
        require(!file(id).exists()) { "immutable_source_exists" }
        // Exclusive creation avoids following or truncating an abandoned staging path.
        val staging = File.createTempFile("$id-", ".part", directory)
        try {
            val measured = FileOutputStream(staging).use { output ->
                val remaining = maxTotalBytes - (directory.listFiles() ?: error("storage_unavailable")).filter { it.name.endsWith(".bin") || it.name.endsWith(".part") }.sumOf { it.length() }
                require(remaining >= 0 && (expectedSize == null || expectedSize.toLong() <= remaining)) { "quota_exceeded" }
                val measured = open().use { digest(it, output, remaining) }
                require(expectedSize == null || measured.first.toString() == expectedSize) { "download_length_mismatch" }
                require(expectedHash == null || measured.second == expectedHash) { "download_hash_mismatch" }
                require(staging.setReadOnly()) { "source_permissions_failed" }
                output.fd.sync()
                measured
            }
            publish(staging, file(id))
            syncDirectory(directory)
            NodusStoredBytes(id, measured.first.toString(), measured.second)
        } finally { staging.delete() } // Only uncommitted staging; installed bytes are never GC'd here.
    }
    private fun digest(input: InputStream, output: FileOutputStream?, remaining: Long = MAX_BLOB_BYTES): Pair<Long,String> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            size += count
            require(size <= remaining) { "quota_exceeded" }
            require(size <= MAX_BLOB_BYTES) { "blob_too_large" }
            hash.update(buffer, 0, count); output?.write(buffer, 0, count)
        }
        return size to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object { private val admissionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>(); const val MAX_BLOB_BYTES = 536870912L; const val MAX_TOTAL_BYTES = 21474836480L }
}
