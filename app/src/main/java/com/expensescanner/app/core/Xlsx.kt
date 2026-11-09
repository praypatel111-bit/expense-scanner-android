package com.expensescanner.app.core

import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Cell value: text, number, formula with cached value, or empty with a style. */
class Cell(val v: Any? = null, val s: Int = 0, val f: String? = null)

/** Image placed in a sheet: JPEG bytes and the size to show it at (pixels). */
class SheetImage(val row: Int, val col: Int, val jpeg: () -> ByteArray, val w: Int, val h: Int, val name: String, val descr: String, val offX: Int = 8, val offY: Int = 8)

/** Minimal XLSX writer: styles, formulas, frozen header, autofilter, internal links, pictures. */
class XlsxWriter(private val title: String, private val creator: String) {
  object Style {
    const val NORMAL = 0
    const val HEADER = 1
    const val DATE = 2
    const val MONEY = 3
    const val WRAP_TOP = 4
    const val LINK = 5
    const val TITLE = 6
    const val MUTED = 7
    const val MONEY_TOTAL = 8
    const val LABEL_TOTAL = 9
    const val INT = 10
    const val DATE_TIME = 11
    const val MONEY_GRAND = 12
    const val SECTION = 13
    const val INT_TOTAL = 14
    const val BOLD = 15
    const val WARN = 16
    const val LABEL_GRAND = 17
    const val CENTER = 18
    const val INT_GRAND = 19
  }

  inner class Sheet(val name: String, val cols: List<Double>, val freeze: Int) {
    val rows = ArrayList<Pair<List<Cell?>, Double?>>()
    val links = ArrayList<Triple<String, String, String>>()
    val images = ArrayList<SheetImage>()
    var autoFilter: String? = null

    fun addRow(cells: List<Cell?> = emptyList(), height: Double? = null): Int {
      rows.add(cells to height)
      return rows.size - 1
    }

    fun addLink(row: Int, col: Int, location: String, display: String) {
      links.add(Triple(ref(row, col), location, display))
    }
  }

  private val sheets = ArrayList<Sheet>()

  fun addSheet(name: String, cols: List<Double>, freeze: Int = 0): Sheet = Sheet(name, cols, freeze).also { sheets.add(it) }

  companion object {
    fun colName(i: Int): String {
      var n = i + 1
      val sb = StringBuilder()
      while (n > 0) {
        val m = (n - 1) % 26
        sb.insert(0, ('A' + m))
        n = (n - 1) / 26
      }
      return sb.toString()
    }

    fun ref(r: Int, c: Int) = colName(c) + (r + 1)

    private val EPOCH = LocalDate.of(1899, 12, 30)

    fun dateSerial(iso: String): Double? {
      val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return null
      return ChronoUnit.DAYS.between(EPOCH, d).toDouble()
    }

    fun dateTimeSerial(t: LocalDateTime): Double =
      ChronoUnit.DAYS.between(EPOCH, t.toLocalDate()) + (t.hour * 60 + t.minute) / 1440.0

    fun x(s: String): String {
      val sb = StringBuilder(s.length + 16)
      for (ch in s) {
        when {
          ch == '&' -> sb.append("&amp;")
          ch == '<' -> sb.append("&lt;")
          ch == '>' -> sb.append("&gt;")
          ch == '"' -> sb.append("&quot;")
          ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
          ch < ' ' || ch == '￾' || ch == '￿' -> {}
          else -> sb.append(ch)
        }
      }
      return sb.toString()
    }

    private fun num(d: Double): String =
      if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
  }

  private val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
  private val nsMain = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
  private val nsR = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  private val nsPkg = "http://schemas.openxmlformats.org/package/2006/relationships"

  private fun font(b: Boolean = false, u: Boolean = false, sz: Int = 11, color: String = "FF1F2328") =
    "<font>${if (b) "<b/>" else ""}${if (u) "<u/>" else ""}<sz val=\"$sz\"/><color rgb=\"$color\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"

  private fun stylesXml(): String = head +
    "<styleSheet xmlns=\"$nsMain\">" +
    "<numFmts count=\"3\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd\"/><numFmt numFmtId=\"165\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"166\" formatCode=\"yyyy-mm-dd hh:mm\"/></numFmts>" +
    "<fonts count=\"8\">" + font() + font(b = true, color = "FFFFFFFF") + font(b = true) + font(u = true, color = "FF0563C1") +
    font(b = true, sz = 18) + font(sz = 10, color = "FF6B7177") + font(b = true, sz = 12) + font(b = true, color = "FF9A5B00") + "</fonts>" +
    "<fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
    "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F2328\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
    "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFE53B\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
    "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF1F2EF\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
    "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
    "<border><left/><right/><top style=\"thin\"><color rgb=\"FF1F2328\"/></top><bottom/><diagonal/></border></borders>" +
    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
    "<cellXfs count=\"20\">" +
    "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
    "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
    "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
    "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
    "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"5\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "<xf numFmtId=\"165\" fontId=\"2\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
    "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
    "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
    "<xf numFmtId=\"165\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"6\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "<xf numFmtId=\"1\" fontId=\"2\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"7\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
    "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\"/></xf>" +
    "<xf numFmtId=\"1\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
    "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
    "<dxfs count=\"0\"/><tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium2\" defaultPivotStyle=\"PivotStyleLight16\"/></styleSheet>"

