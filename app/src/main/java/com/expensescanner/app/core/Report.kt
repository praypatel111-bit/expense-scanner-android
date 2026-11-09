package com.expensescanner.app.core

import java.text.Normalizer
import java.time.LocalDate

/**
 * A receipt photo for the Excel file. The JPEG is only made while the file is being written, one
 * photo at a time, so a big export never holds every photo in memory.
 */
class PhotoForExcel(val w: Int, val h: Int, val jpeg: () -> ByteArray)

/**
 * Builds the Excel expense report. Sheet 1, "Bills", lists every bill (one row each, newest last)
 * with a one-click "Open receipt" link. With photos, each bill's photo sits below the list on the
 * same sheet and the link jumps to it. Without photos, a [build] photoFolder (the .zip download's
 * "Receipts/") makes the link open the photo file instead. Then Summary and Line items.
 */
object Report {
  private val HEAD = listOf(
    "Receipt #", "Date", "Vendor", "Category", "Total", "Currency", "Receipt photo",
    "What was bought", "Note / business purpose", "Paid with", "Paid personally",
    "Subtotal", "GST/HST", "PST/QST/other tax", "Tip",
    "Vendor GST/HST #", "Receipt / invoice #", "Vendor address", "Time", "Items", "Read by", "Status", "Added",
  )
  private val WIDTH = listOf(10.0, 11.0, 26.0, 22.0, 12.0, 9.0, 15.0, 30.0, 30.0, 16.0, 10.0, 12.0, 11.0, 13.0, 9.0, 18.0, 16.0, 32.0, 7.0, 7.0, 10.0, 30.0, 16.0)
  private const val C_DATE = 1
  private const val C_CAT = 3
  private const val C_TOTAL = 4
  private const val C_CUR = 5
  private const val C_PHOTO = 6
  private const val C_PERSONAL = 10
  private const val C_SUB = 11
  private const val C_GST = 12
  private const val C_OTH = 13
  private const val C_TIP = 14
  private const val C_BACK = 7
  private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
  private val S = XlsxWriter.Style

  private fun money(v: Double?) = if (v == null || !v.isFinite()) null else Cell(Books.round2(v), S.MONEY)
  private fun sorted(bills: List<Bill>) = bills.sortedWith(compareBy({ Books.recDate(it) }, { it.num }))

  fun photoFileName(b: Bill): String {
    val vendor = Normalizer.normalize(b.vendor.ifEmpty { "bill" }, Normalizer.Form.NFKD)
      .replace(Regex("""[^\w]+"""), "-").trim('-').take(30).ifEmpty { "bill" }
    val total = b.total?.let { "_" + Books.plain(it) } ?: ""
    return "${Books.label(b)}_${Books.recDate(b)}_$vendor$total.jpg"
  }

  /** File name for each bill with a photo, unique within one download (same names in the Excel and the .zip). */
  fun photoFileNames(bills: List<Bill>): Map<String, String> {
    val used = HashSet<String>()
    val out = LinkedHashMap<String, String>()
    for (b in sorted(bills)) {
      if (b.photo == null) continue
      val base = photoFileName(b)
      var name = base
      var k = 2
      while (!used.add(name.lowercase())) name = base.removeSuffix(".jpg") + "_${k++}.jpg"
      out[b.id] = name
    }
    return out
  }

  private fun payText(b: Bill) = if (b.cardLast4.isNotEmpty()) "${b.payMethod.ifEmpty { "Card" }} ••${b.cardLast4}" else b.payMethod

