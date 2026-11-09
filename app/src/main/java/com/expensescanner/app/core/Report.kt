package com.expensescanner.app.core

import java.text.Normalizer
import java.time.LocalDate

/**
 * A receipt photo for the Excel file. The JPEG is only made while the file is being written, one
 * photo at a time, so a big export never holds every photo in memory.
 */
class PhotoForExcel(val w: Int, val h: Int, val jpeg: () -> ByteArray)

/** Builds the Excel expense report: Summary, Expenses, Line items and (optionally) Receipt photos. */
object Report {
  private val HEAD = listOf(
    "Receipt #", "Date", "Vendor", "Category", "What was bought", "Note / business purpose", "Paid with",
    "Paid personally", "Currency", "Subtotal", "GST/HST", "PST/QST/other tax", "Tip", "Total",
    "Vendor GST/HST #", "Receipt / invoice #", "Vendor address", "Time", "Items", "Read by", "Status", "Receipt photo", "Added",
  )
  private val WIDTH = listOf(10.0, 11.0, 26.0, 24.0, 30.0, 30.0, 16.0, 10.0, 9.0, 12.0, 11.0, 13.0, 9.0, 12.0, 18.0, 16.0, 32.0, 7.0, 7.0, 10.0, 30.0, 14.0, 16.0)
  private const val C_DATE = 1
  private const val C_CAT = 3
  private const val C_PERSONAL = 7
  private const val C_CUR = 8
  private const val C_SUB = 9
  private const val C_GST = 10
  private const val C_OTH = 11
  private const val C_TIP = 12
  private const val C_TOTAL = 13
  private const val C_PHOTO = 21
  private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
  private val S = XlsxWriter.Style

  private fun col(i: Int) = "Expenses!\$${XlsxWriter.colName(i)}:\$${XlsxWriter.colName(i)}"
  private fun money(v: Double?) = if (v == null || !v.isFinite()) null else Cell(Books.round2(v), S.MONEY)

  fun photoFileName(b: Bill): String {
    val vendor = Normalizer.normalize(b.vendor.ifEmpty { "bill" }, Normalizer.Form.NFKD)
      .replace(Regex("""[^\w]+"""), "-").trim('-').take(30).ifEmpty { "bill" }
    val total = b.total?.let { "_" + Books.plain(it) } ?: ""
    return "${Books.label(b)}_${Books.recDate(b)}_$vendor$total.jpg"
  }

  private fun payText(b: Bill) = if (b.cardLast4.isNotEmpty()) "${b.payMethod.ifEmpty { "Card" }} ••${b.cardLast4}" else b.payMethod

