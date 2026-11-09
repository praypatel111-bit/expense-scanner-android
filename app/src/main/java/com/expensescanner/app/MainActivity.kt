package com.expensescanner.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.expensescanner.app.ui.AppRoot
import com.expensescanner.app.ui.ExpenseTheme

class MainActivity : ComponentActivity() {
  private val incoming = mutableStateOf<List<Uri>>(emptyList())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (savedInstanceState == null) handle(intent)
    setContent {
      ExpenseTheme {
        AppRoot(incoming = incoming.value, onIncomingHandled = { incoming.value = emptyList() })
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
  }

  /** Photos shared into the app from Photos, Gmail, WhatsApp, etc. */
  private fun handle(i: Intent?) {
    if (i == null) return
    val uris: List<Uri> = when (i.action) {
      Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
      Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      else -> emptyList()
    }
    if (uris.isNotEmpty()) incoming.value = uris
  }
}
