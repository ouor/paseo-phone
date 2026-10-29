package io.github.ouor.paseophone.runtime.ssh

import java.io.File
import java.util.Base64

/** Host key fingerprints, shown so the owner can verify the first `ssh -p 2222` connection (architecture §5.1). */
object HostKeys {
  fun fingerprints(rootfs: File): List<String> {
    val files = File(rootfs, "etc/ssh").listFiles { f -> f.name.startsWith("ssh_host_") && f.name.endsWith("_key.pub") }
    return files.orEmpty().sortedBy { it.name }.mapNotNull { file ->
      try {
        val tokens = file.readText().trim().split(AuthorizedKeys.WHITESPACE)
        "${tokens[0]} ${AuthorizedKeys.fingerprint(Base64.getDecoder().decode(tokens[1]))}"
      } catch (_: Exception) {
        null
      }
    }
  }
}
