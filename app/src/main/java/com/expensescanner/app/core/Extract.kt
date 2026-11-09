package com.expensescanner.app.core

import java.time.LocalDate
import kotlin.math.abs

/** Prompt + JSON schema for the on-phone model, and the logic that checks its answer. */
object Extract {
  const val SYSTEM =
    "You are a careful bookkeeper. You read the OCR text of receipts and bills and return the facts as JSON. " +
      "Copy names, dates and amounts exactly as printed. Never invent values."

  fun buildPrompt(rows: List<String>, settings: AppSettings, today: LocalDate): String {
    val cats = settings.categories.joinToString("; ") { c -> if (c.hint.isNotEmpty()) "${c.id} = ${c.hint}" else "${c.id} = ${c.name}" }
    val text = rows.joinToString("\n").take(6000)
    return buildString {
      appendLine("Fill in the JSON for this receipt.")
      appendLine("Business location: ${settings.region.ifEmpty { "Canada" }}. Main currency: ${settings.currency}. Today: $today.")
      appendLine()
      appendLine("Rules:")
      appendLine("- total: the final amount paid, after taxes, tip and discounts.")
      appendLine("- subtotal: the amount before tax, or \"\" if not printed.")
      appendLine("- taxes: one entry for each tax line printed (HST, GST, PST, QST, TPS, TVQ, sales tax). Use [] if there is none. Never add a tax that is not printed.")
      appendLine("- items: each product or service line with its price. Skip SUBTOTAL, TAX, TOTAL, payment, CHANGE and loyalty lines.")
      appendLine("- date: YYYY-MM-DD. Dates like 11/09/26 can be month/day or day/month: choose the reading that is not after today.")
      appendLine("- time: HH:MM in 24-hour time, or \"\".")
      appendLine("- card_last4: the last 4 digits of the card number, or \"\".")
      appendLine("- payment_method: Visa, Mastercard, Amex, Interac debit, Cash, or \"\".")
      appendLine("- vendor_tax_id: the GST/HST registration number as printed (like 123456789 RT0001), or \"\".")
      appendLine("- currency: 3-letter code; use ${settings.currency} unless the receipt clearly shows another currency.")
      appendLine("- category: one id from this list: $cats.")
      appendLine("- summary: what was bought, at most 6 words.")
      appendLine("- Write amounts like 12.70 with no \$ sign. Use \"\" for anything not printed.")
      appendLine()
      appendLine("Receipt text, top to bottom (\" | \" separates columns on the same line):")
      append(text)
    }
  }

  fun schema(categoryIds: List<String>): String {
    val str = mapOf("type" to "string")
    val schema = linkedMapOf(
      "type" to "object",
      "properties" to linkedMapOf(
        "vendor" to str,
        "date" to str,
        "time" to str,
        "receipt_number" to str,
        "items" to mapOf(
          "type" to "array",
          "maxItems" to 60,
          "items" to mapOf(
            "type" to "object",
            "properties" to linkedMapOf("description" to str, "quantity" to str, "amount" to str),
            "required" to listOf("description", "quantity", "amount"),
          ),
        ),
        "subtotal" to str,
        "taxes" to mapOf(
          "type" to "array",
          "maxItems" to 6,
          "items" to mapOf(
            "type" to "object",
            "properties" to linkedMapOf("label" to str, "amount" to str),
            "required" to listOf("label", "amount"),
          ),
        ),
        "tip" to str,
        "discount" to str,
        "total" to str,
        "payment_method" to str,
        "card_last4" to str,
        "vendor_address" to str,
        "vendor_tax_id" to str,
        "currency" to str,
        "category" to mapOf("type" to "string", "enum" to categoryIds),
        "summary" to str,
      ),
      "required" to listOf(
        "vendor", "date", "time", "receipt_number", "items", "subtotal", "taxes", "tip", "discount", "total",
        "payment_method", "card_last4", "vendor_address", "vendor_tax_id", "currency", "category", "summary",
      ),
    )
    return Json.stringify(schema)
  }

