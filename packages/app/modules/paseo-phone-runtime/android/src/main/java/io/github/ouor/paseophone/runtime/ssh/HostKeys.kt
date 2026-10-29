package io.github.ouor.paseophone.runtime.ssh

import android.util.Base64
import java.io.File

/** Host key fingerprints, shown so the owner can verify the first `ssh -p 2222` connection (architecture §5.1). */
object HostKeys {
  fun fingerprints(rootfs: File): List<String> {
    val files = File(rootfs, "etc/ssh").listFiles { f -> f.name.startsWith("ssh_host_") && f.name.endsWith("_key.pub") }
    return files.orEmpty().sortedBy { it.name }.mapNotNull { file ->
      try {
        val tokens = file.readText().trim().split(Regex("\s+"))
        "${tokens[0]} ${AuthorizedKeys.fingerprint(Base64.decode(tokens[1], Base64.DEFAULT))}"
      } catch (_: Exception) {
        null
      }
    }
  }
}
