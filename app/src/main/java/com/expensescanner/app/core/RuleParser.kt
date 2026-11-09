package com.expensescanner.app.core

import java.time.LocalDate
import kotlin.math.abs

/** What a reader (Gemma or the rule parser) found on a bill. */
data class Extraction(
  val isReceipt: Boolean = true,
  val vendor: String = "",
  val vendorAddress: String = "",
  val vendorPhone: String = "",
  val vendorTaxId: String = "",
  val date: String = "",
  val time: String = "",
  val receiptNo: String = "",
  val currency: String = "",
  val items: List<Item> = emptyList(),
  val subtotal: Double? = null,
  val taxes: List<TaxLine> = emptyList(),
  val tip: Double? = null,
  val discount: Double? = null,
  val total: Double? = null,
  val payMethod: String = "",
  val cardLast4: String = "",
  val category: String = "",
  val summary: String = "",
  val confidence: String = "medium",
  val warnings: List<String> = emptyList(),
  val source: String = "rules",
)

/**
 * Reads receipts with plain rules: keywords (SUBTOTAL, HST, TOTAL...), amount patterns and date
 * formats. It needs no AI model, runs instantly, and is also used to double-check the AI.
 */
object RuleParser {
  private val AMOUNT = Regex("""(?<![\d.,])(-?)\$?\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})(?![\d])(?![.,]\d)(?!\s?%)(-?)""")
  private val SUBTOTAL = Regex("""SUB\s*-?\s*TOT|SOUS[-\s]?TOTAL""")
  private val TOTAL = Regex("""\bTOTAL\b|\bAMOUNT\s+DUE\b|\bBALANCE\s+DUE\b|\bMONTANT\b|\bTOTAL\s+DUE\b""")
  private val TOTAL_STRONG = Regex("""GRAND\s+TOTAL|AMOUNT\s+DUE|BALANCE\s+DUE|TOTAL\s+DUE|MONTANT\s+TOTAL""")
  private val TOTAL_EXCLUDE = Regex("""SAVING|DISCOUNT|RABAIS|\bITEMS?\b|QTY|POINTS|ARTICLES|TOTAL\s+TAX|TAX\s+TOTAL|\bTIP\b|TOTAL\s+NUMBER""")
  private val TAX = Regex("""\b(H\.?S\.?T|G\.?S\.?T|P\.?S\.?T|Q\.?S\.?T|T\.?P\.?S|T\.?V\.?Q|R\.?S\.?T|SALES\s+TAX|TAXES|TAX)\b\.?""")
  private val TIP = Regex("""\b(TIP|GRATUITY|POURBOIRE)\b""")
  private val DISCOUNT = Regex("""DISCOUNT|COUPON|SAVINGS?\b|RABAIS|PROMO""")
  private val PAYMENT = Regex("""\b(VISA|MASTERCARD|MASTER\s*CARD|M/C|AMEX|AMERICAN\s+EXPRESS|DEBIT|INTERAC|CASH|CHANGE|TENDER(?:ED)?|CREDIT|APPROVED|AUTH|CARD|PAYMENT|PAID|CHIP|TAP|PURCHASE)\b""")
  private val PAY_METHOD = Regex("""\b(VISA|MASTERCARD|MASTER\s*CARD|M/C|AMEX|AMERICAN\s+EXPRESS|INTERAC|DEBIT|CASH|APPLE\s*PAY|GOOGLE\s*PAY|DISCOVER)\b""")
  private val CARD = Regex("""(?:\*{2,}|X{2,}|x{2,}|•{2,}|#{3,})\s*(\d{4})\b|ENDING\s*(?:IN)?\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
  private val TAX_ID = Regex("""(\d{9})\s*-?\s*R\s?T\s*-?\s*(\d{4})""")
  private val TAX_ID_SHORT = Regex("""(?:GST|HST|TPS)[^0-9]{0,14}(\d{9})\b""")
  private val RECEIPT_NO = Regex("""\b(?:RECEIPT|INVOICE|TRANS(?:ACTION)?|ORDER|REF|TRX|TXN|CHECK|CHK|BILL|TICKET)[\s|]*(?:#|NO\.?|NUMBER|NUM)?[\s|]*[:#]?[\s|]*([A-Z0-9][A-Z0-9-]{1,})""")
  private val TIME = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?\s*([AaPp]\.?[Mm]\.?)?""")
  private val PHONE = Regex("""\(?\b(\d{3})\)?[\s.-]?(\d{3})[\s.-](\d{4})\b""")
  private val POSTAL = Regex("""\b[A-Z]\d[A-Z]\s?\d[A-Z]\d\b""")
  private val STREET = Regex("""\b\d{1,6}\s+[A-Z0-9][A-Z0-9.' -]*\b(ST|STREET|AVE|AVENUE|RD|ROAD|BLVD|BOULEVARD|DR|DRIVE|WAY|CRES|CRT|COURT|PKWY|HWY|LANE|LN|PL|PLACE|SQ|TER|W|E|N|S)\b""")
  private val NOISE = Regex("""WELCOME|RECEIPT|THANK|CUSTOMER\s+COPY|MERCHANT|STORE\s*#|TEL\b|PHONE|WWW\.|\.COM|\.CA\b|INVOICE|DUPLICATE|SALE\b|ORDER\b|TABLE|SERVER|CASHIER|HST|GST""")
  private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
  private val D_YMD = Regex("""\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
  private val D_NUM = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})\b""")
  private val D_DMONY = Regex("""\b(\d{1,2})[\s-]*(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?[\s,-]*(\d{4}|\d{2})\b""")
  private val D_MONDY = Regex("""\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s*(\d{1,2})(?:ST|ND|RD|TH)?,?\s*(\d{4}|\d{2})\b""")

  private val CATEGORY_WORDS = linkedMapOf(
    "meals" to listOf("restaurant", "cafe", "café", "coffee", "tim hortons", "starbucks", "mcdonald", "pizza", "burger", "sushi", "grill", "bistro", "bar ", "pub", "kitchen", "subway", "a&w", "wendy", "popeyes", "chipotle", "uber eats", "doordash", "skip the dishes", "latte", "espresso", "sandwich", "breakfast", "lunch", "dinner"),
    "fuel" to listOf("shell", "esso", "petro", "ultramar", "pioneer", "husky", "chevron", "mobil", "irving", "fuel", "gas bar", "gasoline", "diesel", "litre", " pump", "car wash", "oil change", "canadian tire gas"),
    "parking" to listOf("parking", "impark", "green p", "indigo", "precise park", "presto", "ttc", "go transit", "uber", "lyft", "taxi", "407 etr", "toll"),
    "travel" to listOf("hotel", "inn ", "marriott", "hilton", "airbnb", "air canada", "westjet", "porter", "via rail", "expedia", "booking.com", "enterprise rent", "hertz", "avis", "budget rent"),
    "office" to listOf("staples", "office", "paper", "toner", "ink ", "printer", "pens", "notebook", "stationery", "envelope", "binder"),
    "software" to listOf("software", "subscription", "adobe", "microsoft", "google workspace", "dropbox", "apple.com", "icloud", "notion", "canva", "shopify", "zoom", "slack", "openai", "anthropic", "domain", "hosting"),
    "equipment" to listOf("best buy", "computer", "laptop", "monitor", "keyboard", "mouse", "camera", "home depot", "rona", "lowe", "tool", "furniture", "ikea", "apple store", "memory express", "canada computers"),
    "phone" to listOf("rogers", "bell ", "telus", "fido", "koodo", "virgin plus", "freedom mobile", "chatr", "public mobile", "internet", "wireless", "mobility"),
    "marketing" to listOf("advert", "marketing", "facebook", "meta ", "instagram", "google ads", "vistaprint", "print shop", "flyer", "banner", "signage"),
    "materials" to listOf("fabric", "textile", "wholesale", "supplies", "packaging", "inventory", "materials"),
    "shipping" to listOf("canada post", "postes canada", "purolator", "ups ", "fedex", "dhl", "shipping", "postage", "courier"),
    "professional" to listOf("accounting", "bookkeeping", "legal", "lawyer", "consult", "notary", "cpa"),
    "rent" to listOf("rent", "hydro", "enbridge", "electricity", "water bill", "utilities", "toronto hydro", "alectra"),
    "training" to listOf("course", "udemy", "coursera", "conference", "workshop", "seminar", "book", "indigo books", "membership", "tuition"),
  )

  data class Amount(val value: Double, val start: Int)

  fun amounts(row: String): List<Amount> = AMOUNT.findAll(row).mapNotNull { m ->
    val whole = m.groupValues[2].replace(",", "")
    val v = "$whole.${m.groupValues[3]}".toDoubleOrNull() ?: return@mapNotNull null
    val neg = m.groupValues[1] == "-" || m.groupValues[4] == "-"
    Amount(if (neg) -v else v, m.range.first)
  }.toList()

  private fun lastAmount(row: String) = amounts(row).lastOrNull()?.value

  fun parse(rows: List<String>, today: LocalDate = LocalDate.now(), categoryIds: List<String> = DEFAULT_CATEGORIES.map { it.id }): Extraction {
    val up = rows.map { it.uppercase() }
    val n = rows.size
    val all = up.joinToString("\n")

    // --- totals block
    var subtotal: Double? = null
    var firstMoneyRow = n
    val totalCands = ArrayList<Pair<Int, Double>>()
    val strongCands = ArrayList<Double>()
    val taxRows = ArrayList<TaxLine>()
    val taxTotalRows = ArrayList<TaxLine>()
    var tip: Double? = null
    var discount = 0.0
    for (i in 0 until n) {
      val r = up[i]
      val amt = lastAmount(r)
      val isSub = SUBTOTAL.containsMatchIn(r)
      val isTotal = !isSub && TOTAL.containsMatchIn(r) && !TOTAL_EXCLUDE.containsMatchIn(r)
      val isTax = TAX.containsMatchIn(r) && !isSub && !isTotal
      if (isSub && amt != null) {
        subtotal = amt
        firstMoneyRow = minOf(firstMoneyRow, i)
      } else if (isTotal && amt != null) {
        totalCands.add(i to amt)
        if (TOTAL_STRONG.containsMatchIn(r)) strongCands.add(amt)
        firstMoneyRow = minOf(firstMoneyRow, i)
      } else if (isTax && amt != null) {
        val label = taxLabel(r)
        val rate = Regex("""(\d{1,2}(?:[.,]\d{1,3})?)\s?%""").find(r)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val line = TaxLine(label, rate, amt)
        if (Regex("""TOTAL\s+TAX|TAX\s+TOTAL|TOTAL\s+DES\s+TAXES""").containsMatchIn(r)) taxTotalRows.add(line) else taxRows.add(line)
        firstMoneyRow = minOf(firstMoneyRow, i)
      } else if (TIP.containsMatchIn(r) && amt != null) {
        tip = abs(amt)
      } else if (DISCOUNT.containsMatchIn(r) && amt != null) {
        discount += abs(amt)
      }
    }
    val taxes = (if (taxRows.isNotEmpty()) taxRows else taxTotalRows).distinctBy { it.label + "|" + it.amount }
    val taxSum = taxes.sumOf { it.amount ?: 0.0 }
    var total: Double? = null
    var totalSure = false
    if (totalCands.isNotEmpty()) {
      val expected = if (subtotal != null) subtotal + taxSum + (tip ?: 0.0) else null
      val matching = if (expected != null) totalCands.firstOrNull { abs(it.second - expected) <= 0.02 } else null
      total = matching?.second ?: strongCands.maxOrNull() ?: totalCands.maxOf { it.second }
      totalSure = true
    } else {
      // No TOTAL line: use a payment line amount, else the biggest number near the bottom.
      val pay = (0 until n).reversed().firstOrNull { PAY_METHOD.containsMatchIn(up[it]) && lastAmount(up[it]) != null }
      total = pay?.let { lastAmount(up[it]) } ?: up.drop(n / 2).flatMap { amounts(it) }.maxOfOrNull { it.value }
    }

    // --- header facts
    val vendorIdx = findVendor(rows, up)
    val vendor = vendorIdx?.let { cleanVendor(rows[it]) } ?: ""
    val address = findAddress(rows, up, vendorIdx)
    val phone = PHONE.find(all)?.let { "(${it.groupValues[1]}) ${it.groupValues[2]}-${it.groupValues[3]}" } ?: ""
    val taxId = TAX_ID.find(all)?.let { "${it.groupValues[1]} RT${it.groupValues[2]}" }
      ?: TAX_ID_SHORT.find(all)?.groupValues?.get(1) ?: ""
    val receiptNo = RECEIPT_NO.findAll(all).map { it.groupValues[1] }.firstOrNull { it.any(Char::isDigit) } ?: ""
    val date = findDate(up, today)
    val time = TIME.find(all)?.let { m ->
      var h = m.groupValues[1].toInt()
      val ap = m.groupValues[3].uppercase().replace(".", "")
      if (ap == "PM" && h < 12) h += 12
      if (ap == "AM" && h == 12) h = 0
      h.toString().padStart(2, '0') + ":" + m.groupValues[2]
    } ?: ""
    val card = CARD.find(all)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
    val pay = PAY_METHOD.find(all)?.value?.let { normPay(it) } ?: ""
    val currency = when {
      Regex("""\bUSD\b|US\$""").containsMatchIn(all) -> "USD"
      all.contains("€") || Regex("""\bEUR\b""").containsMatchIn(all) -> "EUR"
      all.contains("£") || Regex("""\bGBP\b""").containsMatchIn(all) -> "GBP"
      all.contains("₹") || Regex("""\bINR\b|\bRS\.""").containsMatchIn(all) -> "INR"
      else -> ""
    }

    // --- items: rows with a price between the header and the totals block
    val itemsEnd = if (firstMoneyRow < n) firstMoneyRow else n
    val itemsStart = (vendorIdx ?: -1) + 1
    val items = ArrayList<Item>()
    for (i in itemsStart until itemsEnd) {
      val r = up[i]
      val am = amounts(r)
      if (am.isEmpty()) continue
      if (TAX.containsMatchIn(r) || TOTAL.containsMatchIn(r) || SUBTOTAL.containsMatchIn(r) || PAYMENT.containsMatchIn(r) || TIP.containsMatchIn(r)) continue
      if (D_NUM.containsMatchIn(r) && am.size == 1 && rows[i].count { it.isLetter() } < 3) continue
      var desc = AMOUNT.replace(rows[i], " ")
      desc = desc.replace("|", " ").replace(Regex("""\s+"""), " ").trim().trim('$', '-', ':', '.', ' ')
      if (desc.count { it.isLetter() } < 2) continue
      var q: Double? = null
      Regex("""^(\d{1,3})\s*[X@]\s+""", RegexOption.IGNORE_CASE).find(desc)?.let {
        q = it.groupValues[1].toDouble()
        desc = desc.substring(it.range.last + 1).trim()
      }
      val a = am.last().value
      val u = if (am.size >= 2) am.first().value else null
      items.add(Item(desc.take(160), q, u, a))
    }

    val hay = (vendor + " " + items.joinToString(" ") { it.d } + " " + rows.take(12).joinToString(" ")).lowercase()
    val category = CATEGORY_WORDS.entries.firstOrNull { (id, words) -> id in categoryIds && words.any { hay.contains(it) } }?.key ?: ""
    val summary = items.take(3).joinToString(", ") { it.d.lowercase().replaceFirstChar(Char::titlecase) }.take(60)

    val warnings = ArrayList<String>()
    if (total == null) warnings.add("No total found")
    if (date.isEmpty()) warnings.add("No date found")
    val mathOk = total != null && subtotal != null && abs(subtotal + taxSum + (tip ?: 0.0) - total) <= 0.05
    val confidence = when {
      total != null && totalSure && date.isNotEmpty() && (subtotal == null || mathOk) -> "high"
      total != null -> "medium"
      else -> "low"
    }
    return Extraction(
      isReceipt = total != null || items.isNotEmpty(),
      vendor = vendor,
      vendorAddress = address,
      vendorPhone = phone,
      vendorTaxId = taxId,
      date = date,
      time = time,
      receiptNo = receiptNo,
      currency = currency,
      items = items.take(80),
      subtotal = subtotal,
      taxes = taxes,
      tip = tip,
      discount = if (discount > 0) Books.round2(discount) else null,
      total = total,
      payMethod = pay,
      cardLast4 = card,
      category = category,
      summary = summary,
      confidence = confidence,
      warnings = warnings,
      source = "rules",
    )
  }

  private fun taxLabel(r: String): String {
    val m = TAX.find(r)?.groupValues?.get(1)?.replace(".", "")?.replace(Regex("""\s+"""), " ") ?: "TAX"
    val pct = Regex("""(\d{1,2}(?:[.,]\d{1,3})?)\s?%""").find(r)?.groupValues?.get(1)
    val base = when (m) {
      "TAXES" -> "TAX"
      else -> m
    }
    return if (pct != null) "$base $pct%" else base
  }

  private fun normPay(s: String): String {
    val u = s.uppercase().replace(Regex("""\s+"""), " ")
    return when {
      u.startsWith("VISA") -> "Visa"
      u.startsWith("MASTER") || u == "M/C" -> "Mastercard"
      u.startsWith("AMEX") || u.startsWith("AMERICAN") -> "Amex"
      u == "INTERAC" || u == "DEBIT" -> "Interac debit"
      u == "CASH" -> "Cash"
      u.startsWith("APPLE") -> "Apple Pay"
      u.startsWith("GOOGLE") -> "Google Pay"
      u == "DISCOVER" -> "Discover"
      else -> s
    }
  }

  private fun findVendor(rows: List<String>, up: List<String>): Int? {
    for (i in 0 until minOf(6, rows.size)) {
      val r = rows[i].replace("|", " ").trim()
      val letters = r.count { it.isLetter() }
      if (letters < 3) continue
      if (letters.toDouble() / r.length.coerceAtLeast(1) < 0.5) continue
      if (NOISE.containsMatchIn(up[i]) && letters < 14) continue
      if (STREET.containsMatchIn(up[i]) || POSTAL.containsMatchIn(up[i])) continue
      return i
    }
    return null
  }

  /** "TIM HORTONS #1234" → "Tim Hortons". Short one-word names like LCBO or IKEA keep their capitals. */
  fun cleanVendor(row: String): String {
    var v = row.replace("|", " ").replace(Regex("""\s+"""), " ").trim().trim('*', '#', '-', '=', ' ')
    v = v.replace(Regex("""\s*#?\d{3,}$"""), "").trim()
    val shortAcronym = !v.contains(' ') && v.length <= 4
    if (v.isNotEmpty() && v == v.uppercase() && !shortAcronym) {
      v = v.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }
    return v.take(80)
  }

  /** A subtotal, total, tax, tip or payment line rather than something that was bought. */
  fun isSummaryLine(text: String): Boolean {
    val up = text.uppercase()
    return SUBTOTAL.containsMatchIn(up) || TOTAL.containsMatchIn(up) || TAX.containsMatchIn(up) ||
      TIP.containsMatchIn(up) || PAYMENT.containsMatchIn(up)
  }

  private fun findAddress(rows: List<String>, up: List<String>, vendorIdx: Int?): String {
    val start = (vendorIdx ?: -1) + 1
    val parts = ArrayList<String>()
    for (i in start until minOf(start + 6, rows.size)) {
      if (STREET.containsMatchIn(up[i]) || POSTAL.containsMatchIn(up[i]) || Regex("""\b(ON|QC|BC|AB|MB|SK|NS|NB|NL|PE)\b""").containsMatchIn(up[i]) && Regex("""[A-Z]{3,}""").containsMatchIn(up[i])) {
        if (amounts(up[i]).isNotEmpty()) continue
        parts.add(rows[i].replace("|", " ").replace(Regex("""\s+"""), " ").trim())
        if (parts.size == 2) break
      }
    }
    return parts.joinToString(", ").take(160)
  }

  fun findDate(up: List<String>, today: LocalDate): String {
    val cands = ArrayList<LocalDate>()
    fun add(y: Int, m: Int, d: Int) {
      val yy = if (y < 100) 2000 + y else y
      runCatching { LocalDate.of(yy, m, d) }.getOrNull()?.let { cands.add(it) }
    }
    for (r in up) {
      D_YMD.findAll(r).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
      D_DMONY.findAll(r).forEach { add(it.groupValues[3].toInt(), MONTHS.indexOf(it.groupValues[2]) + 1, it.groupValues[1].toInt()) }
      D_MONDY.findAll(r).forEach { add(it.groupValues[3].toInt(), MONTHS.indexOf(it.groupValues[1]) + 1, it.groupValues[2].toInt()) }
      for (m in D_NUM.findAll(r)) {
        if (D_YMD.containsMatchIn(m.value)) continue
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt()
        add(y, a, b) // MM/DD
        if (a != b) add(y, b, a) // DD/MM
      }
    }
    val ok = cands.filter { !it.isAfter(today.plusDays(1)) && it.isAfter(today.minusYears(8)) }
    return ok.minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }?.toString() ?: ""
  }
}
