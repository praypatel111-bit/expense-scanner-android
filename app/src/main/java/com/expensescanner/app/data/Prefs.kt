package com.expensescanner.app.data

import android.content.Context
import com.expensescanner.app.core.AppSettings
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App settings kept on the phone. */
class Prefs(ctx: Context) {
  private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val _settings = MutableStateFlow(load())
  val settings: StateFlow<AppSettings> = _settings

  var useGpu: Boolean
    get() = sp.getBoolean("useGpu", true)
    set(v) {
      sp.edit().putBoolean("useGpu", v).apply()
    }

  var allowMobileData: Boolean
    get() = sp.getBoolean("mobileData", false)
    set(v) {
      sp.edit().putBoolean("mobileData", v).apply()
    }

  var downloadId: Long
    get() = sp.getLong("downloadId", -1L)
    set(v) {
      sp.edit().putLong("downloadId", v).apply()
    }

  var includePhotos: Boolean
    get() = sp.getBoolean("includePhotos", true)
    set(v) {
      sp.edit().putBoolean("includePhotos", v).apply()
    }

  /** True while the on-phone AI is working. Written straight to disk so it survives a crash. */
  var aiRunning: Boolean
    get() = sp.getBoolean("aiRunning", false)
    set(v) {
      sp.edit().putBoolean("aiRunning", v).commit()
    }

  /** How many times in a row the AI reader took the app down. */
  var aiCrashes: Int
    get() = sp.getInt("aiCrashes", 0)
    set(v) {
      sp.edit().putInt("aiCrashes", v).apply()
    }

  /** The AI reader is switched off after repeated crashes; bills are read with the basic rules. */
  private val _aiPaused = MutableStateFlow(sp.getBoolean("aiPaused", false))
  val aiPausedState: StateFlow<Boolean> = _aiPaused
  var aiPaused: Boolean
    get() = _aiPaused.value
    set(v) {
      sp.edit().putBoolean("aiPaused", v).apply()
      _aiPaused.value = v
    }

  private fun load(): AppSettings {
    val (region, currency) = guess(TimeZone.getDefault().id)
    return AppSettings(
      businessName = sp.getString("businessName", "") ?: "",
      region = sp.getString("region", region) ?: region,
      currency = sp.getString("currency", currency) ?: currency,
      personalCards = (sp.getString("personalCards", "") ?: "").split(",").map { it.trim() }.filter { it.length == 4 },
    )
  }

  fun update(s: AppSettings) {
    sp.edit()
      .putString("businessName", s.businessName)
      .putString("region", s.region)
      .putString("currency", s.currency)
      .putString("personalCards", s.personalCards.joinToString(","))
      .apply()
    _settings.value = s
  }

  companion object {
    fun guess(tz: String): Pair<String, String> = when {
      tz == "America/Toronto" || tz == "America/Nipigon" || tz == "America/Thunder_Bay" -> "Ontario, Canada" to "CAD"
      tz == "America/Montreal" -> "Quebec, Canada" to "CAD"
      tz == "America/Vancouver" -> "British Columbia, Canada" to "CAD"
      tz == "America/Edmonton" -> "Alberta, Canada" to "CAD"
      tz == "America/Winnipeg" -> "Manitoba, Canada" to "CAD"
      tz == "America/Regina" -> "Saskatchewan, Canada" to "CAD"
      tz == "America/Halifax" || tz == "America/Moncton" || tz == "America/St_Johns" -> "Atlantic Canada" to "CAD"
      tz.startsWith("America/New_York") || tz.startsWith("America/Chicago") || tz.startsWith("America/Denver") || tz.startsWith("America/Los_Angeles") -> "United States" to "USD"
      tz == "Europe/London" -> "United Kingdom" to "GBP"
      tz == "Asia/Kolkata" || tz == "Asia/Calcutta" -> "India" to "INR"
      tz.startsWith("Australia/") -> "Australia" to "AUD"
      tz.startsWith("Europe/") -> "Europe" to "EUR"
      else -> "Canada" to "CAD"
    }
  }
}
