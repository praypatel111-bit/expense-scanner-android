import com.expensescanner.app.core.*
import java.io.File
import java.time.LocalDate

fun check(cond: Boolean, msg: String) { if (!cond) { println("FAIL: $msg"); failures++ } else println("ok: $msg") }
var failures = 0

fun main() {
  val today = LocalDate.of(2026, 11, 9)
  // 1. JSON
  val j = Json.parse("""{"a":[1,2.5,"x\n\"y\""],"b":null,"c":true,"d":{"e":-3e2}}""") as Map<*, *>
  check((j["a"] as List<*>)[1] == 2.5, "json numbers")
  check(Json.stringify(j) == """{"a":[1,2.5,"x\n\"y\""],"b":null,"c":true,"d":{"e":-300}}""", "json round trip: " + Json.stringify(j))
  check(Json.parseObjectLoose("Sure! ```json\n{\"total\": \"14.35\", \"x\": \"}\"}\n``` done")?.get("total") == "14.35", "loose json")

  // 2. Layout with a 4 degree tilt: item text and price on separate OCR lines
  val a = Math.toRadians(4.0)
  fun line(t: String, x: Double, y: Double, w: Double, h: Double): OcrLine {
    val pts = listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h).map { (dx, dy) -> (x + dx) * Math.cos(a) - (y + dy) * Math.sin(a) to (x + dx) * Math.sin(a) + (y + dy) * Math.cos(a) }
    return OcrLine(t, pts.map { it.first.toFloat() }.toFloatArray(), pts.map { it.second.toFloat() }.toFloatArray())
  }
  val rows = Layout.rows(listOf(line("TIM HORTONS", 200.0, 10.0, 220.0, 30.0), line("2.45", 520.0, 100.0, 60.0, 28.0), line("LG ORIGINAL BLEND", 40.0, 102.0, 300.0, 28.0), line("5.89", 520.0, 140.0, 60.0, 28.0), line("BACON EGG CHEESE", 40.0, 141.0, 280.0, 28.0)))
  println(rows)
  check(rows.size == 3 && rows[1] == "LG ORIGINAL BLEND | 2.45" && rows[2] == "BACON EGG CHEESE | 5.89", "layout joins item and price rows despite tilt")

  // 3. Rule parser on OCR-like rows
  val tims = listOf("TIM HORTONS", "#2841 - 100 KING ST W", "TORONTO ON M5X 1A9", "HST# 123456789 RT0001", "11/09/26 | 08:14", "Order # | 57",
    "LG ORIGINAL BLEND | 2.45", "BACON EGG CHEESE | 5.89", "HASH BROWN | 2.19", "TIMBITS 10PK | 2.17", "SUBTOTAL | 12.70", "HST 13% | 1.65", "TOTAL | 14.35",
    "VISA ************4417 | 14.35", "APPROVED AUTH 08812", "THANK YOU")
  val r = RuleParser.parse(tims, today)
  println(r)
  check(r.vendor == "Tim Hortons", "vendor")
  check(r.date == "2026-11-09" && r.time == "08:14", "date + time (MM/DD/YY disambiguated)")
  check(r.total == 14.35 && r.subtotal == 12.70, "total + subtotal")
  check(r.taxes.size == 1 && r.taxes[0].amount == 1.65 && r.taxes[0].label.startsWith("HST"), "tax line")
  check(r.items.size == 4 && r.items[0].d == "LG ORIGINAL BLEND" && r.items[0].a == 2.45, "items")
  check(r.cardLast4 == "4417" && r.payMethod == "Visa", "card")
  check(r.vendorTaxId == "123456789 RT0001", "tax id")
  check(r.receiptNo == "57", "receipt no: " + r.receiptNo)
  check(r.category == "meals" && r.confidence == "high", "category + confidence")
  check(r.vendorAddress.contains("KING ST"), "address: " + r.vendorAddress)

  val staples = listOf("STAPLES", "STORE 0412 555 YONGE ST", "TORONTO ON", "GST/HST 897654321 RT0001", "2026-11-07 15:42", "TRANS 0412-03-55871",
    "PRINTER PAPER 5000 | 54.99", "HP 63XL INK | 49.99", "2 @ SHARPIE 12PK | 14.49", "SUBTOTAL | 119.47", "HST 13% | 15.53", "TOTAL | 135.00", "MASTERCARD ****0921 | 135.00")
  val s = RuleParser.parse(staples, today)
  println(s)
  check(s.date == "2026-11-07" && s.time == "15:42", "staples date/time")
  check(s.total == 135.00 && s.taxes.single().amount == 15.53, "staples totals")
  check(s.category == "office" && s.cardLast4 == "0921" && s.payMethod == "Mastercard", "staples category/card")
  check(s.receiptNo == "0412-03-55871", "staples trans no: " + s.receiptNo)
  check(s.items.size == 3 && s.items[2].q == 2.0 && s.items[2].d == "SHARPIE 12PK", "qty prefix: " + s.items)

  // 4. Model output normalisation + reconciliation (model misreads total, invents tax)
  val modelText = """Here you go: {"vendor":"Tim Hortons","date":"2026-11-09","time":"8:14","receipt_number":"57","items":[{"description":"LG ORIGINAL BLEND","quantity":"1","amount":"2.45"},{"description":"BACON EGG CHEESE","quantity":"1","amount":"5.89"}],"subtotal":"12.70","taxes":[{"label":"HST","amount":"1.65"},{"label":"PST","amount":"0.99"}],"tip":"","discount":"","total":"14.53","payment_method":"Visa","card_last4":"4417","vendor_address":"100 King St W, Toronto ON","vendor_tax_id":"123456789 RT0001","currency":"CAD","category":"meals","summary":"Coffee and breakfast"}"""
  val ai = Extract.fromModel(modelText, DEFAULT_CATEGORIES.map { it.id })!!
  check(ai.time == "08:14" && ai.total == 14.53, "model parse")
  val rec = Extract.reconcile(ai, r, tims, today)
  println(rec)
  check(rec.total == 14.35 && rec.warnings.isNotEmpty(), "misread total replaced by printed TOTAL")
  check(rec.taxes.size == 1 && rec.taxes[0].amount == 1.65, "invented PST dropped")
  check(rec.items.size == 2 && rec.confidence == "high", "model items kept, confidence high")

  // 5. Apply onto a bill
  val settings = AppSettings(businessName = "Northbound Consulting Inc.", personalCards = listOf("4417"))
  val bill0 = Bill(id = "b1", num = 1, createdAt = System.currentTimeMillis(), category = null, autoCategory = true, note = "Supplier meeting")
  val bill1 = Extract.apply(bill0, rec, settings, overwrite = false, now = System.currentTimeMillis())
  check(bill1.category == "meals" && bill1.personal && bill1.status == Status.READY && bill1.currency == "CAD", "apply: auto category, personal card, ready")
  check(Books.issues(bill1).isEmpty(), "no issues: " + Books.issues(bill1))
  val back = Bill.fromMap(Json.parse(Json.stringify(bill1.toMap())) as Map<String, Any?>)
  check(back == bill1, "bill JSON round trip")

  // 6. Excel report with photos
  val bill2 = Extract.apply(Bill(id = "b2", num = 2, createdAt = System.currentTimeMillis(), category = "office"), s, settings, false, System.currentTimeMillis())
  val bill3 = bill1.copy(id = "b3", num = 3, personal = false, cardLast4 = "")
  val bill4 = Bill(id = "b4", num = 4, createdAt = System.currentTimeMillis(), status = Status.READY, vendor = "Amazon.com", date = "2026-10-21", currency = "USD", total = 25.0, subtotal = 20.0, taxes = listOf(TaxLine("Sales tax", null, 1.5)))
  val dir = File("/home/claude/receipts/test")
  var made = 0
  val photos = mapOf("b1" to PhotoForExcel(807, 928) { made++; File(dir, "tims.jpg").readBytes() }, "b2" to PhotoForExcel(750, 1451) { made++; File(dir, "staples_long.jpg").readBytes() })
  val wb = Report.build(listOf(bill1, bill2, bill3, bill4), settings, "Oct 1, 2026 to Nov 30, 2026", photos, today)
  check(made == 0, "photos are not loaded while the report is built")
  File("kt_report.xlsx").outputStream().use { wb.write(it) }
  check(made == 2, "each photo is loaded exactly once while writing (got $made)")
  val wb2 = Report.build(listOf(bill1, bill2, bill3, bill4), settings, "All bills", null, today)
  File("kt_report_nophotos.xlsx").outputStream().use { wb2.write(it) }
  println("schema: " + Extract.schema(DEFAULT_CATEGORIES.map { it.id }).take(160) + "...")
  println("prompt chars: " + Extract.buildPrompt(tims, settings, today).length)
  println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
