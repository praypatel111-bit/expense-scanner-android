package com.expensescanner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.expensescanner.app.App
import com.expensescanner.app.ai.ModelManager
import com.expensescanner.app.core.Bill
import com.expensescanner.app.core.Books
import com.expensescanner.app.core.Status
import java.time.YearMonth

@Composable
fun BillsScreen(onOpen: (String) -> Unit, onScan: () -> Unit, onPick: () -> Unit, onSettings: () -> Unit) {
  val p = LocalPalette.current
  val app = App.instance
  val bills by app.store.bills.collectAsState()
  val settings by app.prefs.settings.collectAsState()
  val model by app.models.state.collectAsState()
  val current by app.processor.current.collectAsState()
  val paused by app.prefs.aiPausedState.collectAsState()
  var period by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
  var query by rememberSaveable { mutableStateOf("") }
  var menu by remember { mutableStateOf(false) }

  val cur = settings.currency
  val dups = remember(bills) { Books.dupIndex(bills) }
  val inPeriod = remember(bills, period) {
    if (period == "all") bills else bills.filter { Books.recDate(it).startsWith(period) }
  }
  val shown = remember(bills, inPeriod, query) {
    val base = if (query.isBlank()) inPeriod else bills.filter { matches(it, query, settings.categories.map { c -> c.id to c.name }.toMap()) }
    base.sortedWith(compareByDescending<Bill> { Books.recDate(it) }.thenByDescending { it.createdAt })
  }
  val months = remember(bills) {
    (bills.map { Books.recDate(it).take(7) } + YearMonth.now().toString()).filter { it.length == 7 }.distinct().sortedDescending()
  }

  Box(Modifier.fillMaxSize().background(p.bg)) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp)) {
      item {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("EXPENSES", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp, color = p.ink)
            Text(settings.businessName.ifEmpty { "Bills & receipts" }, color = p.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          if (current != null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.ink)
            HGap(8)
            Text("Reading", color = p.muted, fontSize = 12.sp)
          }
        }
        Gap(12)
      }
      if (model !is ModelManager.State.Ready || paused) {
        item { ModelBanner(model, paused, onSettings) }
      }
      item {
        val main = inPeriod.filter { it.currency.ifEmpty { cur } == cur }
        val total = Books.round2(main.sumOf { it.total ?: 0.0 })
        val tax = Books.round2(main.sumOf { Books.taxTotal(it) })
        val byCat = main.groupBy { it.category }.map { (k, v) -> k to Books.round2(v.sumOf { it.total ?: 0.0 }) }.sortedByDescending { it.second }
        val others = inPeriod.filter { it.currency.ifEmpty { cur } != cur }.groupBy { it.currency }
        ReceiptTape(
          header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.weight(1f)) {
                Row(Modifier.clickable { menu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text(if (period == "all") "ALL TIME" else monthLabel(period).uppercase(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = p.ink)
                  Icon(Icons.Filled.ArrowDropDown, "Choose month", tint = p.ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                  months.forEach { m -> DropdownMenuItem(text = { Text(monthLabel(m)) }, onClick = { period = m; menu = false }) }
                  DropdownMenuItem(text = { Text("All time") }, onClick = { period = "all"; menu = false })
                }
              }
              Text("${inPeriod.size} ${if (inPeriod.size == 1) "BILL" else "BILLS"}", fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = p.muted)
            }
          },
          body = {
            if (byCat.isEmpty()) Text(if (bills.isEmpty()) "Your first bill will print here." else "No bills in this period yet.", color = p.muted, fontFamily = Mono, fontSize = 12.sp)
            byCat.take(3).forEach { (k, v) -> LeaderRow(catOf(settings.categories, k)?.name ?: "No category", Books.plain(v)) }
            if (byCat.size > 3) LeaderRow("${byCat.size - 3} more", Books.plain(Books.round2(byCat.drop(3).sumOf { it.second })))
            LeaderRow("Tax paid", Books.plain(tax), dim = true)
            others.forEach { (c, list) -> LeaderRow("${list.size} $c bills", Books.plain(Books.round2(list.sumOf { it.total ?: 0.0 })), dim = true) }
          },
          totalLabel = "TOTAL $cur",
          total = Books.money(total, cur),
        )
        Gap(12)
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          leadingIcon = { Icon(Icons.Filled.Search, null) },
          placeholder = { Text("Search vendor, item or amount") },
        )
        if (query.isNotBlank()) {
          Gap(6)
          Text("Searching all bills: ${shown.size} found", color = p.muted, fontSize = 12.sp)
        }
      }
      if (bills.isEmpty()) {
        item { EmptyState() }
      } else if (shown.isEmpty()) {
        item {
          Gap(20)
          Text(if (query.isNotBlank()) "No bills match your search." else "No bills in this period.", color = p.muted)
          if (period != "all" && query.isBlank()) TextButton(onClick = { period = "all" }) { Text("Show all time") }
        }
      } else {
        val groups = shown.groupBy { Books.recDate(it) }
        groups.forEach { (day, list) ->
          item(key = "day-$day") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 6.dp)) {
              SectionLabel(dayLabel(day), Modifier.weight(1f))
              SectionLabel(Books.money(Books.round2(list.filter { it.currency.ifEmpty { cur } == cur }.sumOf { it.total ?: 0.0 }), cur))
            }
          }
          items(list, key = { it.id }) { b -> BillRow(b, dups.containsKey(b.id), current == b.id, onOpen) }
        }
      }
    }

    Row(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.bg.copy(alpha = 0.94f)).padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OutlinedButton(onClick = onPick, modifier = Modifier.height(56.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        Icon(Icons.Filled.PhotoLibrary, "Upload photos", tint = p.ink)
      }
      Button(
        onClick = onScan,
        modifier = Modifier.weight(1f).height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.paper),
      ) {
        Icon(Icons.Filled.PhotoCamera, null)
        HGap(10)
        Text("Scan a bill", fontWeight = FontWeight.Bold, fontSize = 16.sp)
      }
    }
  }
}

