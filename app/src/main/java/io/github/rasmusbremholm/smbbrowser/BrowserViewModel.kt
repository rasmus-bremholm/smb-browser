package io.github.rasmusbremholm.smbbrowser

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.ConnectException
import java.net.UnknownHostException

data class Transfer(val label: String, val percent: Int)

class BrowserViewModel(private val app: Application) : AndroidViewModel(app) {
    private var smb: SmbBrowser? = null
    private var transferJob: Job? = null

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Empty = share list, first = share name, rest = folders inside it. */
    var location by mutableStateOf<List<String>>(emptyList())
        private set
    var items by mutableStateOf<List<Entry>>(emptyList())
        private set
    /** The one download in progress, if any. */
    var transfer by mutableStateOf<Transfer?>(null)
        private set

    fun connect(host: String, port: Int, user: String, password: String, domain: String) = run {
        val browser = SmbBrowser.connect(host, port, user, password, domain)
        try {
            items = browser.listShares().map { Entry(it, true, 0, 0) }
        } catch (e: Throwable) {
            closeAsync(browser)
            throw e
        }
        smb = browser
        location = emptyList()
        connected = true
    }

    fun open(target: List<String>) = run {
        val browser = smb ?: return@run
        items = if (target.isEmpty()) {
            browser.listShares().map { Entry(it, true, 0, 0) }
        } else {
            browser.list(target.first(), target.drop(1).joinToString("\\"))
        }
        location = target
    }

    fun enter(entry: Entry) {
        if (entry.isFolder) open(location + entry.name)
    }

    /** Up one level; from the share list this disconnects. */
    fun back() {
        if (busy) return
        if (location.isEmpty()) disconnect() else open(location.dropLast(1))
    }

    /** Saves a file in the current folder to the phone's Downloads. */
    fun save(entry: Entry) = startTransfer("Saving", entry) { copy ->
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(entry.name))
                put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until complete
            }) ?: throw IllegalStateException("Couldn't create the file in Downloads.")
            try {
                resolver.openOutputStream(uri)!!.use { copy(it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 8-9: plain file, needs WRITE_EXTERNAL_STORAGE (the UI asks for it)
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val file = uniqueFile(dir, entry.name)
            copyToFile(file, copy)
            MediaScannerConnection.scanFile(app, arrayOf(file.path), null, null)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(app, "Saved ${entry.name} to Downloads", Toast.LENGTH_SHORT).show()
        }
    }

    /** Downloads a file to the app's cache and hands it to whichever app opens that type. */
    fun openFile(entry: Entry) = startTransfer("Opening", entry) { copy ->
        val dir = File(app.cacheDir, "open")
        dir.deleteRecursively() // ponytail: only the last opened file is kept; streaming (M4) replaces this for big files
        dir.mkdirs()
        val file = File(dir, entry.name)
        copyToFile(file, copy)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(entry.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) {
            try {
                app.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                throw IllegalStateException("No app on this phone can open ${entry.name}.")
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }

    fun disconnect() {
        transferJob?.cancel()
        smb?.let(::closeAsync)
        smb = null
        connected = false
        location = emptyList()
        items = emptyList()
    }

    override fun onCleared() = disconnect()

    // close() does network I/O, which is banned on the main thread
    private fun closeAsync(browser: SmbBrowser) {
        Thread { browser.close() }.start()
    }

    /** Runs [block] on IO with a [copy] that streams the remote file into the given output. One at a time. */
    private fun startTransfer(
        verb: String,
        entry: Entry,
        block: suspend (copy: suspend (OutputStream) -> Unit) -> Unit,
    ) {
        val browser = smb ?: return
        if (transferJob?.isActive == true) return
        // Server-supplied name becomes a local file name; refuse anything that could escape the target folder.
        if ('/' in entry.name || '\\' in entry.name || entry.name == "." || entry.name == "..") {
            error = "Can't download a file named \"${entry.name}\"."
            return
        }
        // Captured now: the user may browse elsewhere while this runs.
        val share = location.first()
        val path = (location.drop(1) + entry.name).joinToString("\\")
        val label = "$verb ${entry.name}"
        error = null
        transfer = Transfer(label, 0)
        transferJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block { out ->
                        browser.read(share, path, out) { done ->
                            val percent = if (entry.size > 0) (done * 100 / entry.size).toInt().coerceAtMost(100) else 0
                            if (percent != transfer?.percent) transfer = Transfer(label, percent)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) error = describe(e) // a cancelled transfer may fail noisily; stay quiet
            } finally {
                transfer = null
            }
        }
    }

    private suspend fun copyToFile(file: File, copy: suspend (OutputStream) -> Unit) {
        try {
            file.outputStream().use { copy(it) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }

    private fun mimeType(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    private fun describe(e: Exception): String = when {
        e is UnknownHostException -> "Can't find that server."
        e is ConnectException -> "Server refused the connection."
        e is SMBApiException && e.statusCode == 0xC000006DL -> "Wrong username or password."
        e is SMBApiException && e.statusCode == 0xC0000022L -> "Access denied."
        else -> e.message ?: e.javaClass.simpleName // ponytail: raw message, proper error states are M5
    }
}
