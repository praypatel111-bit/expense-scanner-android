@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensescanner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.expensescanner.app.App
import com.expensescanner.app.core.Bill
import com.expensescanner.app.core.Books
import com.expensescanner.app.core.Item
import com.expensescanner.app.core.Status
import com.expensescanner.app.core.TaxLine

/** One tax line being edited; [orig] keeps details the form doesn't show (the rate). */
private class TaxRow(val orig: TaxLine?, label: String, amount: String) {
  var label by mutableStateOf(label)
  var amount by mutableStateOf(amount)
}

/** One item being edited; [orig] keeps details the form doesn't show (the unit price). */
private class ItemRow(val orig: Item?, d: String, q: String, a: String) {
  var d by mutableStateOf(d)
  var q by mutableStateOf(q)
  var a by mutableStateOf(a)
}

private fun qtyText(q: Double?): String = when {
  q == null -> ""
  q == Math.rint(q) -> q.toLong().toString()
  else -> q.toString()
}

/** Editable copy of a bill; text fields keep exactly what the person typed until Save. */
private class Form(b: Bill) {
  var vendor by mutableStateOf(b.vendor)
  var date by mutableStateOf(b.date)
  var total by mutableStateOf(Books.plain(b.total))
  var currency by mutableStateOf(b.currency)
  var category by mutableStateOf(b.category)
  var note by mutableStateOf(b.note)
  var personal by mutableStateOf(b.personal)
  var subtotal by mutableStateOf(Books.plain(b.subtotal))
  var tip by mutableStateOf(Books.plain(b.tip))
  var discount by mutableStateOf(Books.plain(b.discount))
  var payMethod by mutableStateOf(b.payMethod)
  var cardLast4 by mutableStateOf(b.cardLast4)
  var receiptNo by mutableStateOf(b.receiptNo)
  var time by mutableStateOf(b.time)
  var vendorTaxId by mutableStateOf(b.vendorTaxId)
  var vendorAddress by mutableStateOf(b.vendorAddress)
  var summary by mutableStateOf(b.summary)
  val taxes = mutableStateListOf<TaxRow>().apply { b.taxes.forEach { t -> add(TaxRow(t, t.label, Books.plain(t.amount))) } }
  val items = mutableStateListOf<ItemRow>().apply { b.items.forEach { i -> add(ItemRow(i, i.d, qtyText(i.q), Books.plain(i.a))) } }

  /** Everything the person can type, exactly as shown. Unsaved changes = this differs from [initial]. */
  fun snapshot(): List<Any?> = listOf(
    vendor, date, total, currency, category, note, personal, subtotal, tip, discount, payMethod, cardLast4,
    receiptNo, time, vendorTaxId, vendorAddress, summary,
    taxes.map { listOf(it.label, it.amount) }, items.map { listOf(it.d, it.q, it.a) },
  )

  val initial = snapshot()

  fun toBill(b: Bill): Bill {
    val newSubtotal = Books.parseAmount(subtotal)
    return b.copy(
      vendor = vendor.trim(),
      date = Books.parseIso(date.trim())?.toString() ?: b.date.takeIf { date.isNotBlank() } ?: "",
      total = Books.parseAmount(total),
      currency = currency.trim().uppercase().take(3),
      category = category,
      autoCategory = if (category != b.category) false else b.autoCategory,
      note = note.trim(),
      personal = personal,
      subtotal = newSubtotal,
      subtotalDerived = if (newSubtotal != b.subtotal) false else b.subtotalDerived,
      tip = Books.parseAmount(tip),
      discount = Books.parseAmount(discount),
      payMethod = payMethod.trim(),
      cardLast4 = cardLast4.filter { it.isDigit() }.takeLast(4),
      receiptNo = receiptNo.trim(),
      time = time.trim(),
      vendorTaxId = vendorTaxId.trim(),
      vendorAddress = vendorAddress.trim(),
      summary = summary.trim(),
      taxes = taxes.mapNotNull { r ->
        val amt = Books.parseAmount(r.amount) ?: return@mapNotNull null
        val o = r.orig
        TaxLine(r.label.trim().ifEmpty { "Tax" }, if (o != null && o.amount == amt) o.rate else null, amt)
      },
      items = items.mapNotNull { r ->
        val a = Books.parseAmount(r.a)
        if (r.d.isBlank() && a == null) return@mapNotNull null
        val q = r.q.trim().replace(',', '.').toDoubleOrNull()
        val o = r.orig
        Item(r.d.trim(), q, if (o != null && o.q == q && o.a == a) o.u else null, a)
      },
    )
  }
}

