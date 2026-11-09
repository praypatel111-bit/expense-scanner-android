package com.expensescanner.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

data class TaxLine(val label: String, val rate: Double? = null, val amount: Double? = null)

data class Item(val d: String, val q: Double? = null, val u: Double? = null, val a: Double? = null)

enum class Status { QUEUED, READING, READY, FAILED }

data class Category(val id: String, val name: String, val icon: String, val hint: String = "")

val DEFAULT_CATEGORIES = listOf(
  Category("meals", "Meals & Entertainment", "🍽️", "restaurants, cafés, coffee, takeout, client meals, catering"),
  Category("fuel", "Fuel & Vehicle", "⛽", "gas, EV charging, car wash, vehicle repairs, oil change"),
  Category("parking", "Parking & Transit", "🅿️", "parking, tolls, 407 ETR, transit, taxi, Uber, Lyft"),
  Category("travel", "Travel & Lodging", "✈️", "flights, hotels, Airbnb, trains, car rental"),
  Category("office", "Office Supplies", "📎", "stationery, paper, ink, printing, small office items"),
  Category("software", "Software & Subscriptions", "💻", "apps, SaaS, cloud, domains, online subscriptions"),
  Category("equipment", "Equipment & Tools", "🛠️", "computers, phones, cameras, tools, furniture, hardware"),
  Category("phone", "Phone & Internet", "📱", "mobile plan, internet, telecom bills"),
  Category("marketing", "Advertising & Marketing", "📣", "ads, promotion, social media, branding, signage, samples"),
  Category("materials", "Materials & Inventory", "📦", "stock for resale, raw materials, fabric, packaging, product costs"),
  Category("shipping", "Shipping & Postage", "✉️", "Canada Post, couriers, UPS, FedEx, shipping supplies"),
  Category("professional", "Professional Fees", "💼", "accounting, bookkeeping, legal, consultants, contractors"),
  Category("rent", "Rent & Utilities", "🏠", "rent, hydro, electricity, water, heating, workspace"),
  Category("training", "Training & Education", "🎓", "courses, books, conferences, workshops, memberships"),
  Category("other", "Other", "🧾", "anything that fits nowhere else"),
)

data class AppSettings(
  val businessName: String = "",
  val region: String = "Ontario, Canada",
  val currency: String = "CAD",
  val personalCards: List<String> = emptyList(),
  val categories: List<Category> = DEFAULT_CATEGORIES,
)

