package com.expensescanner.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import com.expensescanner.app.ai.ModelManager
import com.expensescanner.app.ai.Processor
import com.expensescanner.app.data.BillStore
import com.expensescanner.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  lateinit var prefs: Prefs
    private set
  lateinit var store: BillStore
    private set
  lateinit var models: ModelManager
    private set
  lateinit var processor: Processor
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this
    prefs = Prefs(this)
    checkLastAiRun()
    store = BillStore(this, scope)
    models = ModelManager(this, prefs, scope)
    processor = Processor(this, store, prefs, models, scope)
    processor.resumeUnfinished()
  }

  /**
   * If the app closed while the AI reader was working and Android says it crashed or ran out of
   * memory, step down: GPU to CPU first, then pause the AI reader so the app can't crash on every
   * start. Basic reading keeps working either way.
   */
  private fun checkLastAiRun() {
    if (!prefs.aiRunning) return
    prefs.aiRunning = false
    val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
    val reason = runCatching { am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.reason }.getOrNull()
    val crashed = reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_CRASH ||
      reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_LOW_MEMORY
    if (!crashed) return
    prefs.aiCrashes = prefs.aiCrashes + 1
    if (prefs.useGpu) prefs.useGpu = false else if (prefs.aiCrashes >= 2) prefs.aiPaused = true
  }

  companion object {
    lateinit var instance: App
      private set
  }
}