  fun build(billsIn: List<Bill>, settings: AppSettings, rangeLabel: String, photos: Map<String, PhotoForExcel>?, today: LocalDate = LocalDate.now()): XlsxWriter {
    val main = settings.currency
    val cats = settings.categories.associate { it.id to it.name }
    fun catName(id: String?) = if (id == null) "No category" else cats[id] ?: "Other"
    fun curOf(b: Bill) = b.currency.ifEmpty { main }
    val bills = billsIn.sortedWith(compareBy({ Books.recDate(it) }, { it.num }))
    val currencies = bills.map { curOf(it) }.distinct().sortedWith(compareBy({ if (it == main) 0 else 1 }, { it }))
    val dup = Books.dupIndex(bills)
    val wb = XlsxWriter(((if (settings.businessName.isNotEmpty()) settings.businessName + " " else "") + "Expense report"), settings.businessName.ifEmpty { "Expense Scanner" })
    val sum = wb.addSheet("Summary", listOf(32.0, 10.0, 8.0, 13.0, 12.0, 14.0, 10.0, 14.0))
    val exp = wb.addSheet("Expenses", WIDTH, freeze = 1)
    val lines = wb.addSheet("Line items", listOf(10.0, 11.0, 26.0, 24.0, 44.0, 7.0, 11.0, 12.0), freeze = 1)
    val pics = if (photos != null) wb.addSheet("Receipt photos", listOf(30.0, 60.0)) else null

    // Receipt photos first so the Expenses sheet can link to each row.
    val photoRow = HashMap<String, Int>()
    if (pics != null && photos != null) {
      pics.addRow(listOf(Cell("Receipt photos", S.TITLE)), 28.0)
      pics.addRow(listOf(Cell("Tip: \"View photo\" links in the Expenses sheet jump straight to each bill.", S.MUTED)))
      pics.addRow()
      for (b in bills) {
        val info = listOf(
          Books.label(b), Books.recDate(b), b.vendor.ifEmpty { "Unknown vendor" },
          b.total?.let { "${curOf(b)} ${Books.plain(it)}" } ?: "Total missing", catName(b.category), b.note,
        ).filter { it.isNotEmpty() }.joinToString("\n")
        val p = photos[b.id]
        if (p != null && p.w > 0 && p.h > 0) {
          val scale = minOf(380.0 / p.w, 520.0 / p.h, 1.0)
          val w = (p.w * scale).toInt()
          val h = (p.h * scale).toInt()
          val row = pics.addRow(listOf(Cell(info, S.WRAP_TOP)), maxOf(96.0, (h + 16) * 0.75))
          pics.images.add(SheetImage(row, 1, p.jpeg, w, h, Books.label(b), "${b.vendor.ifEmpty { "Bill" }} ${Books.recDate(b)}"))
          photoRow[b.id] = row
        } else {
          photoRow[b.id] = pics.addRow(listOf(Cell(info, S.WRAP_TOP), Cell("No photo saved", S.MUTED)), 96.0)
        }
      }
    }

    // Expenses: one row per bill.
    exp.addRow(HEAD.map { Cell(it, S.HEADER) }, 32.0)
    for (b in bills) {
      val t = Books.taxSplit(b)
      val notes = Books.issues(b).map { if (it == "Date missing") "Date missing (upload date used)" else it }.toMutableList()
      dup[b.id]?.let { notes.add("Possible duplicate of ${Books.label(it)}") }
      val status = if (notes.isEmpty()) "OK" else notes.joinToString("; ")
      val added = Books.localDateTime(b.createdAt)
      val photoCell = when {
        photos != null -> if (photos.containsKey(b.id)) Cell("View photo", S.LINK) else Cell("No photo", S.MUTED)
        b.photo != null -> Cell(photoFileName(b))
        else -> null
      }
      val r = exp.addRow(listOf(
        Cell(Books.label(b)),
        Cell(XlsxWriter.dateSerial(Books.recDate(b)), S.DATE),
        Cell(b.vendor),
        Cell(catName(b.category)),
        Cell(b.summary),
        Cell(b.note),
        Cell(payText(b)),
        Cell(if (b.personal) "Yes" else ""),
        Cell(curOf(b)),
        money(b.subtotal),
        if (t.any) money(t.gst) else null,
        if (t.any) money(t.other) else null,
        money(b.tip),
        money(b.total),
        Cell(b.vendorTaxId),
        Cell(b.receiptNo),
        Cell(b.vendorAddress),
        Cell(b.time),
        if (b.items.isNotEmpty()) Cell(b.items.size, S.INT) else null,
        Cell(when (b.readBy) { "gemma" -> "AI"; "rules" -> "Rules"; else -> "Manual" }),
        Cell(status, if (notes.isEmpty()) S.NORMAL else S.WARN),
        photoCell,
        Cell(XlsxWriter.dateTimeSerial(added), S.DATE_TIME),
      ))
      if (photos != null && photos.containsKey(b.id)) photoRow[b.id]?.let { exp.addLink(r, C_PHOTO, "'Receipt photos'!A${it + 1}", "View photo") }
    }
    exp.autoFilter = "A1:${XlsxWriter.colName(HEAD.size - 1)}${maxOf(2, bills.size + 1)}"

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
    if (flagged > 0) sum.addRow(listOf(Cell("$flagged bill${if (flagged == 1) " needs" else "s need"} a second look: see the Status column in Expenses.", S.WARN)))
    return wb
  }
}