data class Bill(
  val id: String,
  val num: Int,
  val createdAt: Long,
  val updatedAt: Long = createdAt,
  val status: Status = Status.QUEUED,
  val error: String = "",
  val photo: String? = null,
  val photoW: Int = 0,
  val photoH: Int = 0,
  val category: String? = null,
  val autoCategory: Boolean = false,
  val note: String = "",
  val personal: Boolean = false,
  val reviewed: Boolean = false,
  val vendor: String = "",
  val vendorAddress: String = "",
  val vendorTaxId: String = "",
  val date: String = "",
  val time: String = "",
  val receiptNo: String = "",
  val currency: String = "",
  val subtotal: Double? = null,
  val subtotalDerived: Boolean = false,
  val taxes: List<TaxLine> = emptyList(),
  val tip: Double? = null,
  val discount: Double? = null,
  val total: Double? = null,
  val payMethod: String = "",
  val cardLast4: String = "",
  val summary: String = "",
  val items: List<Item> = emptyList(),
  val readBy: String = "",
  val confidence: String = "",
  val warnings: List<String> = emptyList(),
  val ocrText: String = "",
) {
  fun toMap(): Map<String, Any?> = linkedMapOf(
    "id" to id, "num" to num, "createdAt" to createdAt, "updatedAt" to updatedAt, "status" to status.name,
    "error" to error, "photo" to photo, "photoW" to photoW, "photoH" to photoH, "category" to category,
    "autoCategory" to autoCategory, "note" to note, "personal" to personal, "reviewed" to reviewed,
    "vendor" to vendor, "vendorAddress" to vendorAddress, "vendorTaxId" to vendorTaxId, "date" to date,
    "time" to time, "receiptNo" to receiptNo, "currency" to currency, "subtotal" to subtotal,
    "subtotalDerived" to subtotalDerived,
    "taxes" to taxes.map { mapOf("label" to it.label, "rate" to it.rate, "amount" to it.amount) },
    "tip" to tip, "discount" to discount, "total" to total, "payMethod" to payMethod, "cardLast4" to cardLast4,
    "summary" to summary, "items" to items.map { mapOf("d" to it.d, "q" to it.q, "u" to it.u, "a" to it.a) },
    "readBy" to readBy, "confidence" to confidence, "warnings" to warnings, "ocrText" to ocrText,
  )

  companion object {
    private fun Map<String, Any?>.s(k: String) = (this[k] as? String) ?: ""
    private fun Map<String, Any?>.d(k: String) = (this[k] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun Map<String, Any?>.b(k: String) = this[k] == true

    fun fromMap(m: Map<String, Any?>?): Bill? {
      if (m == null) return null
      val id = m.s("id").ifEmpty { return null }
      return Bill(
        id = id,
        num = (m["num"] as? Number)?.toInt() ?: 0,
        createdAt = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
        status = runCatching { Status.valueOf(m.s("status")) }.getOrDefault(Status.READY),
        error = m.s("error"),
        photo = (m["photo"] as? String)?.takeIf { it.isNotEmpty() },
        photoW = (m["photoW"] as? Number)?.toInt() ?: 0,
        photoH = (m["photoH"] as? Number)?.toInt() ?: 0,
        category = (m["category"] as? String)?.takeIf { it.isNotEmpty() },
        autoCategory = m.b("autoCategory"),
        note = m.s("note"),
        personal = m.b("personal"),
        reviewed = m.b("reviewed"),
        vendor = m.s("vendor"),
        vendorAddress = m.s("vendorAddress"),
        vendorTaxId = m.s("vendorTaxId"),
        date = m.s("date"),
        time = m.s("time"),
        receiptNo = m.s("receiptNo"),
        currency = m.s("currency"),
        subtotal = m.d("subtotal"),
        subtotalDerived = m.b("subtotalDerived"),
        taxes = (m["taxes"] as? List<*>).orEmpty().mapNotNull { t ->
          (t as? Map<*, *>)?.let { TaxLine((it["label"] as? String) ?: "", (it["rate"] as? Number)?.toDouble(), (it["amount"] as? Number)?.toDouble()) }
        },
        tip = m.d("tip"),
        discount = m.d("discount"),
        total = m.d("total"),
        payMethod = m.s("payMethod"),
        cardLast4 = m.s("cardLast4"),
        summary = m.s("summary"),
        items = (m["items"] as? List<*>).orEmpty().mapNotNull { t ->
          (t as? Map<*, *>)?.let { Item((it["d"] as? String) ?: "", (it["q"] as? Number)?.toDouble(), (it["u"] as? Number)?.toDouble(), (it["a"] as? Number)?.toDouble()) }
        },
        readBy = m.s("readBy"),
        confidence = m.s("confidence"),
        warnings = (m["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String },
        ocrText = m.s("ocrText"),
      )
    }
  }
}

/** Shared bookkeeping helpers (mirrors the web app's logic). */
object Books {
  fun round2(n: Double): Double = round(n * 100.0 + if (n >= 0) 1e-9 else -1e-9) / 100.0

  fun isoDate(d: LocalDate): String = d.format(DateTimeFormatter.ISO_LOCAL_DATE)

  fun parseIso(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

  fun recDate(b: Bill): String =
    b.date.ifEmpty { Instant.ofEpochMilli(b.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() }

  fun localDateTime(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

  fun label(b: Bill): String = "R-" + b.num.toString().padStart(4, '0')

  fun isGstLabel(label: String): Boolean {
    val l = label.uppercase().replace(Regex("[^A-Z]"), "")
    return l.isEmpty() || l.contains("HST") || l.contains("GST") || l.contains("TPS") || l == "TAX" || l == "TAXES"
  }

  data class TaxSplit(val gst: Double, val other: Double, val any: Boolean)

  fun taxSplit(b: Bill): TaxSplit = taxSplit(b.taxes)

  fun taxSplit(taxes: List<TaxLine>): TaxSplit {
    var gst = 0.0
    var other = 0.0
    var any = false
    for (t in taxes) {
      val a = t.amount ?: continue
      any = true
      if (isGstLabel(t.label)) gst += a else other += a
    }
    return TaxSplit(round2(gst), round2(other), any)
  }

  fun taxTotal(b: Bill): Double = taxSplit(b).let { round2(it.gst + it.other) }

  fun issues(b: Bill): List<String> {
    val out = ArrayList<String>()
    if (b.status == Status.QUEUED || b.status == Status.READING) return out
    if (b.total == null) out.add("Total missing")
    if (b.date.isEmpty()) out.add("Date missing")
    if (b.category == null) out.add("No category")
    if (!b.reviewed) {
      val sub = b.subtotal
      val total = b.total
      if (sub != null && total != null && !b.subtotalDerived) {
        val expected = sub + taxTotal(b) + (b.tip ?: 0.0)
        val off = abs(expected - total) > 0.05 && abs(expected - (b.discount ?: 0.0) - total) > 0.05
        if (off) out.add("Amounts don't add up")
      }
      if (b.confidence == "low") out.add("Hard to read")
    }
    return out
  }

  /** id -> the other bill that looks like the same purchase (same vendor, date and total). */
  fun dupIndex(bills: List<Bill>): Map<String, Bill> {
    val seen = HashMap<String, Bill>()
    val out = HashMap<String, Bill>()
    for (b in bills) {
      val total = b.total ?: continue
      if (b.date.isEmpty()) continue
      val v = b.vendor.lowercase().replace(Regex("[^a-z0-9]"), "").take(10)
      val key = v + "|" + b.date + "|" + String.format(java.util.Locale.ROOT, "%.2f", total)
      val o = seen[key]
      if (o != null) {
        out[b.id] = o
        if (!out.containsKey(o.id)) out[o.id] = b
      } else {
        seen[key] = b
      }
    }
    return out
  }

  /** Parses "$1,234.56", "12,70", "(4.00)" or "4.00-" into a number. */
  fun parseAmount(v: Any?): Double? {
    if (v == null || v is Boolean) return null
    if (v is Number) return v.toDouble().takeIf { it.isFinite() }?.let { round2(it) }
    var s = v.toString().trim()
    if (s.isEmpty()) return null
    val neg = Regex("^\\(.*\\)$").matches(s) || s.startsWith("-") || Regex("\\d-$").containsMatchIn(s)
    s = s.replace(Regex("[^0-9.,]"), "")
    if (!s.any { it.isDigit() }) return null
    val lastDot = s.lastIndexOf('.')
    val lastComma = s.lastIndexOf(',')
    s = if (lastDot >= 0 && lastComma >= 0) {
      if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
    } else if (lastComma >= 0) {
      val decimals = s.length - lastComma - 1
      if (decimals == 2 && s.indexOf(',') == lastComma) s.replace(',', '.') else s.replace(",", "")
    } else {
      s
    }
    if (s.count { it == '.' } > 1) {
      val i = s.lastIndexOf('.')
      s = s.substring(0, i).replace(".", "") + s.substring(i)
    }
    val n = s.toDoubleOrNull() ?: return null
    return round2(if (neg) -n else n)
  }

  fun money(n: Double?, cur: String = ""): String {
    if (n == null) return "—"
    val body = String.format(java.util.Locale.US, "%,.2f", abs(n))
    val sign = if (n < 0) "-" else ""
    val symbol = when (cur) {
      "", "CAD", "USD", "AUD", "NZD", "MXN" -> "$"
      "EUR" -> "€"
      "GBP" -> "£"
      "INR" -> "₹"
      "JPY", "CNY" -> "¥"
      else -> "$cur "
    }
    return "$sign$symbol$body"
  }

  fun plain(n: Double?): String = if (n == null) "" else String.format(java.util.Locale.US, "%.2f", n)
}
