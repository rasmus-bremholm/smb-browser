package io.github.rasmusbremholm.smbbrowser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val vm: BrowserViewModel = viewModel()
                    Column(Modifier.safeDrawingPadding()) {
                        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (vm.connected) BrowseScreen(vm) else ConnectScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(vm: BrowserViewModel) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect to server", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Host or IP") }, singleLine = true)
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
            label = { Text("Port (default 445)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username (blank = guest)") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(),
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), label = { Text("Domain (optional)") }, singleLine = true)
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.connect(host.trim(), port.toIntOrNull() ?: 445, user.trim(), password, domain.trim()) },
            enabled = host.isNotBlank() && !vm.busy,
        ) { Text("Connect") }
    }
}

@Composable
private fun BrowseScreen(vm: BrowserViewModel) {
    BackHandler { vm.back() }
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getMediumDateFormat(context) }

    // Android 8-9 need a runtime permission to write to Downloads.
    var pendingSave by remember { mutableStateOf<Entry?>(null) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val entry = pendingSave
        pendingSave = null
        if (entry == null) return@rememberLauncherForActivityResult
        if (granted) vm.save(entry)
        else Toast.makeText(context, "Storage permission is needed to save to Downloads", Toast.LENGTH_SHORT).show()
    }
    fun save(entry: Entry) {
        if (Build.VERSION.SDK_INT < 29 &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = entry
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.save(entry)
        }
    }

    Column {
        Text(
            if (vm.location.isEmpty()) "Shares" else vm.location.joinToString(" / "),
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        vm.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        vm.transfer?.let { t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${t.label} · ${t.percent}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator({ t.percent / 100f }, Modifier.fillMaxWidth())
                }
                TextButton(vm::cancelTransfer) { Text("Cancel") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.items, key = { it.name }) { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = entry.isFolder || vm.transfer == null) { if (entry.isFolder) vm.enter(entry) else vm.openFile(entry) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (entry.isFolder) "📁" else "📄") // ponytail: emoji, real icons need material-icons-extended
                    Column(Modifier.weight(1f)) {
                        Text(entry.name)
                        if (entry.modified > 0) {
                            val size = if (entry.isFolder) "" else Formatter.formatShortFileSize(context, entry.size) + " · "
                            Text(
                                size + dateFormat.format(Date(entry.modified)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!entry.isFolder) TextButton({ save(entry) }, enabled = vm.transfer == null) { Text("Save") }
                }
            }
        }
    }
}
