package com.expensescanner.app.ai

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.expensescanner.app.data.Prefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads, imports and removes the on-phone AI model (Gemma 4 E2B, LiteRT-LM format). */
class ModelManager(private val ctx: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
  class Spec(val name: String, val file: String, val url: String, val bytes: Long, val minRamGb: Int)

  val spec = Spec(
    name = "Gemma 4 E2B",
    file = "gemma-4-E2B-it.litertlm",
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
    bytes = 2588147712L,
    minRamGb = 8,
  )

  sealed interface State {
    data object Missing : State
    data class Downloading(val done: Long, val total: Long, val note: String = "") : State
    data class Copying(val done: Long, val total: Long) : State
    data object Ready : State
    data class Failed(val message: String) : State
  }

  private val dir: File
    get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }
  val file: File
    get() = File(dir, spec.file)
  private val partName = spec.file + ".part"
  private val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
  private val _state = MutableStateFlow<State>(State.Missing)
  val state: StateFlow<State> = _state
  private var watcher: Job? = null

  init {
    _state.value = if (isReady()) State.Ready else State.Missing
    if (!isReady() && prefs.downloadId >= 0) watch(prefs.downloadId)
  }

  fun isReady(): Boolean = file.exists() && file.length() == spec.bytes

  fun totalRamGb(): Double {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    return mi.totalMem / 1_000_000_000.0
  }

  fun freeSpaceGb(): Double = (dir.usableSpace) / 1_000_000_000.0

  fun startDownload() {
    if (isReady()) {
      _state.value = State.Ready
      return
    }
    if (prefs.downloadId >= 0) runCatching { dm.remove(prefs.downloadId) }
    File(dir, partName).delete()
    val req = DownloadManager.Request(Uri.parse(spec.url))
      .setTitle("Expense Scanner AI reader")
      .setDescription("${spec.name} model, 2.6 GB")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setDestinationInExternalFilesDir(ctx, null, "models/$partName")
      .setAllowedOverMetered(prefs.allowMobileData)
      .setAllowedOverRoaming(false)
    val id = dm.enqueue(req)
    prefs.downloadId = id
    _state.value = State.Downloading(0, spec.bytes, "Starting…")
    watch(id)
  }

  fun cancel() {
    val id = prefs.downloadId
    if (id >= 0) runCatching { dm.remove(id) }
    prefs.downloadId = -1
    watcher?.cancel()
    File(dir, partName).delete()
    _state.value = if (isReady()) State.Ready else State.Missing
  }

  fun delete() {
    cancel()
    file.delete()
    _state.value = State.Missing
  }

  /** Uses a model file the person downloaded themselves (for example in a browser). */
  suspend fun importFrom(uri: Uri) = withContext(Dispatchers.IO) {
    val part = File(dir, partName)
    try {
      val total = runCatching {
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
      }.getOrNull()?.takeIf { it > 0 } ?: spec.bytes
      if (total != spec.bytes) {
        _state.value = State.Failed("That file isn't the ${spec.name} model (it should be exactly 2,588,147,712 bytes).")
        return@withContext
      }
      ctx.contentResolver.openInputStream(uri)?.use { input ->
        part.outputStream().use { out ->
          val buf = ByteArray(1 shl 20)
          var done = 0L
          var lastShown = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - lastShown > 32L shl 20) {
              lastShown = done
              _state.value = State.Copying(done, total)
            }
          }
        }
      } ?: throw IllegalStateException("Couldn't open that file")
      if (part.length() == spec.bytes) {
        file.delete()
        part.renameTo(file)
        _state.value = State.Ready
      } else {
        part.delete()
        _state.value = State.Failed("The copied file is incomplete. Try again.")
      }
    } catch (e: Exception) {
      part.delete()
      _state.value = State.Failed(e.message ?: "Couldn't copy the model file")
    }
  }

  private fun watch(id: Long) {
    watcher?.cancel()
    watcher = scope.launch(Dispatchers.IO) {
      while (isActive) {
        val c = dm.query(DownloadManager.Query().setFilterById(id))
        if (c == null || !c.moveToFirst()) {
          c?.close()
          prefs.downloadId = -1
          _state.value = if (isReady()) State.Ready else State.Missing
          return@launch
        }
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: spec.bytes
        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        c.close()
        when (status) {
          DownloadManager.STATUS_SUCCESSFUL -> {
            finishDownload()
            return@launch
          }
          DownloadManager.STATUS_FAILED -> {
            prefs.downloadId = -1
            runCatching { dm.remove(id) }
            _state.value = State.Failed(failText(reason))
            return@launch
          }
          DownloadManager.STATUS_PAUSED -> _state.value = State.Downloading(done, total, pauseText(reason))
          else -> _state.value = State.Downloading(done, total)
        }
        delay(1000)
      }
    }
  }

  private fun finishDownload() {
    prefs.downloadId = -1
    val part = File(dir, partName)
    if (part.exists() && part.length() == spec.bytes) {
      file.delete()
      part.renameTo(file)
    }
    _state.value = if (isReady()) State.Ready else State.Failed("The download finished but the file is incomplete. Try again.")
  }

  private fun failText(reason: Int) = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space. The AI reader needs about 2.6 GB."
    DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR -> "The connection dropped. Try again on Wi-Fi."
    DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "The download server refused the request. Try again later, or use \"I already have the file\"."
    else -> "The download stopped (code $reason). Try again on Wi-Fi."
  }

  private fun pauseText(reason: Int) = when (reason) {
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection"
    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection hiccup, retrying"
    else -> "Paused"
  }
}
