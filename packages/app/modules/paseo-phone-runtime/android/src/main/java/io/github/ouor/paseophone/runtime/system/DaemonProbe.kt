package io.github.ouor.paseophone.runtime.system

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Readiness check for the daemon on 127.0.0.1:6767 (architecture §3.4). Blocking; call off the main thread. */
object DaemonProbe {
  const val HOST = "127.0.0.1"
  const val PORT = 6767
  private const val TIMEOUT_MS = 2_000

  /** True when a TCP connection succeeds and any HTTP response comes back (status code ignored; `/` is 404). */
  fun isResponding(): Boolean =
    try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        socket.getOutputStream().apply {
          write("GET / HTTP/1.0\r\nHost: $HOST\r\n\r\n".toByteArray())
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

  /** `$PASEO_HOME/server-id` inside the rootfs, or null before the daemon has written it. */
  fun readServerId(rootfs: File): String? =
    try {
      File(rootfs, "root/.paseo/server-id").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
      null
    }

  /** Supervisor pid from `$PASEO_HOME/paseo.pid` (JSON `{ pid, startedAt }`). proot has no pid namespace. */
  fun readSupervisorPid(rootfs: File): Int? =
    try {
      val file = File(rootfs, "root/.paseo/paseo.pid")
      if (!file.isFile) null else org.json.JSONObject(file.readText()).optInt("pid", 0).takeIf { it > 0 }
    } catch (_: Exception) {
      null
    }
}
