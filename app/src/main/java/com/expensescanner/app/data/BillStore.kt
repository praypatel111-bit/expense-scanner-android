package com.expensescanner.app.data

import android.content.Context
import com.expensescanner.app.core.Bill
import com.expensescanner.app.core.Json
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All bills live in one JSON file in the app's private storage; photos sit next to it. */
class BillStore(ctx: Context, private val scope: CoroutineScope) {
  private val file = File(ctx.filesDir, "bills.json")
  val photosDir: File = File(ctx.filesDir, "photos").apply { mkdirs() }
  private val _bills = MutableStateFlow<List<Bill>>(emptyList())
  val bills: StateFlow<List<Bill>> = _bills
  private val mutex = Mutex()

  init {
    if (file.exists()) {
      runCatching {
        val list = Json.parse(file.readText()) as? List<*>
        @Suppress("UNCHECKED_CAST")
        _bills.value = list.orEmpty().mapNotNull { Bill.fromMap(it as? Map<String, Any?>) }
      }
    }
  }

  fun get(id: String): Bill? = _bills.value.firstOrNull { it.id == id }

  fun nextNum(): Int = (_bills.value.maxOfOrNull { it.num } ?: 0) + 1

  fun upsert(bill: Bill) {
    _bills.update { list ->
      val i = list.indexOfFirst { it.id == bill.id }
      if (i >= 0) list.toMutableList().also { it[i] = bill } else list + bill
    }
    persist()
  }

  fun update(id: String, fn: (Bill) -> Bill) {
    var changed = false
    _bills.update { list ->
      list.map { b ->
        if (b.id == id) {
          changed = true
          fn(b).copy(updatedAt = System.currentTimeMillis())
        } else b
      }
    }
    if (changed) persist()
  }

  fun delete(id: String) {
    val b = get(id)
    _bills.update { list -> list.filter { it.id != id } }
    b?.photo?.let { File(photosDir, it).delete() }
    persist()
  }

  fun photoFile(b: Bill): File? = b.photo?.let { File(photosDir, it) }

  private fun persist() {
    scope.launch(Dispatchers.IO) {
      mutex.withLock {
        val snapshot = _bills.value
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.stringify(snapshot.map { it.toMap() }))
        if (!tmp.renameTo(file)) {
          file.writeText(tmp.readText())
          tmp.delete()
        }
      }
    }
  }
}
