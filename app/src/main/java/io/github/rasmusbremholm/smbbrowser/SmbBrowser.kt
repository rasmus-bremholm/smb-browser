package io.github.rasmusbremholm.smbbrowser

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

data class Entry(val name: String, val isFolder: Boolean, val size: Long, val modified: Long)

/** One open connection to one server. Read-only: only ever lists. */
class SmbBrowser private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
) : Closeable {
    private val shares = mutableMapOf<String, DiskShare>()

    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        ServerService(transport).shares1
            .filter { it.type and 0xFFFF == 0 && !it.netName.endsWith("$") } // disk shares, not IPC$/admin
            .map { it.netName }
            .sorted()
    }

    suspend fun list(share: String, path: String): List<Entry> = withContext(Dispatchers.IO) {
        val disk = shares.getOrPut(share) { session.connectShare(share) as DiskShare }
        disk.list(path)
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
