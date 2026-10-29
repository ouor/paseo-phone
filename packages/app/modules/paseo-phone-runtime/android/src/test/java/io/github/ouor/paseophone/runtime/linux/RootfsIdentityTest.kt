package io.github.ouor.paseophone.runtime.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RootfsIdentityTest {
  private fun tmp() = Files.createTempDirectory("rootfs-test").toFile()

  @Test fun rootfsStateFromDisk() {
    val root = tmp()
    val rootfs = File(root, "rootfs")
    assertEquals(RootfsState.ABSENT, LinuxPaths.stateOf(rootfs))
    File(rootfs, "usr/bin").mkdirs()
    assertEquals(RootfsState.EXTRACTED, LinuxPaths.stateOf(rootfs))
    File(rootfs, LinuxPaths.DAEMON_PACKAGE_JSON).apply { parentFile.mkdirs() }.writeText("{\"version\":\"0.14.0\"}")
    assertEquals(RootfsState.INSTALLED, LinuxPaths.stateOf(rootfs))
  }

  @Test fun identityRoundTrip() {
    val root = tmp()
    val old = File(root, "rootfs")
    val identity = File(root, "identity")
    fun put(base: File, rel: String, text: String) = File(base, rel).apply { parentFile.mkdirs() }.writeText(text)
    put(old, "root/.paseo/server-id", "srv_123")
    put(old, "etc/ssh/ssh_host_ed25519_key", "PRIVATE")
    put(old, "etc/ssh/ssh_host_ed25519_key.pub", "PUBLIC")
    put(old, "etc/ssh/sshd_config", "not identity")
    put(old, "root/.ssh/authorized_keys", "ssh-ed25519 AAAA desk")
    put(old, "root/notes.txt", "user data")

    Identity.save(old, identity)
    LinuxPaths.deleteTree(old)
    // A second save against a missing rootfs (failed reinstall retried) keeps what was saved.
    Identity.save(old, identity)

    val fresh = File(root, "rootfs")
    put(fresh, "etc/ssh/ssh_host_ed25519_key", "NEW KEY FROM POSTINST")
    Identity.restoreIfPresent(fresh, identity)

    assertEquals("srv_123", File(fresh, "root/.paseo/server-id").readText())
    assertEquals("PRIVATE", File(fresh, "etc/ssh/ssh_host_ed25519_key").readText())
    assertEquals("PUBLIC", File(fresh, "etc/ssh/ssh_host_ed25519_key.pub").readText())
    assertEquals("ssh-ed25519 AAAA desk", File(fresh, "root/.ssh/authorized_keys").readText())
    assertFalse(File(fresh, "etc/ssh/sshd_config").exists())
    assertFalse(File(fresh, "root/notes.txt").exists())
    assertFalse(identity.exists())

    // Nothing saved → restore is a no-op.
    Identity.restoreIfPresent(fresh, identity)
    assertTrue(File(fresh, "root/.paseo/server-id").isFile)
  }
}
