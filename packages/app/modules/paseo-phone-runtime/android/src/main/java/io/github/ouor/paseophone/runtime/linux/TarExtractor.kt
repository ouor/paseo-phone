// Adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/TarExtractor.kt, Apache-2.0.
// Modifications: pax (x) path/linkpath/size, GNU L/K long names, base-256 sizes,
// strip-components, full permission bits, string-based path check (Kai's canonicalPath check
// followed symlinks and skipped valid entries), real hard-link targets, entry count.
package io.github.ouor.paseophone.runtime.linux

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

private const val BLOCK = 512
private const val MAX_META_BODY = 1 shl 20

object TarExtractor {
  /**
   * Extracts a `.tar.xz`, `.tar.gz` or plain `.tar` into [targetDir], dropping the first
   * [stripComponents] path elements. Returns the number of entries written (files, dirs,
   * symlinks, hard links), comparable to `tar -tf | wc -l` minus dropped/device entries.
   *
   * The archives are pinned by sha256 (RootfsSpec, NodeDist), so the path check only keeps
   * names inside [targetDir] by string; it does not chase symlinks the archive itself created.
   */
  fun extract(archive: File, targetDir: File, stripComponents: Int): Int {
    val raw = BufferedInputStream(FileInputStream(archive), 1 shl 16)
    val stream = when {
      archive.name.endsWith(".xz") -> XZInputStream(raw)
      archive.name.endsWith(".gz") || archive.name.endsWith(".tgz") -> GZIPInputStream(raw)
      else -> raw
    }
    return stream.use { extractTar(it, targetDir, stripComponents) }
  }

  fun extractTar(input: InputStream, targetDir: File, stripComponents: Int): Int {
    targetDir.mkdirs()
    val header = ByteArray(BLOCK)
    val buf = ByteArray(1 shl 16)
    val dirModes = ArrayList<Pair<File, Int>>()
    var count = 0
    var paxPath: String? = null
    var paxLink: String? = null
    var paxSize: Long? = null
    var longName: String? = null
    var longLink: String? = null

    while (true) {
      if (readFully(input, header, BLOCK) < BLOCK) break
      if (header.all { it == 0.toByte() }) break // end-of-archive marker

      val type = header[156].toInt().toChar()
      var size = parseNumber(header, 124, 12)

      when (type) {
        'x' -> {
          val rec = parsePax(readBody(input, size))
          paxPath = rec["path"]
          paxLink = rec["linkpath"]
          paxSize = rec["size"]?.toLongOrNull()
          continue
        }
        'g' -> {
          skip(input, align(size))
          continue
        }
        'L' -> {
          longName = cString(readBody(input, size))
          continue
        }
        'K' -> {
          longLink = cString(readBody(input, size))
          continue
        }
      }

      val name = paxPath ?: longName ?: ustarName(header)
      val link = paxLink ?: longLink ?: field(header, 157, 100)
      paxSize?.let { size = it }
      paxPath = null
      paxLink = null
      paxSize = null
      longName = null
      longLink = null

      val mode = parseNumber(header, 100, 8).toInt() and 0xFFF
      val rel = relativeEntryPath(name, stripComponents)
      val isRegular = type == '0' || type == '\u0000' || type == '7'
      if (rel == null) {
        skip(input, align(size))
        continue
      }
      val out = File(targetDir, rel)
      val outPath = out.toPath()

      if (isRegular) {
        out.parentFile?.mkdirs()
        Files.deleteIfExists(outPath) // never write through a symlink left at this name
        FileOutputStream(out).use { o ->
          var remaining = size
          while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("Truncated tar entry $name")
            o.write(buf, 0, n)
            remaining -= n
          }
        }
        skip(input, align(size) - size)
        setMode(out, mode or 0b110_000_000) // owner rw so dpkg/npm can rewrite in place
        count++
        continue
      }

      when (type) {
        '5' -> {
          out.mkdirs()
          dirModes.add(out to mode)
          count++
        }
        '2' -> {
          out.parentFile?.mkdirs()
          Files.deleteIfExists(outPath)
          Files.createSymbolicLink(outPath, Paths.get(link))
          count++
        }
        '1' -> {
          val sourceRel = relativeEntryPath(link, stripComponents)
          val source = sourceRel?.let { File(targetDir, it).toPath() }
          // Android SELinux denies link(2) in app data; copy like Kai does.
          if (source != null && Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            out.parentFile?.mkdirs()
            Files.copy(source, outPath, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
            count++
          }
        }
        // '3', '4', '6' (devices, fifo) cannot be created by an app; proot binds the real /dev.
      }
      skip(input, align(size))
    }

    // Applied last so a read-only directory (0555) does not block its own children.
    for ((dir, mode) in dirModes.asReversed()) setMode(dir, mode or 0b111_000_000)
    return count
  }

