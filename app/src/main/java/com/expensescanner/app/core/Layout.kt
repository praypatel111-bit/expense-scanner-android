package com.expensescanner.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One OCR text line with its 4 corner points (top-left, top-right, bottom-right, bottom-left). */
class OcrLine(val text: String, val xs: FloatArray, val ys: FloatArray)

/**
 * Rebuilds receipt rows from OCR lines: item names and prices often come back as separate lines,
 * so lines sharing a baseline are joined left-to-right with " | ". Handles a tilted photo by
 * measuring the dominant text angle first.
 */
object Layout {
  private class Box(val text: String, val x: Double, val y: Double, val h: Double)

  fun rows(lines: List<OcrLine>): List<String> {
    val clean = lines.filter { it.text.isNotBlank() && it.xs.size == 4 && it.ys.size == 4 }
    if (clean.isEmpty()) return emptyList()
    val angles = clean.mapNotNull { l ->
      val dx = (l.xs[1] - l.xs[0]).toDouble()
      val dy = (l.ys[1] - l.ys[0]).toDouble()
      val w = hypot(dx, dy)
      val h = hypot((l.xs[3] - l.xs[0]).toDouble(), (l.ys[3] - l.ys[0]).toDouble())
      if (w > 2 * h && w > 0) atan2(dy, dx) else null
    }.sorted()
    val a = if (angles.isEmpty()) 0.0 else angles[angles.size / 2].coerceIn(-0.35, 0.35)
    val ca = cos(a)
    val sa = sin(a)
    val boxes = clean.map { l ->
      val cx = l.xs.average()
      val cy = l.ys.average()
      val h = hypot((l.xs[3] - l.xs[0]).toDouble(), (l.ys[3] - l.ys[0]).toDouble()).coerceAtLeast(1.0)
      Box(l.text.trim(), cx * ca + cy * sa, -cx * sa + cy * ca, h)
    }.sortedBy { it.y }

    val rows = ArrayList<MutableList<Box>>()
    var rowY = 0.0
    var rowH = 0.0
    for (b in boxes) {
      val cur = rows.lastOrNull()
      if (cur != null && abs(b.y - rowY) <= 0.55 * maxOf(b.h, rowH)) {
        cur.add(b)
        rowY = cur.sumOf { it.y } / cur.size
        rowH = cur.sumOf { it.h } / cur.size
      } else {
        rows.add(mutableListOf(b))
        rowY = b.y
        rowH = b.h
      }
    }
    return rows.map { r -> r.sortedBy { it.x }.joinToString(" | ") { it.text } }
  }
}
