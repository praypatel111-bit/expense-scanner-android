package com.expensescanner.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensescanner.app.App
import com.expensescanner.app.core.Books
import com.expensescanner.app.export.Exporter
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PRESETS = listOf(
  "this-month" to "This month", "last-month" to "Last month", "this-quarter" to "This quarter", "last-quarter" to "Last quarter",
  "this-year" to "This year", "last-year" to "Last year", "all" to "All time",
)

private fun range(preset: String, today: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> {
  val m1 = today.withDayOfMonth(1)
  val q1 = LocalDate.of(today.year, ((today.monthValue - 1) / 3) * 3 + 1, 1)
  return when (preset) {
    "this-month" -> m1 to m1.plusMonths(1).minusDays(1)
    "last-month" -> m1.minusMonths(1) to m1.minusDays(1)
    "this-quarter" -> q1 to q1.plusMonths(3).minusDays(1)
    "last-quarter" -> q1.minusMonths(3) to q1.minusDays(1)
    "this-year" -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
    "last-year" -> LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
    else -> null to null
  }
}

private fun label(r: Pair<LocalDate?, LocalDate?>): String {
  val f = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.CANADA)
  val (a, b) = r
  return if (a == null || b == null) "All bills" else "${a.format(f)} to ${b.format(f)}"
}

