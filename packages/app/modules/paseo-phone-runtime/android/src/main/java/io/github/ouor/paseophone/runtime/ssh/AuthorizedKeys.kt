package io.github.ouor.paseophone.runtime.ssh

import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Reads and writes `rootfs/root/.ssh/authorized_keys` from the Android side (architecture §5.1).
 * sshd re-reads the file per login, so no restart is needed.
 */
class AuthorizedKeys(rootfs: File) {
  data class Key(val type: String, val comment: String, val fingerprint: String)

  /** [code] is one of `invalid-key`, `private-key`, `duplicate-key`, `not-found`. */
  class KeyException(val code: String, message: String) : Exception(message)

  private val sshDir = File(rootfs, "root/.ssh")
  private val file = File(sshDir, "authorized_keys")

  fun list(): List<Key> = readLines().mapNotNull { parse(it) }

  /** Never logs or stores [text] when it looks like a private key. */
  fun add(text: String): Key {
    if (text.contains("-----BEGIN") || text.contains("PRIVATE KEY")) {
      throw KeyException("private-key", "This is a private key. Paste the public key (.pub) instead.")
    }
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val key = lines.singleOrNull()?.let { parse(it) } ?: throw KeyException("invalid-key", "Not a single OpenSSH public key line")
    if (list().any { it.fingerprint == key.fingerprint }) {
      throw KeyException("duplicate-key", "This key is already authorized")
    }
    write(readLines() + lines.single())
    return key
  }

  fun remove(fingerprint: String) {
    val lines = readLines()
    val kept = lines.filter { parse(it)?.fingerprint != fingerprint }
    if (kept.size == lines.size) throw KeyException("not-found", "No authorized key with $fingerprint")
    write(kept)
  }

  private fun readLines(): List<String> = if (file.isFile) file.readLines() else emptyList()

  private fun write(lines: List<String>) {
    if (!sshDir.isDirectory && !sshDir.mkdirs()) throw java.io.IOException("Cannot create $sshDir")
    Os.chmod(sshDir.path, "700".toInt(8))
    val tmp = File(sshDir, "authorized_keys.tmp")
    tmp.writeText(lines.joinToString("\n", postfix = "\n"))
    Os.chmod(tmp.path, "600".toInt(8))
    if (!tmp.renameTo(file)) throw java.io.IOException("Cannot replace $file")
  }

  companion object {
    internal val WHITESPACE = Regex("""\s+""")

    private val KEY_TYPES =
      setOf(
        "ssh-ed25519",
        "ssh-rsa",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "sk-ssh-ed25519@openssh.com",
        "sk-ecdsa-sha2-nistp256@openssh.com",
      )

    /** Parses `[options] type base64 [comment]`; null for comments, blanks and anything malformed. */
    fun parse(line: String): Key? {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
      // shortcut: options containing quoted spaces before the key type are not tokenized exactly; fine for keys this app writes.
      val tokens = trimmed.split(WHITESPACE)
      val typeIndex = tokens.indexOfFirst { it in KEY_TYPES }
      if (typeIndex < 0 || typeIndex + 1 >= tokens.size) return null
      val type = tokens[typeIndex]
      val blob =
        try {
          Base64.getDecoder().decode(tokens[typeIndex + 1])
        } catch (_: IllegalArgumentException) {
          return null
        }
      if (embeddedType(blob) != type) return null
      return Key(type, tokens.drop(typeIndex + 2).joinToString(" "), fingerprint(blob))
    }

    /** OpenSSH `SHA256:` fingerprint of a raw public key blob. */
    fun fingerprint(blob: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(blob)
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    // The blob starts with a length-prefixed copy of the key type.
    private fun embeddedType(blob: ByteArray): String? =
      try {
        val buffer = ByteBuffer.wrap(blob)
        val length = buffer.int
        if (length <= 0 || length > blob.size - 4) null else String(blob, 4, length, Charsets.US_ASCII)
      } catch (_: Exception) {
        null
      }
  }
}
