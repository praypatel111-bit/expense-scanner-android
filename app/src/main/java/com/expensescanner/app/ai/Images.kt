package com.expensescanner.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object Images {
  class Imported(val name: String, val w: Int, val h: Int)

  /** Copies a picked or captured photo into app storage: upright, at most 2400 px, JPEG. */
  fun importPhoto(ctx: Context, uri: Uri, dir: File, name: String): Imported {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("Can't open the photo")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file isn't a photo")
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("Can't read the photo")
    val rotation = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
    var bmp = raw
    if (rotation != 0) {
      val m = Matrix().apply { postRotate(rotation.toFloat()) }
      bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
      if (bmp !== raw) raw.recycle()
    }
    val scaled = scaleDown(bmp, 2400)
    if (scaled !== bmp) bmp.recycle()
    val out = File(dir, name)
    FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
    val result = Imported(name, scaled.width, scaled.height)
    scaled.recycle()
    return result
  }

  fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
    val s = maxSide.toFloat() / max(b.width, b.height)
    if (s >= 1f) return b
    return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
  }

  fun decode(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val s = scaleDown(b, maxSide)
    if (s !== b) b.recycle()
    return s
  }

  /** A smaller JPEG for the Excel file so it stays easy to email. Never fails: a grey tile stands in. */
  fun excelJpeg(file: File): ByteArray {
    val b = runCatching { decode(file, 1600) }.getOrNull() ?: Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFDDDDDD.toInt()) }
    val bos = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 72, bos)
    b.recycle()
    return bos.toByteArray()
  }
}