  private fun cellXml(sb: StringBuilder, r: Int, c: Int, cell: Cell?) {
    if (cell == null) return
    val rf = ref(r, c)
    val sa = if (cell.s != 0) " s=\"${cell.s}\"" else ""
    val v = cell.v
    if (cell.f != null) {
      when (v) {
        is Number -> sb.append("<c r=\"$rf\"$sa><f>${x(cell.f)}</f><v>${num(v.toDouble())}</v></c>")
        is String -> sb.append("<c r=\"$rf\"$sa t=\"str\"><f>${x(cell.f)}</f><v>${x(v)}</v></c>")
        else -> sb.append("<c r=\"$rf\"$sa><f>${x(cell.f)}</f></c>")
      }
      return
    }
    when (v) {
      null -> if (cell.s != 0) sb.append("<c r=\"$rf\"$sa/>")
      is Number -> {
        val d = v.toDouble()
        if (d.isFinite()) sb.append("<c r=\"$rf\"$sa><v>${num(d)}</v></c>")
      }
      is Boolean -> sb.append("<c r=\"$rf\"$sa t=\"b\"><v>${if (v) 1 else 0}</v></c>")
      else -> {
        val s = v.toString()
        if (s.isEmpty() && cell.s == 0) return
        sb.append("<c r=\"$rf\"$sa t=\"inlineStr\"><is><t xml:space=\"preserve\">${x(s.take(32000))}</t></is></c>")
      }
    }
  }

  private fun sheetXml(sh: Sheet, index: Int): String {
    val sb = StringBuilder(8192)
    val maxC = maxOf(1, sh.rows.maxOfOrNull { it.first.size } ?: 1)
    sb.append(head).append("<worksheet xmlns=\"$nsMain\" xmlns:r=\"$nsR\">")
    sb.append("<dimension ref=\"A1:${ref(maxOf(0, sh.rows.size - 1), maxC - 1)}\"/>")
    sb.append("<sheetViews><sheetView workbookViewId=\"0\"${if (index == 0) " tabSelected=\"1\"" else ""}>")
    if (sh.freeze > 0) {
      val tl = "A${sh.freeze + 1}"
      sb.append("<pane ySplit=\"${sh.freeze}\" topLeftCell=\"$tl\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"$tl\" sqref=\"$tl\"/>")
    }
    sb.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>")
    if (sh.cols.isNotEmpty()) {
      sb.append("<cols>")
      sh.cols.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
      sb.append("</cols>")
    }
    sb.append("<sheetData>")
    sh.rows.forEachIndexed { r, (cells, ht) ->
      sb.append("<row r=\"${r + 1}\"")
      if (ht != null) sb.append(" ht=\"${minOf(409.0, ht)}\" customHeight=\"1\"")
      sb.append(">")
      cells.forEachIndexed { c, cell -> cellXml(sb, r, c, cell) }
      sb.append("</row>")
    }
    sb.append("</sheetData>")
    sh.autoFilter?.let { sb.append("<autoFilter ref=\"$it\"/>") }
    if (sh.links.isNotEmpty()) {
      sb.append("<hyperlinks>")
      for ((rf, loc, disp) in sh.links) sb.append("<hyperlink ref=\"$rf\" location=\"${x(loc)}\" display=\"${x(disp)}\"/>")
      sb.append("</hyperlinks>")
    }
    sb.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
    sb.append("<pageSetup orientation=\"landscape\"/>")
    if (sh.images.isNotEmpty()) sb.append("<drawing r:id=\"rId1\"/>")
    sb.append("</worksheet>")
    return sb.toString()
  }

