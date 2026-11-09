@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensescanner.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.expensescanner.app.App
import com.expensescanner.app.ai.Images
import com.expensescanner.app.ai.ModelManager
import com.expensescanner.app.core.Bill
import com.expensescanner.app.core.Status
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
  data object Bills : Screen
  data object Report : Screen
  data object Settings : Screen
  data class Detail(val id: String) : Screen
}

/** Keeps the open screen when the phone rotates or Android recreates the app. */
private val ScreenSaver = Saver<Screen, String>(
  save = { s ->
    when (s) {
      Screen.Bills -> "bills"
      Screen.Report -> "report"
      Screen.Settings -> "settings"
      is Screen.Detail -> "detail:" + s.id
    }
  },
  restore = { v ->
    when {
      v == "report" -> Screen.Report
      v == "settings" -> Screen.Settings
      v.startsWith("detail:") -> Screen.Detail(v.removePrefix("detail:"))
      else -> Screen.Bills
    }
  },
)

private val UrisSaver = Saver<List<Uri>, ArrayList<String>>(
  save = { list -> ArrayList(list.map { it.toString() }) },
  restore = { list -> list.map { Uri.parse(it) } },
)

@Composable
fun AppRoot(incoming: List<Uri>, onIncomingHandled: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val snack = remember { SnackbarHostState() }
  // Messages outlive the screen that sends them (e.g. "Saved" right after leaving a bill).
  val notify: (String) -> Unit = { msg -> scope.launch { snack.showSnackbar(msg) } }
  var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Bills) }
  var pending by rememberSaveable(stateSaver = UrisSaver) { mutableStateOf<List<Uri>>(emptyList()) }
  var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

  val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val u = cameraUri
    if (ok && u != null) pending = listOf(Uri.parse(u))
  }
  val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(25)) { uris ->
    if (uris.isNotEmpty()) pending = uris
  }
  LaunchedEffect(incoming) {
    if (incoming.isNotEmpty()) {
      pending = incoming
      onIncomingHandled()
    }
  }

  val scan: () -> Unit = {
    val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
    val f = File(dir, "bill_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    cameraUri = uri.toString()
    takePicture.launch(uri)
  }
  val pick: () -> Unit = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

  BackHandler(enabled = screen != Screen.Bills) { screen = Screen.Bills }

  Scaffold(
    snackbarHost = { SnackbarHost(snack) },
    bottomBar = {
      if (screen !is Screen.Detail) {
        NavigationBar(containerColor = LocalPalette.current.paper) {
          NavigationBarItem(selected = screen == Screen.Bills, onClick = { screen = Screen.Bills }, icon = { Icon(Icons.Filled.Receipt, null) }, label = { Text("Bills") })
          NavigationBarItem(selected = screen == Screen.Report, onClick = { screen = Screen.Report }, icon = { Icon(Icons.Filled.BarChart, null) }, label = { Text("Report") })
          NavigationBarItem(selected = screen == Screen.Settings, onClick = { screen = Screen.Settings }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
        }
      }
    },
  ) { pad ->
    Box(Modifier.fillMaxSize().padding(pad)) {
      when (val s = screen) {
        Screen.Bills -> BillsScreen(onOpen = { screen = Screen.Detail(it) }, onScan = scan, onPick = pick, onSettings = { screen = Screen.Settings })
        Screen.Report -> ReportScreen(notify)
        Screen.Settings -> SettingsScreen(notify)
        is Screen.Detail -> BillScreen(s.id, onBack = { screen = Screen.Bills }, notify = notify)
      }
    }
  }

  if (pending.isNotEmpty()) {
    CaptureSheet(
      uris = pending,
      onDismiss = { pending = emptyList() },
      onSave = { cat, note, personal ->
        val uris = pending
        pending = emptyList()
        scope.launch {
          val saved = saveBills(ctx, uris, cat, note, personal)
          val ready = App.instance.models.isReady()
          val msg = when {
            saved == 0 -> "Couldn't open that photo. Try again."
            saved < uris.size -> "Saved $saved of ${uris.size} photos. Reading them now."
            else -> if (ready) "Saved. Reading it on your phone…" else "Saved. Reading the basics now (download the AI reader in Settings for better results)."
          }
          snack.showSnackbar(msg)
        }
        screen = Screen.Bills
      },
    )
  }
}

/** Copies the photos in, creates the bills and queues them for reading. */
suspend fun saveBills(ctx: Context, uris: List<Uri>, cat: String, note: String, personal: Boolean): Int = withContext(Dispatchers.IO) {
  val app = App.instance
  var saved = 0
  for (u in uris) {
    val id = "b" + System.currentTimeMillis().toString(36) + (1000..9999).random()
    try {
      val img = Images.importPhoto(ctx, u, app.store.photosDir, "$id.jpg")
      val bill = Bill(
        id = id,
        num = app.store.nextNum(),
        createdAt = System.currentTimeMillis(),
        status = Status.QUEUED,
        photo = img.name,
        photoW = img.w,
        photoH = img.h,
        category = if (cat == "auto") null else cat,
        autoCategory = cat == "auto",
        note = note,
        personal = personal,
      )
      app.store.upsert(bill)
      app.processor.enqueue(id)
      saved++
    } catch (_: Exception) {
    }
    if (u.authority == ctx.packageName + ".files") u.lastPathSegment?.let { File(File(ctx.cacheDir, "captures"), it).delete() }
  }
  saved
}

@Composable
fun CaptureSheet(uris: List<Uri>, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
  val p = LocalPalette.current
  val app = App.instance
  val settings by app.prefs.settings.collectAsState()
  val model by app.models.state.collectAsState()
  var note by remember { mutableStateOf("") }
  var personal by remember { mutableStateOf(false) }
  val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val multi = uris.size > 1

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.paper) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
          uris.take(3).forEachIndexed { i, u ->
            AsyncImage(
              model = u,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.padding(start = (i * 14).dp).size(width = 58.dp, height = 76.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, p.line, RoundedCornerShape(8.dp)),
            )
          }
        }
        HGap(14)
        Column(Modifier.weight(1f)) {
          Text(if (multi) "${uris.size} photos" else "Photo ready", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = p.ink)
          Text(
            if (model is ModelManager.State.Ready) "Tap where it was spent. Your phone reads the rest." else "Tap where it was spent. The basics are read now; add the AI reader in Settings for full detail.",
            color = p.muted, fontSize = 13.sp,
          )
        }
      }
      Gap(12)
      OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(300) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(if (multi) "Note for all of them (optional)" else "Note or business purpose (optional)") },
      )
      Gap(16)
      Text(if (multi) "Where were these spent?" else "Where was it spent?", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = p.ink)
      Gap(10)
      val tiles = listOf(Triple("auto", "", "Let AI pick" + if (multi) " for each" else "")) + settings.categories.map { Triple(it.id, it.icon, it.name) }
      tiles.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.forEach { (id, icon, name) ->
            val auto = id == "auto"
            Column(
              Modifier.weight(1f).heightIn(min = 82.dp).clip(RoundedCornerShape(14.dp))
                .background(if (auto) p.ink else p.field)
                .border(1.dp, if (auto) p.ink else p.line, RoundedCornerShape(14.dp))
                .clickable { onSave(id, note.trim(), personal) }
                .padding(10.dp),
              verticalArrangement = Arrangement.SpaceBetween,
            ) {
              if (auto) Icon(Icons.Filled.AutoAwesome, null, tint = p.hi) else Text(icon, fontSize = 22.sp)
              Gap(8)
              Text(name, color = if (auto) p.paper else p.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 15.sp)
            }
          }
          repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
      Gap(4)
      Row(Modifier.fillMaxWidth().clickable { personal = !personal }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = personal, onCheckedChange = { personal = it })
        HGap(12)
        Text("Paid with my own money (reimburse me)", color = p.ink, fontSize = 14.sp)
      }
      TextButton(onClick = onDismiss) { Text(if (multi) "Discard photos" else "Discard photo", color = p.muted) }
    }
  }
}