  fun build(
    billsIn: List<Bill>, settings: AppSettings, rangeLabel: String, photos: Map<String, PhotoForExcel>?,
    today: LocalDate = LocalDate.now(), photoFolder: String? = null,
  ): XlsxWriter {
    val main = settings.currency
    val cats = settings.categories.associate { it.id to it.name }
    fun catName(id: String?) = if (id == null) "No category" else cats[id] ?: "Other"
    fun curOf(b: Bill) = b.currency.ifEmpty { main }
    val bills = sorted(billsIn)
    val currencies = bills.map { curOf(it) }.distinct().sortedWith(compareBy({ if (it == main) 0 else 1 }, { it }))
    val dup = Books.dupIndex(bills)
    val fileNames = photoFileNames(bills)
    fun photoOf(b: Bill) = photos?.get(b.id)?.takeIf { it.w > 0 && it.h > 0 }
    val wb = XlsxWriter(((if (settings.businessName.isNotEmpty()) settings.businessName + " " else "") + "Expense report"), settings.businessName.ifEmpty { "Expense Scanner" })
    val sheet = wb.addSheet("Bills", WIDTH, freeze = 1)
    val sum = wb.addSheet("Summary", listOf(32.0, 10.0, 8.0, 13.0, 12.0, 14.0, 10.0, 14.0))
    val lines = wb.addSheet("Line items", listOf(10.0, 11.0, 26.0, 24.0, 44.0, 7.0, 11.0, 12.0), freeze = 1)
    // Summary formulas read only the bill rows (the photos sit below them on the same sheet).
    val lastBillRow = maxOf(2, bills.size + 1)
    fun col(i: Int): String {
      val c = XlsxWriter.colName(i)
      return "Bills!\$$c\$2:\$$c\$$lastBillRow"
    }

    // Bills: one row per bill.
    sheet.addRow(HEAD.map { Cell(it, S.HEADER) }, 32.0)
    val billRow = HashMap<String, Int>()
    for (b in bills) {
      val t = Books.taxSplit(b)
      val notes = Books.issues(b).map { if (it == "Date missing") "Date missing (upload date used)" else it }.toMutableList()
      dup[b.id]?.let { notes.add("Possible duplicate of ${Books.label(it)}") }
      val status = if (notes.isEmpty()) "OK" else notes.joinToString("; ")
      val added = Books.localDateTime(b.createdAt)
      val file = fileNames[b.id]
      val photoCell = when {
        photos != null -> if (photoOf(b) != null) Cell("Open receipt", S.LINK) else Cell("No photo", S.MUTED)
        file != null && photoFolder != null -> Cell("Open receipt", S.LINK)
        file != null -> Cell(file)
        else -> Cell("No photo", S.MUTED)
      }
      val r = sheet.addRow(listOf(
        Cell(Books.label(b)),
        Cell(XlsxWriter.dateSerial(Books.recDate(b)), S.DATE),
        Cell(b.vendor),
        Cell(catName(b.category)),
        money(b.total),
        Cell(curOf(b)),
        photoCell,
        Cell(b.summary),
        Cell(b.note),
        Cell(payText(b)),
        Cell(if (b.personal) "Yes" else ""),
        money(b.subtotal),
        if (t.any) money(t.gst) else null,
        if (t.any) money(t.other) else null,
        money(b.tip),
        Cell(b.vendorTaxId),
        Cell(b.receiptNo),
        Cell(b.vendorAddress),
        Cell(b.time),
        if (b.items.isNotEmpty()) Cell(b.items.size, S.INT) else null,
        Cell(when (b.readBy) { "gemma" -> "AI"; "rules" -> "Rules"; else -> "Manual" }),
        Cell(status, if (notes.isEmpty()) S.NORMAL else S.WARN),
        Cell(XlsxWriter.dateTimeSerial(added), S.DATE_TIME),
      ))
      billRow[b.id] = r
      if (photos == null && file != null && photoFolder != null) sheet.addFileLink(r, C_PHOTO, photoFolder + file, "Open receipt")
    }
    sheet.autoFilter = "A1:${XlsxWriter.colName(HEAD.size - 1)}$lastBillRow"

    // Receipt photos below the list, each with a link back to its row.
    if (bills.any { photoOf(it) != null }) {
      sheet.addRow()
      sheet.addRow(listOf(Cell("Receipt photos", S.TITLE)), 28.0)
      sheet.addRow(listOf(Cell("Click \"Open receipt\" in a bill's row to jump to its photo. To save a photo, right-click it and choose Save as Picture.", S.MUTED)))
      for (b in bills) {
        val p = photoOf(b) ?: continue
        val scale = minOf(420.0 / p.w, 520.0 / p.h, 1.0)
        val w = (p.w * scale).toInt()
        val h = (p.h * scale).toInt()
        val caption = listOfNotNull(
          Books.label(b), Books.recDate(b), b.vendor.ifEmpty { "Unknown vendor" },
          b.total?.let { "${curOf(b)} ${Books.plain(it)}" }, catName(b.category), b.note.takeIf { it.isNotEmpty() },
        ).joinToString("  ·  ")
        sheet.addRow()
        val cells = MutableList<Cell?>(C_BACK + 1) { null }
        cells[0] = Cell(caption, S.BOLD)
        cells[C_BACK] = Cell("↑ Back to the list", S.LINK)
        val titleRow = sheet.addRow(cells, 20.0)
        val row = billRow.getValue(b.id)
        sheet.addLink(titleRow, C_BACK, "'Bills'!A${row + 1}", "↑ Back to the list")
        sheet.addLink(row, C_PHOTO, "'Bills'!A${titleRow + 1}", "Open receipt")
        val picRow = sheet.addRow(emptyList(), (h + 16) * 0.75)
        sheet.images.add(SheetImage(picRow, 0, p.jpeg, w, h, Books.label(b), "${b.vendor.ifEmpty { "Bill" }} ${Books.recDate(b)}"))
      }
    }

    // Line items
    lines.addRow(listOf("Receipt #", "Date", "Vendor", "Category", "Item", "Qty", "Unit price", "Amount").map { Cell(it, S.HEADER) }, 24.0)
    var lineCount = 0
    for (b in bills) for (it in b.items) {
      lines.addRow(listOf(Cell(Books.label(b)), Cell(XlsxWriter.dateSerial(Books.recDate(b)), S.DATE), Cell(b.vendor), Cell(catName(b.category)), Cell(it.d), it.q?.let { q -> Cell(q) }, money(it.u), money(it.a)))
      lineCount++
    }
    if (lineCount > 0) lines.autoFilter = "A1:H${lineCount + 1}" else lines.addRow(listOf(Cell("No line items were read from these bills.", S.MUTED)))

    // Summary with live formulas (cached values included for viewers that don't recalculate).
    val title = (if (settings.businessName.isNotEmpty()) settings.businessName + " · " else "") + "Expense report"
    sum.addRow(listOf(Cell(title, S.TITLE)), 28.0)
    sum.addRow(listOf(Cell("Period: $rangeLabel", S.MUTED)))
    sum.addRow(listOf(Cell("${bills.size} bill${if (bills.size == 1) "" else "s"} · generated $today · amounts in each bill's own currency", S.MUTED)))
    sum.addRow()

    class Measure(val title: String, val col: Int, val value: (Bill) -> Double)
    fun group(title: String, first: String, keyOf: (Bill) -> String?, crit: (String, Int) -> String, measures: List<Measure>, sortByTotal: Boolean) {
      sum.addRow(listOf(Cell(title, S.SECTION)), 20.0)
      sum.addRow((listOf(first, "Currency", "Bills") + measures.map { it.title }).map { Cell(it, S.HEADER) }, 22.0)
      for (cur in currencies) {
        val groups = LinkedHashMap<String, MutableList<Bill>>()
        for (b in bills) {
          if (curOf(b) != cur) continue
          val k = keyOf(b) ?: continue
          groups.getOrPut(k) { ArrayList() }.add(b)
        }
        if (groups.isEmpty()) continue
        var entries = groups.entries.toList()
        if (sortByTotal) entries = entries.sortedByDescending { e -> e.value.sumOf { it.total ?: 0.0 } }
        val firstRow = sum.rows.size
        for ((k, rs) in entries) {
          val r1 = sum.rows.size + 1
          val base = "${crit(k, r1)},${col(C_CUR)},\$B$r1"
          sum.addRow(listOf(Cell(k), Cell(cur), Cell(rs.size, S.INT, "COUNTIFS($base)")) +
            measures.map { m -> Cell(Books.round2(rs.sumOf(m.value)), S.MONEY, "SUMIFS(${col(m.col)},$base)") })
        }
        val last = sum.rows.size
        val grand = cur == main
        val all = entries.flatMap { it.value }
        fun tot(c: Int) = "SUM(${XlsxWriter.colName(c)}${firstRow + 1}:${XlsxWriter.colName(c)}$last)"
        sum.addRow(listOf(
          Cell("Total", if (grand) S.LABEL_GRAND else S.LABEL_TOTAL),
          Cell(cur, if (grand) S.LABEL_GRAND else S.LABEL_TOTAL),
          Cell(all.size, if (grand) S.INT_GRAND else S.INT_TOTAL, tot(2)),
        ) + measures.mapIndexed { i, m -> Cell(Books.round2(all.sumOf(m.value)), if (grand) S.MONEY_GRAND else S.MONEY_TOTAL, tot(3 + i)) })
      }
      sum.addRow()
    }
    val totalM = Measure("Total", C_TOTAL) { it.total ?: 0.0 }
    group(
      "By category", "Category", { catName(it.category) }, { _, r1 -> "${col(C_CAT)},\$A$r1" },
      listOf(
        Measure("Subtotal", C_SUB) { it.subtotal ?: 0.0 },
        Measure("GST/HST", C_GST) { b -> Books.taxSplit(b).let { if (it.any) it.gst else 0.0 } },
        Measure("PST/QST/other", C_OTH) { b -> Books.taxSplit(b).let { if (it.any) it.other else 0.0 } },
        Measure("Tip", C_TIP) { it.tip ?: 0.0 },
        totalM,
      ),
      sortByTotal = true,
    )
    group(
      "By month", "Month", { b -> Books.recDate(b).take(7) },
      { k, _ ->
        val y = k.take(4).toInt()
        val m = k.substring(5, 7).toInt()
        val ny = if (m == 12) y + 1 else y
        val nm = if (m == 12) 1 else m + 1
        "${col(C_DATE)},\">=\"&DATE($y,$m,1),${col(C_DATE)},\"<\"&DATE($ny,$nm,1)"
      },
      listOf(totalM), sortByTotal = false,
    )
    // Month labels: replace keys like 2026-11 with "Nov 2026" in place (formulas use the dates, not the label).
    for (i in sum.rows.indices) {
      val (cells, h) = sum.rows[i]
      val first = cells.firstOrNull()?.v as? String ?: continue
      if (Regex("""^\d{4}-\d{2}$""").matches(first)) {
        val label = "${MONTHS[first.substring(5, 7).toInt() - 1]} ${first.take(4)}"
        sum.rows[i] = (listOf(Cell(label)) + cells.drop(1)) to h
      }
    }
    if (bills.any { it.personal }) {
      group(
        "Paid personally (to reimburse)", "Paid with", { if (it.personal) "Paid personally" else null },
        { _, _ -> "${col(C_PERSONAL)},\"Yes\"" }, listOf(totalM), sortByTotal = false,
      )
    }
    val flagged = bills.count { Books.issues(it).isNotEmpty() || dup.containsKey(it.id) }
    if (flagged > 0) sum.addRow(listOf(Cell("$flagged bill${if (flagged == 1) " needs" else "s need"} a second look: see the Status column on the Bills sheet.", S.WARN)))
    return wb
  }
}