  private fun drawingXml(images: List<SheetImage>): String {
    val e = 9525L
    val sb = StringBuilder(head)
    sb.append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$nsR\">")
    images.forEachIndexed { i, im ->
      val cx = im.w * e
      val cy = im.h * e
      sb.append("<xdr:oneCellAnchor><xdr:from><xdr:col>${im.col}</xdr:col><xdr:colOff>${im.offX * e}</xdr:colOff><xdr:row>${im.row}</xdr:row><xdr:rowOff>${im.offY * e}</xdr:rowOff></xdr:from>")
      sb.append("<xdr:ext cx=\"$cx\" cy=\"$cy\"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${i + 2}\" name=\"${x(im.name)}\" descr=\"${x(im.descr)}\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>")
      sb.append("<xdr:blipFill><a:blip r:embed=\"rId${i + 1}\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
      sb.append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
    }
    sb.append("</xdr:wsDr>")
    return sb.toString()
  }

  fun write(out: OutputStream) {
    val used = HashSet<String>()
    val names = sheets.map { s ->
      var n = s.name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().take(31).ifEmpty { "Sheet" }
      val base = n
      var k = 2
      while (!used.add(n.lowercase())) n = (base.take(28) + " " + k++).take(31)
      n
    }
    val zip = ZipOutputStream(out)
    fun put(name: String, data: ByteArray) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(data)
      zip.closeEntry()
    }
    fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

    val ct = StringBuilder(head)
    ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
    ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
    ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
    ct.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
    ct.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
    ct.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
    val wbRels = StringBuilder(head).append("<Relationships xmlns=\"$nsPkg\">")
    val wbSheets = StringBuilder()
    val defined = StringBuilder()
    val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    var imageNo = 0
    var drawingNo = 0
    val parts = ArrayList<Pair<String, Any>>()
    sheets.forEachIndexed { i, sh ->
      val n = i + 1
      parts.add("xl/worksheets/sheet$n.xml" to sheetXml(sh, i))
      ct.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
      wbRels.append("<Relationship Id=\"rId$n\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
      wbSheets.append("<sheet name=\"${x(names[i])}\" sheetId=\"$n\" r:id=\"rId$n\"/>")
      sh.autoFilter?.let { af ->
        val (a, b) = af.split(":")
        fun abs(cr: String) = cr.replace(Regex("^([A-Z]+)(\\d+)$"), "\\$$1\\$$2")
        defined.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"$i\" hidden=\"1\">'${x(names[i].replace("'", "''"))}'!${abs(a)}:${abs(b)}</definedName>")
      }
      if (sh.images.isNotEmpty()) {
        drawingNo++
        parts.add("xl/worksheets/_rels/sheet$n.xml.rels" to "$head<Relationships xmlns=\"$nsPkg\"><Relationship Id=\"rId1\" Type=\"$rel/drawing\" Target=\"../drawings/drawing$drawingNo.xml\"/></Relationships>")
        parts.add("xl/drawings/drawing$drawingNo.xml" to drawingXml(sh.images))
        ct.append("<Override PartName=\"/xl/drawings/drawing$drawingNo.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
        val dr = StringBuilder(head).append("<Relationships xmlns=\"$nsPkg\">")
        sh.images.forEachIndexed { k, im ->
          imageNo++
          parts.add("xl/media/image$imageNo.jpeg" to im.jpeg)
          dr.append("<Relationship Id=\"rId${k + 1}\" Type=\"$rel/image\" Target=\"../media/image$imageNo.jpeg\"/>")
        }
        parts.add("xl/drawings/_rels/drawing$drawingNo.xml.rels" to dr.append("</Relationships>").toString())
      }
    }
    wbRels.append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"$rel/styles\" Target=\"styles.xml\"/></Relationships>")
    ct.append("</Types>")
    val iso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    put("[Content_Types].xml", ct.toString())
    put("_rels/.rels", "$head<Relationships xmlns=\"$nsPkg\"><Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"xl/workbook.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId3\" Type=\"$rel/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>")
    put("docProps/core.xml", "$head<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${x(title)}</dc:title><dc:creator>${x(creator)}</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">$iso</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$iso</dcterms:modified></cp:coreProperties>")
    put("docProps/app.xml", "$head<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>Microsoft Excel</Application></Properties>")
    put("xl/workbook.xml", "$head<workbook xmlns=\"$nsMain\" xmlns:r=\"$nsR\"><bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"28800\" windowHeight=\"16000\" activeTab=\"0\"/></bookViews><sheets>$wbSheets</sheets>${if (defined.isNotEmpty()) "<definedNames>$defined</definedNames>" else ""}<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
    put("xl/_rels/workbook.xml.rels", wbRels.toString())
    put("xl/styles.xml", stylesXml())
    for ((name, data) in parts) {
      @Suppress("UNCHECKED_CAST")
      when (data) {
        is ByteArray -> put(name, data)
        is String -> put(name, data)
        else -> put(name, (data as () -> ByteArray)())
      }
    }
    zip.finish()
  }
}
