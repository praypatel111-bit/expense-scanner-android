package com.expensescanner.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensescanner.app.core.Bill
import com.expensescanner.app.core.Books
import com.expensescanner.app.core.Category
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

fun catOf(cats: List<Category>, id: String?): Category? = if (id == null) null else cats.firstOrNull { it.id == id }

fun catLabel(cats: List<Category>, id: String?): String = catOf(cats, id)?.let { "${it.icon} ${it.name}" } ?: if (id == null) "No category" else "Other"

fun dayLabel(iso: String): String {
  val d = Books.parseIso(iso) ?: return "No date"
  val today = LocalDate.now()
  return when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.CANADA))
  }
}

fun shortDate(iso: String): String {
  val d = Books.parseIso(iso) ?: return ""
  return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy", Locale.CANADA))
}

fun monthLabel(key: String): String = runCatching {
  YearMonth.parse(key).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.CANADA))
}.getOrDefault(key)

fun payShort(b: Bill): String = if (b.cardLast4.isNotEmpty()) "${b.payMethod.ifEmpty { "Card" }} ••${b.cardLast4}" else b.payMethod

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  val p = LocalPalette.current
  Text(text.uppercase(), modifier = modifier, color = p.muted, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
}

/** Text with a hand-drawn highlighter stroke behind it. */
@Composable
fun Highlight(text: String, fontSize: Int = 22) {
  val p = LocalPalette.current
  Box(
    Modifier.drawBehind {
      rotate(-1.4f) {
        drawRoundRect(color = p.hi, topLeft = Offset(-6f, size.height * 0.12f), size = Size(size.width + 10f, size.height * 0.84f), cornerRadius = CornerRadius(10f, 10f))
      }
    }.padding(horizontal = 6.dp)
  ) {
    Text(text, color = p.onHi, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fontSize.sp, maxLines = 1)
  }
}

@Composable
fun Tag(text: String, color: Color? = null) {
  val p = LocalPalette.current
  val c = color ?: p.muted
  Box(Modifier.border(1.dp, if (color == null) p.line else c, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
    Text(text.uppercase(), color = c, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, letterSpacing = 0.5.sp, maxLines = 1)
  }
}

@Composable
fun LeaderRow(label: String, value: String, dim: Boolean = false) {
  val p = LocalPalette.current
  val color = if (dim) p.muted else p.ink
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
    Text(label.uppercase(), color = color, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    Canvas(Modifier.weight(1f).height(10.dp).padding(horizontal = 6.dp)) {
      drawLine(p.line, Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 7f)))
    }
    Text(value, color = color, fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
  }
}

/** The month summary printed like a thermal receipt with a torn bottom edge. */
@Composable
fun ReceiptTape(header: @Composable () -> Unit, body: @Composable () -> Unit, totalLabel: String, total: String) {
  val p = LocalPalette.current
  Column(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().background(p.paper).padding(horizontal = 16.dp, vertical = 12.dp)) {
      header()
      DashedRule()
      body()
      DashedRule()
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(totalLabel, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = p.ink)
        Highlight(total)
      }
    }
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
      val tooth = 16.dp.toPx()
      val path = Path()
      var x = 0f
      path.moveTo(0f, 0f)
      while (x < size.width) {
        path.lineTo(x + tooth / 2, size.height)
        path.lineTo(x + tooth, 0f)
        x += tooth
      }
      path.close()
      drawPath(path, p.paper)
    }
  }
}

@Composable
fun DashedRule() {
  val p = LocalPalette.current
  Canvas(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(2.dp)) {
    drawLine(p.line, Offset(0f, 1f), Offset(size.width, 1f), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
  }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int) = Spacer(Modifier.width(w.dp))
