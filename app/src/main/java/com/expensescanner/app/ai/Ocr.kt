package com.expensescanner.app.ai

import android.graphics.Bitmap
import com.expensescanner.app.core.Layout
import com.expensescanner.app.core.OcrLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Reads the printed text on the phone with Google's ML Kit (offline), then rebuilds receipt rows. */
object Ocr {
  private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

  suspend fun rows(bitmap: Bitmap): List<String> {
    val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
    val lines = ArrayList<OcrLine>()
    for (block in text.textBlocks) {
      for (line in block.lines) {
        val pts = line.cornerPoints
        if (pts != null && pts.size == 4) {
          lines.add(OcrLine(line.text, FloatArray(4) { pts[it].x.toFloat() }, FloatArray(4) { pts[it].y.toFloat() }))
        } else {
          val b = line.boundingBox ?: continue
          lines.add(
            OcrLine(
              line.text,
              floatArrayOf(b.left.toFloat(), b.right.toFloat(), b.right.toFloat(), b.left.toFloat()),
              floatArrayOf(b.top.toFloat(), b.top.toFloat(), b.bottom.toFloat(), b.bottom.toFloat()),
            )
          )
        }
      }
    }
    return Layout.rows(lines)
  }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
  addOnSuccessListener { cont.resume(it) }
  addOnFailureListener { cont.resumeWithException(it) }
  addOnCanceledListener { cont.cancel() }
}