  /** Turns the model's JSON text into an [Extraction]; null when nothing usable came back. */
  fun fromModel(text: String, categoryIds: List<String>): Extraction? {
    val o = Json.parseObjectLoose(text) ?: return null
    fun s(k: String, max: Int = 160): String {
      val v = o[k] ?: return ""
      if (v is Map<*, *> || v is List<*>) return ""
      val t = v.toString().replace(Regex("""\s+"""), " ").trim().take(max)
      return if (Regex("""^(null|none|n/?a|unknown|-+)$""", RegexOption.IGNORE_CASE).matches(t)) "" else t
    }
    fun num(v: Any?) = Books.parseAmount(v)
    val taxes = (o["taxes"] as? List<*>).orEmpty().mapNotNull { t ->
      val m = t as? Map<*, *> ?: return@mapNotNull null
      val amt = num(m["amount"]) ?: return@mapNotNull null
      TaxLine((m["label"]?.toString() ?: "Tax").trim().take(24).ifEmpty { "Tax" }, null, amt)
    }
    val items = (o["items"] as? List<*>).orEmpty().take(80).mapNotNull { t ->
      val m = t as? Map<*, *> ?: return@mapNotNull null
      val d = (m["description"]?.toString() ?: "").trim().take(160)
      val a = num(m["amount"])
      if (d.isEmpty() && a == null) null else Item(d, num(m["quantity"]), null, a)
    }
    var currency = s("currency", 8).uppercase()
    if (!Regex("^[A-Z]{3}$").matches(currency)) currency = ""
    val date = s("date", 20).let { d -> Books.parseIso(d.take(10))?.toString() ?: "" }
    val time = Regex("""^([01]?\d|2[0-3])[:h.]([0-5]\d)""").find(s("time", 10))?.let { "${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2]}" } ?: ""
    var category = s("category", 40)
    if (category !in categoryIds) category = categoryIds.firstOrNull { it.equals(category, true) } ?: ""
    val last4 = s("card_last4", 30).filter { it.isDigit() }.takeLast(4)
    val ex = Extraction(
      isReceipt = true,
      vendor = s("vendor", 80),
      vendorAddress = s("vendor_address", 160),
      vendorTaxId = s("vendor_tax_id", 40),
      date = date,
      time = time,
      receiptNo = s("receipt_number", 40),
      currency = currency,
      items = items,
      subtotal = num(o["subtotal"]),
      taxes = taxes,
      tip = num(o["tip"])?.let { abs(it) }?.takeIf { it > 0 },
      discount = num(o["discount"])?.let { abs(it) }?.takeIf { it > 0 },
      total = num(o["total"]),
      payMethod = s("payment_method", 30),
      cardLast4 = if (last4.length == 4) last4 else "",
      category = category,
      summary = s("summary", 80),
      source = "gemma",
    )
    return if (ex.total == null && ex.vendor.isEmpty() && ex.items.isEmpty()) null else ex
  }

  /**
   * Cross-checks the model against the receipt text and the rule parser. Numbers the model reports
   * must appear in the OCR text; when they don't, the rule parser's reading wins and a note is added.
   */
  /**
   * Small models sometimes list the subtotal, a tax, the total or the card payment as bought items.
   * Those are dropped (a summary-looking name *and* one of those amounts), then whichever list adds
   * up to the subtotal wins: the model's, else the rule-based reader's.
   */
  internal fun chooseItems(
    aiItems: List<Item>, ruleItems: List<Item>, subtotal: Double?, total: Double?, taxes: List<TaxLine>, tip: Double?,
    notes: MutableList<String>, seen: (Double?) -> Boolean,
  ): List<Item> {
    val summaryAmounts = (listOfNotNull(subtotal, total, tip) + taxes.mapNotNull { it.amount }).map { Books.plain(abs(it)) }.toSet()
    val ai = aiItems.filterNot { it.a != null && Books.plain(abs(it.a)) in summaryAmounts && RuleParser.isSummaryLine(it.d) }
    val target = subtotal ?: total?.let { it - taxes.sumOf { t -> t.amount ?: 0.0 } - (tip ?: 0.0) }
    fun fits(list: List<Item>) = target != null && list.isNotEmpty() && abs(list.sumOf { it.a ?: 0.0 } - target) <= 0.05
    val chosen = when {
      ai.isEmpty() -> ruleItems
      fits(ai) -> ai
      fits(ruleItems) -> ruleItems
      ai.count { seen(it.a) } * 2 >= ai.size -> ai
      else -> ruleItems.ifEmpty { ai }
    }
    if (target != null && chosen.size > 1 && !fits(chosen)) notes.add("The items don't add up to the subtotal, so check them against the photo")
    return chosen
  }