private fun matches(b: Bill, q: String, cats: Map<String, String>): Boolean {
  val hay = listOf(
    b.vendor, b.summary, b.note, b.receiptNo, cats[b.category] ?: "", b.payMethod, b.cardLast4,
    b.total?.let { Books.plain(it) } ?: "", Books.recDate(b), Books.label(b),
  ).plus(b.items.map { it.d }).joinToString(" ").lowercase()
  return q.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.all { hay.contains(it.removePrefix("$")) }
}

@Composable
private fun ModelBanner(state: ModelManager.State, paused: Boolean, onSettings: () -> Unit) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(p.paper)
      .border(1.dp, p.line, RoundedCornerShape(14.dp)).clickable { onSettings() }.padding(14.dp)
  ) {
    when (state) {
      is ModelManager.State.Downloading -> {
        Text("Downloading the AI reader… ${(state.done * 100 / state.total.coerceAtLeast(1))}%", fontWeight = FontWeight.SemiBold, color = p.ink)
        Gap(8)
        LinearProgressIndicator(progress = { (state.done.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = p.ink, trackColor = p.field)
        if (state.note.isNotEmpty()) Text(state.note, color = p.muted, fontSize = 12.sp)
      }
      is ModelManager.State.Copying -> Text("Setting up the AI reader…", fontWeight = FontWeight.SemiBold, color = p.ink)
      is ModelManager.State.Failed -> {
        Text("The AI reader didn't download", fontWeight = FontWeight.SemiBold, color = p.bad)
        Text(state.message + " Tap to try again.", color = p.muted, fontSize = 13.sp)
      }
      is ModelManager.State.Ready -> if (paused) {
        Text("The AI reader is paused", fontWeight = FontWeight.SemiBold, color = p.warn)
        Text("It stopped unexpectedly on this phone, so bills are read with the basic reader. Tap to turn it back on.", color = p.muted, fontSize = 13.sp)
      }
      else -> {
        Text("Add the on-phone AI reader (2.6 GB)", fontWeight = FontWeight.SemiBold, color = p.ink)
        Text("Bills are read with basic rules until then. Tap to download it on Wi-Fi.", color = p.muted, fontSize = 13.sp)
      }
    }
  }
}

@Composable
private fun EmptyState() {
  val p = LocalPalette.current
  Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(p.paper).padding(18.dp)) {
    Text("Snap your first bill", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = p.ink)
    Gap(10)
    listOf(
      "Scan a bill with your camera, or upload photos you already have.",
      "Tap where it was spent, like Meals or Fuel.",
      "Your phone reads the vendor, date, items and taxes. Nothing leaves your phone.",
      "Export everything to Excel, save it to your phone or send it to Google Drive.",
    ).forEachIndexed { i, s ->
      Row(Modifier.padding(vertical = 4.dp)) {
        Text("${i + 1}.", color = p.ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Text(s, color = p.muted)
      }
    }
  }
}

@Composable
private fun BillRow(b: Bill, dup: Boolean, readingNow: Boolean, onOpen: (String) -> Unit) {
  val p = LocalPalette.current
  val app = App.instance
  val settings by app.prefs.settings.collectAsState()
  val photo = remember(b.photo) { app.store.photoFile(b) }
  val issues = Books.issues(b)
  Row(
    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(p.paper)
      .clickable { onOpen(b.id) }.padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(p.field)) {
      if (photo != null) AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    HGap(12)
    Column(Modifier.weight(1f)) {
      val waiting = b.status == Status.QUEUED || b.status == Status.READING
      Text(
        b.vendor.ifEmpty { if (waiting) "Reading the bill…" else "Unknown vendor" },
        fontWeight = if (b.vendor.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
        color = if (b.vendor.isEmpty()) p.muted else p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      Text(
        listOf(catLabel(settings.categories, b.category).takeIf { b.category != null || !b.autoCategory } ?: "Category coming…", payShort(b)).filter { it.isNotEmpty() }.joinToString(" · "),
        color = p.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      val tags = ArrayList<Pair<String, androidx.compose.ui.graphics.Color?>>()
      if (readingNow) tags.add("Reading" to p.ink) else if (waiting) tags.add("Waiting" to null)
      if (b.status == Status.FAILED) tags.add("Couldn't read" to p.bad)
      else if (!waiting && issues.isNotEmpty()) tags.add(issues.first() to p.warn)
      if (dup) tags.add("Duplicate?" to p.bad)
      if (b.personal) tags.add("Reimburse" to null)
      if (tags.isNotEmpty()) {
        Gap(4)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { tags.forEach { (t, c) -> Tag(t, c) } }
      }
    }
    HGap(8)
    Column(horizontalAlignment = Alignment.End) {
      Text(if (b.total != null) Books.money(b.total, b.currency) else "—", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (b.total != null) p.ink else p.muted)
      val tax = Books.taxTotal(b)
      if (tax != 0.0) Text("tax ${Books.plain(tax)}", fontFamily = Mono, fontSize = 10.5.sp, color = p.muted)
    }
  }
}