@Composable
fun ReportScreen(notify: (String) -> Unit) {
  val p = LocalPalette.current
  val ctx = LocalContext.current
  val app = App.instance
  val scope = rememberCoroutineScope()
  val bills by app.store.bills.collectAsState()
  val settings by app.prefs.settings.collectAsState()
  var preset by rememberSaveable { mutableStateOf("this-month") }
  var photos by remember { mutableStateOf(app.prefs.includePhotos) }
  var busy by remember { mutableStateOf<String?>(null) }
  // The finished file waiting for "Save to my phone"; kept if Android recreates the screen meanwhile.
  var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

  val r = range(preset)
  val chosen = remember(bills, preset) {
    val (a, b) = r
    bills.filter { bill ->
      val d = Books.parseIso(Books.recDate(bill))
      d != null && (a == null || !d.isBefore(a)) && (b == null || !d.isAfter(b))
    }
  }
  val cur = settings.currency
  val main = chosen.filter { it.currency.ifEmpty { cur } == cur }
  val total = Books.round2(main.sumOf { it.total ?: 0.0 })
  val gst = Books.round2(main.sumOf { Books.taxSplit(it).gst })
  val reimburse = Books.round2(main.filter { it.personal }.sumOf { it.total ?: 0.0 })
  val dups = Books.dupIndex(bills)
  val flagged = chosen.count { Books.issues(it).isNotEmpty() || dups.containsKey(it.id) }
  val byCat = main.groupBy { it.category }.map { (k, v) -> Triple(catLabel(settings.categories, k), v.size, Books.round2(v.sumOf { it.total ?: 0.0 })) }.sortedByDescending { it.third }
  val byMonth = main.groupBy { Books.recDate(it).take(7) }.toSortedMap(compareByDescending<String> { it })
    .map { (k, v) -> Triple(monthLabel(k), v.size, Books.round2(v.sumOf { it.total ?: 0.0 })) }
  val photoCount = chosen.count { it.photo != null }
  val biz = settings.businessName.replace(Regex("""[\\/:*?"<>|]+"""), "").trim()
  val span = r.first?.let { a -> "${a} to ${r.second}" } ?: "all bills"
  val xlsxName = "${if (biz.isNotEmpty()) "$biz " else ""}Expense report $span.xlsx"
  val zipName = "${if (biz.isNotEmpty()) "$biz " else ""}Receipt photos $span.zip"

  val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporter.XLSX)) { uri: Uri? ->
    val f = pendingPath?.let { File(it) }
    if (uri != null && f != null) scope.launch {
      runCatching { withContext(Dispatchers.IO) { Exporter.copyTo(ctx, f, uri) } }
        .onSuccess { notify("Saved to your phone") }
        .onFailure { notify("Couldn't save the file: ${it.message}") }
    }
  }
  val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporter.ZIP)) { uri: Uri? ->
    val f = pendingPath?.let { File(it) }
    if (uri != null && f != null) scope.launch {
      runCatching { withContext(Dispatchers.IO) { Exporter.copyTo(ctx, f, uri) } }
        .onSuccess { notify("Photos saved to your phone") }
        .onFailure { notify("Couldn't save the file: ${it.message}") }
    }
  }

  fun build(zip: Boolean, then: (File) -> Unit) {
    if (busy != null) return
    if (chosen.isEmpty()) {
      notify("There are no bills in this period.")
      return
    }
    busy = "Preparing…"
    scope.launch {
      try {
        val f = withContext(Dispatchers.Default) {
          if (zip) Exporter.buildPhotosZip(ctx, app.store, chosen, zipName) { msg -> busy = msg }
          else Exporter.buildXlsx(ctx, app.store, chosen, settings, label(r), photos, xlsxName) { msg -> busy = msg }
        }
        pendingPath = f.path
        then(f)
      } catch (e: Exception) {
        notify("Couldn't build the file: ${e.message}")
      } finally {
        busy = null
      }
    }
  }
  fun toDrive(f: File, mime: String) {
    if (!Exporter.sendToDrive(ctx, f, mime)) {
      notify("The Google Drive app isn't installed, so choose where to send it.")
      Exporter.share(ctx, f, mime)
    }
  }

  Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text("Report", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = p.ink)
    Gap(10)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      PRESETS.forEach { (k, l) -> FilterChip(selected = preset == k, onClick = { preset = k }, label = { Text(l) }) }
    }
    Gap(6)
    Text(label(r) + " · ${chosen.size} bill${if (chosen.size == 1) "" else "s"}", color = p.muted, fontSize = 13.sp)
    Gap(12)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Kpi("Total spent", Modifier.weight(1f)) { Highlight(Books.money(total, cur), 18) }
      Kpi("GST/HST paid", Modifier.weight(1f)) { Text(Books.money(gst, cur), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = p.ink) }
    }
    Gap(10)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Kpi("Bills", Modifier.weight(1f)) { Text("${chosen.size}", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = p.ink) }
      Kpi("To reimburse you", Modifier.weight(1f)) { Text(Books.money(reimburse, cur), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = p.ink) }
    }
    if (flagged > 0) {
      Gap(10)
      Text("$flagged bill${if (flagged == 1) " needs" else "s need"} a second look before you send this (see the Bills tab).", color = p.warn, fontSize = 13.sp)
    }
    Gap(18)
    SectionLabel("Where it went · $cur")
    Gap(6)
    Bars(byCat)
    Gap(18)
    SectionLabel("By month · $cur")
    Gap(6)
    Bars(byMonth)

    Gap(22)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.paper).padding(16.dp)) {
      Text("Export to Excel", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.ink)
      Text("Sheets: Summary, Expenses, Line items${if (photos) ", Receipt photos" else ""}.", color = p.muted, fontSize = 13.sp)
      Gap(8)
      Row(Modifier.fillMaxWidth().clickable { photos = !photos; app.prefs.includePhotos = photos }, verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = photos, onCheckedChange = { photos = it; app.prefs.includePhotos = it })
        HGap(12)
        Text("Put the $photoCount receipt photo${if (photoCount == 1) "" else "s"} inside the Excel file", color = p.ink, fontSize = 14.sp)
      }
      Gap(10)
      Button(
        onClick = { build(false) { f -> saveDoc.launch(f.name) } },
        enabled = busy == null,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.paper),
      ) {
        Icon(Icons.Filled.Download, null)
        HGap(8)
        Text("Save Excel to my phone")
      }
      Gap(8)
      OutlinedButton(onClick = { build(false) { f -> toDrive(f, Exporter.XLSX) } }, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Icon(Icons.Filled.CloudUpload, null)
        HGap(8)
        Text("Send Excel to Google Drive")
      }
      Gap(8)
      OutlinedButton(onClick = { build(false) { f -> Exporter.share(ctx, f, Exporter.XLSX) } }, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Icon(Icons.Filled.Share, null)
        HGap(8)
        Text("Share Excel (email, WhatsApp…)")
      }
      Gap(14)
      Text("Receipt photos only (.zip)", fontWeight = FontWeight.SemiBold, color = p.ink)
      Gap(6)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { build(true) { f -> saveZip.launch(f.name) } }, enabled = busy == null && photoCount > 0, modifier = Modifier.weight(1f)) { Text("To my phone") }
        OutlinedButton(onClick = { build(true) { f -> toDrive(f, Exporter.ZIP) } }, enabled = busy == null && photoCount > 0, modifier = Modifier.weight(1f)) { Text("To Google Drive") }
      }
      busy?.let { msg ->
        Gap(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.ink)
          HGap(8)
          Text(msg, color = p.muted, fontSize = 13.sp)
        }
      }
    }
    Gap(30)
  }
}

@Composable
private fun Kpi(label: String, modifier: Modifier, value: @Composable () -> Unit) {
  val p = LocalPalette.current
  Column(modifier.clip(RoundedCornerShape(14.dp)).background(p.paper).padding(12.dp)) {
    SectionLabel(label)
    Gap(6)
    value()
  }
}

@Composable
private fun Bars(rows: List<Triple<String, Int, Double>>) {
  val p = LocalPalette.current
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.paper).padding(horizontal = 14.dp, vertical = 6.dp)) {
    if (rows.isEmpty()) Text("No bills in this period.", color = p.muted, modifier = Modifier.padding(vertical = 10.dp))
    val max = rows.maxOfOrNull { it.third }?.coerceAtLeast(0.01) ?: 1.0
    rows.forEach { (name, count, value) ->
      Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(name, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
          HGap(6)
          Text("$count bill${if (count == 1) "" else "s"}", color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
          Text(Books.plain(value), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.ink)
        }
        Gap(6)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.field)) {
          Box(Modifier.fillMaxHeight().fillMaxWidth((value / max).toFloat().coerceIn(0.01f, 1f)).clip(RoundedCornerShape(4.dp)).background(p.ink))
        }
      }
    }
  }
}