  fun reconcile(ai: Extraction?, rules: Extraction, rows: List<String>, today: LocalDate): Extraction {
    if (ai == null) return rules
    val printed = rows.flatMap { RuleParser.amounts(it) }.map { Books.plain(abs(it.value)) }.toHashSet()
    fun seen(v: Double?) = v != null && Books.plain(abs(v)) in printed
    val notes = ArrayList<String>()

    var total = ai.total
    if (total == null || (!seen(total) && rules.total != null && rules.total != total)) {
      if (total != null && rules.total != null) notes.add("Total taken from the printed TOTAL line")
      total = rules.total ?: total
    }
    var subtotal = ai.subtotal
    if (subtotal != null && !seen(subtotal)) subtotal = rules.subtotal
    if (subtotal == null) subtotal = rules.subtotal
    var taxes = ai.taxes.filter { seen(it.amount) }
    if (taxes.isEmpty() || (rules.taxes.isNotEmpty() && taxes.size < ai.taxes.size)) taxes = if (rules.taxes.isNotEmpty()) rules.taxes else taxes
    val aiDate = Books.parseIso(ai.date)
    val date = when {
      aiDate != null && !aiDate.isAfter(today.plusDays(1)) -> ai.date
      else -> rules.date
    }
    val tip = ai.tip?.takeIf { seen(it) } ?: rules.tip
    val items = chooseItems(ai.items, rules.items, subtotal, total, taxes, tip, notes) { seen(it) }
    val card = ai.cardLast4.takeIf { it.isNotEmpty() && rows.any { r -> r.contains(it) } } ?: rules.cardLast4
    val taxId = ai.vendorTaxId.takeIf { it.isNotEmpty() && it.filter(Char::isDigit).length >= 9 } ?: rules.vendorTaxId

    val taxSum = taxes.sumOf { it.amount ?: 0.0 }
    val mathOk = total != null && subtotal != null && abs(subtotal + taxSum + (tip ?: 0.0) - total) <= 0.05
    val confidence = when {
      total == null || date.isEmpty() -> "low"
      subtotal != null && !mathOk -> "medium"
      seen(total) -> "high"
      else -> "medium"
    }
    return Extraction(
      isReceipt = true,
      vendor = RuleParser.cleanVendor(ai.vendor).ifEmpty { rules.vendor },
      vendorAddress = ai.vendorAddress.ifEmpty { rules.vendorAddress },
      vendorPhone = rules.vendorPhone,
      vendorTaxId = taxId,
      date = date,
      time = ai.time.ifEmpty { rules.time },
      receiptNo = ai.receiptNo.ifEmpty { rules.receiptNo },
      currency = ai.currency.ifEmpty { rules.currency },
      items = items,
      subtotal = subtotal,
      taxes = taxes,
      tip = tip,
      discount = ai.discount ?: rules.discount,
      total = total,
      payMethod = ai.payMethod.ifEmpty { rules.payMethod },
      cardLast4 = card,
      category = ai.category.ifEmpty { rules.category },
      summary = ai.summary.ifEmpty { rules.summary },
      confidence = confidence,
      warnings = notes,
      source = "gemma",
    )
  }

  /** Copies what was read onto the bill. User-entered values win unless [overwrite] (a manual "Read again"). */
  fun apply(b: Bill, ex: Extraction, settings: AppSettings, overwrite: Boolean, now: Long): Bill {
    fun str(cur: String, new: String) = if (new.isNotEmpty() && (overwrite || cur.isEmpty())) new else cur
    fun num(cur: Double?, new: Double?) = if (new != null && (overwrite || cur == null)) new else cur
    var subtotal = b.subtotal
    var subtotalDerived = b.subtotalDerived
    if (ex.subtotal != null && (overwrite || subtotal == null || subtotalDerived)) {
      subtotal = ex.subtotal
      subtotalDerived = false
    }
    var out = b.copy(
      vendor = str(b.vendor, ex.vendor),
      vendorAddress = str(b.vendorAddress, ex.vendorAddress),
      vendorTaxId = str(b.vendorTaxId, ex.vendorTaxId),
      date = str(b.date, ex.date),
      time = str(b.time, ex.time),
      receiptNo = str(b.receiptNo, ex.receiptNo),
      currency = str(b.currency, ex.currency).ifEmpty { settings.currency },
      tip = num(b.tip, ex.tip),
      discount = num(b.discount, ex.discount),
      total = num(b.total, ex.total),
      payMethod = str(b.payMethod, ex.payMethod),
      cardLast4 = str(b.cardLast4, ex.cardLast4),
      summary = str(b.summary, ex.summary),
      items = if (ex.items.isNotEmpty() && (overwrite || b.items.isEmpty())) ex.items else b.items,
      taxes = if (ex.taxes.isNotEmpty() && (overwrite || b.taxes.isEmpty())) ex.taxes else b.taxes,
      subtotal = subtotal,
      subtotalDerived = subtotalDerived,
      category = if (ex.category.isNotEmpty() && (b.category == null || b.autoCategory)) ex.category else b.category,
      confidence = ex.confidence,
      warnings = ex.warnings,
      readBy = ex.source,
      status = Status.READY,
      error = "",
      reviewed = if (overwrite) false else b.reviewed,
      updatedAt = now,
    )
    if (out.subtotal == null && out.total != null) {
      out = out.copy(subtotal = Books.round2(out.total!! - Books.taxTotal(out) - (out.tip ?: 0.0)), subtotalDerived = true)
    }
    if (!out.personal && out.cardLast4.isNotEmpty() && out.cardLast4 in settings.personalCards) out = out.copy(personal = true)
    return out
  }
}
