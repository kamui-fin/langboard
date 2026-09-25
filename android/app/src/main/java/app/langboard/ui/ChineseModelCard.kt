package app.langboard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.core.ModelState
import app.langboard.model.ModelManager

/**
 * The Chinese pack: download, progress, installed, remove. Download happens once; loading happens
 * each time the process starts and is shown as "Starting", never as downloading again.
 */
@Composable
fun ChineseModelCard(modifier: Modifier = Modifier) {
  val state by ModelManager.state.collectAsStateWithLifecycle()
  val spec = ModelManager.spec
  var confirmRemove by remember { mutableStateOf(false) }
  // Android 13+ shows the download notification only with permission; ask when the user starts it.
  val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ModelManager.download() }
  val context = LocalContext.current
  val download = {
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    else ModelManager.download()
  }

  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when (val s = state) {
      ModelState.NotInstalled -> {
        Title(spec.title)
        Muted("Hy-MT2 · 1.8B · ${mb(spec.downloadBytes)} download")
        Text(spec.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Button(onClick = download) { Text("Download") }
      }
      is ModelState.Downloading -> {
        Title(spec.title)
        if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Muted(
          if (s.progress == null) "Starting download…"
          else "${(s.progress * 100).toInt()}%  ·  ${mb(s.downloadedBytes)} / ${mb(s.totalBytes)}"
        )
        Muted("You can leave this screen.")
        OutlinedButton(onClick = ModelManager::cancelDownload) { Text("Cancel") }
      }
      is ModelState.Verifying -> {
        Title(spec.title)
        if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Muted("Checking the download and preparing it for this phone…")
      }
      ModelState.Installed, ModelState.Loading, ModelState.Ready -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(8.dp))
          Title("Chinese model installed")
        }
        Muted(
          "${mb(spec.installedBytes)}  ·  " + when (s) {
            ModelState.Loading -> "Starting…"
            ModelState.Ready -> "Ready"
            else -> "Starts when you use it"
          }
        )
        TextButton(onClick = { confirmRemove = true }) { Text("Remove model") }
      }
      is ModelState.Error -> {
        Title(spec.title)
        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          if (ModelManager.file.exists()) OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove model") }
          else Button(onClick = download) { Text("Try again") }
        }
      }
    }
  }

  if (confirmRemove) {
    AlertDialog(
      onDismissRequest = { confirmRemove = false },
      title = { Text("Remove the Chinese model?") },
      text = { Text("Frees ${mb(spec.installedBytes)}. The keyboard keeps working with the dictionary; you can download the model again any time.") },
      confirmButton = { TextButton(onClick = { confirmRemove = false; ModelManager.remove() }) { Text("Remove") } },
      dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Muted(text: String) =
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"