@Composable
fun BillScreen(id: String, onBack: () -> Unit, notify: (String) -> Unit) {
  val p = LocalPalette.current
  val app = App.instance
  val bills by app.store.bills.collectAsState()
  val settings by app.prefs.settings.collectAsState()
  val bill = bills.firstOrNull { it.id == id }
  if (bill == null) {
    LaunchedEffect(Unit) { onBack() }
    return
  }
  var form by remember(id) { mutableStateOf(Form(bill)) }
  var askDelete by remember(id) { mutableStateOf(false) }
  var askLeave by remember(id) { mutableStateOf(false) }
  var zoom by remember(id) { mutableStateOf(false) }
  val dirty = form.snapshot() != form.initial

  // Reading finished (or anything else changed) while the screen is open: refresh untouched fields.
  LaunchedEffect(bill.updatedAt) {
    if (!dirty) form = Form(bill)
  }

  fun save() {
    val latest = app.store.get(id) ?: return
    val edited = form.toBill(latest)
    app.store.upsert(edited.copy(reviewed = true, status = if (latest.status == Status.FAILED) Status.READY else latest.status, updatedAt = System.currentTimeMillis()))
    notify("Saved")
    onBack()
  }

  androidx.activity.compose.BackHandler { if (dirty) askLeave = true else onBack() }

  Scaffold(
    containerColor = p.bg,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
          Column {
            Text(Books.label(bill), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
              when (bill.readBy) {
                "gemma" -> "Read on your phone by AI"
                "rules" -> "Read on your phone (basic)"
                else -> "Added " + Books.localDateTime(bill.createdAt).toLocalDate().toString()
              },
              color = p.muted, fontSize = 12.sp,
            )
          }
        },
        navigationIcon = { IconButton(onClick = { if (dirty) askLeave = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = {
          Button(
            onClick = { save() },
            colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.paper),
            modifier = Modifier.padding(end = 8.dp),
          ) { Text("Save") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
      )
    },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp)) {
      val photo = app.store.photoFile(bill)
      if (photo != null) {
        AsyncImage(
          model = photo,
          contentDescription = "Photo of the bill. Tap to zoom.",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(p.field).clickable { zoom = true },
        )
      }
      Gap(10)
      Notes(bill, bills)
      Gap(6)
      Field("Vendor", form.vendor) { form.vendor = it }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Date (YYYY-MM-DD)", form.date, Modifier.weight(1.25f)) { form.date = it }
        Field("Total", form.total, Modifier.weight(1f), number = true, mono = true) { form.total = it }
        Field("Currency", form.currency, Modifier.width(84.dp)) { form.currency = it }
      }
      Gap(8)
      SectionLabel("Where it was spent")
      Gap(6)
      // Start the row at the chosen category so it's visible without scrolling.
      val chips = rememberLazyListState(initialFirstVisibleItemIndex = settings.categories.indexOfFirst { it.id == form.category }.coerceAtLeast(0))
      LazyRow(state = chips, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(settings.categories, key = { it.id }) { c ->
          FilterChip(selected = form.category == c.id, onClick = { form.category = c.id }, label = { Text("${c.icon} ${c.name}") })
        }
      }
      Gap(8)
      Field("Note or business purpose", form.note, singleLine = false) { form.note = it }
      Row(Modifier.fillMaxWidth().clickable { form.personal = !form.personal }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = form.personal, onCheckedChange = { form.personal = it })
        HGap(12)
        Text("Paid with my own money (reimburse me)", color = p.ink, fontSize = 14.sp)
      }
      Gap(10)
      SectionLabel("Amounts")
      Gap(4)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Subtotal", form.subtotal, Modifier.weight(1f), number = true, mono = true) { form.subtotal = it }
        Field("Tip", form.tip, Modifier.weight(1f), number = true, mono = true) { form.tip = it }
        Field("Discount", form.discount, Modifier.weight(1f), number = true, mono = true) { form.discount = it }
      }
      Gap(6)
      Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Taxes", Modifier.weight(1f))
        TextButton(onClick = { form.taxes.add(TaxRow(null, if (form.taxes.isEmpty()) "HST" else "", "")) }) {
          Icon(Icons.Filled.Add, null)
          Text("Add tax line")
        }
      }
      form.taxes.forEachIndexed { i, t ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Field("Tax", t.label, Modifier.weight(1f)) { t.label = it }
          Field("Amount", t.amount, Modifier.weight(1f), number = true, mono = true) { t.amount = it }
          IconButton(onClick = { form.taxes.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove tax line", tint = p.muted) }
        }
      }
      Gap(6)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Paid with", form.payMethod, Modifier.weight(1f)) { form.payMethod = it }
        Field("Card last 4", form.cardLast4, Modifier.weight(0.8f), number = true) { form.cardLast4 = it }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Receipt / invoice #", form.receiptNo, Modifier.weight(1f)) { form.receiptNo = it }
        Field("Time", form.time, Modifier.weight(0.6f)) { form.time = it }
      }
      Field("Vendor GST/HST number", form.vendorTaxId) { form.vendorTaxId = it }
      Field("Vendor address", form.vendorAddress) { form.vendorAddress = it }
      Field("What was bought", form.summary) { form.summary = it }
      Gap(10)
      Row(verticalAlignment = Alignment.CenterVertically) {
        val sum = Books.round2(form.items.sumOf { Books.parseAmount(it.a) ?: 0.0 })
        SectionLabel("Items (${form.items.size}) · sum ${Books.plain(sum)}", Modifier.weight(1f))
        TextButton(onClick = { form.items.add(ItemRow(null, "", "", "")) }) {
          Icon(Icons.Filled.Add, null)
          Text("Add item")
        }
      }
      form.items.forEachIndexed { i, row ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Field("Item", row.d, Modifier.weight(1f)) { v -> row.d = v }
          Field("Qty", row.q, Modifier.width(64.dp), number = true) { v -> row.q = v }
          Field("Amount", row.a, Modifier.width(96.dp), number = true, mono = true) { v -> row.a = v }
          IconButton(onClick = { form.items.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove item", tint = p.muted) }
        }
      }
      Gap(18)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = {
          app.processor.enqueue(id, overwrite = true)
          notify("Reading the bill again…")
        }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Refresh, null)
          HGap(6)
          Text("Read again")
        }
        OutlinedButton(onClick = { askDelete = true }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Delete, null, tint = p.bad)
          HGap(6)
          Text("Delete", color = p.bad)
        }
      }
      if (bill.ocrText.isNotEmpty()) {
        Gap(18)
        SectionLabel("Text found on the photo")
        Gap(4)
        Text(bill.ocrText, fontFamily = Mono, fontSize = 11.sp, color = p.muted, lineHeight = 15.sp)
      }
    }
  }

  if (askDelete) {
    AlertDialog(
      onDismissRequest = { askDelete = false },
      title = { Text("Delete this bill?") },
      text = { Text("${bill.vendor.ifEmpty { "This bill" }} ${Books.money(bill.total, bill.currency).takeIf { bill.total != null } ?: ""} and its photo will be removed from your phone.") },
      confirmButton = {
        TextButton(onClick = {
          askDelete = false
          app.store.delete(id)
          notify("Bill deleted")
          onBack()
        }) { Text("Delete", color = p.bad) }
      },
      dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
    )
  }
  if (askLeave) {
    AlertDialog(
      onDismissRequest = { askLeave = false },
      title = { Text("Save your changes?") },
      confirmButton = { TextButton(onClick = { askLeave = false; save() }) { Text("Save") } },
      dismissButton = { TextButton(onClick = { askLeave = false; onBack() }) { Text("Discard") } },
    )
  }
  if (zoom) {
    val photo = app.store.photoFile(bill)
    Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      var scale by remember { mutableStateOf(1f) }
      var offset by remember { mutableStateOf(Offset.Zero) }
      Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
          model = photo,
          contentDescription = "Photo of the bill",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
              detectTransformGestures { _, pan, z, _ ->
                scale = (scale * z).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
              }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
        IconButton(onClick = { zoom = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
          Icon(Icons.Filled.Close, "Close", tint = Color.White)
        }
      }
    }
  }
}

