package io.github.ouor.paseophone.runtime.linux

import java.io.ByteArrayOutputStream
import java.io.File

/** Minimal tar writer: ustar headers with a valid checksum. */
internal class TestTar {
  val out = ByteArrayOutputStream()

  fun entry(name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "", mode: Int = "644".toInt(8), prefix: String = "") {
    val h = ByteArray(512)
    put(h, 0, name.toByteArray())
    put(h, 100, "%07o\u0000".format(mode).toByteArray())
    put(h, 108, "0000000\u0000".toByteArray())
    put(h, 116, "0000000\u0000".toByteArray())
    put(h, 124, "%011o\u0000".format(body.size).toByteArray())
    put(h, 136, "00000000000\u0000".toByteArray())
    h[156] = type.code.toByte()
    put(h, 157, link.toByteArray())
    put(h, 257, "ustar\u000000".toByteArray())
    put(h, 345, prefix.toByteArray())
    for (i in 148 until 156) h[i] = ' '.code.toByte()
    val sum = h.sumOf { it.toInt() and 0xFF }
    put(h, 148, "%06o\u0000 ".format(sum).toByteArray())
    out.write(h)
    out.write(body)
    val pad = (512 - body.size % 512) % 512
    out.write(ByteArray(pad))
  }

  fun pax(vararg kv: Pair<String, String>) {
    val body = StringBuilder()
    for ((k, v) in kv) {
      val rec = " $k=$v\n"
      var len = rec.toByteArray().size + 1
      while ((len.toString() + rec).toByteArray().size != len) len++
      body.append(len).append(rec)
    }
    entry("PaxHeaders/x", 'x', body.toString().toByteArray())
  }

  fun writeTo(file: File): File {
    file.parentFile?.mkdirs()
    file.writeBytes(bytes())
    return file
  }

  fun bytes(): ByteArray {
    out.write(ByteArray(1024))
    return out.toByteArray()
  }

  private fun put(h: ByteArray, off: Int, b: ByteArray) = b.copyInto(h, off)
}
