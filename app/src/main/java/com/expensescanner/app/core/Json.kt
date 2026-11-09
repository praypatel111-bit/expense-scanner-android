package com.expensescanner.app.core

/** Small dependency-free JSON reader/writer (objects become LinkedHashMap, numbers become Double). */
object Json {
  fun parse(text: String): Any? {
    val p = Parser(text)
    val v = p.value()
    p.ws()
    if (p.i != text.length) throw IllegalArgumentException("Unexpected data at ${p.i}")
    return v
  }

  /** Finds the first JSON object in [text] (handles code fences and chatter around it). */
  fun parseObjectLoose(text: String): Map<String, Any?>? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inStr = false
    var esc = false
    for (k in start until text.length) {
      val c = text[k]
      if (inStr) {
        if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
        continue
      }
      when (c) {
        '"' -> inStr = true
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) {
            @Suppress("UNCHECKED_CAST")
            return runCatching { parse(text.substring(start, k + 1)) as? Map<String, Any?> }.getOrNull()
          }
        }
      }
    }
    return null
  }

  fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

  private fun write(sb: StringBuilder, v: Any?) {
    when (v) {
      null -> sb.append("null")
      is String -> quote(sb, v)
      is Boolean -> sb.append(if (v) "true" else "false")
      is Int, is Long -> sb.append(v.toString())
      is Number -> {
        val d = v.toDouble()
        if (d.isNaN() || d.isInfinite()) sb.append("null")
        else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong().toString())
        else sb.append(d.toString())
      }
      is Map<*, *> -> {
        sb.append('{')
        var first = true
        for ((k, value) in v) {
          if (!first) sb.append(',')
          first = false
          quote(sb, k.toString())
          sb.append(':')
          write(sb, value)
        }
        sb.append('}')
      }
      is Iterable<*> -> {
        sb.append('[')
        var first = true
        for (item in v) {
          if (!first) sb.append(',')
          first = false
          write(sb, item)
        }
        sb.append(']')
      }
      else -> quote(sb, v.toString())
    }
  }

  private fun quote(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
      }
    }
    sb.append('"')
  }

  private class Parser(val s: String) {
    var i = 0

    fun ws() {
      while (i < s.length && s[i].isWhitespace()) i++
    }

    fun value(): Any? {
      ws()
      if (i >= s.length) throw IllegalArgumentException("Unexpected end")
      return when (val c = s[i]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> str()
        't' -> lit("true", true)
        'f' -> lit("false", false)
        'n' -> lit("null", null)
        else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Bad char '$c' at $i")
      }
    }

    private fun lit(word: String, v: Any?): Any? {
      if (!s.startsWith(word, i)) throw IllegalArgumentException("Bad literal at $i")
      i += word.length
      return v
    }

    private fun num(): Double {
      val start = i
      if (s[i] == '-') i++
      while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
      return s.substring(start, i).toDouble()
    }

    private fun str(): String {
      i++
      val sb = StringBuilder()
      while (i < s.length) {
        val c = s[i++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            val e = s[i++]
            when (e) {
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'u' -> {
                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                i += 4
              }
              else -> sb.append(e)
            }
          }
          else -> sb.append(c)
        }
      }
      throw IllegalArgumentException("Unterminated string")
    }

    private fun arr(): List<Any?> {
      i++
      val out = ArrayList<Any?>()
      ws()
      if (i < s.length && s[i] == ']') {
        i++
        return out
      }
      while (true) {
        out.add(value())
        ws()
        when (s.getOrNull(i)) {
          ',' -> i++
          ']' -> {
            i++
            return out
          }
          else -> throw IllegalArgumentException("Expected , or ] at $i")
        }
      }
    }

    private fun obj(): Map<String, Any?> {
      i++
      val out = LinkedHashMap<String, Any?>()
      ws()
      if (i < s.length && s[i] == '}') {
        i++
        return out
      }
      while (true) {
        ws()
        if (s.getOrNull(i) != '"') throw IllegalArgumentException("Expected key at $i")
        val k = str()
        ws()
        if (s.getOrNull(i) != ':') throw IllegalArgumentException("Expected : at $i")
        i++
        out[k] = value()
        ws()
        when (s.getOrNull(i)) {
          ',' -> i++
          '}' -> {
            i++
            return out
          }
          else -> throw IllegalArgumentException("Expected , or } at $i")
        }
      }
    }
  }
}
