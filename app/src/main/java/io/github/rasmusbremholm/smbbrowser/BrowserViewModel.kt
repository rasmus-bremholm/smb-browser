package io.github.rasmusbremholm.smbbrowser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.UnknownHostException

class BrowserViewModel : ViewModel() {
    private var smb: SmbBrowser? = null

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

    fun disconnect() {
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
