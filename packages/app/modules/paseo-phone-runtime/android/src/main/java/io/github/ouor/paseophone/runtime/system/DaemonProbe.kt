package io.github.ouor.paseophone.runtime.system

import io.github.ouor.paseophone.runtime.linux.PhoneConstants
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Readiness and port checks for the daemon and sshd (architecture §3.4). Blocking; call off the main thread. */
object DaemonProbe {
  private const val TIMEOUT_MS = 2_000

  /** True when the daemon accepts TCP and any HTTP response comes back (status code ignored; `/` is 404). */
  fun isResponding(): Boolean =
    try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(PhoneConstants.DAEMON_HOST, PhoneConstants.DAEMON_PORT), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.getOutputStream().apply {
          write("GET / HTTP/1.0\r\nHost: ${PhoneConstants.DAEMON_HOST}\r\n\r\n".toByteArray())
          flush()
        }
        val head = ByteArray(5)
        var read = 0
        val input = socket.getInputStream()
        while (read < head.size) {
          val n = input.read(head, read, head.size - read)
          if (n < 0) break
          read += n
        }
        read == head.size && String(head) == "HTTP/"
      }
    } catch (_: Exception) {
      false
    }

  /** True when something already accepts connections on loopback [port] (an orphan from a previous run). */
  fun isListening(port: Int): Boolean =
    try {
      Socket().use { it.connect(InetSocketAddress(PhoneConstants.DAEMON_HOST, port), TIMEOUT_MS) }
      true
    } catch (_: Exception) {
      false
    }

  /** `$PASEO_HOME/server-id` inside the rootfs, or null before the daemon has written it. */
  fun readServerId(rootfs: File): String? =
    try {
      File(rootfs, PhoneConstants.GUEST_PASEO_HOME.removePrefix("/") + "/server-id")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.ifEmpty { null }
    } catch (_: Exception) {
      null
    }
}
