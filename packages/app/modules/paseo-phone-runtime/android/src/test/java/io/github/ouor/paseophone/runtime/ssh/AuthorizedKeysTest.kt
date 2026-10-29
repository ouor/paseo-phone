package io.github.ouor.paseophone.runtime.ssh

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AuthorizedKeysTest {
  // Generated with ssh-keygen; fingerprint from `ssh-keygen -lf`.
  private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAcYs108jfa0hon87YKT6rJQIJpv0lAWEcUV2SNy4HQ9"
  private val fingerprint = "SHA256:8U6KLuWB+vF3OoAwYwDpYUr8pJ0EOpASSW+rGVeTHMQ"

  @Test
  fun parsesTypeCommentAndOpenSshFingerprint() {
    assertEquals(AuthorizedKeys.Key("ssh-ed25519", "me@laptop", fingerprint), AuthorizedKeys.parse("  $key me@laptop  "))
    assertEquals(AuthorizedKeys.Key("ssh-ed25519", "", fingerprint), AuthorizedKeys.parse(key))
  }

  @Test
  fun skipsOptionsBeforeTheKeyType() {
    assertEquals(fingerprint, AuthorizedKeys.parse("no-pty,no-port-forwarding $key a b")?.fingerprint)
    assertEquals("a b", AuthorizedKeys.parse("no-pty $key a b")?.comment)
  }

  @Test
  fun rejectsCommentsBlanksAndMalformedKeys() {
    assertNull(AuthorizedKeys.parse("# $key"))
    assertNull(AuthorizedKeys.parse("   "))
    assertNull(AuthorizedKeys.parse("ssh-ed25519"))
    assertNull(AuthorizedKeys.parse("ssh-ed25519 not*base64"))
    // Blob says ssh-ed25519, label says ssh-rsa.
    assertNull(AuthorizedKeys.parse(key.replace("ssh-ed25519 ", "ssh-rsa ")))
  }

  @Test
  fun addRejectsPrivateKeysAndNonKeysWithoutWriting() {
    val rootfs = Files.createTempDirectory("rootfs").toFile()
    val keys = AuthorizedKeys(rootfs)
    assertCode("private-key") { keys.add("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----") }
    assertCode("invalid-key") { keys.add("hello") }
    assertCode("invalid-key") { keys.add("$key a\n$key b") }
    // Options would let a pasted line run a command or widen access; add() only takes bare keys.
    assertCode("invalid-key") { keys.add("command=\"/bin/sh\" $key") }
    assertCode("invalid-key") { keys.add("from=\"10.0.0.1\" $key me") }
    assertEquals(false, File(rootfs, "root/.ssh/authorized_keys").exists())
    rootfs.deleteRecursively()
  }

  private fun assertCode(code: String, block: () -> Unit) {
    try {
      block()
      fail("expected $code")
    } catch (e: AuthorizedKeys.KeyException) {
      assertEquals(code, e.code)
    }
  }
}
