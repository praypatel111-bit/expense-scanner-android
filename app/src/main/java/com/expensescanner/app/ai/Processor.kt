package com.expensescanner.app.ai

import com.expensescanner.app.core.AppSettings
import com.expensescanner.app.core.Extract
import com.expensescanner.app.core.Extraction
import com.expensescanner.app.core.RuleParser
import com.expensescanner.app.core.Status
import com.expensescanner.app.data.BillStore
import com.expensescanner.app.data.Prefs
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Reads bills one at a time in the background: OCR, then the on-phone model, then checks. */
class Processor(
  @Suppress("unused") private val ctx: android.content.Context,
  private val store: BillStore,
  private val prefs: Prefs,
  private val models: ModelManager,
  private val scope: CoroutineScope,
) {
  private class ReadTask(val id: String, val overwrite: Boolean)

  private val queue = Channel<ReadTask>(Channel.UNLIMITED)
  private val _current = MutableStateFlow<String?>(null)
  val current: StateFlow<String?> = _current
  val gemma = GemmaReader(models, prefs)
  private var releaseJob: Job? = null

  init {
    // IO threads: the model call blocks for several seconds per bill.
    scope.launch(Dispatchers.IO) {
      for (task in queue) process(task)
    }
  }

  fun enqueue(id: String, overwrite: Boolean = false) {
    store.update(id) { it.copy(status = Status.QUEUED, error = "") }
    queue.trySend(ReadTask(id, overwrite))
  }

  fun resumeUnfinished() {
    store.bills.value.filter { it.status == Status.QUEUED || it.status == Status.READING }.forEach { queue.trySend(ReadTask(it.id, false)) }
  }

  /** Frees the model's memory. Runs in the background: a bill being read finishes first. */
  fun releaseModel() {
    scope.launch(Dispatchers.IO) { gemma.release() }
  }

  /** Unloads the model, then deletes its file. */
  fun removeModel() {
    scope.launch(Dispatchers.IO) {
      gemma.release()
      models.delete()
    }
  }

  private fun readWithAi(rows: List<String>, settings: AppSettings, today: LocalDate): Extraction? {
    prefs.aiRunning = true
    try {
      val ex = gemma.read(rows, settings, today)
      prefs.aiCrashes = 0
      return ex
    } catch (t: Throwable) {
      return null
    } finally {
      prefs.aiRunning = false
    }
  }

  private suspend fun process(task: ReadTask) {
    releaseJob?.cancel()
    val bill = store.get(task.id) ?: return
    _current.value = task.id
    store.update(task.id) { it.copy(status = Status.READING) }
    try {
      val photo = store.photoFile(bill)
      if (photo == null || !photo.exists()) throw IllegalStateException("The photo for this bill is missing")
      val bmp = Images.decode(photo, 3000) ?: throw IllegalStateException("The photo couldn't be opened")
      val rows = try {
        Ocr.rows(bmp)
      } finally {
        bmp.recycle()
      }
      val settings = prefs.settings.value
      val today = LocalDate.now()
      val rules = RuleParser.parse(rows, today, settings.categories.map { it.id })
      val ai = if (rows.isNotEmpty() && models.isReady() && !prefs.aiPaused) readWithAi(rows, settings, today) else null
      val ex = Extract.reconcile(ai, rules, rows, today)
      store.update(task.id) { b ->
        val read = Extract.apply(b, ex, settings, task.overwrite, System.currentTimeMillis()).copy(ocrText = rows.joinToString("\n"))
        if (rows.isEmpty()) read.copy(confidence = "low", warnings = listOf("No text found on this photo. Is it a bill?")) else read
      }
    } catch (t: Throwable) {
      store.update(task.id) { it.copy(status = Status.FAILED, error = t.message ?: "Couldn't read this bill") }
    } finally {
      _current.value = null
    }
    releaseJob = scope.launch {
      delay(90_000)
      if (_current.value == null) gemma.release()
    }
  }
}