@Composable
private fun Notes(bill: Bill, all: List<Bill>) {
  val p = LocalPalette.current
  val notes = ArrayList<Pair<String, Color>>()
  when (bill.status) {
    Status.QUEUED -> notes.add("Waiting to be read on your phone." to p.ink)
    Status.READING -> notes.add("Reading this bill now. The fields fill in when it's done." to p.ink)
    Status.FAILED -> notes.add("Couldn't read this bill: ${bill.error}. Fill it in by hand or tap Read again." to p.bad)
    else -> {}
  }
  Books.issues(bill).forEach { notes.add(it to p.warn) }
  bill.warnings.forEach { notes.add(it to p.muted) }
  Books.dupIndex(all)[bill.id]?.let { d -> notes.add("Looks like the same bill as ${Books.label(d)} (${d.vendor.ifEmpty { "unknown" }}, ${shortDate(d.date)})." to p.bad) }
  notes.forEach { (t, c) ->
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(10.dp)).background(p.paper)) {
      Box(Modifier.width(4.dp).fillMaxHeight().background(c))
      Text(t, color = p.ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
    }
  }
}

@Composable
private fun Field(
  label: String,
  value: String,
  modifier: Modifier = Modifier.fillMaxWidth(),
  number: Boolean = false,
  mono: Boolean = false,
  singleLine: Boolean = true,
  onChange: (String) -> Unit,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label, maxLines = 1) },
    singleLine = singleLine,
    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (mono) Mono else null, fontSize = 15.sp),
    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    modifier = modifier.padding(vertical = 3.dp),
  )
}
