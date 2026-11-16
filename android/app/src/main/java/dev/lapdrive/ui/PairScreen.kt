package dev.lapdrive.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import dev.lapdrive.AppLock
import dev.lapdrive.AppViewModel
import kotlinx.coroutines.launch

@Composable
fun PairScreen(vm: AppViewModel, lockAvailable: Boolean) {
    var link by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(Build.MODEL) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun doPair(text: String) {
        busy = true
        error = null
        scope.launch {
            try {
                vm.pair(text, deviceName)
            } catch (e: Exception) {
                error = when (e) {
                    is javax.net.ssl.SSLException -> "Security check failed: the laptop certificate does not match the QR code."
                    is java.io.IOException -> e.message?.takeIf { it.isNotBlank() && e is dev.lapdrive.ApiException }
                        ?: "Cannot reach the laptop. Check the network and that the server runs with --pair."
                    else -> e.message ?: e.toString()
                }
            } finally {
                busy = false
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { link = it; doPair(it) }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Laptop, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Pair with your laptop", style = MaterialTheme.typography.headlineSmall)
        Text(
            "On the laptop, run:\n  lapdrive serve --pair\nThen scan the QR code that it shows.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!lockAvailable) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null)
                    Text(
                        "Your phone has no screen lock. Set one, so that LapDrive can lock itself.",
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        OutlinedTextField(
            value = deviceName, onValueChange = { deviceName = it.take(64) },
            label = { Text("Name of this phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                AppLock.expectExternal = true
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the QR code on your laptop").setBeepEnabled(false).setOrientationLocked(false)
                )
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.QrCodeScanner, null)
            Text("  Scan QR code")
        }

        Text("or paste the pairing link", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = link, onValueChange = { link = it },
            label = { Text("lapdrive://pair?...") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        FilledTonalButton(onClick = { doPair(link) }, enabled = !busy && link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Pair")
        }

        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
