package com.expensescanner.app.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.expensescanner.app.ai.Images
import com.expensescanner.app.core.AppSettings
import com.expensescanner.app.core.Bill
import com.expensescanner.app.core.PhotoForExcel
import com.expensescanner.app.core.Report
import com.expensescanner.app.data.BillStore
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the Excel report / photo zip and hands them to the phone, Google Drive or any app. */
object Exporter {
  const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  const val ZIP = "application/zip"
  private const val DRIVE = "com.google.android.apps.docs"

  private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

  fun buildXlsx(
    ctx: Context,
    store: BillStore,
    bills: List<Bill>,
    settings: AppSettings,
    rangeLabel: String,
    withPhotos: Boolean,
    fileName: String,
    progress: (String) -> Unit,
    photoFolder: String? = null,
  ): File {
    // Photos are shrunk one at a time while the file is written, so memory stays low.
    val photos: Map<String, PhotoForExcel>? = if (withPhotos) {
      val files = bills.mapNotNull { b ->
        val f = store.photoFile(b)?.takeIf { it.exists() && b.photoW > 0 && b.photoH > 0 } ?: return@mapNotNull null
        b to f
      }
      var done = 0
      files.associate { (b, f) ->
        b.id to PhotoForExcel(b.photoW, b.photoH) {
          done++
          progress("Adding receipt photos: $done of ${files.size}")
          Images.excelJpeg(f)
        }
      }
    } else null
    progress("Building the Excel file…")
    val wb = Report.build(bills, settings, rangeLabel, photos, photoFolder = photoFolder)
    val out = freshFile(ctx, fileName)
    out.outputStream().buffered().use { wb.write(it) }
    return out
  }

  /** A new file in the export folder. Older exports are cleared after an hour (Drive may still be reading a recent one). */
  private fun freshFile(ctx: Context, fileName: String): File {
    val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
    dir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff || it.name == fileName) it.delete() }
    return File(dir(ctx), fileName)
  }

  /**
   * One download for the chosen dates: the Excel report plus every receipt photo in a "Receipts"
   * folder (R-0001_2026-11-09_Tim-Hortons_14.35.jpg). When the photos aren't inside the Excel file,
   * its "Open receipt" links open these photo files once the .zip is unzipped.
   */
  fun buildZip(
    ctx: Context,
    store: BillStore,
    bills: List<Bill>,
    settings: AppSettings,
    rangeLabel: String,
    withPhotos: Boolean,
    xlsxName: String,
    zipName: String,
    progress: (String) -> Unit,
  ): File {
    val xlsx = buildXlsx(ctx, store, bills, settings, rangeLabel, withPhotos, xlsxName, progress, photoFolder = "$FOLDER/")
    val names = Report.photoFileNames(bills)
    val out = freshFile(ctx, zipName)
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
      zip.setLevel(Deflater.BEST_SPEED) // photos and the Excel file are already compressed
      zip.putNextEntry(ZipEntry(xlsxName))
      xlsx.inputStream().use { it.copyTo(zip) }
      zip.closeEntry()
      var done = 0
      for (b in bills) {
        val name = names[b.id] ?: continue
        val f = store.photoFile(b)?.takeIf { it.exists() } ?: continue
        progress("Packing photos: ${++done} of ${names.size}")
        zip.putNextEntry(ZipEntry("$FOLDER/$name"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
    xlsx.delete()
    return out
  }

  private const val FOLDER = "Receipts"

  fun copyTo(ctx: Context, src: File, dest: Uri) {
    // "wt" truncates when replacing an existing file; not every provider supports it.
    val cr = ctx.contentResolver
    val os = runCatching { cr.openOutputStream(dest, "wt") }.getOrNull() ?: cr.openOutputStream(dest) ?: throw IOException("Couldn't open the destination")
    os.use { o -> src.inputStream().use { it.copyTo(o) } }
  }

  private fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

  private fun sendIntent(ctx: Context, f: File, mime: String): Intent {
    val uri = uriFor(ctx, f)
    return Intent(Intent.ACTION_SEND).apply {
      type = mime
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_TITLE, f.name)
      putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
      clipData = ClipData.newRawUri(f.name, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /** Opens Google Drive's "Upload to Drive" screen with the file. False when Drive isn't installed. */
  fun sendToDrive(ctx: Context, f: File, mime: String): Boolean = try {
    ctx.startActivity(sendIntent(ctx, f, mime).setPackage(DRIVE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
  } catch (e: ActivityNotFoundException) {
    false
  }

  fun share(ctx: Context, f: File, mime: String) {
    val chooser = Intent.createChooser(sendIntent(ctx, f, mime), "Send ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(chooser)
  }
}
