package io.github.rasmusbremholm.smbbrowser

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

data class Entry(val name: String, val isFolder: Boolean, val size: Long, val modified: Long)

/** One open connection to one server. Read-only: lists and reads, never writes. */
class SmbBrowser private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
) : Closeable {
    // Browsing and a download can touch this from different IO threads.
    private val shares = ConcurrentHashMap<String, DiskShare>()

    private fun disk(share: String) = shares.getOrPut(share) { session.connectShare(share) as DiskShare }

    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        ServerService(transport).shares1
            .filter { it.type and 0xFFFF == 0 && !it.netName.endsWith("$") } // disk shares, not IPC$/admin
            .map { it.netName }
            .sorted()
    }

    suspend fun list(share: String, path: String): List<Entry> = withContext(Dispatchers.IO) {
        disk(share).list(path)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                Entry(
                    name = it.fileName,
                    isFolder = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = it.endOfFile,
                    modified = it.changeTime.toEpochMillis(),
                )
            }
    }

    /** Copies a file into [out], reporting bytes copied so far. Cancellable between chunks. */
    suspend fun read(share: String, path: String, out: OutputStream, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            disk(share).openFile(
                path, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { file ->
                file.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
        }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        /** Blank [user] means guest. */
        suspend fun connect(host: String, port: Int, user: String, password: String, domain: String): SmbBrowser =
            withContext(Dispatchers.IO) {
                val client = SMBClient()
                try {
                    val connection = client.connect(host, port)
                    val auth = if (user.isBlank()) AuthenticationContext.guest()
                    else AuthenticationContext(user, password.toCharArray(), domain)
                    SmbBrowser(client, connection, connection.authenticate(auth))
                } catch (e: Throwable) {
                    client.close()
                    throw e
                }
            }
    }
}
