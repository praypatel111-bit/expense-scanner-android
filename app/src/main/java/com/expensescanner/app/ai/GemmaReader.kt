package com.expensescanner.app.ai

import com.expensescanner.app.core.AppSettings
import com.expensescanner.app.core.Extract
import com.expensescanner.app.core.Extraction
import com.expensescanner.app.data.Prefs
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.time.LocalDate

/**
 * Runs Gemma 4 E2B on the phone (LiteRT-LM) to sort the receipt text into fields. The answer is
 * constrained to a JSON schema so it parses; if that attempt gives nothing usable, it asks once
 * more without the schema. One engine stays loaded while bills are being read and is closed after
 * a quiet spell to give the memory back.
 */
class GemmaReader(private val models: ModelManager, private val prefs: Prefs) {
  private var engine: Engine? = null
  private var gpuFailed = false
  var backendName: String = ""
    private set
  var lastError: String = ""
    private set

  @Synchronized
  fun read(rows: List<String>, settings: AppSettings, today: LocalDate): Extraction? {
    val eng = ensureEngine() ?: return null
    val ids = settings.categories.map { it.id }
    val prompt = Extract.buildPrompt(rows, settings, today)
    try {
      return readWith(eng, prompt, ids)
    } catch (e: Exception) {
      if (backendName != "GPU") throw e
      // The graphics chip failed during a real read: use the CPU from now on and try again.
      Log.w(TAG, "GPU failed while reading, switching to the CPU", e)
      giveUpOnGpu()
      release()
      val cpu = ensureEngine() ?: throw e
      return readWith(cpu, prompt, ids)
    }
  }

  private fun readWith(eng: Engine, prompt: String, ids: List<String>): Extraction? {
    val constrained = try {
      Extract.fromModel(ask(eng, prompt, ResponseFormat.json(Extract.schema(ids))), ids)
    } catch (e: Exception) {
      lastError = e.message ?: e.toString()
      null
    }
    if (constrained != null) return constrained
    return Extract.fromModel(ask(eng, "$prompt\n\nReply with only the JSON object.", null), ids)
  }

  /** One fresh conversation per bill, so nothing from an earlier bill leaks into the next. */
  private fun ask(eng: Engine, prompt: String, format: ResponseFormat?): String =
    eng.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(Extract.SYSTEM),
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        maxOutputToken = MAX_OUTPUT,
        enableResponseFormat = format != null,
      )
    ).use { conv ->
      conv.sendMessage(prompt, extraContext = NO_THINKING, maxOutputToken = MAX_OUTPUT, responseFormat = format).toString()
    }

  @Synchronized
  private fun ensureEngine(): Engine? {
    engine?.let { return it }
    if (!models.isReady()) return null
    val path = models.file.absolutePath
    val order = if (prefs.useGpu && !gpuFailed) listOf("GPU", "CPU") else listOf("CPU")
    for (name in order) {
      var e: Engine? = null
      try {
        // Room for a long receipt (prompt is capped near 2,600 tokens) plus the answer.
        e = Engine(EngineConfig(modelPath = path, backend = if (name == "GPU") Backend.GPU() else Backend.CPU(), maxNumTokens = 6144))
        e.initialize()
        // Some phones accept the graphics chip at load time and only fail on the first run
        // (no OpenCL). A one-word answer finds out in about a second.
        if (name == "GPU") probe(e)
        engine = e
        backendName = name
        lastError = ""
        Log.i(TAG, "AI reader loaded on the $name")
        return e
      } catch (t: Throwable) {
        lastError = "$name: ${t.message ?: t}"
        Log.w(TAG, "AI reader couldn't start on the $name", t)
        runCatching { e?.close() }
        if (name == "GPU") giveUpOnGpu()
      }
    }
    return null
  }

  private fun probe(e: Engine) {
    e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0), maxOutputToken = 1)).use {
      it.sendMessage("Hi", extraContext = NO_THINKING, maxOutputToken = 1)
    }
  }

  /** Remembered across restarts; Settings shows the switch turned off and it can be turned back on. */
  private fun giveUpOnGpu() {
    gpuFailed = true
    prefs.useGpu = false
  }

  @Synchronized
  fun release() {
    try {
      engine?.close()
    } catch (_: Throwable) {
    }
    engine = null
  }

  private companion object {
    const val TAG = "ExpenseScanner"
    const val MAX_OUTPUT = 1600
    // Gemma 4 can "think" before answering; a receipt doesn't need it and it would cost time.
    val NO_THINKING: Map<String, Any> = mapOf("enable_thinking" to false)
  }
}
