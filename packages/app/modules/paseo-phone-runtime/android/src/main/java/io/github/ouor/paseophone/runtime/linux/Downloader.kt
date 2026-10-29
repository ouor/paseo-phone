// Adapted from Kai (https://github.com/SimonSchubert/Kai),
// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/RootfsDownloader.kt, Apache-2.0.
// Modifications: Ktor replaced by HttpURLConnection, Range resume, sha256 verification,
// byte progress, single URL.
package io.github.ouor.paseophone.runtime.linux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val BUFFER_SIZE = 64 * 1024
private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 60_000

/** The downloaded bytes do not hash to the pinned sha256. The partial file is deleted. */
class ChecksumMismatchException(message: String) : IOException(message)

object Downloader {
  /**
   * Downloads [url] to [target] and verifies [sha256]. A verified [target] is kept and returned
   * without network access. A partial `<target>.part` is resumed with `Range`. Cancellable
   * through the calling coroutine; the partial file stays for the next attempt.
   *
   * Throws [ChecksumMismatchException], or IOException (UnknownHostException etc. when offline).
   */
  suspend fun download(
    url: String,
    target: File,
    sha256: String,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
  ) = withContext(Dispatchers.IO) {
    if (target.isFile) {
      if (sha256Of(target).equals(sha256, ignoreCase = true)) {
        onProgress(target.length(), target.length())
        return@withContext
      }
      target.delete()
    }
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    fetch(url, part, onProgress)
    val actual = sha256Of(part)
    if (!actual.equals(sha256, ignoreCase = true)) {
      part.delete()
      throw ChecksumMismatchException("sha256 mismatch for ${target.name}: expected $sha256, got $actual")
    }
    if (!part.renameTo(target)) throw IOException("Cannot rename ${part.name}")
  }

  fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buf = ByteArray(BUFFER_SIZE)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private suspend fun fetch(url: String, part: File, onProgress: (Long, Long) -> Unit) {
    var offset = if (part.isFile) part.length() else 0L
    val conn = open(url, offset)
    try {
      val code = conn.responseCode
      val append = when {
        code == HttpURLConnection.HTTP_PARTIAL && offset > 0 -> true
        code == HttpURLConnection.HTTP_OK -> false // server ignored Range: start over
        code == 416 && offset > 0 -> {
          // Range past the end: the part file is already complete (or bigger). Let sha256 decide.
          onProgress(offset, offset)
          return
        }
        else -> throw IOException("HTTP $code from $url")
      }
      if (!append) offset = 0
      val length = conn.contentLengthLong
      val total = if (length >= 0) offset + length else -1L
      var done = offset
      conn.inputStream.use { input ->
        FileOutputStream(part, append).use { output ->
          val buf = ByteArray(BUFFER_SIZE)
          while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            onProgress(done, total)
          }
        }
      }
      if (total >= 0 && done != total) throw IOException("Connection closed at $done of $total bytes")
    } finally {
      conn.disconnect()
    }
  }

  private fun open(url: String, offset: Long): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT_MS
    conn.readTimeout = READ_TIMEOUT_MS
    conn.instanceFollowRedirects = true // GitHub release assets redirect to objects.githubusercontent.com
    // Byte offsets must refer to the raw file, not a gzip-decoded stream.
    conn.setRequestProperty("Accept-Encoding", "identity")
    if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
    return conn
  }
}