  /**
   * Strips [strip] leading elements, drops `.` and empty parts. Null when nothing is left or
   * the name contains `..` (would leave the target).
   */
  internal fun relativeEntryPath(name: String, strip: Int): String? {
    val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) return null
    if (parts.size <= strip) return null
    return parts.drop(strip).joinToString("/")
  }

  internal fun parsePax(body: ByteArray): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < body.size) {
      val space = (i until body.size).firstOrNull { body[it] == ' '.code.toByte() } ?: break
      val len = String(body, i, space - i, Charsets.US_ASCII).toIntOrNull() ?: break
      if (len <= 0 || i + len > body.size) break
      // "<len> <key>=<value>\n", value is UTF-8
      val record = String(body, space + 1, i + len - space - 2, Charsets.UTF_8)
      val eq = record.indexOf('=')
      if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
      i += len
    }
    return out
  }

  private fun ustarName(header: ByteArray): String {
    val name = field(header, 0, 100)
    // POSIX ustar ("ustar\0") has a prefix field; GNU ("ustar  ") uses those bytes for other data.
    val posix = String(header, 257, 6, Charsets.US_ASCII) == "ustar\u0000"
    val prefix = if (posix) field(header, 345, 155) else ""
    return if (prefix.isEmpty()) name else "$prefix/$name"
  }

  private fun field(b: ByteArray, off: Int, len: Int): String {
    var end = off
    while (end < off + len && b[end] != 0.toByte()) end++
    return String(b, off, end - off, Charsets.UTF_8)
  }

  private fun cString(b: ByteArray): String {
    val end = b.indexOf(0.toByte()).let { if (it < 0) b.size else it }
    return String(b, 0, end, Charsets.UTF_8)
  }

  internal fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
    if (b[off].toInt() and 0x80 != 0) {
      // GNU base-256: big-endian binary in the remaining bytes.
      var v = (b[off].toLong() and 0x7F)
      for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
      return v
    }
    val s = String(b, off, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
    return if (s.isEmpty()) 0 else s.toLong(8)
  }

  private fun readBody(input: InputStream, size: Long): ByteArray {
    if (size > MAX_META_BODY) throw IOException("tar metadata entry too large: $size")
    val body = ByteArray(size.toInt())
    if (readFully(input, body, body.size) < body.size) throw IOException("Truncated tar metadata")
    skip(input, align(size) - size)
    return body
  }

  private fun setMode(file: File, mode: Int) {
    val perms = HashSet<PosixFilePermission>()
    val all = PosixFilePermission.values() // OWNER_READ … OTHERS_EXECUTE, high bit first
    for (i in all.indices) if (mode and (1 shl (8 - i)) != 0) perms.add(all[i])
    try {
      Files.setPosixFilePermissions(file.toPath(), perms)
    } catch (_: UnsupportedOperationException) {
      // Non-POSIX host file systems (JVM unit tests on Windows). Android always supports it.
    }
  }

  private fun readFully(input: InputStream, buffer: ByteArray, len: Int): Int {
    var total = 0
    while (total < len) {
      val n = input.read(buffer, total, len - total)
      if (n < 0) break
      total += n
    }
    return total
  }

  private fun skip(input: InputStream, count: Long) {
    var remaining = count
    while (remaining > 0) {
      val skipped = input.skip(remaining)
      if (skipped > 0) {
        remaining -= skipped
      } else {
        if (input.read() < 0) throw IOException("Truncated tar stream")
        remaining--
      }
    }
  }

  private fun align(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK
}
