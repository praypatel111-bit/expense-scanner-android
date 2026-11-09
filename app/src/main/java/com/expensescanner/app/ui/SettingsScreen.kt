package com.expensescanner.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensescanner.app.App
import com.expensescanner.app.ai.ModelManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(notify: (String) -> Unit) {
  val p = LocalPalette.current
  val app = App.instance
  val settings by app.prefs.settings.collectAsState()
  val model by app.models.state.collectAsState()
  val bills by app.store.bills.collectAsState()
  val paused by app.prefs.aiPausedState.collectAsState()
  var name by remember { mutableStateOf(settings.businessName) }
  var region by remember { mutableStateOf(settings.region) }
  var currency by remember { mutableStateOf(settings.currency) }
  var cards by remember { mutableStateOf(settings.personalCards.joinToString(", ")) }
  var gpu by remember { mutableStateOf(app.prefs.useGpu) }
  var mobile by remember { mutableStateOf(app.prefs.allowMobileData) }
  val ram = remember { app.models.totalRamGb() }

  val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    // App-wide scope: copying 2.6 GB keeps going if you leave this screen.
    if (uri != null) app.scope.launch { app.models.importFrom(uri) }
  }

  Column(Modifier.fillMaxSize().background(p.bg).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text("Settings", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = p.ink)
    Gap(12)

    Card {
      Text("On-phone AI reader", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.ink)
      Text(
        "${app.models.spec.name} reads and sorts each bill on your phone. Photos and numbers never leave your phone. One-time download: 2.6 GB.",
        color = p.muted, fontSize = 13.sp,
      )
      Gap(8)
      val ramText = String.format(java.util.Locale.US, "%.1f", ram)
      if (ram < 7.0) {
        Text("This phone has about $ramText GB of memory. The AI reader needs about 8 GB, so it may be slow or stop. The basic reader still works.", color = p.warn, fontSize = 13.sp)
        Gap(8)
      }
      when (val s = model) {
        is ModelManager.State.Ready -> {
          if (paused) {
            Text(
              "Paused: the AI reader stopped unexpectedly on this phone, so new bills are read with the basic reader.",
              color = p.warn, fontSize = 13.sp,
            )
            TextButton(onClick = {
              app.prefs.aiCrashes = 0
              app.prefs.aiPaused = false
            }) { Text("Turn the AI reader back on") }
          } else {
            Text("Ready. New bills are read by AI.", color = p.good, fontWeight = FontWeight.SemiBold)
          }
          Gap(8)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !paused, onClick = {
              val unread = bills.filter { it.readBy != "gemma" && it.photo != null }
              // Fields you corrected by hand are kept; everything else gets the AI's reading.
              unread.forEach { app.processor.enqueue(it.id, overwrite = !it.reviewed) }
              notify(if (unread.isEmpty()) "Every bill has already been read by AI." else "Reading ${unread.size} bill${if (unread.size == 1) "" else "s"} again with AI…")
            }) { Text("Re-read older bills") }
            TextButton(onClick = { app.processor.removeModel() }) { Text("Remove", color = p.bad) }
          }
        }
        is ModelManager.State.Downloading -> {
          val pct = (s.done * 100 / s.total.coerceAtLeast(1)).toInt()
          Text("Downloading… $pct% (${s.done / 1_000_000} of ${s.total / 1_000_000} MB)", color = p.ink)
          if (s.note.isNotEmpty()) Text(s.note, color = p.muted, fontSize = 13.sp)
          Gap(6)
          LinearProgressIndicator(progress = { (s.done.toFloat() / s.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = p.ink, trackColor = p.field)
          TextButton(onClick = { app.models.cancel() }) { Text("Cancel download") }
        }
        is ModelManager.State.Copying -> {
          Text("Copying the model file…", color = p.ink)
          Gap(6)
          LinearProgressIndicator(progress = { (s.done.toFloat() / s.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = p.ink, trackColor = p.field)
        }
        is ModelManager.State.Failed, ModelManager.State.Missing -> {
          if (s is ModelManager.State.Failed) {
            Text(s.message, color = p.bad, fontSize = 13.sp)
            Gap(6)
          }
          Text("Free space: ${String.format(java.util.Locale.US, "%.1f", app.models.freeSpaceGb())} GB", color = p.muted, fontSize = 13.sp)
          Gap(8)
          Button(
            onClick = { app.models.startDownload() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.paper),
          ) { Text(if (mobile) "Download the AI reader (2.6 GB)" else "Download on Wi-Fi (2.6 GB)") }
          TextButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("I already have the gemma-4-E2B-it.litertlm file") }
        }
      }
      Gap(6)
      Toggle("Use mobile data for the download", mobile) {
        mobile = it
        app.prefs.allowMobileData = it
      }
      Toggle("Use the graphics chip (faster)", gpu) {
        gpu = it
        app.prefs.useGpu = it
        app.processor.releaseModel()
      }
      if (ram >= 7.0) Text("This phone: about ${String.format(java.util.Locale.US, "%.1f", ram)} GB of memory.", color = p.muted, fontSize = 12.sp)
    }

    Gap(12)
    Card {
      Text("Your business", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.ink)
      OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Business name (shown on reports)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = region, onValueChange = { region = it }, label = { Text("Where you're based") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = currency, onValueChange = { currency = it.uppercase().take(3) }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(0.6f))
      }
      OutlinedTextField(
        value = cards,
        onValueChange = { cards = it },
        label = { Text("Your personal cards (last 4 digits)") },
        placeholder = { Text("e.g. 4417, 0921") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
      )
      Text("Bills paid with these cards are marked \"Reimburse\" so you can pay yourself back.", color = p.muted, fontSize = 12.sp)
      Gap(8)
      Button(
        onClick = {
          val list = cards.split(Regex("""[^0-9]+""")).map { it.takeLast(4) }.filter { it.length == 4 }.distinct()
          app.prefs.update(
            settings.copy(
              businessName = name.trim(),
              region = region.trim(),
              currency = currency.trim().uppercase().ifEmpty { "CAD" },
              personalCards = list,
            )
          )
          cards = list.joinToString(", ")
          notify("Settings saved")
        },
        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.paper),
      ) { Text("Save") }
    }

    Gap(12)
    Card {
      Text("How it works", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.ink)
      listOf(
        "Google ML Kit reads the printed text on your phone.",
        "Gemma 4 E2B sorts it into vendor, date, items, HST and total, also on your phone.",
        "The app double-checks every amount against the printed text and flags anything that doesn't add up.",
        "Bills and photos are stored only in this app. Export to Excel to back them up to your phone or Google Drive.",
      ).forEach { Text("• $it", color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
      Gap(6)
      Text("${bills.size} bill${if (bills.size == 1) "" else "s"} saved.", color = p.ink, fontSize = 13.sp)
    }
    Gap(30)
  }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
  val p = LocalPalette.current
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    content()
  }
}

@Composable
private fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  val p = LocalPalette.current
  Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Switch(checked = checked, onCheckedChange = onChange)
    HGap(12)
    Text(text, color = p.ink, fontSize = 14.sp)
  }
}
